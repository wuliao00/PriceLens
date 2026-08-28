package com.pricelens.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.accessibility.PriceEvents
import com.pricelens.data.remote.ApiClient
import com.pricelens.data.remote.BiliApi
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.data.repository.PriceRepository
import com.pricelens.domain.ProductCandidate
import com.pricelens.domain.ProductCandidateResolver
import com.pricelens.ui.common.AsyncValue
import com.pricelens.util.LogT
import com.pricelens.util.PriceJudgment
import com.pricelens.util.judgePrice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 搜索编排 ViewModel（阶段2：从上帝 MainViewModel 拆出）。
 *
 *  - 提交即搜契约（评审修复）：搜索仅由 [search]/[research]/无障碍检测触发，
 *    键入只更新状态、不自动发起搜索（防抖自动搜索会污染搜索历史）
 *  - 新搜索取消旧 Job（[searchJob]），避免结果乱序覆盖
 *  - 四模块 per-source 独立 [AsyncValue]：product / history / videos / coupons / posts / shihuo，
 *    某数据源抛异常或被反爬时真实写入 Error（为阶段4 SourceStatusRow 做准备）
 *  - 保留"1.5s 内并行上屏"的并行 launch 语义与现有日志语义
 *  - 商品候选由 [ProductCandidateResolver] 单例持有（概览/盯价/找券共用）
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PriceRepository,
    private val resolver: ProductCandidateResolver,
    private val apiClient: ApiClient
) : ViewModel() {

    // ---------- 输入与整体进度 ----------

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    /** 整体搜索进行中（骨架屏门控，与原全局 loading 语义一致） */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // ---------- per-source 独立状态 ----------

    /** 商品候选：由 Resolver 单例持有，概览/盯价/找券共用 */
    val product: StateFlow<AsyncValue<ProductCandidate>> = resolver.candidate

    private val _history = MutableStateFlow<AsyncValue<ManmanbuyApi.History>>(AsyncValue.Idle)
    val history: StateFlow<AsyncValue<ManmanbuyApi.History>> = _history

    private val _judgment = MutableStateFlow<PriceJudgment>(PriceJudgment.NORMAL())
    val judgment: StateFlow<PriceJudgment> = _judgment

    private val _videos = MutableStateFlow<AsyncValue<List<BiliApi.BiliVideo>>>(AsyncValue.Idle)
    val videos: StateFlow<AsyncValue<List<BiliApi.BiliVideo>>> = _videos

    private val _coupons = MutableStateFlow<AsyncValue<List<GwdangApi.Coupon>>>(AsyncValue.Idle)
    val coupons: StateFlow<AsyncValue<List<GwdangApi.Coupon>>> = _coupons

    private val _posts = MutableStateFlow<AsyncValue<List<SmzdmApi.SmzdmPost>>>(AsyncValue.Idle)
    val posts: StateFlow<AsyncValue<List<SmzdmApi.SmzdmPost>>> = _posts

    private val _shihuo = MutableStateFlow<AsyncValue<List<ShihuoApi.ShihuoItem>>>(AsyncValue.Idle)
    val shihuo: StateFlow<AsyncValue<List<ShihuoApi.ShihuoItem>>> = _shihuo

    /** 到手价（找券页 countUp 用） */
    private val _netPrice = MutableStateFlow<Double?>(null)
    val netPrice: StateFlow<Double?> = _netPrice

    /** 无障碍实时价：本机登录账号在商品页看到的价格（含会员价） */
    private val _livePrice = MutableStateFlow<Double?>(null)
    val livePrice: StateFlow<Double?> = _livePrice

    /** 实时价来源：如 "本机京东 App 登录账号" */
    private val _realtimeSource = MutableStateFlow<String?>(null)
    val realtimeSource: StateFlow<String?> = _realtimeSource

    /** 反爬/失败诊断版本：每轮搜索结束后递增，驱动 SourceStatusRow 刷新域名结果 */
    private val _outcomesVersion = MutableStateFlow(0)
    val outcomesVersion: StateFlow<Int> = _outcomesVersion

    /** 某域名最近一次失败结果（成功/未请求过返回 null），供 SourceStatusRow 诊断徽标 */
    fun lastOutcome(domain: String): CrawlerResult<String>? = apiClient.lastOutcomeFor(domain)

    // ---------- 任务管理 ----------

    private var searchJob: Job? = null

    /** 防抖节流：无障碍检测 3 秒内同签名跳过、同一标题不重复触发搜索 */
    private var lastDetectionSignature: String? = null
    private var lastDetectionAt = 0L
    private var lastSearchedTitle: String? = null
    private var lastSearchedTitleAt = 0L

    fun updateKeyword(text: String) {
        // 评审修复：仅更新状态；搜索只由提交（onSubmit）/research()/无障碍检测触发，
        // 键入防抖自动搜索会改变「提交即搜」契约并污染搜索历史（recordSearch）
        _keyword.value = text
    }

    /** 从历史/收藏快速重新搜索 */
    fun research(keyword: String) = search(keyword)

    /** 四模块并行加载（验收：1.5s 内全部展示 —— 无缓存时各自独立请求） */
    fun search(keywordRaw: String) {
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return
        searchJob?.cancel() // 新搜索取消旧 Job
        _keyword.value = keyword
        _loading.value = true

        searchJob = viewModelScope.launch {
            repository.recordSearch(keyword)
            val jdSku = resolver.extractJdSku(keyword)
            LogT.i("搜索开始: [$keyword] jdSku=$jdSku")

            // 纯关键词且非京东链接：商品候选优先当当搜索（SSR 稳定），值得买爆料兜底。
            // 值得买/慢慢买等源近年均上线反爬，当当作为主数据源保证"搜商品名有结果"。
            var smzdmPosts: List<SmzdmApi.SmzdmPost> = emptyList()
            if (jdSku == null) {
                smzdmPosts = try {
                    resolver.resolvePrimary(keyword)
                } catch (e: CancellationException) {
                    throw e // 取消透传：不把取消写成空结果/继续执行
                } catch (e: Exception) {
                    LogT.w("候选兜底链异常: ${e.javaClass.simpleName}")
                    emptyList()
                }
                if (smzdmPosts.isNotEmpty()) {
                    _posts.value = AsyncValue.Success(smzdmPosts)
                }
            }

            // 识货：社区页补充源（鞋服/数码）；当当+值得买均无候选时充当商品候选兜底。
            // 与上述串行块无数据依赖，放并行 jobs 不拖慢主流程。
            val needShihuoCandidate = jdSku == null && !resolver.hasCandidate()
            val postsForHistory = smzdmPosts

            val jobs = listOf(
                launch {
                    // 京东 SKU 直查（无障碍/链接场景）；失败时保留账号实时价占位
                    if (jdSku != null) {
                        val product = try {
                            repository.getJdProduct(jdSku)
                        } catch (e: CancellationException) {
                            throw e // 取消透传：不吞异常、不在取消后继续执行
                        } catch (e: Exception) {
                            LogT.w("京东直查异常: ${e.javaClass.simpleName}")
                            null
                        }
                        if (product != null) {
                            resolver.fillFromJd(product)
                            _realtimeSource.value = null
                        }
                    }
                },
                launch {
                    // 历史价：优先京东商品页 URL；其次取值得买候选里的京东链接；否则放弃
                    val candidateUrl = postsForHistory
                        .firstOrNull { Regex("item\\.jd\\.com/\\d+").containsMatchIn(it.url) }
                        ?.url
                    val url = when {
                        jdSku != null -> "https://item.jd.com/$jdSku.html"
                        candidateUrl != null -> Regex("https?://item\\.jd\\.com/\\d+\\.html")
                            .find(candidateUrl)?.value
                        else -> null
                    }
                    if (url == null) {
                        _history.value = AsyncValue.Idle
                        _judgment.value = PriceJudgment.NORMAL()
                        return@launch
                    }
                    _history.value = AsyncValue.Loading(
                        _history.value.let {
                            (it as? AsyncValue.Success)?.data
                        }
                    )
                    val result = try {
                        Result.success(repository.getPriceHistory(url))
                    } catch (e: CancellationException) {
                        throw e // 取消透传：不把取消写成 AsyncValue.Error
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    _history.value = result.fold(
                        onSuccess = { h ->
                            if (h != null) AsyncValue.Success(h) else AsyncValue.Idle
                        },
                        onFailure = { e ->
                            AsyncValue.Error(
                                e,
                                (_history.value as? AsyncValue.Loading)?.previous
                            )
                        }
                    )
                    val h = (_history.value as? AsyncValue.Success)?.data
                    _judgment.value = h?.let { judgePrice(it.current, it.points.map { p -> p.price }) }
                        ?: PriceJudgment.NORMAL()
                },
                launch {
                    loadList(_videos, { repository.searchVideos(keyword) })
                },
                launch {
                    val coupons = loadList(_coupons, { repository.searchCoupons(keyword) })
                    val product = resolver.candidate.value.let {
                        (it as? AsyncValue.Success)?.data
                    }
                    val net = product?.let { p ->
                        val best = coupons.maxByOrNull { it.amount }
                        if (best != null && p.price >= best.threshold) {
                            p.price - best.amount
                        } else {
                            null
                        }
                    }
                    _netPrice.value = net
                },
                launch {
                    // 关键词分支已在上方填充 posts；京东链接场景这里才查
                    if (smzdmPosts.isEmpty()) {
                        loadList(_posts, { repository.searchSmzdm(keyword) })
                    }
                },
                launch {
                    val items = loadList(_shihuo, { repository.searchShihuo(keyword) })
                    LogT.i("识货结果: ${items.size} 条")
                    resolver.maybeFillFromShihuo(items, needShihuoCandidate)
                }
            )
            jobs.forEach { it.join() }
            val hasProduct = resolver.hasCandidate()
            val hist = _history.value
            LogT.i(
                "搜索完成: product=$hasProduct history=${hist is AsyncValue.Success} " +
                    "videos=${listSize(_videos)} coupons=${listSize(_coupons)} posts=${listSize(_posts)}"
            )
            _loading.value = false
            _outcomesVersion.value++
        }
    }

    /**
     * 无障碍检测到价格 → 立即上屏（价格以用户手机登录的电商账号所见为准，
     * 包含会员价/Plus 价），并自动触发全网比价搜索（§1.1：免去手动复制）。
     * 签名去重 + 节流：3 秒内同签名跳过、同一标题不重复触发 search，防请求风暴。
     */
    init {
        viewModelScope.launch {
            PriceEvents.detections.collect { detected ->
                val now = System.currentTimeMillis()
                val signature = "${detected.packageName}|${detected.title}|${detected.price}"
                if (signature == lastDetectionSignature && now - lastDetectionAt < 3_000) {
                    return@collect
                }
                lastDetectionSignature = signature
                lastDetectionAt = now

                val source = when {
                    detected.packageName.startsWith("com.jingdong") -> "本机京东 App 登录账号"
                    detected.packageName.startsWith("com.taobao") -> "本机淘宝 App 登录账号"
                    detected.packageName.startsWith("com.xunmeng") -> "本机拼多多 App 登录账号"
                    else -> "本机电商 App 登录账号"
                }
                // 网络搜索还没出结果时，先用账号实时价占位展示，概览页立即有内容
                resolver.fillFromDetection(detected.price, detected.title)
                _keyword.value = detected.title?.take(30) ?: _keyword.value
                _livePrice.value = detected.price
                _realtimeSource.value = source

                detected.title?.let { title ->
                    val t = title.take(30)
                    if (t != lastSearchedTitle || now - lastSearchedTitleAt >= 3_000) {
                        lastSearchedTitle = t
                        lastSearchedTitleAt = now
                        search(t)
                    }
                }
            }
        }
    }

    // ---------- 内部工具 ----------

    /** 列表型数据源统一加载：异常真实写入 Error，成功写 Success；取消异常透传 */
    private suspend fun <T> loadList(state: MutableStateFlow<AsyncValue<List<T>>>, block: suspend () -> List<T>): List<T> {
        val previous = (state.value as? AsyncValue.Success)?.data
        state.value = AsyncValue.Loading(previous)
        return try {
            val list = block()
            state.value = AsyncValue.Success(list)
            list
        } catch (e: CancellationException) {
            throw e // 取消透传：不把取消写成 Error，不在取消后继续执行
        } catch (e: Exception) {
            state.value = AsyncValue.Error(e, previous)
            previous ?: emptyList()
        }
    }

    private fun listSize(state: StateFlow<AsyncValue<List<*>>>): Int = ((state.value) as? AsyncValue.Success)?.data?.size ?: 0
}
