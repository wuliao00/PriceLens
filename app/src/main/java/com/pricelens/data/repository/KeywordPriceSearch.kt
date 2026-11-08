package com.pricelens.data.repository

import com.pricelens.data.local.AppDatabase
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.LinkstarsApi
import com.pricelens.domain.AutoTrackPolicy
import com.pricelens.domain.PriceSample
import com.pricelens.domain.PriceSampling
import com.pricelens.domain.PriceSource
import com.pricelens.domain.WatchChannel
import com.pricelens.domain.WatchTargetPolicy
import com.pricelens.util.LogT
import com.pricelens.util.SearchQueryCleaner
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CancellationException

/**
 * 关键词自动搜索取价（2026-11 自动追价的核心取数层）。
 *
 * 背景：京东 p.3.cn 域名 2026-09 起公网不可达后，只靠直查几乎所有目标都查不到价；
 * 非京东平台（淘宝/拼多多/当当/识货/值得买）更是从来没有直查通道。本类把「商品标题 →
 * 关键词 → 既有搜索源候选 → 严格同款判定 → 带来源的价格样本」收口成一处：
 *
 *  - 取数**只走** [PriceRepository] 的既有搜索方法（`searchDangdang/searchShihuo/searchSmzdm`，
 *    共享 L1/L2 缓存与 singleflight）与既有 [JdApi]/[LinkstarsApi] 直查，绝不新增裸 OkHttp 请求；
 *  - 京东目标优先直查（p.3.cn 在售价 → 星罗在售价兜底），直查拿到 live 价就不再搜索；
 *    直查失败/只有参考值时才走关键词搜索补缺口（合并规则见
 *    [com.pricelens.worker.WatchCheckRunner]：**直查优先**）；
 *  - 候选必须过 [AutoTrackPolicy.matchOffer]（QueryRelevance 严格相关性 + 正数价格）
 *    与 [AutoTrackPolicy.isPriceRatioReasonable]（与该商品历史/买入价比值在 0.5~2.0），
 *    否则宁可"本轮无价"——绝不把不相关商品/不同规格的低价当成现价；
 *  - 本轮某个源失败一次后**整轮不再访问它**（失败闩锁），避免 30 个目标 × 3 个源把超时放大；
 *  - 每轮关键词搜索目标数有上限 [MAX_TARGETS_PER_ROUND]（搜索源均带 1 小时缓存，
 *    下一轮命中缓存、不产生放大流量）。
 *
 * 选中的样本带 [PriceSource.KEYWORD_SEARCH]（label「同款搜索价」，`referenceOnly = false`）：
 * 它是刚搜到的在售价，够格发降价通知/写曲线，但通知文案会写明"同款搜索价"。
 */
@Singleton
class KeywordPriceSearch @Inject constructor(
    private val priceRepository: PriceRepository,
    private val jdApi: JdApi,
    private val linkstarsApi: LinkstarsApi,
    private val settings: SettingsRepository,
    private val db: AppDatabase
) {

    /**
     * 一个待搜索的目标（与 Room 实体解耦：盯价目标与价保购买记录都能用）。
     *
     * @param key 调用方的唯一键（盯价用 `productId`；价保用 `purchase:<id>`），结果按它回填
     * @param platform 平台小写名（决定搜索源优先级与主键拼法）
     * @param productId 平台内商品号或 `<platform>:<id>`（可空：只有标题也能搜；null 时无法读历史参考价）
     * @param title 商品标题（关键词来源）
     * @param referencePrice 历史参考价（用于比值合理性）；null = 由本类按 [productId] 从曲线读最近收盘价
     */
    data class SearchTarget(
        val key: String,
        val platform: String,
        val productId: String?,
        val title: String,
        val referencePrice: Double? = null
    )

    /**
     * 单个目标的搜索结果。
     *
     * @param sample 选中的同款价格样本（null = 没有可信候选）
     * @param sourceLabel 命中的搜索源（"当当"/"识货"/"值得买"）；null = 没命中
     * @param failedSources 本轮失败过的源（人读；来自闩锁，可能包含之前目标触发的失败）
     * @param note 人读说明（没命中时写原因，便于日志/UI 展示"为什么没查到"）
     */
    data class SearchResult(
        val sample: PriceSample?,
        val sourceLabel: String?,
        val failedSources: List<String>,
        val note: String?
    )

    /**
     * 一轮批量搜索的结果。
     *
     * @param samples key → 选中的样本（只含成功的目标，失败的不落 0/旧价）
     * @param failedSources 本轮失败过的源（去重、人读）
     * @param searched 真正发起过关键词搜索的目标数
     * @param unreachable 没有拿到可信候选的目标数（含关键词为空被跳过的）
     */
    data class RoundReport(
        val samples: Map<String, PriceSample>,
        val failedSources: List<String>,
        val searched: Int,
        val unreachable: Int
    )

    /**
     * 批量搜索：按 [targets] 顺序逐个找同款，最多搜 [maxTargets] 个目标。
     *
     * 失败闩锁跨目标共享：某个源第一次抛异常后，本轮后续目标直接跳过它
     * （源级熔断 `SourceHealth` 之外的第二道保护，保证单轮耗时可控）。
     */
    suspend fun searchAll(targets: List<SearchTarget>, maxTargets: Int = MAX_TARGETS_PER_ROUND): RoundReport {
        val failed = linkedSetOf<String>()
        val samples = LinkedHashMap<String, PriceSample>()
        var searched = 0
        var unreachable = 0
        for (target in targets) {
            if (searched >= maxTargets) break
            val keyword = normalizeKeyword(target.title)
            if (keyword == null) {
                unreachable++
                continue
            }
            searched++
            val result = keywordOffer(target, keyword, failed)
            if (result.sample != null) {
                samples[target.key] = result.sample
            } else {
                unreachable++
            }
        }
        if (samples.isNotEmpty() || failed.isNotEmpty()) {
            LogT.i(
                "同款搜索：目标 ${targets.size}，实际搜索 $searched，选中 ${samples.size}，" +
                    "不可得 $unreachable，失败源 ${failed.ifEmpty { listOf("无") }}"
            )
        }
        return RoundReport(
            samples = samples,
            failedSources = failed.toList(),
            searched = searched,
            unreachable = unreachable
        )
    }

    /**
     * 价保助手用：拿该商品「当前同款最低可信价」（2026-11 价保规则：可申请金额 = 实付 − 同款最低可信价）。
     *
     * 与 [findLivePrice] 的差别：
     *  - 京东目标先走直查（p.3.cn / 星罗在售价）——价保比较的是**同平台**价格，直查口径正确；
     *    直查不可得时才退回关键词搜索价，并在来源标签里写清是「同款搜索价」；
     *  - 非京东平台遍历**全部**搜索源（不提前停），收集所有通过严格判定的候选取**最低价**，
     *    并返回候选数量（UI 可据此说明"多源一致/仅单源"）。
     *
     * @param referencePrice 历史参考价（购买记录传"实付价"即可）：候选价与它的比值必须在
     *   `AutoTrackPolicy.MIN_PRICE_RATIO..MAX_PRICE_RATIO` 内，否则不算"可信"
     * @return null = 没有可信候选（调用方应如实报"暂取不到现价"，不得落 0/旧价）
     */
    suspend fun findLowestTrustedPrice(platform: String, productId: String?, title: String, referencePrice: Double? = null): TrustedPrice? {
        if (WatchTargetPolicy.channelOf(platform) == WatchChannel.NONE) return null
        val reference = referencePrice?.takeIf { it > 0.0 } ?: historyReference(platform, productId)
        if (platform == WatchTargetPolicy.PLATFORM_JD) {
            directJdPrice(externalIdOf(platform, productId))?.let { return TrustedPrice(it, "京东直查", 1) }
        }
        val keyword = normalizeKeyword(title) ?: return null
        val accepted = mutableListOf<Pair<Candidate, String>>()
        for (source in sourceOrder(platform)) {
            val candidates = try {
                fetch(source, keyword)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单个源失败不影响"最低可信价"结论：其它源有可信候选就继续
                LogT.w("价保取价：${source.label}失败：${e.javaClass.simpleName}")
                continue
            }
            for (candidate in candidates) {
                if (!AutoTrackPolicy.matchOffer(keyword, candidate.title, candidate.price).accepted) continue
                if (!AutoTrackPolicy.isPriceRatioReasonable(candidate.price, reference)) continue
                accepted += candidate to source.label
            }
        }
        if (accepted.isEmpty()) return null
        val lowest = accepted.minByOrNull { it.first.price } ?: return null
        return TrustedPrice(
            sample = PriceSample(lowest.first.price, PriceSource.KEYWORD_SEARCH),
            sourceLabel = lowest.second,
            candidateCount = accepted.size
        )
    }

    /** [findLowestTrustedPrice] 的结果：最低可信价样本 + 命中来源 + 可信候选数量 */
    data class TrustedPrice(
        val sample: PriceSample,
        val sourceLabel: String,
        val candidateCount: Int
    )

    // ---------- 关键词候选获取与严格筛选 ----------

    /** 搜索源（既有三个关键词通道；顺序即优先级，见 [sourceOrder]） */
    private enum class SearchSource(val label: String) {
        DANGDANG("当当"),
        SHIHUO("识货"),
        SMZDM("值得买")
    }

    /** 搜索源返回的一条原始候选（未判定） */
    private data class Candidate(val title: String, val price: Double, val url: String, val source: SearchSource)

    /**
     * 单目标：按源优先级逐个搜，**第一个给出通过严格判定的候选的源命中即停**。
     *
     * 同源内多个候选按（相关性高 → 与参考价比值更接近 1 → 价格低）排序取最优；
     * 参考价拿不到时比值项退化为"不比较"，只按相关性排序（仍要求价格为正）。
     */
    private suspend fun keywordOffer(target: SearchTarget, keyword: String, failedLatch: MutableSet<String>): SearchResult {
        val reference = target.referencePrice?.takeIf { it > 0.0 } ?: historyReference(target.platform, target.productId)
        for (source in sourceOrder(target.platform)) {
            if (source.label in failedLatch) continue
            val candidates = try {
                fetch(source, keyword)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedLatch += source.label
                LogT.w("同款搜索：${source.label}本轮失败（后续目标跳过该源）：${e.javaClass.simpleName}")
                continue
            }
            val accepted = candidates.mapNotNull { candidate ->
                val match = AutoTrackPolicy.matchOffer(keyword, candidate.title, candidate.price)
                if (!match.accepted) return@mapNotNull null
                if (!AutoTrackPolicy.isPriceRatioReasonable(candidate.price, reference)) return@mapNotNull null
                candidate to match.relevance
            }.sortedWith(candidateComparator(reference, source))
            val best = accepted.firstOrNull() ?: continue
            return SearchResult(
                sample = PriceSample(best.first.price, PriceSource.KEYWORD_SEARCH),
                sourceLabel = source.label,
                failedSources = failedLatch.toList(),
                note = "同款候选：${best.first.title.take(40)}（${source.label}）"
            )
        }
        return SearchResult(
            sample = null,
            sourceLabel = null,
            failedSources = failedLatch.toList(),
            note = "没有通过严格相关性/价格比值判定的候选"
        )
    }

    /** 相关性优先 → 与参考价比值更接近 1 → 价格更低（确定性排序，便于排查） */
    private fun candidateComparator(reference: Double?, source: SearchSource): Comparator<Pair<Candidate, Double>> =
        compareByDescending<Pair<Candidate, Double>> { it.second }
            .thenBy { (candidate, _) ->
                if (reference == null || reference <= 0.0) 0.0 else abs(ln(candidate.price / reference))
            }
            .thenBy { (candidate, _) -> candidate.price }
            .thenBy { (candidate, _) -> if (candidate.source == source) 0 else 1 }

    /** 源优先级：目标平台的原生平价源优先，其余按既有可用性排序（值得买常被 WAF 拦 → 放前面失败也快） */
    private fun sourceOrder(platform: String): List<SearchSource> = when (platform) {
        WatchTargetPolicy.PLATFORM_DANGDANG -> listOf(SearchSource.DANGDANG, SearchSource.SMZDM, SearchSource.SHIHUO)
        WatchTargetPolicy.PLATFORM_SHIHUO -> listOf(SearchSource.SHIHUO, SearchSource.DANGDANG, SearchSource.SMZDM)
        WatchTargetPolicy.PLATFORM_SMZDM -> listOf(SearchSource.SMZDM, SearchSource.DANGDANG, SearchSource.SHIHUO)
        else -> listOf(SearchSource.SMZDM, SearchSource.DANGDANG, SearchSource.SHIHUO)
    }

    /** 单个源的取数（只调既有 PriceRepository 方法；它们自己带缓存/健康度/singleflight） */
    private suspend fun fetch(source: SearchSource, keyword: String): List<Candidate> = when (source) {
        SearchSource.DANGDANG -> priceRepository.searchDangdang(keyword)
            .map { Candidate(it.title, it.price, it.url, source) }
        SearchSource.SHIHUO -> priceRepository.searchShihuo(keyword)
            .map { Candidate(it.title, it.price, it.url, source) }
        SearchSource.SMZDM -> priceRepository.searchSmzdm(keyword)
            .mapNotNull { post -> post.price?.let { Candidate(post.title, it, post.url, source) } }
    }

    // ---------- 直查（京东）与参考价 ----------

    /**
     * 京东直查：p.3.cn 在售价优先，失败/缺价时星罗「在售价」兜底。
     * 星罗只有券后历史低价（`referenceOnly`）时返回 null —— 参考值不是现价（F1 纪律）。
     */
    private suspend fun directJdPrice(sku: String?): PriceSample? {
        if (sku == null || !WatchTargetPolicy.JD_SKU.matches(sku)) return null
        val price = runCatching { jdApi.getPrices(listOf(sku))[sku]?.first }.getOrNull()
        if (price != null && price > 0.0) return PriceSample(price, PriceSource.JD_P3CN)
        val apiKey = settings.linkstarsApiKey
        if (apiKey.isBlank()) return null
        val deal = runCatching { linkstarsApi.lookupSku(sku, apiKey) }.getOrNull() ?: return null
        return PriceSampling.curveWorthy(PriceSampling.linkstarsSample(deal.listPrice, deal.couponPrice))
    }

    /** 该商品最近一天的曲线收盘价（比值合理性参考）；null = 还没有任何曲线点 */
    private suspend fun historyReference(platform: String, productId: String?): Double? {
        val raw = productId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val curveKey = if (raw.contains(':')) raw else "${platform.trim().lowercase()}:$raw"
        return runCatching {
            db.priceHistoryDao().getByProduct(curveKey).lastOrNull()?.price?.takeIf { it > 0.0 }
        }.onFailure {
            LogT.w("同款搜索：读历史参考价失败（改为不比较比值）：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** 商品号：`<platform>:<id>` → `<id>`；纯数字/字母串原样返回；空 → null */
    private fun externalIdOf(platform: String, productId: String?): String? {
        val raw = productId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val prefix = "$platform:"
        return if (raw.startsWith(prefix)) raw.removePrefix(prefix).takeIf { it.isNotEmpty() } else raw
    }

    /**
     * 标题 → 搜索关键词：优先 [SearchQueryCleaner.clean]（去促销前缀/规格/颜色，按 token 边界截断）。
     * 清洗结果为 null（标题全是【】/规格）时退回原文截断，仍为空返回 null（该目标本轮跳过）。
     *
     * 刻意**不追加平台词**：三个搜索源都是跨平台关键词通道，标题里出现"京东/拼多多"这类
     * 平台名反而过不了 `QueryRelevance`（商品标题不含别家平台名），只会把候选全过滤掉。
     */
    private fun normalizeKeyword(title: String): String? {
        val cleaned = SearchQueryCleaner.clean(title)?.takeIf { it.length >= 2 }
            ?: title.trim().takeIf { it.length >= 2 }
            ?: return null
        return cleaned
    }

    companion object {
        /** 每轮最多真正发起关键词搜索的目标数（按住轮次预算；其余目标下一轮再看） */
        const val MAX_TARGETS_PER_ROUND: Int = 12
    }
}
