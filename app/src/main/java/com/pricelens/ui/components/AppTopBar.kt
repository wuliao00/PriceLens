package com.pricelens.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pricelens.R

/** 顶栏半透明度（顺从原则：不抢内容视线，滚动时自动隐去） */
private const val TOP_BAR_ALPHA = 0.92f

/**
 * 应用主顶栏（顺从原则核心落点）：
 *  - 半透明 surface 背景（[TOP_BAR_ALPHA]），与内容层级柔和衔接
 *  - 搭配 [TopAppBarDefaults.enterAlwaysScrollBehavior] 时，向上滚动自动隐去、
 *    向下滚动立即回归——顶栏为内容让路
 *  - 内嵌无边框搜索框（高对比：onSurface 文字 / surfaceVariant 底）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val barColor = MaterialTheme.colorScheme.surface.copy(alpha = TOP_BAR_ALPHA)
    TopAppBar(
        title = {
            TextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 52.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_cd_search))
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
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
