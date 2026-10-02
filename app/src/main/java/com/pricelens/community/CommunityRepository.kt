package com.pricelens.community

import android.content.Context
import com.pricelens.data.remote.ApiClient
import com.pricelens.util.LogT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request

/** 社区动态的对外状态（UI 只认这四种，不含"假装成功"） */
sealed interface CommunityState {

    /** 首次读取中（只在没有任何磁盘缓存时短暂出现） */
    data object Loading : CommunityState

    /**
     * 有列表可展示。[stale] = 本轮刷新失败、展示的是上次缓存的数据（UI 要如实标注）。
     */
    data class Ready(
        val generatedAtMs: Long,
        val posts: List<CommunityPost>,
        val stale: Boolean
    ) : CommunityState

    /** 没缓存也拉不动：带一句如实的原因（网络不可达 / 尚未生成 / 格式不认识） */
    data class Unavailable(val reason: String) : CommunityState
}

/**
 * 社区动态仓库（文档 §九 的客户端侧）：
 * 磁盘缓存先亮 → 拉 Gitee raw（ETag/304）→ 校验解析 → 原子替换 → 更新状态。
 * 任何失败都退化成"用旧数据并标注"或"如实说拿不到"，绝不抛异常、绝不刷重试。
 */
@Singleton
class CommunityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient
) {

    private val cacheFile = File(File(context.filesDir, DIR_NAME), FILE_NAME)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<CommunityState>(CommunityState.Loading)
    val state: StateFlow<CommunityState> = _state

    private var diskLoaded = false

    /** 进入社区页调用：先亮磁盘缓存，再静默刷新一次。幂等。 */
    suspend fun loadAndRefresh(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!diskLoaded) {
                diskLoaded = true
                readDisk()?.let { result ->
                    _state.value = CommunityState.Ready(
                        generatedAtMs = result.generatedAtMs,
                        posts = result.posts,
                        stale = false
                    )
                }
            }
        }
        refresh()
    }

    /** 只刷新（下拉/重试时用） */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { refreshOnce() } }
    }

    private fun refreshOnce() {
        val etag = prefs.getString(KEY_ETAG, null)
        val request = Request.Builder()
            .url(CommunityFeed.FEED_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        val response = try {
            apiClient.http.newCall(request).execute()
        } catch (t: Exception) {
            LogT.w("社区动态请求失败：${t.javaClass.simpleName}")
            degrade("网络不可达")
            return
        }
        response.use { r ->
            when {
                r.code == HTTP_NOT_MODIFIED -> {
                    // 本地已最新：保持现状；若当前没有 Ready（比如磁盘被清）就当拿不到
                    if (_state.value !is CommunityState.Ready) degrade("本地缓存缺失")
                }

                r.isSuccessful -> {
                    val body = r.body?.bytes()?.toString(Charsets.UTF_8)
                    when (val parsed = CommunityFeed.parse(body)) {
                        is FeedParseResult.Valid -> {
                            writeCache(body)
                            prefs.edit().putString(KEY_ETAG, r.header("ETag")).apply()
                            _state.value = CommunityState.Ready(
                                generatedAtMs = parsed.generatedAtMs,
                                posts = parsed.posts,
                                stale = false
                            )
                            LogT.i("社区动态已更新：${parsed.posts.size} 条")
                        }

                        is FeedParseResult.Rejected -> {
                            LogT.w("社区动态内容被拒：${parsed.reason}")
                            degrade("数据格式不认识")
                        }
                    }
                }

                r.code == HTTP_NOT_FOUND -> degrade("社区数据尚未生成")

                else -> degrade("服务返回 ${r.code}")
            }
        }
    }

    /** 失败路径：有缓存 → 旧数据 + stale；没缓存 → 如实说拿不到 */
    private fun degrade(reason: String) {
        val disk = readDisk()
        _state.value = if (disk != null) {
            CommunityState.Ready(generatedAtMs = disk.generatedAtMs, posts = disk.posts, stale = true)
        } else {
            CommunityState.Unavailable(reason)
        }
    }

    private fun readDisk(): FeedParseResult.Valid? {
        val raw = runCatching { cacheFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        return CommunityFeed.parse(raw) as? FeedParseResult.Valid
    }

    private fun writeCache(text: String?) {
        if (text.isNullOrBlank()) return
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) {
                tmp.delete()
                cacheFile.writeText(text)
            }
        }
    }

    companion object {
        private const val DIR_NAME = "community"
        private const val FILE_NAME = "community.json"
        private const val PREFS_NAME = "community_feed"
        private const val KEY_ETAG = "etag"
        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_NOT_FOUND = 404
    }
}
