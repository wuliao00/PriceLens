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

    /** 缓存占用统计："内存 x KB · 图片 y MB" */
    private val _cacheStats = MutableStateFlow("计算中…")
    val cacheStats: StateFlow<String> = _cacheStats

    fun refreshCacheStats() {
        viewModelScope.launch {
            val mem = repository.memoryCacheSizeBytes()
            val imgBytes = withContext(Dispatchers.IO) {
                File(appContext.cacheDir, "img").walkBottomUp()
                    .filter { it.isFile }.sumOf { it.length() }
            }
            _cacheStats.value = "内存 ${mem / 1024} KB · 图片 ${imgBytes / 1024 / 1024} MB"
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
