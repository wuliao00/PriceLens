package com.pricelens.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricelens.data.repository.PriceProtectionRepository
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 价保助手周期检查（每 [PERIOD_HOURS] 小时一次；WorkManager 周期任务最小 15 分钟，6 小时足够）。
 *
 * 价格一天不会变几十次，价保窗口以「天」计，6 小时的检查密度既不影响时效，也不浪费流量。
 * 约束：网络可用 + 电量不低（与自动追价链一致）。
 * 开关：设置里的 `auto_protection_enabled`（[SettingsRepository.autoProtectionEnabled]），关闭后
 * Worker 醒来立即返回、不发任何通知。
 *
 * @see PriceProtectionRepository.checkAll
 */
@HiltWorker
class PriceProtectionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: PriceProtectionRepository,
    private val settings: SettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.autoProtectionEnabled) {
            LogT.i("价保检查跳过：价保助手已关闭")
            return Result.success()
        }
        return try {
            val result = repository.checkAll()
            LogT.i(
                "价保检查完成：查到价 ${result.checked}，新可申请 ${result.newClaimable}" +
                    "（${result.details.joinToString("；")}）"
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败走 WorkManager 退避重试（价保检查没有自适应链，退避是正确选择）
            LogT.e("价保检查失败（等待退避重试）：${e.javaClass.simpleName} ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        /** 周期任务的唯一名（App 启动/开机都补登记，KEEP 保证不会重复排） */
        const val UNIQUE_NAME: String = "price_protection_check"

        /** 检查周期（小时）：与 [PriceProtectionRepository.PRICE_PROTECTION_PERIOD_MS] 对齐 */
        const val PERIOD_HOURS: Long = 6L

        /** 补登记价保周期检查（幂等：已存在则 KEEP） */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceProtectionWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** 取消价保周期检查（价保助手被关闭时可选调用；KEEP 保留已排任务不影响正确性） */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
