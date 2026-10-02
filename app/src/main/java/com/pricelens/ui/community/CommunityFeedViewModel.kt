package com.pricelens.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.community.CommunityRepository
import com.pricelens.community.CommunityState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 社区动态（GitHub Discussions 的镜像数据）状态；进入页面刷一次，幂等。 */
@HiltViewModel
class CommunityFeedViewModel @Inject constructor(
    private val repository: CommunityRepository
) : ViewModel() {

    val state: StateFlow<CommunityState> = repository.state

    private var kicked = false

    fun ensureLoaded() {
        if (kicked) return
        kicked = true
        viewModelScope.launch { repository.loadAndRefresh() }
    }

    fun retry() {
        viewModelScope.launch { repository.refresh() }
    }
}
