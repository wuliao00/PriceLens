package com.pricelens.ui.product

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.coupon.ClipboardCapture
import com.pricelens.coupon.CouponExtractor
import com.pricelens.coupon.CouponMisreadFiles
import com.pricelens.coupon.CouponMisreadJsonl
import com.pricelens.coupon.CouponMisreadStore
import com.pricelens.coupon.LocalCouponInputPlanner
import com.pricelens.coupon.MisreadLedger
import com.pricelens.coupon.PageCapture
import com.pricelens.data.remote.GwdangApi
import com.pricelens.ui.common.AsyncValue
import com.pricelens.ui.common.valueOrDefault
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.ShimmerList
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.LocalSemanticColors
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceType
import com.pricelens.ui.theme.fg
import com.pricelens.util.PriceFormatter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §十一 详情页「找券」段。
 *
 * 原 `ui/coupon/CouponScreen.kt` 的渲染整体搬进详情页（底部导航 6→4 之后找券不再是独立 tab，
 * 顶层页文件删除，不留两份平行实现），三条既有口径原样保留：
 *  - 券源只展示爆料里显式写出的券，不做价差反推；
 *  - 有券但都没到门槛 → 如实说"暂无可算的到手价"，不显示误导性的 ¥0；
 *  - 到手价 countUp 走 graphicsLayer alpha（§2.1 铁律），500ms 属 §2.3 的历史例外时长。
 *
 * 任务书 B2 之后这里并排放**两组券**（B2 口径：不许悄悄替换/吞掉 Gwdang 那条路）：
 *  - 远端组：`GwdangApi.Coupon`，什么值得买券频道给的列表，渲染一行都不改；
 *  - 本机组：[CouponExtractor] 从**两路输入**逐句读出的 [com.pricelens.coupon.model.CouponSlot]，
 *    带三档置信、逐槽"未识别"与证据句；每张券一个"这条不对"按钮，点了只进内存清单，
 *    导出走 files 下的本地通道（[CouponMisreadJsonl] 与 `tools/golden/coupons.jsonl` 逐字段兼容，
 *    人工放进评测集才参与回归）。
 *    两路输入是 #61（2026-10-05）接上的：浮窗侧的页面节点树经 [PageCapture] 单槽交接喂 `fromPage`，
 *    关键词/分享文本喂 `fromClipboard`；两路都有时 [CouponExtractor.merge] **做加法**
 *    （面额+门槛都相同的券只算一张），谁都不许被悄悄换掉。用哪一路由纯函数
 *    [LocalCouponInputPlanner.plan] 判，出处写在组标题下面一行。
 */
@Composable
fun ProductCouponSection(searchViewModel: SearchViewModel) {
    val loading by searchViewModel.loading.collectAsStateWithLifecycle()
    val couponsAsync by searchViewModel.coupons.collectAsStateWithLifecycle()
    val netPrice by searchViewModel.netPrice.collectAsStateWithLifecycle()
    val coupons = couponsAsync.valueOrDefault(emptyList())
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    val capture by PageCapture.latest.collectAsStateWithLifecycle()
    val clipboard by ClipboardCapture.latest.collectAsStateWithLifecycle()
    // #61 + #63：本机识别有**三路输入**（这一页的节点 / 刚复制的文本 / 关键词）。
    // "哪几路可用"是判据，写在纯函数 `LocalCouponInputPlanner.plan` 里（JVM 可测边界），
    // 这里只取当下时钟、按判据把每一路跑起来，再 mergeAll **做加法**
    // （同面额同门槛只算一张）—— 任何一路都不许因为别路有东西就被换掉。
    val plan = LocalCouponInputPlanner.plan(capture, clipboard, keyword, SystemClock.elapsedRealtime())
    val localExtraction = remember(capture, clipboard, keyword, plan.inputs) {
        CouponExtractor.mergeAll(
            plan.inputs.map { input ->
                when (input) {
                    LocalCouponInputPlanner.Input.PAGE_TREE -> CouponExtractor.fromPage(checkNotNull(capture).root)
                    LocalCouponInputPlanner.Input.CLIPBOARD_TEXT -> CouponExtractor.fromClipboard(checkNotNull(clipboard).raw)
                    LocalCouponInputPlanner.Input.KEYWORD_TEXT -> CouponExtractor.fromClipboard(keyword.trim())
                }
            }
        )
    }
    val localRows = localCouponRows(localExtraction)
    val ledger = remember { MisreadLedger() }
    var markedCount by remember { mutableStateOf(0) }

    // "这一屏该出现哪几块"是判据，写在纯函数 `CouponSectionShape` 里（JVM 可测），这里只照着渲染。
    // 旧形状里有两处 `return` 会把**本机识别组**整段藏掉：一处在"远端为空且本机也没抽到"，
    // 一处在错误块中间。真机 2026-10-05 从京东商详页回到 App 时抓到了前者：
    // 屏幕上只剩一张"请先搜索商品"的大空卡，怎么划都不出本机组（见 docs/ROADMAP §9.17）。
    val blocks = CouponSectionShape.of(
        loading = couponsAsync is AsyncValue.Loading<*> || (loading && coupons.isEmpty()),
        failed = couponsAsync is AsyncValue.Error<*>,
        remoteCouponCount = coupons.size
    )
    if (blocks.shimmer) {
        ShimmerList()
        return
    }
    if (couponsAsync is AsyncValue.Error<*>) {
        // 失败：友好提示。**不提前退出**——本机组读的是页面树/剪贴板/关键词，和网络无关，
        // 远端挂了它照样有东西可说（旧代码在这里 return，等于用一次网络失败把两条路一起藏掉）
        Column(Modifier.fillMaxSize().padding(Dims.SpacingXL)) {
            EmptyState(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.error_load_failed),
                desc = stringResource(R.string.error_retry_hint)
            )
            Spacer(Modifier.height(Dims.SpacingL))
        }
    }

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // SAF 兜底（崩溃日志同款写法）：把逐行 JSONL 写到用户当场选定的位置，程序不挑地方
    val safMisreadExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val marks = ledger.snapshot()
            val jsonl = CouponMisreadJsonl.export(marks)
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)
                            ?.use { it.write(jsonl.toByteArray(Charsets.UTF_8)) }
                            ?: error("openOutputStream 返回 null")
                    }
                }
                val msg = if (outcome.isSuccess) {
                    context.getString(R.string.coupon_local_export_saved, marks.size, uri.lastPathSegment ?: uri.toString())
                } else {
                    context.getString(R.string.coupon_local_export_failed)
                }
                snackbar.showSnackbar(msg)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(Dims.SpacingXL),
            modifier = Modifier.weight(1f)
        ) {
            // 委托属性无法智能转换，先取本地值（同时避免 !!）
            val applicableNet = netPrice
            if (applicableNet != null) {
                item(key = "net_price") {
                    NetPriceHeader(applicableNet)
                    Spacer(Modifier.height(Dims.SpacingL))
                }
            } else if (coupons.isNotEmpty()) {
                // 有券但都不适用于当前商品价（未达门槛）：如实说明，不再显示误导性的 ¥0
                item(key = "net_price_hint") {
                    Text(
                        stringResource(R.string.coupon_not_applicable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Dims.SpacingL))
                }
            }
            itemsIndexed(coupons, key = { index, coupon -> "cpn:${index}_${coupon.amount}-${coupon.title}" }) { _, coupon ->
                CouponCard(coupon) {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("coupon", coupon.title))
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.coupon_copied)) }
                }
                Spacer(Modifier.height(Dims.SpacingM))
            }
            // 远端这条路"查过了、确实没有"要单独说一句：它和本机那条路的空态是两回事
            // （旧代码只在"两边都空"时才显示这块，于是本机抽出券时远端查没查过反而看不出来）。
            // 失败时**不**显示：`blocks.remoteEmpty` 已经把 failed 排除掉了，
            // 把"没连上"说成"查过了没有"是同一个病根的另一种说法。
            if (blocks.remoteEmpty) {
                item(key = "remote_empty") {
                    EmptyState(
                        icon = Icons.Filled.ConfirmationNumber,
                        title = stringResource(
                            if (keyword.isNotBlank()) R.string.coupon_empty_title else R.string.empty_search_first
                        ),
                        desc = stringResource(R.string.coupon_empty_hint)
                    )
                    Spacer(Modifier.height(Dims.SpacingL))
                }
            }
            // 本机识别组（B2 交付一/二）：小标题明说来源，逐卡三档+逐槽+证据句+纠错按钮。
            // **标题与空态不许一起藏起来**：真机 2026-10-04 搜「雷神ZERO」时整段消失，
            // 用户无法区分"这功能没做 / 被我关了 / 这次没识别到"——静默没有正是这一批要消灭的形状。
            if (blocks.localGroup) {
                item(key = "local_header") {
                    LocalGroupHeader(plan.inputs)
                }
                if (localRows.isEmpty()) {
                    item(key = "local_empty") {
                        LocalGroupEmpty(plan.inputs, keyword, clipboard?.raw.orEmpty())
                    }
                } else {
                    itemsIndexed(localRows, key = { _, row -> "local:${row.index}_${row.sourceText.hashCode()}" }) { _, row ->
                        LocalCouponCard(
                            row = row,
                            marked = ledger.isMarked(localExtraction, row.index),
                            onMark = {
                                if (ledger.mark(localExtraction, row.index, System.currentTimeMillis())) {
                                    markedCount = ledger.markedCount()
                                }
                            }
                        )
                        Spacer(Modifier.height(Dims.SpacingM))
                    }
                    if (markedCount > 0) {
                        item(key = "local_export") {
                            MisreadExportRow(
                                markedCount = markedCount,
                                onWriteLocal = {
                                    val marks = ledger.snapshot()
                                    val jsonl = CouponMisreadJsonl.export(marks)
                                    scope.launch {
                                        val outcome = withContext(Dispatchers.IO) {
                                            runCatching {
                                                CouponMisreadStore(File(context.filesDir, CouponMisreadFiles.DIR_NAME))
                                                    .write(jsonl, System.currentTimeMillis())
                                            }
                                        }
                                        val result = outcome.getOrNull()
                                        val msg = if (result != null) {
                                            context.getString(R.string.coupon_local_export_saved, marks.size, result.name)
                                        } else {
                                            context.getString(R.string.coupon_local_export_failed)
                                        }
                                        snackbar.showSnackbar(msg)
                                    }
                                },
                                onExportSaf = { safMisreadExport.launch(CouponMisreadFiles.fileName(System.currentTimeMillis())) }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

/** 到手价大字（PriceType.PriceHero 等宽数字）+ countUp */
@Composable
private fun NetPriceHeader(netPrice: Double) {
    var target by remember(netPrice) { mutableStateOf(0f) }
    LaunchedEffect(netPrice) { target = 1f }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(MotionDurations.PriceRoll, easing = PriceLensEasing),
        label = "netPriceCountUp"
    )
    val display = netPrice * progress

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            stringResource(R.string.coupon_net_price),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Dims.SpacingS)
        )
        Spacer(Modifier.width(Dims.SpacingS))
        Text(
            PriceFormatter.format(display),
            style = PriceType.PriceHero,
            color = LocalSemanticColors.current.suspicious,
            modifier = Modifier.graphicsLayer {
                // §2.1：动画值只影响绘制通道
                alpha = 0.4f + 0.6f * progress
            }
        )
    }
}

@Composable
private fun CouponCard(coupon: GwdangApi.Coupon, onCopy: () -> Unit) {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.coupon_amount, coupon.amount.toInt()),
                style = PriceType.PriceLarge,
                color = LocalSemanticColors.current.suspicious
            )
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(coupon.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    if (coupon.threshold > 0) {
                        stringResource(R.string.coupon_threshold, coupon.threshold.toInt())
                    } else {
                        stringResource(R.string.coupon_no_threshold)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onCopy, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.coupon_copy))
            }
        }
    }
}

/** 本机识别组的小标题：明说这组是"从当前文案里识别的"，与上面远端券列表是两回事 */
@Composable
private fun LocalGroupHeader(inputs: List<LocalCouponInputPlanner.Input>) {
    val context = LocalContext.current
    Column {
        Spacer(Modifier.height(Dims.SpacingL))
        Text(stringResource(R.string.coupon_local_header), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.coupon_local_subheader),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 出处单独一行：这一组东西**是从哪几路读的**（#61 树、#63 剪贴板，加上原有的关键词）。
        // 三路同时可读而不写清，用户就没法判断"没找到券"是没输入还是真没有。
        Text(
            text = if (inputs.isEmpty()) {
                context.getString(R.string.coupon_local_source_none)
            } else {
                context.getString(
                    R.string.coupon_local_source_inputs,
                    inputs.joinToString(" + ") { context.getString(inputLabelRes(it)) }
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Dims.SpacingM))
    }
}

/** 一路输入的名字（出处行与空态回显共用同一份措辞，避免两处各写一套） */
@StringRes
private fun inputLabelRes(input: LocalCouponInputPlanner.Input): Int = when (input) {
    LocalCouponInputPlanner.Input.PAGE_TREE -> R.string.coupon_local_input_page
    LocalCouponInputPlanner.Input.CLIPBOARD_TEXT -> R.string.coupon_local_input_clipboard
    LocalCouponInputPlanner.Input.KEYWORD_TEXT -> R.string.coupon_local_input_keyword
}

/**
 * 空态：明说"读的是哪段文字、这次没看到券形态"。
 *
 * 为什么不写"暂无数据"：那四个字区分不了"功能没开""网络失败""这段文案确实没券"三种情况，
 * 而这三件事用户该做的动作完全不同。这里把**输入本身**回显出来，
 * 用户一眼能判断"我搜的这个词本来就不该有券"还是"我贴的分享文案明明有券却没认出来"
 * —— 后者正是"这条不对"要报的东西。
 */
@Composable
private fun LocalGroupEmpty(inputs: List<LocalCouponInputPlanner.Input>, keyword: String, clipboardText: String) {
    val context = LocalContext.current
    // 文本两路原样回显（用户要靠它判断），树那一路没有整句原文，回显它的名字
    val shown = inputs.joinToString(" ｜ ") { input ->
        when (input) {
            LocalCouponInputPlanner.Input.PAGE_TREE -> context.getString(R.string.coupon_local_input_page)
            LocalCouponInputPlanner.Input.CLIPBOARD_TEXT -> clipboardText
            LocalCouponInputPlanner.Input.KEYWORD_TEXT -> keyword.trim()
        }
    }.ifBlank { keyword.trim() }
    Column {
        Text(
            text = stringResource(R.string.coupon_local_empty, shown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dims.SpacingM))
    }
}

/** 单个槽位的显示文本：未识别就是一句"未识别"，args 为空即没携带任何别的槽位的数 */
@Composable
private fun slotValueText(cell: SlotCell): String = when {
    cell.unknown -> stringResource(R.string.coupon_local_unrecognized)
    cell.args.isEmpty() -> stringResource(cell.valueRes)
    else -> stringResource(cell.valueRes, *cell.args.toTypedArray())
}

/**
 * 本机识别的一张券：档位（措辞+色调）、固定七格逐槽、证据句、"这条不对"。
 * 读屏拿到的是一条拼好的句子（档位 + 逐槽读数 + 证据句），不是散落的标签串。
 */
@Composable
private fun LocalCouponCard(row: LocalCouponRow, marked: Boolean, onMark: () -> Unit) {
    val tierText = stringResource(tierLabelRes(row.tier))
    // 读屏句子逐格拼：这里不用 joinToString(transform)——那个内联 lambda 不继承 Composable 上下文
    val cellParts = mutableListOf<String>()
    for (cell in row.cells) {
        cellParts.add(stringResource(R.string.coupon_local_cd_slot, stringResource(cell.labelRes), slotValueText(cell)))
    }
    val readout = cellParts.joinToString("，")
    val description = stringResource(R.string.coupon_local_cd_row, tierText, readout, row.sourceText)
    PriceCard(modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Column {
            Text(tierText, style = MaterialTheme.typography.labelLarge, color = tierTone(row.tier).fg())
            row.cells.forEach { cell ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(cell.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(Dims.SpacingS))
                    Text(slotValueText(cell), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            Text(
                stringResource(R.string.coupon_local_source_line, row.sourceText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3
            )
            TextButton(onClick = onMark, enabled = !marked, shape = MaterialTheme.shapes.small) {
                Text(stringResource(if (marked) R.string.coupon_local_marked else R.string.coupon_local_mark_wrong))
            }
        }
    }
}

/** 导出行：只导被标记的那几条；两条通道（本机文件 / SAF 另存）都不联网 */
@Composable
private fun MisreadExportRow(markedCount: Int, onWriteLocal: () -> Unit, onExportSaf: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Dims.SpacingS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onWriteLocal, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.coupon_local_export, markedCount))
            }
            Spacer(Modifier.width(Dims.SpacingS))
            TextButton(onClick = onExportSaf, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.coupon_local_export_saf))
            }
        }
        Text(
            stringResource(R.string.coupon_local_export_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
