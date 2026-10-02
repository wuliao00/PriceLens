package com.pricelens.data.backup

import android.content.Context
import android.net.Uri
import com.pricelens.data.local.AppDatabase
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** 备份/恢复影响的条目数（「恢复 N 个收藏 / M 个盯价目标」的 N/M 出处） */
data class BackupCounts(val favorites: Int, val targets: Int)

/**
 * §五 备份仓储：WebDAV（主路径）与 SAF 本地文件（兜底路径）共享同一份 payload 编解码与合并规则。
 *
 * WebDAV 约定（见 [WebDavClient] 注释）：
 *  - 目录 `/pricelens/`；文件 `pricelens-<yyyyMMdd-HHmmss>.json`；
 *  - 每次备份成功后只保留最新 [BackupFormat.KEEP_LATEST] 份（按 getlastmodified 降序删旧，
 *    删除失败**吞掉**——备份已经成功，清理是尽力而为）；
 *  - 凭证（地址/账号/密码）从 [SettingsRepository]（SecretStore 密文）现取现用，绝不落库、绝不出现在 payload 里。
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val db: AppDatabase
) {

    /** 专用客户端：15s 三超时（进度反馈在 UI 侧；这里不配长重试，失败就报失败） */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(WebDavClient.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WebDavClient.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WebDavClient.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 是否已配置 WebDAV（每周自动备份只在这条为真时才真正跑） */
    fun isConfigured(): Boolean = settings.webdavUrl.isNotBlank()

    private fun client(): WebDavClient = WebDavClient(settings.webdavUrl, settings.webdavUser, settings.webdavPassword, http)

    private fun client(url: String, user: String, password: String): WebDavClient = WebDavClient(url, user, password, http)

    /**
     * 测试连接：用**输入框当前值**（不是已保存值）打一次测活，未保存也能先试。
     * 地址为空 / 非法如实返回失败原因，不假装"成功"。
     */
    suspend fun testConnection(url: String, user: String, password: String): Result<WebDavProbe> = runCatching {
        if (url.isBlank()) throw IOException("先填 WebDAV 地址")
        client(url, user, password).testConnection()
    }

    /** 远端备份文件列表（最新在前）；失败给可读原因 */
    suspend fun listRemote(): Result<List<DavItem>> = runCatching {
        if (!isConfigured()) throw IOException("还没有配置 WebDAV 地址")
        val items = client().list(BackupFormat.DIR) ?: throw IOException("没能读到远端目录（网络/权限/地址）")
        BackupRetention.newestFirst(items.filter { !it.isCollection && BackupFormat.isBackupFileName(it.fileName) })
    }

    /**
     * 立即备份：mkcol → put 新文件 → 保留最新 N 份（删旧失败吞掉）。
     * @return 备份文件名（成功那句反馈要用它）
     */
    suspend fun backupNow(): Result<String> = runCatching {
        if (!isConfigured()) throw IOException("还没有配置 WebDAV 地址")
        val dav = client()
        if (!dav.mkcol(BackupFormat.DIR)) throw IOException("远端创建目录 ${BackupFormat.DIR}/ 失败（权限或路径不对）")
        val now = System.currentTimeMillis()
        val name = BackupFormat.fileName(now)
        val payload = BackupCodec.capture(db.productDao().getPinnedOnce(), db.priceTargetDao().getAllActive(), now)
        dav.put("${BackupFormat.DIR}/$name", BackupCodec.encode(payload))
        // 保留策略：删除失败不算备份失败（备份已在远端，清理是尽力而为）
        runCatching {
            val mine = dav.list(BackupFormat.DIR)?.filter { !it.isCollection && BackupFormat.isBackupFileName(it.fileName) }.orEmpty()
            val doomed = BackupRetention.toDelete(mine)
            doomed.forEach { dav.deleteHref(it.href) }
            if (doomed.isNotEmpty()) LogT.i("WEBDAV 保留策略已清理 ${doomed.size} 份旧备份")
        }
        settings.webdavLastBackupAtMs = now
        name
    }

    /**
     * 恢复指定远端文件：get → 解码（schema 不认识即拒绝并给原因）→ 合并落库（新者胜）。
     * 失败时 Result 的 exception message 就是给用户看的原因（[BackupFormatException] 不吞）。
     */
    suspend fun restore(name: String): Result<BackupCounts> = runCatching {
        if (!isConfigured()) throw IOException("还没有配置 WebDAV 地址")
        val text = client().get("${BackupFormat.DIR}/$name")
            ?: throw IOException("下载 $name 失败（文件不存在或无权限）")
        applyPayload(BackupCodec.decode(text))
    }

    /** SAF 兜底 · 导出：写本机文件（content URI），payload 与 WebDAV 完全同源 */
    suspend fun exportTo(uri: Uri): Result<BackupCounts> = runCatching {
        val payload = BackupCodec.capture(db.productDao().getPinnedOnce(), db.priceTargetDao().getAllActive(), System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(BackupCodec.encode(payload).toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("无法写入所选文件")
        }
        BackupCounts(payload.favorites.size, payload.targets.size)
    }

    /** SAF 兜底 · 导入：读本机文件 → 同一套解码/校验/合并（schema 不认识同样拒绝） */
    suspend fun importFrom(uri: Uri): Result<BackupCounts> = runCatching {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("无法读取所选文件")
        }
        applyPayload(BackupCodec.decode(text))
    }

    /** 合并落库：逐条「新者胜」（[BackupMerge]），返回实际写入的条数 */
    private suspend fun applyPayload(payload: BackupPayload): BackupCounts {
        var favorites = 0
        for (dto in payload.favorites) {
            val local = db.productDao().getById(dto.id)
            BackupMerge.favorite(local, dto)?.let {
                db.productDao().upsert(it)
                favorites++
            }
        }
        var targets = 0
        for (dto in payload.targets) {
            val local = db.priceTargetDao().get(dto.productId)
            BackupMerge.target(local, dto)?.let {
                db.priceTargetDao().upsert(it)
                targets++
            }
        }
        return BackupCounts(favorites, targets)
    }
}
