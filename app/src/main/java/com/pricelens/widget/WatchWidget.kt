package com.pricelens.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.pricelens.R
import com.pricelens.ui.main.MainActivity
import com.pricelens.worker.WidgetSnapshot
import com.pricelens.worker.WidgetStateStore
import com.pricelens.worker.WidgetStats

/**
 * 小组件点击传给 MainActivity 的 extra：让 App 打开后直接切到盯价 tab
 * （key 名与 [MainActivity.handleIntent] 读的那个常量一致）。
 */
private val OpenWatchTabKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_OPEN_WATCH_TAB)

/**
 * 盯价桌面小组件（用户优化文档 §七，4x2）：三行内容 ——
 * 「盯价中 N 个」（加粗）/「累计降价 M 次」/「上次检查 HH:mm」。
 *
 * 数据只读 [WidgetStateStore] 的持久快照：每个盯价轮次收尾时由
 * [com.pricelens.worker.WatchCheckRunner] 写入快照并调用 [updateAll] 刷新本组件，
 * 因此这里不做任何 Room/网络查询。点击任意位置打开 App 并切到盯价 tab。
 */
class WatchWidget : GlanceAppWidget() {

    /** 4x2 是承诺的形态；Exact 让系统在任意格子尺寸下都按实际尺寸重新组合（不拉伸旧图） */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStateStore(context).snapshot()
        provideContent {
            GlanceTheme {
                WatchWidgetContent(snapshot)
            }
        }
    }
}

@Composable
private fun WatchWidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(OpenWatchTabKey to true))),
        verticalAlignment = Alignment.Vertical.Top,
        horizontalAlignment = Alignment.Horizontal.Start
    ) {
        Text(
            text = context.getString(R.string.widget_watching, snapshot.watching),
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
            maxLines = 1
        )
        Text(
            text = context.getString(R.string.widget_dropped, snapshot.dropped),
            style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
            maxLines = 1
        )
        Text(
            text = context.getString(R.string.widget_last_check, WidgetStats.clockText(snapshot.lastCheckAt)),
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1
        )
    }
}

/** Glance 官方模板 receiver：把 APPWIDGET_UPDATE 等系统回调转给 [WatchWidget] */
class WatchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WatchWidget()
}
