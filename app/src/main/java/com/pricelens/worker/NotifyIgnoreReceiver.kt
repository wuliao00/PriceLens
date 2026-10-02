package com.pricelens.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.pricelens.util.LogT

/**
 * 降价通知上的「忽略」动作：记下当前价（价格不变就不再提醒，创新低自动解除），
 * 并撤掉这条通知。进程可能没在跑 —— 广播会拉起本类，所有步骤 runCatching（通知动作绝不许崩）。
 */
class NotifyIgnoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_NOTIFY_IGNORE) return
        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID) ?: return
        val price = intent.getDoubleExtra(EXTRA_PRICE, 0.0)
        runCatching {
            NotifyIgnoreStore(context).ignore(productId, price)
            NotificationManagerCompat.from(context).cancel(productId.hashCode())
        }.onFailure { LogT.w("忽略降价通知失败：${it.javaClass.simpleName}") }
    }

    companion object {
        const val ACTION_NOTIFY_IGNORE = "com.pricelens.action.NOTIFY_IGNORE"
        const val EXTRA_PRODUCT_ID = "productId"
        const val EXTRA_PRICE = "price"
    }
}
