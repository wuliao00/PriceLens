package com.pricelens.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.accessibility.PriceEvents
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.data.remote.ApiClient
import com.pricelens.data.remote.BiliApi
import com.pricelens.data.remote.CrawlerBlockedException
import com.pricelens.data.remote.CrawlerResult
import com.pricelens.data.remote.GwdangApi
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.data.remote.SourceUnreachableException
import com.pricelens.data.repository.CurveProvenance
import com.pricelens.data.repository.PriceRepository
import com.pricelens.domain.PriceAdvice
import com.pricelens.domain.ProductCandidate
import com.pricelens.domain.ProductCandidateResolver
import com.pricelens.ui.common.AsyncValue
import com.pricelens.util.LogT
import com.pricelens.util.PriceJudgment
import com.pricelens.util.QueryRelevance
import com.pricelens.util.SearchQueryCleaner
import com.pricelens.util.judgePrice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 *  - A2（读取准确性与口径大改造）：search() 开头统一复位实时价/来源/到手价；
 *    关键词经 [SearchQueryCleaner] 清洗（不再 take(30) 盲截断）；候选非同款时展示层门控 +
 *    [staleNotice] 消费 [PriceRepository.staleKeys]（"上一次商品数据/缓存降级"提示）；
 *    历史价 URL 只认本轮候选自己的 SKU（爆料链接须过相关性过滤）
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

    /** 最近搜索（顶栏聚焦时的历史 chips；与「我的」页同一数据源） */
    val recentSearches: StateFlow<List<String>> =
        repository.recentSearches().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 整体搜索进行中（骨架屏门控，与原全局 loading 语义一致） */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** 本轮搜索用到的缓存 key（供 [staleNotice] 消费 staleKeys；搜索开始时整表替换） */
    private val _searchedCacheKeys = MutableStateFlow<List<String>>(emptyList())

    // ---------- per-source 独立状态 ----------

    /**
     * 商品候选：由 Resolver 单例持有，概览/盯价/找券共用。
     *
     * A2 生命周期门控：[ProductCandidateResolver] 是 @Singleton 且没有把候选置回 Idle 的路径
     * （grep 确认），手动搜 B 商品而候选仍是 A 时会"A 的价标在 B 上"。这里在展示层做门控：
     * 候选标题与当前关键词明显不同款（QueryRelevance 不过且重叠分 <0.6）→ 输出 Idle，
     * 概览页据此回落到"未找到"而非旧商品；边界情况保留展示、由 [staleNotice] 提示。
     */
    val product: StateFlow<AsyncValue<ProductCandidate>> = combine(resolver.candidate, _keyword) { candidate, keyword ->
        if (candidateIsStale(candidate, keyword)) AsyncValue.Idle else candidate
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AsyncValue.Idle)

    /**
     * "上一次商品数据"提示（A2 新增，浮窗/概览共用口径）：
     *  - 候选与当前关键词非同款（含边界保留展示的情况）；或
     *  - 本轮搜索用到的缓存 key 命中 [PriceRepository.staleKeys]（站点降级回吐旧数据，此前 UI 从未消费）。
     * OverviewScreen 侧绑定一行 collectAsStateWithLifecycle + [com.pricelens.R.string.ovl_overview_stale]
     * 即可显示（该文件不归 A2，见交付报告）。
     */
    val staleNotice: StateFlow<Boolean> = combine(
        resolver.candidate,
        _keyword,
        repository.staleKeys,
        _searchedCacheKeys
    ) { candidate, keyword, staleKeys, usedKeys ->
        val mismatch = (candidate as? AsyncValue.Success)?.data?.let { c ->
            keyword.isNotBlank() && extractJdSkuLocal(keyword) == null &&
                !QueryRelevance.isRelevant(keyword, c.title)
        } == true
        mismatch || usedKeys.any { it in staleKeys }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _history = MutableStateFlow<AsyncValue<ManmanbuyApi.History>>(AsyncValue.Idle)
    val history: StateFlow<AsyncValue<ManmanbuyApi.History>> = _history

    private val _judgment = MutableStateFlow<PriceJudgment>(PriceJudgment.NORMAL())
    val judgment: StateFlow<PriceJudgment> = _judgment

    /**
     * 购买建议：当前价处在历史的什么位置（文档 §10）。null = 本轮没算（没搜到/没有历史点）。
     * 口径全在 [com.pricelens.domain.PriceAdvice]，UI 只负责显示。
     */
    private val _advice = MutableStateFlow<com.pricelens.domain.PriceAdvice.Advice?>(null)
    val advice: StateFlow<com.pricelens.domain.PriceAdvice.Advice?> = _advice

    private val _advicePercentile = MutableStateFlow<Int?>(null)
    val advicePercentile: StateFlow<Int?> = _advicePercentile

    /**
     * 历史曲线的出处（盯价页脚注）：这条线是本机盯价自采长出来的、还是慢慢买给的。
     * null = 本轮还没算过（没搜、或归属不到京东 SKU）。
     */
    private val _curveProvenance = MutableStateFlow<CurveProvenance?>(null)
    val curveProvenance: StateFlow<CurveProvenance?> = _curveProvenance

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
        // A2 生命周期复位：换商品时清掉"上一个商品的实时价/来源/到手价"。
        // 旧版三者从无复位 → 手动搜 B 后概览仍显示"本机京东账号 · 实时价 ¥A"（A 的价标在 B 上）。
        _livePrice.value = null
        _realtimeSource.value = null
        _netPrice.value = null
        _searchedCacheKeys.value = listOf(
            "gwd:coupon:$keyword",
            "dd:search:$keyword",
            "smz:search:$keyword",
            "sh:search:$keyword",
            "bili:search:$keyword"
        )

        searchJob = viewModelScope.launch {
            repository.recordSearch(keyword)
            // 本地超集提取（m 站链接/纯数字/sku= 参数均可识别；resolver 版只认 item.jd.com/(\d+)，
            // App 分享出的 m 站链接此前会被当关键词搜——resolver 的同步补齐已写入交付报告，不归本文件改）
            val jdSku = extractJdSkuLocal(keyword)
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
                    // F4（2026-09-29）：类型化的"源没够着"不能只写日志。
                    // 候选链是 当当 → 值得买 串行，这里吞掉的话 爆料/当当 两枚徽标会停在非 Error 态，
                    // 而下面的并行 jobs 又各自独立问一次——把成因写进 posts，徽标与空态才有正确的因。
                    if (e is CrawlerBlockedException || e is SourceUnreachableException) {
                        val previous = (_posts.value as? AsyncValue.Success)?.data
                        _posts.value = AsyncValue.Error(e, previous)
                    }
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
                    // 京东 SKU 直查（无障碍/链接场景）。
                    // A2：不再在这里清 _realtimeSource —— search() 开头已统一复位，
                    // 检测链路又会在 search() 返回后重新赋值；此处二次清空会把
                    // "本轮检测的账号实时价来源"误杀（时序竞态，旧版唯一复位点反而不完整）。
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
                        }
                    }
                },
                launch {
                    // 历史价 URL 归属（A2 P1-6）：只认"本轮搜索候选自己的 SKU"——
                    //  1) 关键词自带京东 SKU/链接（含 m 站）→ 直接用；
                    //  2) 否则仅当值得买爆料**标题过关键词相关性过滤**且含京东链接时才采用。
                    // 旧版从"任意一条爆料"抓链接，可能是别的 SKU，历史曲线张冠李戴。
                    val ownSku = jdSku ?: postsForHistory.firstOrNull { post ->
                        QueryRelevance.isRelevant(keyword, post.title) && extractJdSkuLocal(post.url) != null
                    }?.let { extractJdSkuLocal(it.url) }
                    val url = ownSku?.let { "https://item.jd.com/$it.html" }
                    if (url == null) {
                        _history.value = AsyncValue.Idle
                        _judgment.value = PriceJudgment.NORMAL()
                        _curveProvenance.value = null
                        _advice.value = null
                        _advicePercentile.value = null
                        return@launch
                    }
                    _searchedCacheKeys.value = _searchedCacheKeys.value + "mmb:history:$url"
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
                    // 购买建议（文档 §10）：同一批历史点算分位，口径全在 PriceAdvice
                    val historyPrices = h?.points?.map { p -> p.price }.orEmpty()
                    _advice.value = h?.let { PriceAdvice.advise(it.current, historyPrices) }
                    _advicePercentile.value = h?.let { PriceAdvice.percentile(it.current, historyPrices) }
                    // 曲线出处：放在历史取数之后算，才包含这一轮可能写回的外源点。
                    // 问失败就不显示脚注（宁可不说话，也不说一条猜出来的出处）。
                    _curveProvenance.value = ownSku?.let { id ->
                        runCatching { repository.curveProvenance("jd:$id") }.getOrNull()
                    }
                },
                launch {
                    loadList(_videos, { repository.searchVideos(keyword) })
                },
                launch {
                    val coupons = loadList(_coupons, { repository.searchCoupons(keyword) })
                    val candidate = resolver.candidate.value.let {
                        (it as? AsyncValue.Success)?.data
                    }
                    // A2：到手价只算"本轮关键词自己的候选"；候选明显是上一个商品时不产净价
                    val product = candidate?.takeIf {
                        jdSku != null || QueryRelevance.isRelevant(keyword, it.title) ||
                            SearchQueryCleaner.titleOverlap(keyword, it.title) >= STALE_OVERLAP_FLOOR
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
                val signature = detected.signature
                if (signature == lastDetectionSignature && now - lastDetectionAt < 3_000) {
                    return@collect
                }
                lastDetectionSignature = signature
                lastDetectionAt = now

                val source = detected.sourceText ?: when {
                    detected.packageName.startsWith("com.jingdong") -> "本机京东 App 登录账号"
                    detected.packageName.startsWith("com.taobao") -> "本机淘宝 App 登录账号"
                    detected.packageName.startsWith("com.xunmeng") -> "本机拼多多 App 登录账号"
                    else -> "本机电商 App 登录账号"
                }
                // 网络搜索还没出结果时，先用账号实时价占位展示（@Singleton 候选已有则不覆盖，
                // 展示层由 [product] 门控 + [staleNotice] 提示防"A 价标 B"）
                resolver.fillFromDetection(detected.price, detected.title)

                // A2 P0-3：关键词清洗取代 take(30) 盲截断（截半型号会搜到旧款价）；
                // 拿到确定性京东 SKU 时直接按 SKU 精查，不再用标题碰运气。
                val cleaned = SearchQueryCleaner.clean(detected.title)
                val query = if (detected.platform == ShopPlatform.JD && detected.itemId != null) {
                    detected.itemId
                } else {
                    cleaned ?: detected.title?.take(30)
                }
                if (query != null && (query != lastSearchedTitle || now - lastSearchedTitleAt >= 3_000)) {
                    lastSearchedTitle = query
                    lastSearchedTitleAt = now
                    // search() 会同步复位 livePrice/来源/到手价，所以下面的赋值必须发生在其后
                    search(query)
                }
                if (cleaned != null) _keyword.value = cleaned
                _livePrice.value = detected.price
                _realtimeSource.value = source
            }
        }
    }

    // ---------- 内部工具 ----------

    /**
     * 京东 SKU 提取（本地超集）：item.jd.com / item.m.jd.com/product/ / sku= 参数 / 纯数字。
     * 与 PriceRepository.buildHistory 的 `item(?:\.m)?\.jd\.com/(?:product/)?(\d{6,})` 口径一致。
     */
    private fun extractJdSkuLocal(text: String): String? {
        JD_URL_SKU.find(text)?.let { return it.groupValues[1] }
        ID_PARAM.find(text)?.let { return it.groupValues[1] }
        return text.trim().takeIf { it.matches(PURE_SKU) }
    }

    /** 候选与当前关键词"明显不同款"（展示层门控，见 [product] 注释） */
    private fun candidateIsStale(candidate: AsyncValue<ProductCandidate>, keyword: String): Boolean {
        val data = (candidate as? AsyncValue.Success)?.data ?: return false
        if (keyword.isBlank()) return false
        if (extractJdSkuLocal(keyword) != null) return false // SKU/链接搜索：无稳定标题可比，不门控
        if (QueryRelevance.isRelevant(keyword, data.title)) return false
        return SearchQueryCleaner.titleOverlap(keyword, data.title) < STALE_OVERLAP_FLOOR
    }

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

    private companion object {
        /** 候选标题与关键词的重叠分下限：低于此值按"上一次商品数据"处理（门控/提示共用） */
        const val STALE_OVERLAP_FLOOR = 0.6

        /** 京东商详链接（含 m 站）与 sku=/goods_id= 参数（预编译，搜索高频调用） */
        val JD_URL_SKU = Regex("item(?:\\.m)?\\.jd\\.com/(?:product/)?(\\d{6,})")
        val ID_PARAM = Regex("[?&](?:sku|goods_id|product_id)=(\\d{6,})")
        val PURE_SKU = Regex("\\d{6,}")
    }
}
