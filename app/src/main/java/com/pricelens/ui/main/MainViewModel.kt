package com.pricelens.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.accessibility.PriceEvents
import com.pricelens.data.cache.CacheCleanupWorker
import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import com.pricelens.data.remote.BiliApi
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.data.repository.PriceRepository
import com.pricelens.util.PriceJudgment
import com.pricelens.util.judgePrice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 四模块数据状态：概览 / B站 / 盯价 / 券 / 社区 */
data class MainUiState(
    val keyword: String = "",
    val loading: Boolean = false,
    val product: JdApi.JdProduct? = null,
    val history: ManmanbuyApi.History? = null,
    val judgment: PriceJudgment = PriceJudgment.NORMAL(),
    val videos: List<BiliApi.BiliVideo> = emptyList(),
    val coupons: List<GwdangApi.Coupon> = emptyList(),
    val posts: List<SmzdmApi.SmzdmPost> = emptyList(),
    val netPrice: Double? = null,           // 到手价（找券页 countUp 用）
    val error: String? = null,
    /** 无障碍实时价：本机登录账号在商品页看到的价格（含会员价） */
    val livePrice: Double? = null,
    /** 实时价来源：如 "本机京东 App 登录账号" */
    val realtimeSource: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: PriceRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state

    // ---------- 设置（个人页 / 设置页数据） ----------

    private val prefs get() = appContext.getSharedPreferences("pricelens", Context.MODE_PRIVATE)

    /** Material You 动态取色开关（低版本自动回退品牌蓝） */
    private val _dynamicTheme = MutableStateFlow(prefs.getBoolean("dynamic_color", true))
    val dynamicTheme: StateFlow<Boolean> = _dynamicTheme

    fun setDynamicTheme(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicTheme.value = enabled
    }

    /** 我的收藏（pinned 商品，TLRU 永不淘汰） */
    val pinnedProducts: StateFlow<List<ProductEntity>> =
        repository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 进行中的盯价目标 */
    val watchTargets: StateFlow<List<PriceTargetEntity>> =
        repository.observeTargets()
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

    fun removeTarget(productId: String) {
        viewModelScope.launch { repository.deactivateTarget(productId) }
    }

    /** 从历史/收藏快速重新搜索 */
    fun research(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
        search(keyword)
    }

    /**
     * 无障碍检测到价格 → 立即上屏（价格以用户手机登录的电商账号所见为准，
     * 包含会员价/Plus 价），并自动触发全网比价搜索（§1.1：免去手动复制）。
     */
    init {
        viewModelScope.launch {
            PriceEvents.detections.collect { detected ->
                val source = when {
                    detected.packageName.startsWith("com.jingdong") -> "本机京东 App 登录账号"
                    detected.packageName.startsWith("com.taobao") -> "本机淘宝 App 登录账号"
                    detected.packageName.startsWith("com.xunmeng") -> "本机拼多多 App 登录账号"
                    else -> "本机电商 App 登录账号"
                }
                _state.update { st ->
                    // 网络搜索还没出结果时，先用账号实时价占位展示，概览页立即有内容
                    val placeholder = st.product ?: JdApi.JdProduct(
                        skuId = "",
                        title = detected.title ?: "正在查看的商品",
                        price = detected.price,
                        originalPrice = null,
                        image = "",
                        url = ""
                    )
                    st.copy(
                        keyword = detected.title?.take(30) ?: st.keyword,
                        product = placeholder,
                        livePrice = detected.price,
                        realtimeSource = source
                    )
                }
                detected.title?.let { title ->
                    search(title.take(30))
                }
            }
        }
    }

    fun updateKeyword(text: String) = _state.update { it.copy(keyword = text) }

    /** 四模块并行加载（验收：1.5s 内全部展示 —— 无缓存时各自独立请求） */
    fun search(keywordRaw: String) {
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return
        _state.update { it.copy(loading = true, error = null, keyword = keyword) }

        viewModelScope.launch {
            repository.recordSearch(keyword)
            val jdSku = extractJdSku(keyword)
            android.util.Log.i("PriceLens", "搜索开始: [$keyword] jdSku=$jdSku")

            // 纯关键词且非京东链接：商品候选优先当当搜索（SSR 稳定），值得买爆料兜底。
            // 值得买/慢慢买等源近年均上线反爬，当当作为主数据源保证"搜商品名有结果"。
            if (jdSku == null) {
                var filled = false
                val ddItems = repository.searchDangdang(keyword)
                android.util.Log.i("PriceLens", "当当结果: ${ddItems.size} 条")
                val ddCandidate = ddItems.firstOrNull { it.price > 0 }
                if (ddCandidate != null) {
                    _state.update { st ->
                        st.copy(
                            product = JdApi.JdProduct(
                                skuId = "",
                                title = ddCandidate.title,
                                price = ddCandidate.price,
                                originalPrice = ddCandidate.originalPrice,
                                image = ddCandidate.image,
                                url = ddCandidate.url
                            )
                        )
                    }
                    filled = true
                }

                val posts = repository.searchSmzdm(keyword)
                android.util.Log.i("PriceLens", "值得买结果: ${posts.size} 条")
                if (!filled) {
                    val candidate = posts.firstOrNull { it.price != null && it.url.startsWith("http") }
                    if (candidate != null) {
                        _state.update { st ->
                            st.copy(
                                posts = posts,
                                product = JdApi.JdProduct(
                                    skuId = "",
                                    title = candidate.title,
                                    price = candidate.price ?: 0.0,
                                    originalPrice = null,
                                    image = candidate.image,
                                    url = candidate.url
                                )
                            )
                        }
                        filled = true
                    }
                }
                if (posts.isNotEmpty()) {
                    _state.update { it.copy(posts = posts) }
                }
                if (!filled) {
                    android.util.Log.w("PriceLens", "关键词 [$keyword] 无商品候选（当当/值得买均无结果）")
                }
            }

            val jobs = listOf(
                launch {
                    // 京东 SKU 直查（无障碍/链接场景）；失败时保留账号实时价占位
                    if (jdSku != null) {
                        val product = repository.getJdProduct(jdSku)
                        if (product != null) {
                            _state.update { it.copy(product = product, realtimeSource = null) }
                        }
                    }
                },
                launch {
                    // 历史价：优先京东商品页 URL；其次取值得买候选里的京东链接；否则放弃
                    val candidateUrl = _state.value.posts
                        .firstOrNull { Regex("item\\.jd\\.com/\\d+").containsMatchIn(it.url) }
                        ?.url
                    val url = when {
                        jdSku != null -> "https://item.jd.com/$jdSku.html"
                        candidateUrl != null -> Regex("https?://item\\.jd\\.com/\\d+\\.html").find(candidateUrl)?.value
                        else -> null
                    }
                    val history = url?.let { repository.getPriceHistory(it) }
                    _state.update { st ->
                        st.copy(
                            history = history,
                            judgment = history?.let {
                                judgePrice(it.current, it.points.map { p -> p.price })
                            } ?: PriceJudgment.NORMAL()
                        )
                    }
                },
                launch {
                    val videos = repository.searchVideos(keyword)
                    _state.update { it.copy(videos = videos) }
                },
                launch {
                    val coupons = repository.searchCoupons(keyword)
                    val product = _state.value.product
                    val net = product?.let { p ->
                        val best = coupons.maxByOrNull { it.amount }
                        if (best != null && p.price >= best.threshold)
                            p.price - best.amount else null
                    }
                    _state.update { it.copy(coupons = coupons, netPrice = net) }
                },
                launch {
                    // 关键词分支已在上方填充 posts；京东链接场景这里才查
                    if (_state.value.posts.isEmpty()) {
                        val posts = repository.searchSmzdm(keyword)
                        _state.update { it.copy(posts = posts) }
                    }
                }
            )
            jobs.forEach { it.join() }
            val s = _state.value
            android.util.Log.i("PriceLens", "搜索完成: product=${s.product != null} history=${s.history != null} videos=${s.videos.size} coupons=${s.coupons.size} posts=${s.posts.size}")
            _state.update { it.copy(loading = false) }
        }
    }

    /** 关键词里若含京东链接/SKU，提取商品 ID；否则视为纯关键词搜索 */
    private fun extractJdSku(keyword: String): String? {
        Regex("item\\.jd\\.com/(\\d+)").find(keyword)?.let { return it.groupValues[1] }
        return keyword.takeIf { it.matches(Regex("\\d{6,}")) }
    }
}
