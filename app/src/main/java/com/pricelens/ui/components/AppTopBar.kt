package com.pricelens.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.ui.theme.PillShape

/** 顶栏半透明度（顺从原则：不抢内容视线，滚动时自动隐去） */
private const val TOP_BAR_ALPHA = 0.94f

/**
 * 应用主顶栏（顺从原则核心落点）：
 *  - 半透明 surface 背景（[TOP_BAR_ALPHA]），与内容层级柔和衔接
 *  - 搭配 [TopAppBarDefaults.enterAlwaysScrollBehavior] 时，向上滚动自动隐去、
 *    向下滚动立即回归——顶栏为内容让路
 *  - 内嵌无边框搜索框（高对比：onSurface 文字 / surfaceContainerHigh 底）
 *
 * 2026-11 大改造：左侧加一枚金色品牌标记（价格放大镜），搜索框收成全圆角胶囊，
 * 聚焦/未聚焦两档容器色都走新的 surfaceContainer 阶梯，设置键保留在右。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val barColor = MaterialTheme.colorScheme.background.copy(alpha = TOP_BAR_ALPHA)
    TopAppBar(
        navigationIcon = {
            // 品牌标记：纯展示（不是按钮），给顶栏一个识别锚点； tint 走主题金。
            // 尺寸/边距收一档（28→26dp、start 12→10、end 4→2）：412dp 下把宽度让给搜索框，
            // 保证占位文案完整可见（真机 2026-11 首轮：占位被截掉最后一个字）。
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = stringResource(R.string.app_name),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 10.dp, end = 2.dp)
                    .size(26.dp)
            )
        },
        title = {
            TextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 52.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                placeholder = {
                    Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.search_cd_search),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { onKeywordChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_cd_clear))
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = PillShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() })
            )
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
            }
        },
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = barColor,
            scrolledContainerColor = barColor
        ),
        scrollBehavior = scrollBehavior
    )
}
