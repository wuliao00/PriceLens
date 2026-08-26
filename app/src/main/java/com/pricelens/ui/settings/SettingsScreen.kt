package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.BuildConfig
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.profile.ProfileViewModel
import com.pricelens.ui.theme.Dims

/**
 * 设置页（阶段4 拆分）：主壳只负责 Scaffold / 顶栏 / 滚动容器与页脚，
 * 内容按 外观/权限/数据/关于 拆到同包 4 个子文件：
 * [AppearanceSection] / [PermissionSection] / [DataSection] / [AboutSection]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val cacheStats by profileViewModel.cacheStats.collectAsStateWithLifecycle()

    // 进入设置页自动计算一次缓存占用（否则一直停在"计算中…"）
    LaunchedEffect(Unit) { profileViewModel.refreshCacheStats() }

    // 评审修复：与主屏 AppTopBar 一致，顶栏随内容滚动隐去（enterAlways）
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dims.SpacingL)
        ) {
            AppearanceSection(settings)
            PermissionSection()
            DataSection(
                cacheStats = cacheStats,
                onRefresh = { profileViewModel.refreshCacheStats() },
                onClear = { profileViewModel.clearCache() }
            )
            AboutSection(BuildConfig.VERSION_NAME)

            Spacer(Modifier.height(Dims.SpacingS))
            Text(
                stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dims.SpacingXL),
                textAlign = TextAlign.Center
            )
        }
    }
}
