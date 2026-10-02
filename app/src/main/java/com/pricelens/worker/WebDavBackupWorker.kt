package com.pricelens.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pricelens.data.backup.BackupRepository
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * §五 每周自动备份（仅 WebDAV）：
 *  - 7 天周期 + 网络约束；**未配置 WebDAV 直接空跑**（return success，不算失败）；
 *  - 只在「上次成功备份 > 6.5 天或从未成功」时才真正备份（简化策略，别过度设计）：
 *    周期任务是 7 天一次，6.5 天阈值把"用户刚手动备份过"的重复上传挡掉；
 *  - 「立即备份」按钮不走本 Worker，直接调 [BackupRepository.backupNow]。
 *
 * 调度方式照抄 [PriceCheckWorker]（unique periodic KEEP，唯一名 `"webdav_backup"`）。
 */
@HiltWorker
class WebDavBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backup: BackupRepository,
    private val settings: SettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 没配 WebDAV：周期任务空转（不是错误，也不提示用户——没配就没这个功能）
        if (!backup.isConfigured()) return Result.success()
        val last = settings.webdavLastBackupAtMs
        val due = last <= 0L || System.currentTimeMillis() - last > DUE_INTERVAL_MS
        if (!due) return Result.success()
        return backup.backupNow().fold(
            onSuccess = { name ->
                LogT.i("WEBDAV 每周自动备份完成：$name")
                Result.success()
            },
            onFailure = { e ->
                LogT.w("WEBDAV 每周自动备份失败（将按退避重试）：${e.message}")
                Result.retry()
            }
        )
    }

    companion object {
        /** 6.5 天：上次成功超过这个间隔或从未成功才真正备份 */
        const val DUE_INTERVAL_MS = (6.5 * 24 * 3600 * 1000).toLong()

        /** 每周一次（WorkManager 周期下限是 15 分钟，7 天无压力）；网络可用即执行 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "webdav_backup",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
