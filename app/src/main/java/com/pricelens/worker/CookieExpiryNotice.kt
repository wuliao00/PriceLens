package com.pricelens.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.main.MainActivity
import com.pricelens.util.LogT

/**
 * 慢慢买 Cookie 到期提醒（文档 §2.5）。
 *
 * 依据：慢慢买的登录态通常 7~30 天有效（设置页也是这么写的），而到期后**现象是静默的**
 * ——曲线不再更新、取数失败，用户不知道是"网站没数据"还是"自己该重新登录"。
 * 所以在盯价轮次里顺带看一眼抓取时间，超过阈值就发一条**低优先级**通知，
 * 点开就是设置页；同一次抓取只提醒一次（[SettingsRepository.cookieExpiryNotifiedFor]）。
 *
 * 判定逻辑抽成纯函数 [cookieExpiryDue]，JVM 可测。
 */
object CookieExpiryNotice {

    /** 阈值：25 天（"7~30 天有效"的下沿之前提醒，留出重新登录的余量） */
    const val THRESHOLD_DAYS: Long = 25L

    /**
     * 该不该提醒。纯函数。
     *
     * @param hasCookie 现在有没有 Cookie（没有就是不提醒：没配的人不需要"到期"）
     * @param fetchedAt 抓取时刻（毫秒），0 = 没记录（老数据）→ 不提醒（宁可不提，也不凭空断言"快过期"）
     * @param notifiedFor 已提醒过的那次 fetchedAt
     */
    fun cookieExpiryDue(
        hasCookie: Boolean,
        fetchedAt: Long,
        notifiedFor: Long,
        nowMs: Long,
        thresholdDays: Long = THRESHOLD_DAYS
    ): Boolean {
        if (!hasCookie || fetchedAt <= 0L) return false
        if (notifiedFor == fetchedAt) return false
        val ageDays = (nowMs - fetchedAt) / 86_400_000L
        return ageDays >= thresholdDays
    }

    /** 在盯价轮次里调用；失败只记日志（提醒不上不该影响盯价本身） */
    fun checkAndNotify(context: Context, settings: SettingsRepository) {
        val fetchedAt = settings.manmanbuyCookieFetchedAt
        val hasCookie = settings.manmanbuyCookie.isNotBlank()
        if (!cookieExpiryDue(hasCookie, fetchedAt, settings.cookieExpiryNotifiedFor, System.currentTimeMillis())) {
            return
        }
        val ageDays = (System.currentTimeMillis() - fetchedAt) / 86_400_000L
        runCatching {
            val intent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, "watch_status")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.cookie_expiry_title))
                .setContentText(context.getString(R.string.cookie_expiry_text, ageDays))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.cookie_expiry_big, ageDays)))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            settings.cookieExpiryNotifiedFor = fetchedAt
            LogT.i("慢慢买 Cookie 已抓取 $ageDays 天：已发到期提醒（同一次抓取只提醒一次）")
        }.onFailure { e ->
            LogT.w("Cookie 到期提醒发送失败(${e.javaClass.simpleName})：不影响盯价")
        }
    }

    private const val NOTIFICATION_ID = 4202
}
