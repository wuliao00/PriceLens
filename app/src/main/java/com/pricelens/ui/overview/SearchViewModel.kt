package com.pricelens.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.accessibility.PriceEvents
import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.accessibility.ShopPlatform
import com.pricelens.accuracy.AccuracyFeedbackStore
import com.pricelens.accuracy.AccuracyInput
import com.pricelens.accuracy.AccuracyIssueKind
import com.pricelens.accuracy.AccuracySnapshot
import com.pricelens.accuracy.PriceAccuracyEngine
import com.pricelens.accuracy.RelevanceGuard
import com.pricelens.accuracy.RemotePrice
import com.pricelens.accuracy.SourceCell
import com.pricelens.accuracy.SourceState
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
import com.pricelens.domain.DetectionContext
import com.pricelens.domain.NetPrice
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
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *  - 信息源准确性（2026-11 大更新）：新搜索复位 [_accuracy]，并行 jobs 全部 join 后由
 *    [buildAccuracySnapshot] 组装 [AccuracyInput] 交给 [PriceAccuracyEngine]（多源共识/口径/离群/
 *    时鲜/来源健康板）；「这条不对」经 [reportAccuracyIssue] 写本机 JSONL（[AccuracyFeedbackStore]，
 *    不上传），[accuracyFeedbackCount] 同步刷新
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PriceRepository,
    private val resolver: ProductCandidateResolver,
    private val apiClient: ApiClient,
    private val accuracyFeedbackStore: AccuracyFeedbackStore
) : ViewModel() {

    // ---------- 输入与整体进度 ----------

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    /**
     * 这一次商品上下文的**身份**（#73）：只有当它是某一次无障碍检测带进来的，
     * 那一页的节点树才允许参与找券（`LocalCouponInputPlanner.plan` 拿它比对 `capture.signature`）。
     *
     * 为什么放在这里而不是让 UI 直接读 holder：身份的"生效路径"就是"用户现在看的是哪件商品"，
     * 而这归本 VM 管 —— `search()`（用户自己搜/换词）与检测回填都走这里，两个方向的复位
     * 才能落在同一个点上（漏一个就是"A 的券挂在 B 上"或者"这一路永远不生效"）。
     */
    private val _detectedSignature = MutableStateFlow<String?>(null)
    val detectedSignature: StateFlow<String?> = _detectedSignature

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

    // ---------- 信息源准确性（2026-11 大更新；UI 只消费，不自己算口径） ----------

    /**
     * 本轮搜索的准确性快照：多源共识/置信度/主口径/冲突/证据/来源健康板。
     *
     * 生命周期（与 [product] 同一条纪律）：**新搜索开始时立刻置 null**，避免"上一个商品的准确性"
     * 挂在新搜索的结果上；在并行 jobs 全部 join 之后由 [buildAccuracySnapshot] 计算并发布。
     * null = 本轮还没算（搜索进行中/尚未搜索）。
     */
    private val _accuracy = MutableStateFlow<AccuracySnapshot?>(null)
    val accuracy: StateFlow<AccuracySnapshot?> = _accuracy

    /** 本机已记录的纠错条数（「这条不对」；纯本机 JSONL、不上传） */
    private val _accuracyFeedbackCount = MutableStateFlow(0)
    val accuracyFeedbackCount: StateFlow<Int> = _accuracyFeedbackCount

    /**
     * 「这条不对」纠错：把 [kind] + 当前关键词 + [accuracy] 快照摘要 + 可选备注写入本机
     * `filesDir/accuracy_feedback.jsonl`（[AccuracyFeedbackStore]，不上传），随后刷新
     * [accuracyFeedbackCount] 供 UI 提示"已记录到本机"。
     *
     * 纠错是用户本机资产：记录失败不打断浏览（仓储层吞掉 IO 异常并返回当前可读条数）。
     */
    fun reportAccuracyIssue(kind: AccuracyIssueKind, note: String? = null) {
        val snapshot = _accuracy.value
        val keyword = _keyword.value
        viewModelScope.launch {
            val count = accuracyFeedbackStore.record(
                kind = kind,
                keyword = keyword,
                note = note,
                snapshot = snapshot
            )
            _accuracyFeedbackCount.value = count
        }
    }

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
        // 用户开始改词 = 他正在离开"刚才那一页"的上下文 ⇒ 页面树立刻不作数（#73）
        _detectedSignature.value = null
    }

    /**
     * 登记"这一次商品上下文来自哪一次识别"（#73）。
     *
     * 调用方有两条：① 消费 `PriceEvents.detections` 时（App 活着，检测当场进来）；
     * ② 浮窗 CTA 用 intent 带进来（冷启动那条路 —— 检测发生时本 VM 还不存在，
     * 而 `PriceEvents` 是无 replay 的 SharedFlow，事件已经丢了）。
     * 两条都必须排在 `search()` 之后：`search()` 会把身份清掉（换商品复位清单里的一项）。
     */
    fun adoptDetectionContext(signature: String) {
        _detectedSignature.value = signature
    }

    /** 从历史/收藏快速重新搜索 */
    fun research(keyword: String) = search(keyword)

    /** 四模块并行加载（验收：1.5s 内全部展示 —— 无缓存时各自独立请求） */
    fun search(keywordRaw: String) {
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return
        // 必须在改 _keyword 之前问：这一句和界面上已经是的那一句是不是同一件商品
        // （「看详情」传的是候选标题而不是关键词，所以还要问"是不是这一轮已经站住脚的那件"）
        val matchedTitle = ((resolver.candidate.value as? AsyncValue.Success<*>)?.data as? ProductCandidate)?.title
        val sameContext = DetectionContext.survivesSearch(_keyword.value, keyword, matchedTitle)
        searchJob?.cancel() // 新搜索取消旧 Job
        _keyword.value = keyword
        _loading.value = true
        // A2 生命周期复位：换商品时清掉"上一个商品的实时价/来源/到手价"。
        // 旧版三者从无复位 → 手动搜 B 后概览仍显示"本机京东账号 · 实时价 ¥A"（A 的价标在 B 上）。
        // #73 把同一个道理用到**页面树**上：搜 B 之后 A 那一页的券也不许再挂在这里。
        // 但"同一句关键词再搜一轮"**不算换商品**：概览页点「看详情」进详情页时就是会再搜一轮
        // （MainActivity 的详情页入口），无条件清零会把浮窗 CTA 带上来的身份在那一瞬间抹掉，
        // 「页面」芯片于是结构上永远出不来（2026-10-06 真机走通整条链才发现，见 §9.25）。
        if (!sameContext) _detectedSignature.value = null
        _livePrice.value = null
        _realtimeSource.value = null
        _netPrice.value = null
        // 准确性快照同一条生命周期纪律：新搜索 = 新商品上下文，旧商品的置信度/口径不许挂到新结果上
        _accuracy.value = null
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
                    val net = product?.let { NetPrice.of(it.price, coupons) }
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
            // 准确性放在并行任务全部 join 之后算：此时候选/历史/券/识货/实时价都是本轮已完成态，
            // 不会把上一轮的中途状态当成本轮证据（新搜索开始时已置 null，这里只发布本轮结果）。
            _accuracy.value = buildAccuracySnapshot(keyword, jdSku)
            LogT.i("准确性: level=${_accuracy.value?.level} score=${_accuracy.value?.score} headline=${_accuracy.value?.headline}")
        }
    }

    /**
     * 无障碍检测到价格 → 立即上屏（价格以用户手机登录的电商账号所见为准，
     * 包含会员价/Plus 价），并自动触发全网比价搜索（§1.1：免去手动复制）。
     * 签名去重 + 节流：3 秒内同签名跳过、同一标题不重复触发 search，防请求风暴。
     */
    init {
        // 纠错条数初始化：磁盘读文件放 IO，不在主线程读（feedbackCount 只用于 UI 提示）
        viewModelScope.launch {
            _accuracyFeedbackCount.value = withContext(Dispatchers.IO) { accuracyFeedbackStore.count() }
        }
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
                // 标题不可信时**绝不用它做任何事**（真机 2026-10-03 取证：规则把京东加载中那一帧的
                // 竖排提示「继 续 滑 动 查 看 图 文 详 情」当商品名 → 这里拿它发起全网搜索，
                // 当当/识货/什么值得买各拉一次 ~90KB HTML，还把它写进了搜索历史）。
                // 检测侧已有一道闸（DetectionPipeline.pickTitle），这条是消费侧的第二道：
                // 规则文件可以从远端热更，一条新规则不该能把垃圾关键词打进网络和搜索历史。
                val title = detected.title?.takeIf { PriceNodeMatcher.isDisplayableTitle(it) }
                // 网络搜索还没出结果时，先用账号实时价占位展示（@Singleton 候选已有则不覆盖，
                // 展示层由 [product] 门控 + [staleNotice] 提示防"A 价标 B"）
                resolver.fillFromDetection(detected.price, title)

                // A2 P0-3：关键词清洗取代 take(30) 盲截断（截半型号会搜到旧款价）；
                // 拿到确定性京东 SKU 时直接按 SKU 精查，不再用标题碰运气。
                val cleaned = SearchQueryCleaner.clean(title)
                val query = if (detected.platform == ShopPlatform.JD && detected.itemId != null) {
                    detected.itemId
                } else {
                    cleaned ?: title?.take(30)
                }
                if (query != null && (query != lastSearchedTitle || now - lastSearchedTitleAt >= 3_000)) {
                    lastSearchedTitle = query
                    lastSearchedTitleAt = now
                    // search() 会同步复位 livePrice/来源/到手价，所以下面的赋值必须发生在其后
                    search(query)
                }
                if (cleaned != null) _keyword.value = cleaned
                // #73：登记身份。必须排在 search() 之后 —— search() 会把它连同实时价一起复位
                // （上面那句"下面的赋值必须发生在其后"是同一个顺序要求）。
                adoptDetectionContext(signature)
                _livePrice.value = detected.price
                _realtimeSource.value = source
            }
        }
    }

    // ---------- 准确性快照（2026-11）：把本轮真实结果翻译成引擎输入 ----------

    /**
     * 组装本轮准确性输入并交给 [PriceAccuracyEngine]（在并行 jobs 全部 join 后、主线程同步执行）。
     *
     * 数据纪律（**只使用本轮真实拿到的东西**，取不到就留 null，绝不补 0/旧价）：
     *  - 页面价：无障碍检测在 [search] 之后回填的 [_livePrice]（手动搜索本轮为 null）；
     *  - 候选价：resolver 当前候选；SKU 场景按"相关度 1.0"，纯关键词须过 [RelevanceGuard]
     *    （关键词相关 + 无资源机/配件/图书等否定词），不过则压到 0.2 让引擎剔除；
     *  - 历史：只取本轮 [_history] 的 Success（历史 job 在本轮无归属 URL 时会先把状态置 Idle），
     *    stale 由本轮历史缓存 key 是否命中 [PriceRepository.staleKeys] 决定；
     *  - 券/到手：券面额 = 候选价 − 到手价（本轮唯一真实可用的券口径，不把券列表面额之和强加给商品）；
     *  - 额外现价：只收**文本同款 + 与参照价比值在 0.5~2.0 带内**的识货报价；爆料价不进现价集合。
     */
    private fun buildAccuracySnapshot(keyword: String, jdSku: String?): AccuracySnapshot {
        val candidate = when (val candidateState = resolver.candidate.value) {
            is AsyncValue.Success -> candidateState.data
            else -> null
        }
        val candidatePrice = candidate?.price?.takeIf { it.isFinite() && it > 0.0 }
        val candidateRelevance = candidate?.let { product ->
            when {
                jdSku != null -> 1.0
                // 真机 round1：识货/值得买列表混入"稳健医疗/资源机"同款，纯 QueryRelevance 挡不住；
                // 守卫不过就压低相关度（引擎 <0.6 会剔除这条证据），不让它污染多源共识
                RelevanceGuard.isLikelySameProduct(keyword, product.title, candidatePrice, null) -> 1.0
                else -> 0.2
            }
        }
        val history = when (val historyState = _history.value) {
            is AsyncValue.Success -> historyState.data
            else -> null
        }
        val historyStale = _searchedCacheKeys.value.any { key ->
            key.startsWith(KEY_PREFIX_HISTORY) && key in repository.staleKeys.value
        }
        val netPrice = _netPrice.value?.takeIf { it.isFinite() && it > 0.0 }
        val couponTotal = if (candidatePrice != null && netPrice != null && candidatePrice > netPrice) {
            candidatePrice - netPrice
        } else {
            null
        }
        // 远端价的唯一参照：候选价 > 历史当前价 > 页面实时价（都是本轮真实拿到的现价口径）
        val referencePrice = candidatePrice
            ?: history?.current?.takeIf { it.isFinite() && it > 0.0 }
            ?: _livePrice.value?.takeIf { it.isFinite() && it > 0.0 }
        return PriceAccuracyEngine.evaluate(
            AccuracyInput(
                keyword = keyword,
                pagePrice = _livePrice.value,
                pageSourceLabel = _realtimeSource.value,
                candidatePrice = candidatePrice,
                candidateTitle = candidate?.title,
                candidateRelevance = candidateRelevance,
                historyCurrent = history?.current,
                historyLowest = history?.lowest,
                historyStale = historyStale,
                couponTotal = couponTotal,
                netPrice = netPrice,
                extraRemotePrices = buildAccuracyRemotePrices(keyword, candidate?.title, referencePrice),
                sourceCells = buildAccuracySourceCells(),
                nowMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * 额外远端现价：**只收"可能同款"的现价**，宁缺毋滥。
     *
     * 真机 round1 教训：什么值得买爆料价 ¥2,898 被当成现价喂进共识，引擎据此报
     * 「候选 ¥5,999 与值得买 ¥2,898 相差 52%」+「候选离群」→ 假冲突、假离群、低置信。
     * 两条纪律：
     *  1) **爆料价 ≠ 现价**：`SmzdmApi.SmzdmPost.price` 是爆料发布时的价格/引流价，语义属于参考值，
     *     本轮不进现价一致性集合（也不进 evidence）——爆料价仍在社区/爆料卡单独展示；
     *  2) 识货等列表价只有在**文本同款 + 与参照价比值在 [RelevanceGuard.MIN_PRICE_RATIO]..
     *     [RelevanceGuard.MAX_PRICE_RATIO] 带内**才收（[RelevanceGuard.isLikelySameProduct]）。
     *     没有任何参照价时**整表返回空**：无法验证的"第二来源"比没有更糟（宁可是单源 MEDIUM）。
     *
     * @param referencePrice 本轮可比参照价（候选价 > 历史当前价 > 页面实时价）；null = 不收远端价
     */
    private fun buildAccuracyRemotePrices(keyword: String, candidateTitle: String?, referencePrice: Double?): List<RemotePrice> {
        if (referencePrice == null) return emptyList()
        val shihuoItems = when (val shihuoState = _shihuo.value) {
            is AsyncValue.Success -> shihuoState.data
            else -> emptyList()
        }
        val quote = shihuoItems.firstOrNull { item ->
            item.title != candidateTitle &&
                RelevanceGuard.isLikelySameProduct(keyword, item.title, item.price, referencePrice)
        } ?: return emptyList()
        return listOf(RemotePrice(SOURCE_ID_SHIHUO, "识货", quote.price, 1.0))
    }

    /**
     * 来源健康板：与 [com.pricelens.ui.components.SourceStatusRow] 同一组域名键、同一套已知合并口径
     * （当当与值得买共用 posts 状态——当当做候选主源，成功即进入爆料/候选）；另加一格"页面"，
     * 因为无障碍实时价也是"本轮够没够着数据"的一部分。
     */
    private fun buildAccuracySourceCells(): List<SourceCell> {
        val livePrice = _livePrice.value
        return listOf(
            SourceCell(
                SOURCE_ID_PAGE,
                "页面",
                if (livePrice != null) SourceState.OK else SourceState.UNKNOWN,
                if (livePrice != null) _realtimeSource.value else "本轮未读取页面实时价"
            ),
            sourceCellOf(SOURCE_ID_HISTORY, "历史价", _history.value, "apapia-history.manmanbuy.com"),
            sourceCellOf(SOURCE_ID_DANGDANG, "当当", _posts.value, "search.dangdang.com"),
            sourceCellOf(SOURCE_ID_BILI, "B站", _videos.value, "api.bilibili.com"),
            sourceCellOf(SOURCE_ID_GWDANG, "购物党", _coupons.value, "www.gwdang.com"),
            sourceCellOf(SOURCE_ID_SMZDM, "什么值得买", _posts.value, "search.smzdm.com"),
            sourceCellOf(SOURCE_ID_SHIHUO, "识货", _shihuo.value, "m.shihuo.cn")
        )
    }

    /**
     * 单个来源的归一状态：判定与 [com.pricelens.ui.components.sourceChipStateOf] 同口径，
     * 额外把"本轮缓存 key 命中 [PriceRepository.staleKeys]"收敛成 [SourceState.STALE]（旧数据）。
     */
    private fun sourceCellOf(id: String, label: String, value: AsyncValue<*>, domain: String): SourceCell {
        val outcome = apiClient.lastOutcomeFor(domain)
        return when (value) {
            is AsyncValue.Loading -> SourceCell(id, label, SourceState.UNKNOWN, "加载中")
            is AsyncValue.Success -> if (sourceIsStale(id)) {
                SourceCell(id, label, SourceState.STALE, "缓存降级，展示旧数据")
            } else {
                SourceCell(id, label, SourceState.OK, if (sourceHasNoItems(value)) "无匹配结果" else null)
            }
            is AsyncValue.Error -> if (value.cause is CrawlerBlockedException || outcome is CrawlerResult.Blocked) {
                SourceCell(id, label, SourceState.BLOCKED, blockedNote(value.cause, outcome))
            } else {
                SourceCell(id, label, SourceState.DEGRADED, unreachableNote(value.cause))
            }
            is AsyncValue.Idle -> SourceCell(id, label, SourceState.UNKNOWN, "本轮未查询")
        }
    }

    /** 某来源本轮用到的缓存 key 是否命中 [PriceRepository.staleKeys]（站点降级回吐旧数据） */
    private fun sourceIsStale(id: String): Boolean {
        val prefix = sourceKeyPrefix(id)
        val staleKeys = repository.staleKeys.value
        return _searchedCacheKeys.value.any { it.startsWith(prefix) && it in staleKeys }
    }

    /** 来源 ID → 本轮缓存 key 前缀（与 search() 开头的 _searchedCacheKeys、history job 追加的 key 对齐） */
    private fun sourceKeyPrefix(id: String): String = when (id) {
        SOURCE_ID_HISTORY -> KEY_PREFIX_HISTORY
        SOURCE_ID_DANGDANG -> "dd:search:"
        SOURCE_ID_BILI -> "bili:search:"
        SOURCE_ID_GWDANG -> "gwd:coupon:"
        SOURCE_ID_SMZDM -> "smz:search:"
        SOURCE_ID_SHIHUO -> "sh:search:"
        else -> id
    }

    /** 成功态但载荷是空集合（"够着了但 0 条"，与"没够着"是两件事） */
    private fun sourceHasNoItems(value: AsyncValue<*>): Boolean {
        val data = (value as? AsyncValue.Success)?.data ?: return false
        return data is Collection<*> && data.isEmpty()
    }

    /**
     * BLOCKED 的一句话中文原因（用户可见，**不暴露异常类名**）。
     *
     * 真机 round1 教训：旧写法把 `cause.javaClass.simpleName` 拼进 note，健康板上出现
     * 「B站 · 降级 · 网络失败: SourceUnreacha…」——截断且用户读不懂。这里只保留能指导
     * 用户动作的中文分类，技术原因看日志（[LogT]）。
     */
    private fun blockedNote(cause: Throwable?, outcome: CrawlerResult<String>?): String {
        val reason = (
            (cause as? CrawlerBlockedException)?.reason
                ?: (outcome as? CrawlerResult.Blocked)?.reason
            )?.trim().orEmpty()
        return when {
            reason.contains("403") || reason.contains("412") -> "反爬拦截（HTTP 403/412）"
            reason.contains("熔断") -> "反爬拦截（本站暂时熔断）"
            reason.contains("挑战") -> "反爬拦截（验证页）"
            else -> "反爬拦截"
        }
    }

    /**
     * DEGRADED 的一句话中文原因（用户可见，**不暴露异常类名**）。
     *
     * 网络类失败分三档：源不可达（超时/DNS/连接被拒）、网络异常（IO）、取数失败（其它）。
     * 具体异常类型只写日志，不上健康板。
     */
    private fun unreachableNote(cause: Throwable): String = when (cause) {
        is SourceUnreachableException -> "源不可达"
        is IOException -> "网络异常"
        is CrawlerBlockedException -> "反爬拦截"
        else -> "取数失败"
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

        /** 来源健康板 sourceId（与 SourceCell / 缓存 key 前缀一一对应；页面/历史与引擎常量同值） */
        const val SOURCE_ID_PAGE = "page"
        const val SOURCE_ID_HISTORY = "history"
        const val SOURCE_ID_DANGDANG = "dangdang"
        const val SOURCE_ID_BILI = "bilibili"
        const val SOURCE_ID_GWDANG = "gwdang"
        const val SOURCE_ID_SMZDM = "smzdm"
        const val SOURCE_ID_SHIHUO = "shihuo"

        /** 历史价缓存 key 前缀（search() 启动时登记，history job 拿到归属 URL 后追加） */
        const val KEY_PREFIX_HISTORY = "mmb:history:"
    }
}
