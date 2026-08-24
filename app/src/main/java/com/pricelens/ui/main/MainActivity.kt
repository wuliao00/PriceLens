package com.pricelens.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.R
import com.pricelens.accessibility.OverlayManager
import com.pricelens.ui.bilibili.BilibiliScreen
import com.pricelens.ui.community.CommunityScreen
import com.pricelens.ui.coupon.CouponScreen
import com.pricelens.ui.overview.OverviewScreen
import com.pricelens.ui.price.PriceScreen
import com.pricelens.ui.profile.ProfileScreen
import com.pricelens.ui.settings.SettingsScreen
import com.pricelens.ui.theme.PriceLensTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val focusTitle = intent?.getStringExtra("focus_title")
        setContent {
            // 设置页的“动态取色”开关在 Activity 级生效
            val dynamicColor by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            PriceLensTheme(dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        initialKeyword = focusTitle,
                        overlayPermissionAvailable = !OverlayManager.canDrawOverlays(this),
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    OVERVIEW("概览"), BILIBILI("B站"), PRICE("盯价"),
    COUPON("找券"), COMMUNITY("社区"), PROFILE("我的")
}

@Composable
fun MainScreen(
    initialKeyword: String?,
    overlayPermissionAvailable: Boolean,
    viewModel: MainViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 免责声明：用户勾选"不再提示"后持久化，之后不再弹出
    val prefs = remember {
        context.getSharedPreferences("pricelens", android.content.Context.MODE_PRIVATE)
    }
    var showDisclaimer by remember {
        mutableStateOf(!prefs.getBoolean("disclaimer_dismissed", false))
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showScripts by rememberSaveable { mutableStateOf(false) }

    // 通知权限（Android 13+ 需运行时申请，盯价提醒依赖它）
    var notificationAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
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

    // 作者 / 免费声明：默认每次冷启动弹出；"不再提示"后持久化不再打扰
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = {
                Text(
                    stringResource(R.string.disclaimer_body),
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = { showDisclaimer = false }) {
                    Text(stringResource(R.string.disclaimer_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("disclaimer_dismissed", true).apply()
                    showDisclaimer = false
                }) { Text("不再提示") }
            }
        )
    }

    androidx.compose.runtime.LaunchedEffect(initialKeyword) {
        if (!initialKeyword.isNullOrBlank()) viewModel.search(initialKeyword)
    }

    Scaffold(
        topBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    com.pricelens.ui.components.SearchBar(
                        value = state.keyword,
                        onValueChange = viewModel::updateKeyword,
                        onSubmit = { viewModel.search(state.keyword) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            }
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
                                contentDescription = t.label
                            )
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        val content = @Composable {
            when (tab) {
                Tab.OVERVIEW -> OverviewScreen(state, onGoBilibili = { tab = Tab.BILIBILI })
                Tab.BILIBILI -> BilibiliScreen(state)
                Tab.PRICE -> PriceScreen(state)
                Tab.COUPON -> CouponScreen(state)
                Tab.COMMUNITY -> CommunityScreen(state)
                Tab.PROFILE -> ProfileScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettings = true },
                    onOpenScripts = { showScripts = true }
                )
            }
        }
        Column(Modifier.padding(inner).fillMaxSize()) { content() }
    }

    // 设置页：全屏覆盖，权限 / 外观 / 数据 / 关于
    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
    }

    // 自定义脚本页：Shizuku ADB 级 shell 执行
    if (showScripts) {
        com.pricelens.ui.scripts.ScriptScreen(onBack = { showScripts = false })
    }
}
