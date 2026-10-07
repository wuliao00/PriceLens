package com.pricelens.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.data.cache.CacheCleanupWorker
import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.repository.PriceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个人页 ViewModel（阶段2：从上帝 MainViewModel 拆出，收编原 L73-120）。
 * 我的收藏 / 搜索历史 / 缓存统计与清理。
 *
 * 注：`research`（从历史/收藏快速重新搜索）本质是搜索编排，
 * 已归入 SearchViewModel；个人页通过同一 Activity 作用域的 SearchViewModel 触发。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: PriceRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** 我的收藏（pinned 商品，TLRU 永不淘汰） */
    val pinnedProducts: StateFlow<List<ProductEntity>> =
        repository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 搜索历史（点按即重新搜索） */
    val searchHistory: StateFlow<List<String>> =
        repository.recentSearches()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 缓存占用统计。形状从"预拼一句字符串"改成两个字段（真机 2026-10-07 截图复核）：
     * 旧写法让 UI 只能给整句再套一个标签「缓存」，屏上就成了「缓存 内存 0 KB · 图片 …」——
     * 标签套标签，且单行放不下被省略号截断。拆开后个人页两格各读各的，设置页仍用 [CacheStats.asSentence]。
     */
    private val _cacheStats = MutableStateFlow(CacheStats(null, null))
    val cacheStats: StateFlow<CacheStats> = _cacheStats

    fun refreshCacheStats() {
        viewModelScope.launch {
            val mem = repository.memoryCacheSizeBytes()
            val imgBytes = withContext(Dispatchers.IO) {
                File(appContext.cacheDir, "img").walkBottomUp()
                    .filter { it.isFile }.sumOf { it.length() }
            }
            _cacheStats.value = CacheStats(mem / 1024, imgBytes / 1024 / 1024)
        }
    }

    /** 清理缓存：保留收藏，其余立即淘汰（后台再做 VACUUM 压缩） */
    fun clearCache() {
        viewModelScope.launch {
            repository.clearCaches()
            CacheCleanupWorker.enqueueEmergency(appContext)
            refreshCacheStats()
        }
    }
}

/**
 * 缓存占用（个人页/设置页共用）。
 *
 * 为什么不是一个字符串：见 [ProfileViewModel.cacheStats] 的注释——预拼一句会让 UI 只能
 * 给整句再套一层标签，屏上成"标签套标签"，且一行放不下就被截断。
 * 两格各自只放"数值 + 单位"，标签由 UI 那一侧的 label 管，读法才是「缓存 12 KB」「图片 3 MB」。
 *
 * 可空 = "还没算出来"，不是 0。把未算出来渲染成 0 会让用户以为清过缓存。
 */
data class CacheStats(val memoryKb: Long?, val imageMb: Long?) {

    /** 一格：只含数值与单位，**不含标签词**（标签归 UI） */
    val memoryText: String get() = memoryKb?.let { "$it KB" } ?: PENDING

    /** 一格：同上 */
    val imageText: String get() = imageMb?.let { "$it MB" } ?: PENDING

    /** 设置页那一行有整行宽度，用一句话更省事；没算全时宁可只说"计算中"也不报半截 */
    val asSentence: String
        get() = if (memoryKb == null || imageMb == null) PENDING else "内存 $memoryKb KB · 图片 $imageMb MB"

    private companion object {
        const val PENDING = "计算中…"
    }
}
