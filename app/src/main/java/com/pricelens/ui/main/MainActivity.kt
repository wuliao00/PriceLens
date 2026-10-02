package com.pricelens.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.components.AppTopBar
import com.pricelens.ui.onboarding.OnboardingFlow
import com.pricelens.ui.onboarding.SetupHintBar
import com.pricelens.ui.onboarding.rememberPermissionStates
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceLensTheme
import com.pricelens.ui.update.ForcedUpdateDialog
import com.pricelens.ui.update.SkippableUpdateDialog
import com.pricelens.update.UpdateRepository
import com.pricelens.update.UpdateState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * 一次"要搜索的入口"（文档 §4.3）：分享、浏览器打开、无障碍浮窗的 focus_title 都汇到这里。
 *
 * 为什么要 nonce：Compose 的 `LaunchedEffect(key)` 在 key 相等时不会重跑，
 * 而"同一段文本再来一次"（比如用户又分享了一次同一个链接）必须能再次触发搜索。
 */
data class IncomingSearch(val text: String, val nonce: Long)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 阶段2：设置单点收口（动态取色 / 免责声明），不再裸取 prefs */
    @Inject
    lateinit var settings: SettingsRepository

    /** v2.6.0：更新闸门（清单拉取 + 判定 + 下载安装状态都收口在这里） */
    @Inject
    lateinit var updateRepository: UpdateRepository

    private var incomingSearch by mutableStateOf<IncomingSearch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            // 设置页的“动态取色”开关在 Activity 级生效
            val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
            PriceLensTheme(dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        incomingSearch = incomingSearch,
                        overlayPermissionAvailable = !OverlayManager.canDrawOverlays(this),
                        settings = settings,
                        updateRepository = updateRepository
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * 分享 / 浏览器打开 / 浮窗"去比价"（focus_title）三合一。
     * 不在这里解析链接：短链跳转要联网，交给搜索流程（它已有 SKU 解析与历史价加载）。
     */
    private fun handleIntent(intent: android.content.Intent?) {
        val text = when (intent?.action) {
            android.content.Intent.ACTION_SEND -> intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            android.content.Intent.ACTION_VIEW -> intent.dataString
            else -> intent?.getStringExtra("focus_title")
        }?.trim()
        if (text.isNullOrBlank()) return
        incomingSearch = IncomingSearch(text, System.currentTimeMillis())
    }
}

private enum class Tab(@StringRes val labelRes: Int) {
    OVERVIEW(R.string.tab_overview),
    BILIBILI(R.string.tab_bilibili),
    PRICE(R.string.tab_price),
    COUPON(R.string.tab_coupon),
    COMMUNITY(R.string.tab_community),
    PROFILE(R.string.tab_profile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    incomingSearch: IncomingSearch?,
    overlayPermissionAvailable: Boolean,
    settings: SettingsRepository,
    updateRepository: UpdateRepository
) {
    // 阶段2：搜索编排集中在 SearchViewModel（Activity 作用域单例，跨标签共享）
    val searchViewModel: SearchViewModel = hiltViewModel()
    val priceWatchViewModel: com.pricelens.ui.price.PriceWatchViewModel = hiltViewModel()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()
    // 搜索框聚焦时展示的历史 chips（文档 UX）——「我的」页搜索历史同一数据源
    val recentSearches by searchViewModel.recentSearches.collectAsStateWithLifecycle()

    // 顺从原则：顶栏随内容滚动自动隐去（enterAlways），向下滚动立即回归
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topBarState)
    // 深度原则：Tab 转场微位移量（12dp，落在 8-16dp 区间）
    val tabShiftPx = with(LocalDensity.current) { 12.dp.roundToPx() }

    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    var showDisclaimer by remember { mutableStateOf(!settings.disclaimerAgreed) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searchFocused by rememberSaveable { mutableStateOf(false) }
    var showKeepAlive by rememberSaveable { mutableStateOf(false) }
    var showScripts by rememberSaveable { mutableStateOf(false) }
    // 首启引导：完成/跳过后持久化 onboardingDone，之后只从设置页"重新查看新手引导"进入
    var showOnboarding by rememberSaveable { mutableStateOf(!settings.onboardingDone) }
    var setupHintDismissed by rememberSaveable { mutableStateOf(false) }

    val updateState by updateRepository.state.collectAsStateWithLifecycle()
    val downloadState by updateRepository.downloadState.collectAsStateWithLifecycle()
    val permissionStates = rememberPermissionStates()

    // 通知权限（Android 13+ 需运行时申请，盯价提醒依赖它）
    var notificationAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    // 声明确认后顺手请求通知权限（引导进行中不打断，引导结束再问）
    LaunchedEffect(showDisclaimer, notifGranted) {
        if (!showDisclaimer && !showOnboarding && !notificationAsked && !notifGranted) {
            notificationAsked = true
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 更新闸门只在冷启动判定一次（versionCode 由外部传入，判定逻辑不读 BuildConfig）
    LaunchedEffect(Unit) {
        updateRepository.checkOnColdStart(BuildConfig.VERSION_CODE)
    }

    // 分享 / 浏览器 / 浮窗入口：每次带 nonce 的请求都切回概览并搜索
    LaunchedEffect(incomingSearch) {
        val text = incomingSearch?.text
        if (!text.isNullOrBlank()) {
            tab = Tab.OVERVIEW
            searchViewModel.search(text)
        }
    }

    // 剪贴板识别（文档 §4.2）：回前台时读一次，认出商品链接就在概览页顶部给一条横条。
    // Android 10+ 只有前台应用能读剪贴板，所以必须挂在 ON_RESUME 上；同一段内容只提示一次。
    var clipboardLink by remember { mutableStateOf<com.pricelens.domain.LinkParser.ParsedLink?>(null) }
    val clipboardLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(clipboardLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && settings.clipboardDetectEnabled) {
                clipboardLink = com.pricelens.ui.common.ClipboardDetector.detect(context)
            }
        }
        clipboardLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { clipboardLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 阻断层严格互斥、顺序固定：免责声明 → 强制更新 → 新手引导。
    // 同一时刻只存在一个阻断面，绝不叠两层（强制更新排在声明之后，避免用户还没看完声明就被锁）。
    if (showDisclaimer) {
        DisclaimerDialog(
            onDismissRequest = { showDisclaimer = false },
            onAgree = {
                settings.setDisclaimerAgreed(true)
                showDisclaimer = false
            }
        )
        return
    }

    val forced = updateState as? UpdateState.Forced
    if (forced != null) {
        ForcedUpdateDialog(
            offer = forced.offer,
            download = downloadState,
            repository = updateRepository
        )
        return
    }

    if (showOnboarding) {
        OnboardingFlow(
            settings = settings,
            onFinish = {
                showOnboarding = false
                setupHintDismissed = false
            }
        )
        return
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopBar(
                keyword = keyword,
                onKeywordChange = searchViewModel::updateKeyword,
                onSearch = {
                    searchFocused = false
                    searchViewModel.search(keyword)
                },
                onOpenSettings = { showSettings = true },
                onFocusChanged = { searchFocused = it },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.OVERVIEW -> Icons.Filled.QueryStats
                                    Tab.BILIBILI -> Icons.Filled.OndemandVideo
                                    Tab.PRICE -> Icons.Filled.PriceCheck
                                    Tab.COUPON -> Icons.Filled.ConfirmationNumber
                                    Tab.COMMUNITY -> Icons.Filled.ChatBubble
                                    Tab.PROFILE -> Icons.Filled.Person
                                },
                                contentDescription = stringResource(t.labelRes)
                            )
                        },
                        label = { Text(stringResource(t.labelRes)) }
                    )
                }
            }
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            // 剪贴板横条：只在概览页、只在认出商品链接时出现；「忽略」= 同一段内容不再提示
            val detected = clipboardLink
            if (tab == Tab.OVERVIEW && detected != null) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.clipboard_banner_text,
                                detected.platform?.label ?: stringResource(R.string.clipboard_banner_unknown)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val target = detected.url.ifBlank { detected.raw }
                            com.pricelens.ui.common.ClipboardDetector.markIgnored(detected.raw)
                            clipboardLink = null
                            tab = Tab.OVERVIEW
                            searchViewModel.search(target)
                        }) {
                            Text(stringResource(R.string.clipboard_banner_go))
                        }
                        TextButton(onClick = {
                            com.pricelens.ui.common.ClipboardDetector.markIgnored(detected.raw)
                            clipboardLink = null
                        }) {
                            Text(stringResource(R.string.clipboard_banner_ignore))
                        }
                    }
                }
            }
            // 搜索框聚焦时展示搜索历史 chips（文档 UX）：最近搜过的一步直达
            if (tab == Tab.OVERVIEW && searchFocused && recentSearches.isNotEmpty()) {
                SearchHistoryStrip(recentSearches) { kw ->
                    searchFocused = false
                    searchViewModel.research(kw)
                }
            }
            // 引导跳过/完成后仍缺必要权限：首页顶部给一条可关闭的提示条（含"重开引导"入口）
            if (tab == Tab.OVERVIEW && !permissionStates.essentialsReady && !setupHintDismissed) {
                SetupHintBar(
                    missing = permissionStates.missingEssentials,
                    onReopenOnboarding = {
                        setupHintDismissed = false
                        showOnboarding = true
                    },
                    onDismiss = { setupHintDismissed = true }
                )
            }
            // 深度原则：Tab 切换转场——交叉淡入 + 微位移（克制，250ms 标准时长）
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val fadeSpec = tween<Float>(MotionDurations.Standard, easing = PriceLensEasing)
                    val slideSpec = tween<IntOffset>(MotionDurations.Standard, easing = PriceLensEasing)
                    (fadeIn(fadeSpec) + slideInVertically(slideSpec) { tabShiftPx })
                        .togetherWith(fadeOut(fadeSpec) + slideOutVertically(slideSpec) { -tabShiftPx })
                },
                label = "tabSwitch"
            ) { targetTab ->
                when (targetTab) {
                    Tab.OVERVIEW -> com.pricelens.ui.overview.OverviewScreen(
                        searchViewModel,
                        onGoBilibili = { tab = Tab.BILIBILI }
                    )
                    Tab.BILIBILI -> com.pricelens.ui.bilibili.BilibiliScreen(searchViewModel)
                    Tab.PRICE -> com.pricelens.ui.price.PriceScreen(searchViewModel, priceWatchViewModel)
                    Tab.COUPON -> com.pricelens.ui.coupon.CouponScreen(searchViewModel)
                    Tab.COMMUNITY -> com.pricelens.ui.community.CommunityScreen(searchViewModel)
                    Tab.PROFILE -> com.pricelens.ui.profile.ProfileScreen(
                        onOpenSettings = { showSettings = true },
                        onOpenScripts = { showScripts = true }
                    )
                }
            }
        }
    }

    // 两级可跳过的更新提示：主界面之上，随时能走（与阻断层互斥，此处必然已在主界面）
    when (val state = updateState) {
        is UpdateState.StrongHint -> SkippableUpdateDialog(
            offer = state.offer,
            strong = true,
            download = downloadState,
            repository = updateRepository
        )
        is UpdateState.Optional -> SkippableUpdateDialog(
            offer = state.offer,
            strong = false,
            download = downloadState,
            repository = updateRepository
        )
        else -> {}
    }

    // 设置页：全屏覆盖，权限 / 外观 / 数据 / 关于
    if (showSettings) {
        com.pricelens.ui.settings.SettingsScreen(
            settings = settings,
            updateRepository = updateRepository,
            onBack = { showSettings = false },
            onReplayOnboarding = {
                showSettings = false
                showOnboarding = true
            },
            onOpenKeepAlive = {
                showSettings = false
                showKeepAlive = true
            }
        )
    }

    // 后台保活引导页（文档 §12）：从设置页进入，返回时回到设置页
    if (showKeepAlive) {
        com.pricelens.ui.settings.KeepAliveScreen(
            onBack = {
                showKeepAlive = false
                showSettings = true
            }
        )
    }

    // 自定义脚本页：Shizuku ADB 级 shell 执行
    if (showScripts) {
        com.pricelens.ui.scripts.ScriptScreen(onBack = { showScripts = false })
    }
}

/**
 * 免费声明弹窗：正文可滚动，30 秒倒计时结束前「同意」按钮禁用，
 * 倒计时期间用户只能阅读和滚动。
 */
@Composable
private fun DisclaimerDialog(onDismissRequest: () -> Unit, onAgree: () -> Unit) {
    var countdown by remember { mutableStateOf(30) }
    // 倒计时：每秒递减，归零后按钮启用；弹窗销毁时协程随组合自动取消
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1_000)
            countdown--
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.disclaimer_body),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAgree,
                enabled = countdown == 0
            ) {
                Text(
                    if (countdown > 0) {
                        stringResource(R.string.disclaimer_ok_countdown, stringResource(R.string.disclaimer_ok), countdown)
                    } else {
                        stringResource(R.string.disclaimer_ok)
                    }
                )
            }
        }
    )
}
