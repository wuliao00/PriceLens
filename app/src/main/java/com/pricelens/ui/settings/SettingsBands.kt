package com.pricelens.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pricelens.ui.components.SectionHeader
import com.pricelens.ui.theme.Dims

/**
 * 设置页的分组骨架（2026-10-02 版式统一）。
 *
 * 2.8.0 的真实排布是八个 section 平铺：外观 / 权限 / 数据 / 备份 / 凭证 / 通知 / 诊断 / 关于，
 * 没有"组"这一层，滚到半途说不出自己在哪一组。现在按用户指定的五组收口：
 *
 *  ① 账号与凭证 = 权限 + 数据源凭证
 *  ② 通知与提醒 = 通知
 *  ③ 数据与备份 = 数据 + 备份与恢复
 *  ④ 外观 = 外观
 *  ⑤ 关于与诊断 = 关于 + 诊断
 *
 * 三条被点名的既有相邻关系一条没动：**Backup 紧跟 Data 之后** ✓、**Notify 在 Credentials 之后** ✓、
 * **Diagnostics 收尾**（全页最后一格）✓。位移只有一处需要说明：Appearance 从第 1 位挪到第 4 位——
 * 它只是一个"动态取色"开关，摆最前会把真正要办的授权路径推到第二屏；Permission 与 Credentials
 * 同组（都是"让本机账号能取到数"）。
 *
 * 两级标题的分工：组标题用共用组件 [SectionHeader]（本批改它的人不是我，只调用），
 * 组内各 section 的原标题降为 [SettingsSubtitle]；单 section 的组（②④）不再重复出小标题，
 * 免得"通知与提醒 / 通知"连着读两遍。
 */
@Composable
fun SettingsBand(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // 组间留白 = GroupGap + SectionHeader 自带的 20dp 顶部留白；靠留白认组，不画分割线
        Spacer(Modifier.height(Dims.GroupGap))
        SectionHeader(title)
        content()
    }
}

/** 组内小标题：比组标题轻一档（次要色 + 无 primary 着色），层级一眼可辨 */
@Composable
fun SettingsSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dims.SpacingM, bottom = Dims.SpacingXS)
    )
}
