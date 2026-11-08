package com.pricelens.ui.autotrack

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.data.local.entity.PurchaseRecordEntity
import com.pricelens.data.repository.PurchaseDraft
import com.pricelens.domain.ProtectionPlatform
import com.pricelens.ui.components.EmptyState
import com.pricelens.ui.components.IconTile
import com.pricelens.ui.components.PillChoice
import com.pricelens.ui.components.PriceBadge
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.components.StatTile
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.ui.theme.Dims
import com.pricelens.ui.theme.PriceType
import com.pricelens.util.PriceFormatter
import com.pricelens.util.TimeAgo
import com.pricelens.util.UrlOpener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 价保助手（全屏二级页，2026-11 新增）：买后链路。
 *
 * 页面分三段：
 *  ① 价保概览（可申请笔数/金额、最近检查、立即检查）；
 *  ② 录入购买（平台 / 商品名 / 实付价 / 购买日期）——从商品详情页「我买了」进来时预填；
 *  ③ 已记录购买列表（现价 vs 实付、可申请金额、截止日、已申请标记、去平台申请/复制文案/删除）。
 *
 * 口径纪律：拿不到现价就写「暂取不到现价」，绝不拿 0 或旧价充数；可申请金额是**名义差额**，
 * 实际以平台审核为准（页脚写明）。所有数据只在本机，表单不发起任何网络请求。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PriceProtectionScreen(
    onBack: () -> Unit,
    initialTitle: String? = null,
    initialPlatform: String? = null,
    initialPrice: Double? = null,
    viewModel: AutoTrackViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val protection by viewModel.protection.collectAsStateWithLifecycle()
    val purchases by viewModel.purchases.collectAsStateWithLifecycle()
    val purchaseEvent by viewModel.purchaseEvent.collectAsStateWithLifecycle()
    val checking by viewModel.protectionChecking.collectAsStateWithLifecycle()

    var showForm by rememberSaveable { mutableStateOf(purchases.isEmpty() || initialTitle != null) }
    var platformKey by rememberSaveable {
        mutableStateOf(ProtectionPlatform.of(initialPlatform).platformKey)
    }
    var title by rememberSaveable { mutableStateOf(initialTitle.orEmpty()) }
    var priceText by rememberSaveable {
        mutableStateOf(initialPrice?.takeIf { it > 0 }?.let { formatEditablePrice(it) }.orEmpty())
    }
    var purchasedAtMs by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PurchaseRowView?>(null) }

    val savedText = stringResource(R.string.rd_protect_saved)
    LaunchedEffect(purchaseEvent) {
        when (val event = purchaseEvent) {
            null -> return@LaunchedEffect
            is PurchaseEvent.Saved -> {
                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                title = ""
                priceText = ""
                purchasedAtMs = System.currentTimeMillis()
                showForm = false
            }
            PurchaseEvent.Rejected ->
                Toast.makeText(context, R.string.rd_protect_invalid_price, Toast.LENGTH_SHORT).show()
        }
        viewModel.acknowledgePurchaseEvent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rd_protect_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(Dims.SpacingXL),
            verticalArrangement = Arrangement.spacedBy(Dims.SpacingL)
        ) {
            item(key = "summary") {
                ProtectionSummaryCard(
                    watchCount = protection.watchCount,
                    claimableCount = protection.claimableCount,
                    claimableYuan = protection.claimableYuan,
                    lastCheckAtMs = protection.lastCheckAtMs.takeIf { it > 0L },
                    checking = checking,
                    onCheckNow = { viewModel.checkProtectionNow() }
                )
            }
            if (initialTitle != null || initialPlatform != null || initialPrice != null) {
                item(key = "prefill_hint") {
                    Text(
                        stringResource(
                            R.string.rd_protect_prefill_hint,
                            initialTitle.orEmpty(),
                            initialPlatform?.let { ProtectionPlatform.of(it).label } ?: ""
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (initialPrice != null) {
                        Text(
                            stringResource(R.string.rd_protect_prefill_price_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item(key = "form") {
                PriceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Filled.ConfirmationNumber)
                        Spacer(Modifier.width(Dims.SpacingM))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.rd_protect_add_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.rd_protect_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { showForm = !showForm }) {
                            Text(
                                stringResource(
                                    if (showForm) R.string.overview_guide_collapse else R.string.overview_guide_expand
                                )
                            )
                        }
                    }
                    if (showForm) {
                        Spacer(Modifier.height(Dims.SpacingS))
                        Text(
                            stringResource(R.string.rd_protect_field_platform),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)) {
                            ProtectionPlatformFilter.forEach { key ->
                                PillChoice(
                                    label = stringResource(protectionPlatformLabelRes(key)),
                                    selected = platformKey == key,
                                    onClick = { platformKey = key }
                                )
                            }
                        }
                        Spacer(Modifier.height(Dims.SpacingS))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.rd_protect_field_title)) },
                            isError = title.isBlank() && title.isNotEmpty(),
                            supportingText = {
                                if (title.isBlank()) Text(stringResource(R.string.rd_protect_title_required))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Dims.SpacingS))
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text(stringResource(R.string.rd_protect_field_price)) },
                            supportingText = {
                                val parsed = priceText.toDoubleOrNull()
                                if (parsed == null || parsed <= 0) {
                                    Text(stringResource(R.string.rd_protect_price_required))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Dims.SpacingS))
                        Text(
                            stringResource(R.string.rd_protect_field_date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Dims.TouchMin)
                                .padding(top = Dims.SpacingXS)
                        ) {
                            TextButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(dateFormat.format(Date(purchasedAtMs)))
                            }
                        }
                        Spacer(Modifier.height(Dims.SpacingM))
                        val parsedPrice = priceText.toDoubleOrNull()
                        val canSubmit = title.isNotBlank() && parsedPrice != null && parsedPrice > 0
                        TextButton(
                            onClick = {
                                val price = parsedPrice ?: return@TextButton
                                viewModel.recordPurchase(
                                    PurchaseDraft(
                                        platform = platformKey,
                                        productId = null,
                                        title = title.trim(),
                                        price = price,
                                        purchasedAtMs = purchasedAtMs,
                                        url = null
                                    )
                                )
                            },
                            enabled = canSubmit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Dims.TouchMin)
                        ) { Text(stringResource(R.string.rd_protect_save)) }
                    }
                }
            }
            item(key = "list_title") {
                SectionHeader(stringResource(R.string.rd_protect_list_title, purchases.size))
            }
            if (purchases.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.ConfirmationNumber,
                        title = stringResource(R.string.rd_protect_title),
                        desc = stringResource(R.string.rd_protect_empty),
                        actionLabel = stringResource(R.string.rd_protect_empty_action),
                        onAction = { showForm = true }
                    )
                }
            } else {
                purchases.forEach { entity ->
                    item(key = "purchase_${entity.id}") {
                        PurchaseCard(
                            row = entity.toRowView(),
                            onApply = { row ->
                                if (!row.url.isNullOrBlank()) {
                                    UrlOpener.open(context, row.url!!)
                                } else {
                                    Toast.makeText(context, R.string.rd_protect_no_link, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCopyClaim = { row -> copyClaimText(context, row) },
                            onMarkClaimed = { viewModel.markClaimed(entity.id) },
                            onDelete = { pendingDelete = entity.toRowView() }
                        )
                    }
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.rd_protect_window_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dims.SpacingS)
                )
            }
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = purchasedAtMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { purchasedAtMs = it }
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.price_watch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.rd_protect_delete_title)) },
            text = { Text(stringResource(R.string.rd_protect_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePurchase(row.id)
                        pendingDelete = null
                    }
                ) {
                    Text(
                        stringResource(R.string.rd_protect_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 价保概览卡：可申请笔数/金额 + 最近检查 + 立即检查。
 * 没有购买记录时也照常渲染（数字为 0），因为"立即检查"与"录入购买"是两条独立路径。
 */
@Composable
private fun ProtectionSummaryCard(
    watchCount: Int,
    claimableCount: Int,
    claimableYuan: Double,
    lastCheckAtMs: Long?,
    checking: Boolean,
    onCheckNow: () -> Unit
) {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.ConfirmationNumber)
            Spacer(Modifier.width(Dims.SpacingM))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rd_protect_summary_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.rd_protect_last_check,
                        lastCheckAtMs?.let { TimeAgo.format(it) }
                            ?: stringResource(R.string.rd_watch_target_never_checked)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Dims.SpacingM))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dims.SpacingL),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatTile(
                label = stringResource(R.string.rd_protect_claimable_label_short),
                value = stringResource(R.string.rd_protect_claimable_count, claimableCount),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.rd_protect_claimable_amount_label),
                value = PriceFormatter.format(claimableYuan),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.rd_protect_list_title_short),
                value = stringResource(R.string.rd_protect_claimable_count, watchCount),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Dims.SpacingS))
        TextButton(
            onClick = onCheckNow,
            enabled = !checking,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dims.TouchMin)
        ) {
            Text(
                stringResource(
                    if (checking) R.string.rd_protect_checking else R.string.rd_protect_check_now
                )
            )
        }
    }
}

/**
 * 一条购买记录卡：标题行（平台徽标 + 标题 + 状态）→ 现价/实付 → 可申请金额与截止日 →
 * 操作（去平台申请 / 复制文案 / 标记已申请 / 删除）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PurchaseCard(
    row: PurchaseRowView,
    onApply: (PurchaseRowView) -> Unit,
    onCopyClaim: (PurchaseRowView) -> Unit,
    onMarkClaimed: () -> Unit,
    onDelete: () -> Unit
) {
    val statusRes = purchaseStatusLabelRes(row)
    val statusTone = if (row.claimableYuan > 0 && !row.claimed) BadgeTone.POSITIVE else BadgeTone.NEUTRAL
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(Dims.SpacingL)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriceBadge(stringResource(protectionPlatformLabelRes(row.platform)), BadgeTone.INFO)
                Spacer(Modifier.width(Dims.SpacingS))
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Dims.SpacingS))
                PriceBadge(stringResource(statusRes), statusTone)
            }
            Spacer(Modifier.height(Dims.SpacingS))
            Text(
                stringResource(
                    R.string.rd_protect_price_change,
                    row.lastSeenPrice?.let { PriceFormatter.format(it) }
                        ?: stringResource(R.string.rd_watch_target_current_unknown),
                    PriceFormatter.format(row.price)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.claimableYuan > 0) {
                Spacer(Modifier.height(Dims.SpacingXS))
                Text(
                    stringResource(
                        R.string.rd_protect_claimable_label,
                        PriceFormatter.format(row.claimableYuan)
                    ),
                    style = PriceType.PriceInline,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val deadlineText = row.claimDeadlineMs?.let {
                stringResource(R.string.rd_protect_deadline, dateFormat.format(Date(it)))
            }
            if (deadlineText != null) {
                Spacer(Modifier.height(Dims.SpacingXS))
                Text(
                    deadlineText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            row.lastCheckedAtMs?.let {
                Text(
                    stringResource(R.string.rd_protect_last_check, TimeAgo.format(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Dims.SpacingXS))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)) {
                if (row.claimableYuan > 0 && !row.claimed) {
                    TextButton(
                        onClick = { onApply(row) },
                        modifier = Modifier.heightIn(min = Dims.TouchMin)
                    ) { Text(stringResource(R.string.rd_protect_apply)) }
                    TextButton(
                        onClick = { onCopyClaim(row) },
                        modifier = Modifier.heightIn(min = Dims.TouchMin)
                    ) { Text(stringResource(R.string.rd_protect_copy_text)) }
                    TextButton(
                        onClick = onMarkClaimed,
                        modifier = Modifier.heightIn(min = Dims.TouchMin)
                    ) { Text(stringResource(R.string.rd_protect_mark_claimed)) }
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.heightIn(min = Dims.TouchMin)
                ) {
                    Text(
                        stringResource(R.string.rd_protect_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 购买记录 → 行模型（0 哨兵翻成 null：列表据此显示「未知」而不是 1970 年） */
data class PurchaseRowView(
    val id: Long,
    val platform: String,
    val title: String,
    val price: Double,
    val url: String?,
    val lastSeenPrice: Double?,
    val lastCheckedAtMs: Long?,
    val claimableYuan: Double,
    val claimDeadlineMs: Long?,
    val claimed: Boolean,
    val purchasedAtMs: Long
)

private fun PurchaseRecordEntity.toRowView(): PurchaseRowView = PurchaseRowView(
    id = id,
    platform = platform,
    title = title,
    price = price,
    url = url?.takeIf { it.isNotBlank() },
    lastSeenPrice = lastSeenPrice.takeIf { it > 0.0 },
    lastCheckedAtMs = lastCheckedAtMs.takeIf { it > 0L },
    claimableYuan = claimableYuan,
    claimDeadlineMs = claimDeadlineMs.takeIf { it > 0L },
    claimed = claimedAtMs > 0L,
    purchasedAtMs = purchasedAtMs
)

/** 一条记录当前该显示的结论：已申请 > 可申请 > 无现价 > 已过期 > 未降价（与仓库口径一致） */
private fun purchaseStatusLabelRes(row: PurchaseRowView): Int = when {
    row.claimed -> R.string.rd_protect_claimed
    row.claimableYuan > 0.0 -> R.string.rd_protect_state_claimable
    row.lastSeenPrice == null -> R.string.rd_protect_state_no_price
    row.claimDeadlineMs != null && row.claimDeadlineMs < System.currentTimeMillis() -> R.string.rd_protect_state_expired
    else -> R.string.rd_protect_state_not_dropped
}

/** 复制申请文案：把平台、标题、实付、现价写成一句可直接粘贴给客服的话 */
private fun copyClaimText(context: Context, row: PurchaseRowView) {
    val text = context.getString(
        R.string.rd_protect_claim_copy_line,
        context.getString(protectionPlatformLabelRes(row.platform)),
        row.title,
        PriceFormatter.format(row.price),
        row.lastSeenPrice?.let { PriceFormatter.format(it) } ?: context.getString(R.string.rd_watch_target_current_unknown)
    )
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("price_protection", text))
    Toast.makeText(context, R.string.rd_protect_copied, Toast.LENGTH_SHORT).show()
}

/** 录入表单的平台档：与价保规则表对齐（京东自营/京东第三方/淘宝天猫/拼多多/唯品会/其他） */
private val ProtectionPlatformFilter: List<String> = listOf(
    ProtectionPlatform.JD.platformKey,
    ProtectionPlatform.JD_THIRD.platformKey,
    ProtectionPlatform.TAOBAO.platformKey,
    ProtectionPlatform.PDD.platformKey,
    ProtectionPlatform.VIP.platformKey,
    ProtectionPlatform.OTHER.platformKey
)

/** 价保平台 → 文案资源（复用盯价页那批平台名，不另起说法） */
private fun protectionPlatformLabelRes(platform: String): Int = when (ProtectionPlatform.of(platform)) {
    ProtectionPlatform.JD -> R.string.watch_platform_jd
    ProtectionPlatform.JD_THIRD -> R.string.rd_protect_platform_jd_third
    ProtectionPlatform.TAOBAO -> R.string.watch_platform_taobao
    ProtectionPlatform.PDD -> R.string.watch_platform_pdd
    ProtectionPlatform.VIP -> R.string.rd_protect_platform_vip
    ProtectionPlatform.OTHER -> when (platform.lowercase()) {
        "dangdang" -> R.string.watch_platform_dangdang
        "shihuo" -> R.string.watch_platform_shihuo
        "smzdm" -> R.string.watch_platform_smzdm
        else -> R.string.rd_protect_platform_other
    }
}

/** 实付价输入框初始值：整数不带小数点，非整数保持原样 */
private fun formatEditablePrice(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** 列表/表单的日期口径：yyyy-MM-dd（价保窗口按自然日算，不显示时分） */
private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
