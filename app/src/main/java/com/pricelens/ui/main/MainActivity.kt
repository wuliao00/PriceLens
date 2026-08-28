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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.components.AppTopBar
import com.pricelens.ui.overview.SearchViewModel
import com.pricelens.ui.theme.MotionDurations
import com.pricelens.ui.theme.PriceLensEasing
import com.pricelens.ui.theme.PriceLensTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 阶段2：设置单点收口（动态取色 / 免责声明），不再裸取 prefs */
    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val focusTitle = intent?.getStringExtra("focus_title")
        setContent {
            // 设置页的“动态取色”开关在 Activity 级生效
            val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
            PriceLensTheme(dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        initialKeyword = focusTitle,
                        overlayPermissionAvailable = !OverlayManager.canDrawOverlays(this),
                        settings = settings
                    )
                }
            }
        }
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
fun MainScreen(initialKeyword: String?, overlayPermissionAvailable: Boolean, settings: SettingsRepository) {
    // 阶段2：搜索编排集中在 SearchViewModel（Activity 作用域单例，跨标签共享）
    val searchViewModel: SearchViewModel = hiltViewModel()
    val keyword by searchViewModel.keyword.collectAsStateWithLifecycle()

    // 顺从原则：顶栏随内容滚动自动隐去（enterAlways），向下滚动立即回归
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topBarState)
    // 深度原则：Tab 转场微位移量（12dp，落在 8-16dp 区间）
    val tabShiftPx = with(LocalDensity.current) { 12.dp.roundToPx() }

    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    var showDisclaimer by remember { mutableStateOf(!settings.disclaimerAgreed) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showScripts by rememberSaveable { mutableStateOf(false) }

    // 通知权限（Android 13+ 需运行时申请，盯价提醒依赖它）
    var notificationAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    // 声明确认后顺手请求通知权限
    androidx.compose.runtime.LaunchedEffect(showDisclaimer, notifGranted) {
        if (!showDisclaimer && !notificationAsked && !notifGranted) {
            notificationAsked = true
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 作者 / 免费声明：未同意前启动弹出；阅读 30 秒后方可同意，同意即持久化不再展示
    if (showDisclaimer) {
        DisclaimerDialog(
            onDismissRequest = { showDisclaimer = false },
            onAgree = {
                settings.setDisclaimerAgreed(true)
                showDisclaimer = false
            }
        )
    }

    androidx.compose.runtime.LaunchedEffect(initialKeyword) {
        if (!initialKeyword.isNullOrBlank()) searchViewModel.search(initialKeyword)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopBar(
                keyword = keyword,
                onKeywordChange = searchViewModel::updateKeyword,
                onSearch = { searchViewModel.search(keyword) },
                onOpenSettings = { showSettings = true },
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
                    Tab.PRICE -> com.pricelens.ui.price.PriceScreen(searchViewModel)
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

    // 设置页：全屏覆盖，权限 / 外观 / 数据 / 关于
    if (showSettings) {
        com.pricelens.ui.settings.SettingsScreen(settings = settings, onBack = { showSettings = false })
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
