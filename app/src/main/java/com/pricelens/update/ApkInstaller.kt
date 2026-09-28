package com.pricelens.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pricelens.data.remote.ApiClient
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import com.pricelens.util.UserAgents
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * APK 下载与安装（对标 azhon/AppUpdate 的链路）：
 *  Range 断点续传 → `cacheDir/apk/` 下的 `.part` 文件 → sha256 校验 → rename →
 *  FileProvider `content://` → `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`；
 *  未授予"安装未知应用"时跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`。
 *
 * 与 azhon 的差别（有意为之）：
 *  - 不引任何下载库，直接用仓内已有的 [ApiClient.http]（仅放宽读超时，不新建连接池）；
 *  - **不靠 Content-Type 判断**是否 APK（Gitee raw 把 JSON 都当 text/plain 返回），
 *    只认 sha256；
 *  - 三级下载目标按清单顺序逐个尝试，全部失败才计一次失败次数；
 *  - 连续 [MAX_CONSECUTIVE_FAILURES] 次失败后由 [UpdateRepository] 降级为普通提示，
 *    不再每次冷启强弹。
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
    private val settings: SettingsRepository,
    private val applicationScope: CoroutineScope
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var job: Job? = null
    private var pendingFile: File? = null

    /** 大文件下载复用同一连接池，只放宽单次读超时与总超时 */
    private val downloadClient: OkHttpClient by lazy {
        apiClient.http.newBuilder()
            .readTimeout(java.time.Duration.ofSeconds(30))
            .callTimeout(java.time.Duration.ofSeconds(0))
            .build()
    }

    val failCount: Int get() = settings.updateFailCount

    fun start(offer: UpdateOffer) {
        if (_state.value is DownloadState.Downloading) return
        if (!offer.hasVerifiableDownload) {
            _state.value = DownloadState.Unverifiable
            return
        }
        job = applicationScope.launch(Dispatchers.IO) { runPipeline(offer) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = DownloadState.Idle
    }

    /** 用户去系统开启"安装未知应用"后回来，点"继续安装" */
    fun resumePendingInstall() {
        val file = pendingFile ?: return
        launchInstall(file)
    }

    private suspend fun runPipeline(offer: UpdateOffer) {
        val targets = offer.apkUrls.filter { it.kind == ApkTargetKind.APK }
        var lastError = "无可用下载源"
        for (target in targets) {
            val outcome = tryFetch(target, offer)
            when (outcome) {
                is Fetch.Ok -> {
                    settings.resetUpdateFailCount()
                    pendingFile = outcome.file
                    _state.value = DownloadState.Ready(outcome.file)
                    launchInstall(outcome.file)
                    return
                }
                is Fetch.Err -> {
                    lastError = outcome.message
                    LogT.w("UPDATE 下载源失败[${target.label}]: ${outcome.message}")
                }
            }
        }
        val fails = settings.incrementUpdateFailCount()
        _state.value = DownloadState.Failed(lastError, fails)
    }

    /** 单个下载目标：断点续传 → 落盘 → 校验 */
    private suspend fun tryFetch(target: ApkTarget, offer: UpdateOffer): Fetch {
        val dir = apkDir()
        val part = File(dir, "${offer.versionCode}.apk.part")
        val final = File(dir, "pricelens-${offer.versionCode}.apk")
        if (final.exists() && sha256Of(final) == offer.sha256) {
            return Fetch.Ok(final)
        }
        var resumeFrom = part.length().coerceAtLeast(0L)
        if (offer.sizeBytes > 0 && resumeFrom > offer.sizeBytes) {
            part.delete()
            resumeFrom = 0L
        }
        val request = Request.Builder()
            .url(target.url)
            .header("User-Agent", UserAgents.next())
            .header("Accept", "*/*")
            .header("Range", "bytes=$resumeFrom-")
            .build()

        return try {
            downloadClient.newCall(request).execute().use { resp ->
                when {
                    !resp.isSuccessful -> {
                        // 服务端明确拒绝（404/403/需登录）：断点无意义，清掉残留
                        if (resp.code == 416) part.delete()
                        Fetch.Err("HTTP ${resp.code}")
                    }
                    else -> {
                        // 206=按 Range 续传；200=服务端忽略 Range，必须从头覆盖写
                        val append = resp.code == 206 && resumeFrom > 0
                        if (!append) {
                            part.delete()
                            resumeFrom = 0L
                        }
                        writeBody(resp, part, append, resumeFrom, offer.sizeBytes)?.let { return it }
                        val written = sha256Of(part)
                        if (written == null) {
                            Fetch.Err("校验读取失败")
                        } else if (!written.equals(offer.sha256, ignoreCase = true)) {
                            // 内容不对：这份断点不可信，删掉避免下次继续拼错数据
                            part.delete()
                            Fetch.Err("sha256 不匹配")
                        } else if (final.exists() && !final.delete()) {
                            Fetch.Err("旧安装包无法覆盖")
                        } else if (!part.renameTo(final)) {
                            Fetch.Err("安装包落盘失败")
                        } else {
                            Fetch.Ok(final)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // 网络中断：保留 .part 供下次 Range 续传
            Fetch.Err("网络中断（可续传）：${e.javaClass.simpleName}")
        } catch (e: CancellationException) {
            // 取消透传：保留 .part 供下次续传，且不计入失败次数
            throw e
        } catch (e: Exception) {
            part.delete()
            Fetch.Err(e.javaClass.simpleName)
        }
    }

    /** 流式写盘；返回非 null 表示需要提前结束（取消/超限） */
    private suspend fun writeBody(resp: Response, part: File, append: Boolean, alreadyWritten: Long, declaredTotal: Long): Fetch? {
        val body = resp.body ?: return Fetch.Err("空响应体")
        val remaining = if (body.contentLength() >= 0) body.contentLength() else -1L
        val total = if (remaining >= 0) alreadyWritten + remaining else declaredTotal
        var downloaded = alreadyWritten
        var lastEmit = alreadyWritten
        FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    if (total in 1 until downloaded || downloaded > MAX_DOWNLOAD_BYTES) {
                        part.delete()
                        return Fetch.Err("下载体积超出预期，已中止")
                    }
                    if (downloaded - lastEmit >= PROGRESS_EMIT_STEP || downloaded == total) {
                        lastEmit = downloaded
                        _state.value = DownloadState.Downloading(downloaded, total.coerceAtLeast(0L), targetLabelOf(resp))
                    }
                }
            }
        }
        return null
    }

    private fun targetLabelOf(resp: Response): String = resp.request.url.host ?: "下载源"

    /**
     * 拉起系统安装器。
     * Android 8+ 需要用户授予"安装未知应用"；未授予时跳系统设置页（azhon 同款处理）。
     */
    private fun launchInstall(file: File) {
        if (!canRequestInstalls()) {
            _state.value = DownloadState.NeedsInstallPermission
            openUnknownSourcesSettings()
            return
        }
        pendingFile = file
        try {
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(), file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _state.value = DownloadState.Launched(file)
        } catch (e: Exception) {
            LogT.e("UPDATE 拉起安装器失败", e)
            _state.value = DownloadState.Failed("无法打开系统安装器", settings.updateFailCount)
        }
    }

    /** 系统"安装未知应用"授权状态（API 26 起才有该概念） */
    fun canRequestInstalls(): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    fun openUnknownSourcesSettings() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            LogT.e("UPDATE 无法打开未知来源设置页", e)
        }
    }

    private fun apkDir(): File = File(context.cacheDir, "apk").apply { if (!exists()) mkdirs() }

    /** 与 AndroidManifest 里 FileProvider 的 authorities 保持一致（${applicationId}.fileprovider） */
    private fun fileProviderAuthority(): String = context.packageName + ".fileprovider"

    /** 逐块读，避免一次性把 18MB 读进内存 */
    private fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (e: Exception) {
        LogT.e("UPDATE sha256 计算失败", e)
        null
    }

    private sealed interface Fetch {
        data class Ok(val file: File) : Fetch
        data class Err(val message: String) : Fetch
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val PROGRESS_EMIT_STEP = 256L * 1024
        private const val MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024

        /** 连续失败达到该次数后，[UpdateRepository] 把阻断式弹窗降级为普通提示 */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

/** 下载/安装状态机（UI 直接消费） */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val from: String) : DownloadState

    /** 已下载并校验通过 */
    data class Ready(val file: File) : DownloadState

    /** 缺"安装未知应用"授权，已跳系统设置页；授权后点"继续安装" */
    data object NeedsInstallPermission : DownloadState

    /** 已拉起系统安装器 */
    data class Launched(val file: File) : DownloadState

    /** 清单没有可信 sha256 / 没有 APK 直链：只能走"打开下载页 / 复制链接" */
    data object Unverifiable : DownloadState
    data class Failed(val reason: String, val failCount: Int) : DownloadState
}
