package com.pricelens.update

import android.content.Context
import com.pricelens.data.remote.ApiClient
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.data.remote.reasonOrNull
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.util.LogT
import com.pricelens.util.UrlOpener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 更新清单获取 + 闸门状态流。
 *
 * 主源必须是 Gitee（本机实测：gitee.com 0.5s 可达；github.com / api.github.com /
 * raw.githubusercontent.com 直连 000 不通）。GitHub raw 只作为"Gitee 明确 404"时的次级源，
 * 网络层失败不追发（避免国内环境白等两个超时）。
 *
 * 必须走 [ApiClient.getJsonResult]（内部 CacheControl.FORCE_NETWORK），
 * **禁止** 用 getJsonAllowCacheResult：清单陈旧一次就可能把旧版本判成"最新"。
 * 另外 Gitee CDN 有 60s 服务端缓存（Cache-Control: public, max-age=60 + Varnish），
 * 所以 URL 追加 `?v=<versionCode>&t=<epoch 分钟桶>` 做破缓存（与 CDN 的 60s 同粒度）。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
    private val settings: SettingsRepository,
    private val installer: ApkInstaller,
    private val applicationScope: CoroutineScope
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 下载/安装进度（透传 [ApkInstaller] 状态流，UI 只需订阅一个仓库） */
    val downloadState: StateFlow<DownloadState> = installer.state

    /** 冷启动只判定一次，运行中不打断 */
    private var coldStartDone = false

    /**
     * 冷启动闸门。[currentVersionCode] 由调用方（Activity）传入 ——
     * 判定逻辑不读 BuildConfig，保证可单测。
     */
    fun checkOnColdStart(currentVersionCode: Int) {
        if (coldStartDone) return
        coldStartDone = true
        launchCheck(currentVersionCode, manual = false)
    }

    /** 设置页"检查更新"：忽略静默期与"已提示过"，结果一定给 UI 反馈 */
    fun checkManually(currentVersionCode: Int) {
        _state.value = UpdateState.Checking
        launchCheck(currentVersionCode, manual = true)
    }

    private fun launchCheck(currentVersionCode: Int, manual: Boolean) {
        val nowMs = System.currentTimeMillis()
        // 灰度桶首启生成后永久固定：否则同一份清单今天提示、明天不提示
        val bucket = settings.installBucket
        applicationScope.launch(Dispatchers.IO) {
            val raw = requestManifest(currentVersionCode, nowMs)
            val decision = UpdateEvaluator.evaluate(
                currentVersionCode = currentVersionCode,
                result = UpdateManifest.parse(raw),
                nowMs = nowMs,
                installBucket = bucket,
                lastAcceptedGeneratedAtMs = settings.lastCheckedGeneratedAt,
                skipUntilMs = settings.skipUntilMs,
                ignoreSilence = manual
            )
            LogT.i("UPDATE 判定 current=$currentVersionCode bucket=$bucket -> $decision")
            publish(decision, manual)
        }
    }

    private fun publish(decision: UpdateDecision, manual: Boolean) {
        // 连续 3 次安装失败：阻断层降级为可跳过的普通提示（不再每次冷启强弹）
        val degraded = decision is UpdateDecision.Forced &&
            installer.failCount >= ApkInstaller.MAX_CONSECUTIVE_FAILURES
        val effective = if (degraded) {
            LogT.w("UPDATE 连续 ${installer.failCount} 次安装失败，阻断层降级为普通提示")
            UpdateDecision.StrongHint((decision as UpdateDecision.Forced).offer)
        } else {
            decision
        }
        val next = when (effective) {
            is UpdateDecision.Silent -> if (manual) {
                UpdateState.Unavailable(effective.reason)
            } else {
                UpdateState.Idle
            }
            is UpdateDecision.Forced -> {
                settings.setLastCheckedGeneratedAt(effective.offer.generatedAtMs)
                UpdateState.Forced(effective.offer)
            }
            is UpdateDecision.StrongHint -> {
                settings.setLastCheckedGeneratedAt(effective.offer.generatedAtMs)
                UpdateState.StrongHint(effective.offer)
            }
            is UpdateDecision.Optional -> {
                settings.setLastCheckedGeneratedAt(effective.offer.generatedAtMs)
                UpdateState.Optional(effective.offer)
            }
            is UpdateDecision.UpToDate -> UpdateState.UpToDate(effective.offer)
        }
        _state.value = next
    }

    /**
     * 取清单：Gitee 主源 → 仅当 Gitee 明确返回 404 时才试 GitHub raw。
     * 任何失败都返回 null，由判定层按"无更新"静默处理（fail-open）。
     */
    private suspend fun requestManifest(currentVersionCode: Int, nowMs: Long): String? {
        val primary = UpdateSources.cacheBusted(UpdateSources.giteeManifest, currentVersionCode, nowMs)
        return when (val result = apiClient.getJsonResult(primary)) {
            is CrawlerResult.Success -> result.data
            is CrawlerResult.Network -> {
                val definitiveNotFound = result.cause.message?.contains("HTTP 404") == true
                if (!definitiveNotFound) {
                    LogT.w("UPDATE 清单获取失败(${result.reasonOrNull()})，按无更新静默")
                    return null
                }
                // 镜像没同步到这个文件才回退 GitHub；国内直连大概率超时，回退失败同样静默
                val secondary = UpdateSources.cacheBusted(UpdateSources.githubManifest, currentVersionCode, nowMs)
                val fallback = apiClient.getJsonResult(secondary)
                (fallback as? CrawlerResult.Success)?.data
            }
            else -> {
                LogT.w("UPDATE 清单不可用(${result.reasonOrNull()})，按无更新静默")
                null
            }
        }
    }

    /** 阻断层逃生口："我已升级仍提示我" → 本地静默 [UpdateEvaluator.ESCAPE_HATCH_MS] */
    fun acknowledgeUpgradeStillPrompting() {
        settings.setSkipUntilMs(System.currentTimeMillis() + UpdateEvaluator.ESCAPE_HATCH_MS)
        installer.cancel()
        _state.value = UpdateState.Idle
    }

    /** "以后再说"：按清单 cooldownHours 静默 */
    fun snooze(cooldownHours: Int) {
        val hours = cooldownHours.coerceIn(1, UpdateManifest.MAX_COOLDOWN_HOURS)
        settings.setSkipUntilMs(System.currentTimeMillis() + hours * 3_600_000L)
        installer.cancel()
        _state.value = UpdateState.Idle
    }

    fun startDownload(offer: UpdateOffer) = installer.start(offer)

    fun cancelDownload() = installer.cancel()

    /** 用户从"安装未知应用"设置页回来后，继续把已下载的包交给系统安装器 */
    fun resumePendingInstall() = installer.resumePendingInstall()

    /** 打开人工兜底下载页（Gitee 附件需登录 / sha 为占位符时的通路） */
    fun openDownloadPage(offer: UpdateOffer) {
        val url = offer.pageTarget?.url ?: offer.copyableUrl ?: return
        UrlOpener.open(context, url)
    }

    /** 供 UI 判断"复制哪条链接" */
    fun copyableUrl(offer: UpdateOffer): String? = offer.copyableUrl

    val isInstallerDegraded: Boolean
        get() = installer.failCount >= ApkInstaller.MAX_CONSECUTIVE_FAILURES
}

/**
 * 清单与产物的源。主源必须是 Gitee（国内实测唯一可达）。
 * Gitee raw 直链无需登录、不存在的文件返回干净 404、JSON 按 text/plain 返回
 * （因此全程不靠 content-type 判断，只认状态码与 sha256）。
 */
object UpdateSources {

    /** Gitee 公开镜像仓库 raw 直链（仓库根 update.json，随镜像同步） */
    const val giteeManifest = "https://gitee.com/wuliao11541/PriceLens/raw/main/update.json"

    /** 次级源：仅在 Gitee 明确 404（镜像尚未同步该文件）时才尝试 */
    const val githubManifest = "https://raw.githubusercontent.com/wuliao00/PriceLens/main/update.json"

    /**
     * 破缓存：Gitee CDN `Cache-Control: public, max-age=60`（Varnish X-CACHE: HIT），
     * 追加 `?v=<versionCode>&t=<nowMs/60000>` —— t 是**分钟桶**，与 CDN 的 60s 同粒度：
     * 同一分钟内 URL 稳定（可复用连接、不放大请求数），跨分钟即换新 URL 绕开服务端缓存，
     * 所以清单发布后约 1 分钟内客户端就能读到。
     */
    fun cacheBusted(base: String, currentVersionCode: Int, nowMs: Long): String = "$base?v=$currentVersionCode&t=${nowMs / 60_000L}"
}

/**
 * 更新闸门状态（MainActivity 只认这个流，不自己算）。
 * [Forced] 是唯一的阻断层；其余都是可跳过的提示。
 */
sealed interface UpdateState {
    /** 尚未检查 / 已静默（含全部 fail-open 分支） */
    data object Idle : UpdateState

    /** 手动检查进行中 */
    data object Checking : UpdateState

    data class Forced(val offer: UpdateOffer) : UpdateState
    data class StrongHint(val offer: UpdateOffer) : UpdateState
    data class Optional(val offer: UpdateOffer) : UpdateState
    data class UpToDate(val offer: UpdateOffer) : UpdateState

    /** 仅手动检查时使用：拿不到清单也如实告诉用户原因（不阻断） */
    data class Unavailable(val reason: String) : UpdateState
}
