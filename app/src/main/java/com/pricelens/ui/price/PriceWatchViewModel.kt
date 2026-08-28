package com.pricelens.ui.price

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.repository.PriceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 盯价目标 ViewModel（阶段2：从上帝 MainViewModel 拆出）。
 * 仅负责"进行中的盯价目标"的展示与移除；
 * 历史价曲线数据仍由 SearchViewModel 的 history 源提供。
 */
@HiltViewModel
class PriceWatchViewModel @Inject constructor(
    private val repository: PriceRepository
) : ViewModel() {

    /** 进行中的盯价目标 */
    val watchTargets: StateFlow<List<PriceTargetEntity>> =
        repository.observeTargets()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun removeTarget(productId: String) {
        viewModelScope.launch { repository.deactivateTarget(productId) }
    }
}
