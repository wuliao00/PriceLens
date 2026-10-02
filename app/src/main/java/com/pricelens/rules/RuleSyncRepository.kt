package com.pricelens.rules

import android.content.Context
import com.pricelens.data.remote.ApiClient
import com.pricelens.util.LogT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request

/**
 * 选择器规则远端同步（设计文档 §3.3 的落地；本仓库现实约束见下）。
 *
 * 与 update.json **同一套信任模型**：Gitee raw 直链 + HTTPS 做传输信任，
 * `rules/manifest.json` 是信任根，每个规则文件用它声明的 sha256 校验完整性；
 * 解析失败 / 校验失败一律丢弃，**宁可用旧的**（磁盘上的上一版）或内置规则。
 *
 * 与设计文档参考实现的三处刻意偏差（以本仓库现实为准）：
 *  1. 用**磁盘文件 + sha256 版本化命名**而不是原地覆盖 `<id>.json`：
 *     写成 `<id>.<sha前12位>.json`，`manifest.json` 作为提交点最后原子替换。
 *     这样任何中途失败（半写、进程被杀）都不会破坏"旧规则可用"——
 *     旧清单仍指向旧文件，旧文件还在；原地覆盖在"清单换新前崩溃"时会丢旧版。
 *  2. 清单 URL 稳定、靠 ETag/If-None-Match 省流量（更新检查那种分钟级新鲜度
 *     对 6 小时一轮的规则同步没有意义，不追加破缓存参数，保证 304 能对上）。
 *  3. 内置规则（assets/rules/）不做 sha256 校验：它随 APK 分发，信任级别与代码相同。
 *
 * 同步完全自洽（收集所有失败、绝不抛异常）；失败时当前生效规则保持不变。
 */
@Singleton
class RuleSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient
) {

    private val rulesDir = File(context.filesDir, DIR_NAME)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    /**
     * 本地装载（App 启动时同步调用，只读几 KB）：磁盘已校验规则 > 内置 assets。
     * 幂等；任何一步失败都退到内置规则，再失败就是空集（纯启发式，行为同改造前）。
     */
    fun loadAndInstallLocal() {
        val builtin = loadBuiltin()
        val disk = reloadVerifiedFromDisk()
        RuleProvider.install(RuleMerge.effective(disk, builtin))
        LogT.i(
            "RULES 本地装载 builtin=[${builtin.keys.joinToString()}] disk=[${disk.keys.joinToString()}] " +
                "生效=${RuleProvider.snapshot().rules.map { "${it.id}@v${it.version}" }}"
        )
    }

    /** 拉取并落地；返回是否更新了生效规则。永不抛异常（无网/坏内容都是"保持现状"）。 */
    suspend fun syncIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching { syncOnce() }.getOrElse {
                LogT.w("RULES 同步异常（保留现有规则）：${it.javaClass.simpleName} ${it.message}")
                false
            }
        }
    }

    private suspend fun syncOnce(): Boolean {
        val diskManifest = readDiskManifest()
        // 只有磁盘上真有清单时才发 If-None-Match：否则 304 会让"没清单"的坏状态永远修不回来
        val etag = if (diskManifest != null) prefs.getString(KEY_ETAG, null) else null
        val fetched = fetch(MANIFEST_URL, etag) ?: return false
        if (fetched.code == HTTP_NOT_MODIFIED) {
            LogT.i("RULES 清单 304，本地已最新")
            return false
        }
        val body = fetched.body ?: return false
        val manifest = when (val parsed = RuleJson.parseManifest(body.toString(Charsets.UTF_8))) {
            is ManifestResult.Valid -> parsed.manifest
            is ManifestResult.Rejected -> {
                LogT.w("RULES 清单被拒：${parsed.reason}（保留现有规则）")
                return false
            }
        }
        if (manifest.manifestVersion <= (diskManifest?.manifestVersion ?: 0L)) {
            storeEtag(fetched.etag)
            return false
        }

        // 先全部下载 + 校验，任何一个不过就整轮放弃（清单是一个提交单元，不做半份更新）
        val payloads = LinkedHashMap<String, Pair<RuleManifestEntry, ByteArray>>()
        for (entry in manifest.rules) {
            if (!needsFetch(entry, diskManifest)) continue
            val bytes = fetchBytes(RULE_BASE_URL + entry.file)
            if (bytes == null) {
                LogT.w("RULES ${entry.id} 下载失败，本轮放弃（保留旧规则）")
                return false
            }
            if (!RuleIntegrity.verify(bytes, entry.sha256)) {
                LogT.w("RULES ${entry.id} sha256 校验失败，丢弃（保留旧规则）")
                return false
            }
            payloads[entry.id] = entry to bytes
        }

        // 落地：先规则文件（sha 命名 + tmp→rename 原子替换），最后写清单（提交点）
        rulesDir.mkdirs()
        for ((id, pair) in payloads) {
            writeFileAtomically(ruleFile(id, pair.first.sha256), pair.second)
        }
        writeFileAtomically(File(rulesDir, MANIFEST_FILE), body)
        prefs.edit()
            .putString(KEY_ETAG, fetched.etag)
            .putLong(KEY_VERSION, manifest.manifestVersion)
            .apply()
        cleanupStaleFiles(manifest)

        val disk = reloadVerifiedFromDisk()
        RuleProvider.install(RuleMerge.effective(disk, loadBuiltin()))
        LogT.i(
            "RULES 同步完成 manifestVersion=${manifest.manifestVersion} 更新=[${payloads.keys.joinToString()}] " +
                "生效=${RuleProvider.snapshot().rules.map { "${it.id}@v${it.version}" }}"
        )
        return true
    }

    // ---------- 本地装载 ----------

    private fun loadBuiltin(): Map<String, PlatformRule> {
        val out = LinkedHashMap<String, PlatformRule>()
        val names = runCatching { context.assets.list(DIR_NAME) }.getOrNull() ?: return out
        for (name in names.filter { it.endsWith(".json") }.sorted()) {
            val raw = runCatching {
                context.assets.open("$DIR_NAME/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            when (val parsed = RuleJson.parsePlatformRule(raw, expectedId = name.removeSuffix(".json"))) {
                is PlatformRuleResult.Valid -> out[parsed.rule.id] = parsed.rule
                is PlatformRuleResult.Rejected -> LogT.e("RULES 内置规则被拒（$name）：${parsed.reason}")
            }
        }
        return out
    }

    /** 从磁盘重载：逐文件对磁盘清单的 sha256 复验（防半写/位腐），坏的一份单独弃用 */
    private fun reloadVerifiedFromDisk(): Map<String, PlatformRule> {
        val manifest = readDiskManifest() ?: return emptyMap()
        val out = LinkedHashMap<String, PlatformRule>()
        for (entry in manifest.rules) {
            val bytes = runCatching { ruleFile(entry.id, entry.sha256).takeIf { it.isFile }?.readBytes() }.getOrNull()
                ?: continue
            if (!RuleIntegrity.verify(bytes, entry.sha256)) {
                LogT.w("RULES 磁盘 ${entry.id} sha256 不符，弃用（回落内置）")
                continue
            }
            when (val parsed = RuleJson.parsePlatformRule(bytes.toString(Charsets.UTF_8), entry.id)) {
                is PlatformRuleResult.Valid -> out[entry.id] = parsed.rule
                is PlatformRuleResult.Rejected -> LogT.w("RULES 磁盘 ${entry.id} 解析被拒：${parsed.reason}")
            }
        }
        return out
    }

    private fun readDiskManifest(): RuleManifest? {
        val raw = runCatching {
            File(rulesDir, MANIFEST_FILE).takeIf { it.isFile }?.readBytes()?.toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        return when (val parsed = RuleJson.parseManifest(raw)) {
            is ManifestResult.Valid -> parsed.manifest
            is ManifestResult.Rejected -> {
                LogT.w("RULES 磁盘清单被拒：${parsed.reason}")
                null
            }
        }
    }

    // ---------- 网络 ----------

    private class Fetched(val code: Int, val etag: String?, val body: ByteArray?)

    private suspend fun fetch(url: String, ifNoneMatch: String?): Fetched? = try {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { ifNoneMatch?.let { header("If-None-Match", it) } }
            .build()
        apiClient.http.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_NOT_MODIFIED -> Fetched(HTTP_NOT_MODIFIED, response.header("ETag"), null)
                response.isSuccessful -> Fetched(response.code, response.header("ETag"), response.body?.bytes())
                else -> {
                    LogT.w("RULES HTTP ${response.code} $url")
                    null
                }
            }
        }
    } catch (t: Exception) {
        LogT.w("RULES 请求失败 ${t.javaClass.simpleName}: ${t.message} $url")
        null
    }

    private suspend fun fetchBytes(url: String): ByteArray? = fetch(url, ifNoneMatch = null)?.body

    // ---------- 磁盘写入 ----------

    /** tmp → rename 原子替换；rename 失败（目标被占/磁盘满）抛异常由上层转成"本轮放弃" */
    private fun writeFileAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("rename 失败：${target.name}")
        }
    }

    /** 只保留当前清单引用的文件（旧 sha 版本 + 残留 tmp 一并清掉） */
    private fun cleanupStaleFiles(manifest: RuleManifest) {
        val keep = manifest.rules.map { ruleFile(it.id, it.sha256).name }.toSet()
        rulesDir.listFiles()?.forEach { file ->
            if (file.name == MANIFEST_FILE) return@forEach
            if (file.name !in keep) file.delete()
        }
    }

    private fun needsFetch(entry: RuleManifestEntry, diskManifest: RuleManifest?): Boolean {
        val local = diskManifest?.entry(entry.id) ?: return true
        val upToDate = local.version >= entry.version && local.sha256 == entry.sha256
        // 清单说没变但文件丢了（被清理/损坏）也要补下载，否则该平台永远停在内置规则
        return !upToDate || !ruleFile(entry.id, entry.sha256).isFile
    }

    private fun ruleFile(id: String, sha256: String): File = File(rulesDir, "$id.${sha256.take(SHA_PREFIX_LENGTH)}.json")

    private fun storeEtag(etag: String?) {
        if (etag.isNullOrBlank()) return
        prefs.edit().putString(KEY_ETAG, etag).apply()
    }

    companion object {
        /**
         * 规则清单主源：Gitee 镜像仓库 raw（与 update.json 的 `UpdateSources.giteeManifest`
         * 同一仓库同一分支）。实测国内 GitHub raw 直连不通，只设 Gitee 一个源。
         */
        const val RULE_BASE_URL = "https://gitee.com/wuliao11541/PriceLens/raw/main/"
        const val MANIFEST_URL = RULE_BASE_URL + "rules/manifest.json"

        private const val DIR_NAME = "rules"
        private const val MANIFEST_FILE = "manifest.json"
        private const val TMP_SUFFIX = ".tmp"
        private const val SHA_PREFIX_LENGTH = 12
        private const val PREFS_NAME = "rule_sync"
        private const val KEY_ETAG = "etag"
        private const val KEY_VERSION = "manifest_version"
        private const val HTTP_NOT_MODIFIED = 304
    }
}
