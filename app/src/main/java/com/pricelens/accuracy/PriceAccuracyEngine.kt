package com.pricelens.accuracy

import com.pricelens.util.PriceFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 信息源准确性引擎（纯函数、无 Android 依赖，JVM 单测可直接钉住每条规则）。
 *
 * 背景：同一个商品在"页面价 / 候选价 / 历史价 / 到手价"之间经常给出不同数字，用户看到的是
 * 一个没有出处的价格。引擎把本轮**真实拿到**的价格当作证据，做四件事：
 *
 *  1. **口径归一**：[PriceBasis] 把页面价、候选价、到手价、券后价、历史当前价、参考值分开记；
 *     不同口径的差额是信息（有券/不同规格），不是数据错误 —— 不一致时输出 [AccuracyConflict]；
 *  2. **多源共识**：可比现价两两相对差 ≤5% 记一致，≥15% 记冲突（5%~15% 记"口径差异"）；
 *     三源及以上且多数一致 → [ConfidenceLevel.HIGH]；两源一致视时鲜/相关性给 MEDIUM/HIGH；
 *     单源新鲜 → MEDIUM；只有历史/参考值 → LOW；没有价格 → UNKNOWN；
 *  3. **离群 / 时鲜**：中位数 + MAD（小样本用 max/min 比值兜底）判离群，离群证据在 reasons 点名
 *     且不给 HIGH；数据年龄 >24h 记 stale 并降档；**时刻未知不等于新鲜**；
 *  4. **人读结论**：≤14 字 headline、≤3 条带具体数字与来源名的 reasons、0..100 可解释评分
 *     （一致性 40 / 相关性 20 / 时鲜 20 / 来源多样 10 / 无冲突 10）、归一后的来源健康板。
 *
 * 输入契约不含逐条抓取时刻（只有 [AccuracyInput.historyStale] 一个布尔）：页面价、候选价、
 * 额外远端现价都来自**本轮**搜索/检测的内存结果，默认 ageMs=0（本轮新鲜）；历史当前价与参考值
 * 默认 ageMs=null（时刻未知）。需要显式指定时刻的内部调用可走 [evaluateWithAges]。
 *
 * 所有计算只对**有效价格**（非 null、有限、>0）进行：0/负价/NaN/Infinity 一律剔除，
 * 引擎不补 0、不补旧价、不猜数字。
 */
object PriceAccuracyEngine {

    /** 相关度下限：低于该值的证据视为"可能不是同一件商品"，剔除出共识计算 */
    const val RELEVANCE_FLOOR: Double = 0.6

    /** 相对差 ≤5% 记一致（分母取两者较大值） */
    const val CONSISTENT_DIFF: Double = 0.05

    /** 相对差 ≥15% 记冲突 */
    const val CONFLICT_DIFF: Double = 0.15

    /** 超过 24 小时的数据记为过期 */
    const val STALE_AGE_MS: Long = 24L * 60L * 60L * 1000L

    /** 小样本离群兜底：最大值 / 最小值 > 2.0 判离群（MAD 失效时使用） */
    const val OUTLIER_MAX_MIN_RATIO: Double = 2.0

    /** MAD 修正 Z 分数的离群阈值（Iglewicz-Hoaglin 常用 3.5） */
    const val MAD_Z_THRESHOLD: Double = 3.5

    /** 证据 sourceId：页面实时价（无障碍读取，最高信任） */
    const val SOURCE_PAGE: String = "page"

    /** 证据 sourceId：商品候选价（本页主价格） */
    const val SOURCE_CANDIDATE: String = "candidate"

    /** 证据 sourceId：历史当前价 / 历史最低价（同一来源"慢慢买/自建曲线"） */
    const val SOURCE_HISTORY: String = "history"

    /** 证据 sourceId：到手价（候选价减券后的实付价） */
    const val SOURCE_NET: String = "net"

    /** 证据 sourceId：券后价（候选价减券面额；与到手价不同口径时单列） */
    const val SOURCE_COUPON_AFTER: String = "coupon-after"

    private const val EPS = 1e-9
    private const val MAX_CONFLICTS = 3
    private const val MAX_REASONS = 3
    private const val MAX_EXACT_GROUP = 12
    private const val MAD_Z_SCALE = 0.6745
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val HEADLINE_CANDIDATE_TITLE_MAX = 12

    private const val LABEL_PAGE = "页面价"
    private const val LABEL_PAGE_FALLBACK = "页面实时价"
    private const val LABEL_CANDIDATE = "候选价"
    private const val LABEL_CANDIDATE_EVIDENCE = "商品候选"
    private const val LABEL_NET = "到手价"
    private const val LABEL_COUPON_AFTER = "券后价"
    private const val LABEL_HISTORY_CURRENT = "历史当前价"
    private const val LABEL_HISTORY_LOWEST = "历史最低价"
    private const val LABEL_NO_PRICE = "暂无价格"

    private const val LABEL_HARD_CONFLICT = "价格冲突"
    private const val LABEL_NEAR_CONFLICT = "口径差异"
    private const val LABEL_BASIS_CONFLICT = "券口径"

    /**
     * 评估一轮搜索的准确性（UI 与 ViewModel 的唯一调用入口）。
     *
     * 契约时刻约定见类 KDoc：本轮取到的价格按 ageMs=0 计，历史证据 ageMs=null。
     * 纯函数：同一输入必然得到同一输出（无时钟、无随机、无 I/O）。
     */
    fun evaluate(input: AccuracyInput): AccuracySnapshot = evaluateWithAges(input, emptyMap())

    /**
     * 与 [evaluate] 同算法，但允许显式指定各证据的数据年龄（毫秒；null = 已知时刻不可得）。
     *
     * 用途：
     *  - JVM 单测覆盖 "ageMs > 24h → stale 降档" 与 "时刻未知 ≠ 新鲜" 两条规则
     *    （公开输入契约没有逐条 age 字段，只能从内部注入）；
     *  - 未来契约若补齐时刻字段，ViewModel 可直接改调本方法而不用动规则。
     *
     * @param ageHints key = evidence.sourceId。未指定的 source 走默认约定：页面/候选/额外现价 0，
     *                 历史证据 null。
     */
    internal fun evaluateWithAges(input: AccuracyInput, ageHints: Map<String, Long?>): AccuracySnapshot {
        val bag = collectEvidence(input, ageHints)
        // 复制成只读列表再对外发布：Snapshot 声称不可变，内部不再持有可变引用
        val evidence: List<PriceEvidence> = bag.evidence.toList()
        val independentCurrent = evidence.filter { it.price != null && isComparableCurrent(it) }
        val netEvidence = evidence.firstOrNull { it.price != null && it.basis == PriceBasis.NET }
        val candidateEvidence = evidence.firstOrNull { it.price != null && it.sourceId == SOURCE_CANDIDATE }
        val pageEvidence = evidence.firstOrNull { it.price != null && it.basis == PriceBasis.PAGE }
        val couponEvidence = evidence.firstOrNull { it.price != null && it.basis == PriceBasis.COUPON_AFTER }
        val historyCurrentEvidence = evidence.firstOrNull {
            it.price != null && it.sourceId == SOURCE_HISTORY && it.basis == PriceBasis.UNKNOWN
        }
        val referenceEvidence = evidence.firstOrNull { it.price != null && it.basis == PriceBasis.REFERENCE }

        // ---- 一致性：可比现价的两两共识 / 冲突 ----
        val group = largestConsistentGroup(independentCurrent.map { it.price ?: 0.0 })
        val clusterEvidence = group.indices.map { independentCurrent[it] }
        val pairConflicts = comparePairs(independentCurrent, input.candidateTitle)
        val netConflicts = checkNet(netEvidence, bag.couponAmount, candidateEvidence, independentCurrent, input.candidateTitle)
        val priceConflicts = pairConflicts + netConflicts
        val hardConflict = priceConflicts.any { it.label == LABEL_HARD_CONFLICT }
        val nearConflict = priceConflicts.any { it.label == LABEL_NEAR_CONFLICT } || bag.couponBasisConflict != null
        val conflicts = buildList {
            addAll(priceConflicts)
            bag.couponBasisConflict?.let { add(it) }
        }.distinctBy { it.label + '|' + it.detail }.take(MAX_CONFLICTS)

        // ---- 离群：只对可比现价做（参考值/券后价天然与现价不同，不参与） ----
        val outliers = findOutliers(independentCurrent)
        val hasOutlier = outliers.isNotEmpty()

        // ---- 时鲜度 ----
        val freshCount = independentCurrent.count { isFresh(it) }
        val netFresh = netEvidence?.let { isFresh(it) } ?: false

        // ---- 档位 / 评分 ----
        // 无任何证据 → UNKNOWN；只有历史/参考值（无可比现价且无到手价）→ LOW；其余交给规则定档
        val level = when {
            evidence.isEmpty() -> ConfidenceLevel.UNKNOWN
            independentCurrent.isEmpty() && netEvidence == null -> ConfidenceLevel.LOW
            else -> decideLevel(
                independentCount = independentCurrent.size,
                clusterSize = group.size,
                freshCount = freshCount,
                hasNet = netEvidence != null,
                netFresh = netFresh,
                hardConflict = hardConflict,
                hasOutlier = hasOutlier,
                nearConflict = nearConflict,
                avgRelevance = averageRelevance(independentCurrent, netEvidence)
            )
        }
        val maxRelevance = evidence.maxOfOrNull { it.relevance } ?: 0.0
        val score = computeScore(
            evidence = evidence,
            independent = independentCurrent,
            netEvidence = netEvidence,
            clusterSize = group.size,
            freshCount = freshCount,
            netFresh = netFresh,
            maxRelevance = maxRelevance,
            hardConflict = hardConflict,
            hasOutlier = hasOutlier,
            nearConflict = nearConflict
        )

        // ---- 主口径与文案 ----
        val (basis, basisLabel) = pickBasis(
            pageEvidence = pageEvidence,
            netEvidence = netEvidence,
            couponEvidence = couponEvidence,
            candidateEvidence = candidateEvidence,
            historyCurrentEvidence = historyCurrentEvidence,
            referenceEvidence = referenceEvidence
        )
        val onlyHistoryLike = evidence.isNotEmpty() &&
            (independentCurrent.isEmpty() || independentCurrent.all { it.sourceId == SOURCE_HISTORY }) &&
            netEvidence == null
        val hasStaleEvidence = evidence.any { it.stale }
        val headline = buildHeadline(
            level = level,
            clusterSize = group.size,
            hardConflict = hardConflict,
            nearConflict = nearConflict,
            basis = basis,
            onlyHistoryLike = onlyHistoryLike,
            hasStaleEvidence = hasStaleEvidence
        )
        val sourceBoard = normalizeSourceBoard(input.sourceCells)
        val reasons = buildReasons(
            input = input,
            bag = bag,
            evidence = evidence,
            independentCurrent = independentCurrent,
            clusterEvidence = clusterEvidence,
            clusterSize = group.size,
            outliers = outliers,
            conflicts = conflicts,
            priceConflicts = priceConflicts,
            referenceEvidence = referenceEvidence,
            basisLabel = basisLabel,
            sourceBoard = sourceBoard
        )

        return AccuracySnapshot(
            level = level,
            score = score,
            headline = headline,
            reasons = reasons,
            basis = basis,
            basisLabel = basisLabel,
            evidence = evidence,
            conflicts = conflicts,
            sourceBoard = sourceBoard,
            generatedAtMs = input.nowMs
        )
    }

    // ---------- 证据收集与口径 ----------

    /** 证据收集的中间结果（可变，只在单次评估内使用） */
    private class EvidenceBag {
        /** 已收集的有效证据（按口径顺序：页面 → 候选 → 额外远端 → 历史当前 → 历史最低 → 到手 → 券后） */
        val evidence = mutableListOf<PriceEvidence>()

        /** 因相关度 < [RELEVANCE_FLOOR] 被剔除的证据条数（reasons 要如实说明） */
        var droppedLowRelevance: Int = 0

        /** 本轮可用券面额（>0；用于券后价与到手价归一比较） */
        var couponAmount: Double? = null

        /** 券后价与到手价口径不一致时的冲突项（两者不该混为一谈） */
        var couponBasisConflict: AccuracyConflict? = null
    }

    /**
     * 按口径收集证据。顺序固定（页面 → 候选 → 额外远端 → 历史当前 → 历史最低 → 到手 → 券后），
     * 保证同一输入产出稳定顺序，UI 展开面板不会每轮跳位。
     */
    private fun collectEvidence(input: AccuracyInput, ageHints: Map<String, Long?>): EvidenceBag {
        val bag = EvidenceBag()
        bag.couponAmount = input.couponTotal?.takeIf { isUsablePrice(it) }
        val pagePrice = input.pagePrice?.takeIf { isUsablePrice(it) }
        val candidatePrice = input.candidatePrice?.takeIf { isUsablePrice(it) }
        val candidateRelevance = (input.candidateRelevance ?: 1.0).coerceIn(0.0, 1.0)
        // 候选价相关度不足时整条剔除；到手价/券后价是它的派生口径，一并不采用
        val candidateKept = candidatePrice != null && candidateRelevance >= RELEVANCE_FLOOR
        val netPrice = input.netPrice?.takeIf { isUsablePrice(it) }

        if (pagePrice != null) {
            val pageAge = ageOf(ageHints, SOURCE_PAGE)
            val label = input.pageSourceLabel?.trim()?.takeIf { it.isNotEmpty() } ?: LABEL_PAGE_FALLBACK
            bag.evidence += PriceEvidence(
                sourceId = SOURCE_PAGE,
                label = label,
                price = pagePrice,
                basis = PriceBasis.PAGE,
                ageMs = pageAge,
                stale = isStaleAge(pageAge),
                relevance = 1.0
            )
        }
        if (candidatePrice != null) {
            if (candidateKept) {
                val age = ageOf(ageHints, SOURCE_CANDIDATE)
                bag.evidence += PriceEvidence(
                    sourceId = SOURCE_CANDIDATE,
                    label = LABEL_CANDIDATE_EVIDENCE,
                    price = candidatePrice,
                    basis = PriceBasis.UNKNOWN,
                    ageMs = age,
                    stale = isStaleAge(age),
                    relevance = candidateRelevance
                )
            } else {
                bag.droppedLowRelevance++
            }
        }

        // 额外远端现价：调用方已过 QueryRelevance，这里只兜"有效价格 + 相关性下限 + 同源去重"
        val seenRemoteSources = mutableSetOf<String>()
        for (remote in input.extraRemotePrices) {
            if (!isUsablePrice(remote.price)) continue
            val relevance = remote.relevance.coerceIn(0.0, 1.0)
            if (relevance < RELEVANCE_FLOOR) {
                bag.droppedLowRelevance++
                continue
            }
            val sourceId = remote.sourceId.trim().ifEmpty { remote.label.trim() }
            if (sourceId.isEmpty() || !seenRemoteSources.add(sourceId)) continue
            val age = ageOf(ageHints, sourceId)
            bag.evidence += PriceEvidence(
                sourceId = sourceId,
                label = remote.label.trim().ifEmpty { sourceId },
                price = remote.price,
                basis = PriceBasis.UNKNOWN,
                ageMs = age,
                stale = isStaleAge(age),
                relevance = relevance
            )
        }

        // 历史当前价：现价，但时刻未知（契约无逐条时刻）；stale 由降级缓存标记决定
        val historyAge = ageOf(ageHints, SOURCE_HISTORY)
        val historyStale = input.historyStale || isStaleAge(historyAge)
        val historyCurrent = input.historyCurrent?.takeIf { isUsablePrice(it) }
        if (historyCurrent != null) {
            bag.evidence += PriceEvidence(
                sourceId = SOURCE_HISTORY,
                label = LABEL_HISTORY_CURRENT,
                price = historyCurrent,
                basis = PriceBasis.UNKNOWN,
                ageMs = historyAge,
                stale = historyStale,
                relevance = 1.0
            )
        }
        // 历史最低价：参考值（非现价），只作参考，不参与现价一致性/离群
        val historyLowest = input.historyLowest?.takeIf { isUsablePrice(it) }
        if (historyLowest != null) {
            bag.evidence += PriceEvidence(
                sourceId = SOURCE_HISTORY,
                label = LABEL_HISTORY_LOWEST,
                price = historyLowest,
                basis = PriceBasis.REFERENCE,
                ageMs = historyAge,
                stale = historyStale,
                relevance = 1.0
            )
        }

        // 到手价：候选价减券后的实付价。候选相关度不足时不采用派生口径
        val netAllowed = netPrice != null && (candidatePrice == null || candidateKept)
        if (netPrice != null && netAllowed) {
            val age = ageOf(ageHints, SOURCE_NET)
            bag.evidence += PriceEvidence(
                sourceId = SOURCE_NET,
                label = LABEL_NET,
                price = netPrice,
                basis = PriceBasis.NET,
                ageMs = age,
                stale = isStaleAge(age),
                relevance = if (candidatePrice != null) candidateRelevance else 1.0
            )
        }

        // 券后价：候选价 − 券面额（>0 才算）。与到手价同值时不重复记证据；
        // 两者明显不同则是"券是否可叠加"的口径差异，如实报出来（不按数据错误处理）
        val couponAmount = bag.couponAmount
        if (candidatePrice != null && candidateKept && couponAmount != null) {
            val afterCoupon = candidatePrice - couponAmount
            if (afterCoupon > 0.0 && isUsablePrice(afterCoupon)) {
                if (netPrice != null && netAllowed && relDiff(afterCoupon, netPrice) <= CONSISTENT_DIFF + EPS) {
                    // 同一口径：调用方正是用（候选价 − 到手价）反推券面额，不重复计证据
                } else {
                    val age = ageOf(ageHints, SOURCE_COUPON_AFTER)
                    bag.evidence += PriceEvidence(
                        sourceId = SOURCE_COUPON_AFTER,
                        label = LABEL_COUPON_AFTER,
                        price = afterCoupon,
                        basis = PriceBasis.COUPON_AFTER,
                        ageMs = age,
                        stale = isStaleAge(age),
                        relevance = candidateRelevance
                    )
                    if (netPrice != null && netAllowed) {
                        bag.couponBasisConflict = AccuracyConflict(
                            LABEL_BASIS_CONFLICT,
                            "券后价 ${money(afterCoupon)} 与到手价 ${money(netPrice)} 相差 " +
                                "${percent(relDiff(afterCoupon, netPrice))}：券是否可叠加未知，按到手价为准"
                        )
                    }
                }
            }
        }
        return bag
    }

    /**
     * 一条证据是否属于"可比现价"（参与一致性 / 离群判定）：
     * 页面价、候选价、额外远端现价、历史当前价（basis=UNKNOWN）都算；
     * 参考值（历史最低价）、券后价、到手价不算 —— 它们与券前现价天然不同口径。
     */
    private fun isComparableCurrent(evidence: PriceEvidence): Boolean =
        evidence.basis == PriceBasis.PAGE || evidence.basis == PriceBasis.UNKNOWN

    // ---------- 一致性 / 冲突 ----------

    /** 两两比较可比现价：≥15% 记价格冲突，5%~15% 记口径差异 */
    private fun comparePairs(items: List<PriceEvidence>, candidateTitle: String?): List<AccuracyConflict> {
        val conflicts = mutableListOf<AccuracyConflict>()
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                val first = items[i]
                val second = items[j]
                val firstPrice = first.price ?: continue
                val secondPrice = second.price ?: continue
                val diff = relDiff(firstPrice, secondPrice)
                when {
                    diff + EPS >= CONFLICT_DIFF -> conflicts += AccuracyConflict(
                        LABEL_HARD_CONFLICT,
                        "${name(first, candidateTitle)} ${money(firstPrice)} 与 " +
                            "${name(second, candidateTitle)} ${money(secondPrice)} 相差 ${percent(diff)}：" +
                            "可能不是同款商品，建议核对规格"
                    )
                    diff > CONSISTENT_DIFF + EPS -> conflicts += AccuracyConflict(
                        LABEL_NEAR_CONFLICT,
                        "${name(first, candidateTitle)} ${money(firstPrice)} 与 " +
                            "${name(second, candidateTitle)} ${money(secondPrice)} 相差 ${percent(diff)}：" +
                            "可能是不同规格/券后，建议核对"
                    )
                }
            }
        }
        return conflicts
    }

    /**
     * 到手价交叉验证：到手价是"券后口径"，不能直接和券前价比数字大小。
     *  - 券面额已知：到手价 + 券面额 = 券前等价，与候选价一致说明同源派生（不重复判定），
     *    否则与其它现价比较，差异 ≥15% 报硬冲突、5%~15% 报口径差异；
     *  - 券面额未知：只做方向检查 —— 到手价不应高于券前价（高于 15% 说明口径可能弄反）。
     */
    private fun checkNet(
        netEvidence: PriceEvidence?,
        couponAmount: Double?,
        candidateEvidence: PriceEvidence?,
        independentCurrent: List<PriceEvidence>,
        candidateTitle: String?
    ): List<AccuracyConflict> {
        val netItem = netEvidence ?: return emptyList()
        val net = netItem.price ?: return emptyList()
        if (independentCurrent.isEmpty()) return emptyList()
        val netName = name(netItem, candidateTitle)
        if (couponAmount != null) {
            val normalizedNet = net + couponAmount
            val candidatePrice = candidateEvidence?.price
            if (candidatePrice != null && relDiff(candidatePrice, normalizedNet) <= CONSISTENT_DIFF + EPS) {
                // 到手价与候选价同源（券面额就是两者差值），不再重复判定
                return emptyList()
            }
            val worst = independentCurrent.maxByOrNull { relDiff(it.price ?: 0.0, normalizedNet) }
                ?: return emptyList()
            val worstPrice = worst.price ?: return emptyList()
            val diff = relDiff(worstPrice, normalizedNet)
            return when {
                diff + EPS >= CONFLICT_DIFF -> listOf(
                    AccuracyConflict(
                        LABEL_HARD_CONFLICT,
                        "$netName ${money(net)} 按券面额归一到手前 ${money(normalizedNet)} 后，与 " +
                            "${name(worst, candidateTitle)} ${money(worstPrice)} 相差 ${percent(diff)}：" +
                            "券或口径可能不对，建议核对"
                    )
                )
                diff > CONSISTENT_DIFF + EPS -> listOf(
                    AccuracyConflict(
                        LABEL_NEAR_CONFLICT,
                        "$netName ${money(net)} 与 ${name(worst, candidateTitle)} ${money(worstPrice)} " +
                            "按券面额归一后相差 ${percent(diff)}：券或口径待核对"
                    )
                )
                else -> emptyList()
            }
        }
        val lowest = independentCurrent.minOfOrNull { it.price ?: 0.0 } ?: return emptyList()
        if (net > lowest * (1.0 + CONFLICT_DIFF)) {
            return listOf(
                AccuracyConflict(
                    LABEL_HARD_CONFLICT,
                    "$netName ${money(net)} 高于券前价最低 ${money(lowest)}：到手价口径可能弄反了"
                )
            )
        }
        return emptyList()
    }

    // ---------- 离群 ----------

    /** 中位数 + MAD 判离群；MAD 为 0（小样本/中位数附近完全重合）时用 max/min 比值兜底 */
    private fun findOutliers(items: List<PriceEvidence>): List<OutlierHit> {
        val values = items.mapNotNull { it.price }
        if (values.size < 2) return emptyList()
        val median = medianOf(values)
        val mad = medianOf(values.map { abs(it - median) })
        val hits = mutableListOf<OutlierHit>()
        if (mad > EPS) {
            for (item in items) {
                val price = item.price ?: continue
                val z = MAD_Z_SCALE * abs(price - median) / mad
                if (z > MAD_Z_THRESHOLD) hits += OutlierHit(item, median)
            }
        }
        if (hits.isEmpty()) {
            val maxValue = values.maxOrNull() ?: return emptyList()
            val minValue = values.minOrNull() ?: return emptyList()
            if (minValue > 0.0 && maxValue / minValue > OUTLIER_MAX_MIN_RATIO) {
                // 比值兜底点名最高的那条（实际成因多为规格/套装错配挂在低价商品上）
                items.maxByOrNull { it.price ?: 0.0 }?.let { hits += OutlierHit(it, median) }
            }
        }
        return hits
    }

    /** 一条离群证据 + 本轮中位数（写 reasons 用） */
    private class OutlierHit(val evidence: PriceEvidence, val median: Double)

    // ---------- 档位 / 评分 ----------

    /**
     * 按规则定档（分数不参与定档：评分是可解释的辅助信号，档位由共识结构决定）。
     *
     * 规则原文映射：
     *  - 三源及以上且多数一致 → HIGH（另有 ≥2 条新鲜现价、无硬冲突/离群/口径差异）；
     *  - 两源一致且至少一源新鲜 → MEDIUM / HIGH（两源都新鲜且相关性 ≥0.8 才 HIGH）；
     *  - 单源新鲜 → MEDIUM；单源不新鲜 → LOW；
     *  - 只有历史/参考值 → LOW；无价 → UNKNOWN（由调用方前置判定）。
     */
    private fun decideLevel(
        independentCount: Int,
        clusterSize: Int,
        freshCount: Int,
        hasNet: Boolean,
        netFresh: Boolean,
        hardConflict: Boolean,
        hasOutlier: Boolean,
        nearConflict: Boolean,
        avgRelevance: Double
    ): ConfidenceLevel = when {
        independentCount == 0 -> if (hasNet && netFresh) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW
        independentCount >= 3 -> {
            val majority = clusterSize > independentCount / 2
            when {
                !majority -> ConfidenceLevel.LOW
                hardConflict -> ConfidenceLevel.MEDIUM
                hasOutlier || nearConflict -> ConfidenceLevel.MEDIUM
                freshCount >= 2 -> ConfidenceLevel.HIGH
                else -> ConfidenceLevel.MEDIUM
            }
        }
        independentCount == 2 -> when {
            clusterSize < 2 -> ConfidenceLevel.LOW
            hardConflict -> ConfidenceLevel.LOW
            freshCount == 0 -> ConfidenceLevel.LOW
            freshCount == 2 && avgRelevance >= 0.8 && !hasOutlier && !nearConflict -> ConfidenceLevel.HIGH
            else -> ConfidenceLevel.MEDIUM
        }
        else -> when {
            hardConflict -> ConfidenceLevel.LOW
            freshCount == 1 && !hasOutlier -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    /**
     * 0..100 可解释评分：一致性 40 / 相关性 20 / 时鲜 20 / 来源多样 10 / 无冲突 10。
     *
     * 一致性：3 条两两一致 40，2 条一致 28，两条以上互不一致 0，单源 12（无法交叉验证但无矛盾）；
     * 相关性：证据里最高的相关度 ×20；
     * 时鲜：可比现价全部新鲜 20，部分新鲜 12，全不新鲜 0；
     * 来源多样：参与现价的来源数 3+ →10，2 →7，1 →3；
     * 无冲突：硬冲突 0，离群/口径差异 5，干净 10。没有任何证据时总分 0。
     */
    private fun computeScore(
        evidence: List<PriceEvidence>,
        independent: List<PriceEvidence>,
        netEvidence: PriceEvidence?,
        clusterSize: Int,
        freshCount: Int,
        netFresh: Boolean,
        maxRelevance: Double,
        hardConflict: Boolean,
        hasOutlier: Boolean,
        nearConflict: Boolean
    ): Int {
        if (evidence.isEmpty()) return 0
        val consistencyScore = when {
            independent.size >= 2 -> when {
                clusterSize >= 3 -> 40
                clusterSize == 2 -> 28
                else -> 0
            }
            independent.size == 1 -> 12
            netEvidence != null -> 10
            else -> 0
        }
        val relevanceScore = (maxRelevance.coerceIn(0.0, 1.0) * 20.0).roundToInt()
        val freshnessScore = when {
            independent.isNotEmpty() -> when {
                freshCount == independent.size -> 20
                freshCount > 0 -> 12
                else -> 0
            }
            netEvidence != null -> if (netFresh) 20 else 0
            else -> 0
        }
        val distinctSources = (independent.map { it.sourceId } + listOfNotNull(netEvidence?.sourceId)).toSet().size
        val diversityScore = when {
            distinctSources >= 3 -> 10
            distinctSources == 2 -> 7
            distinctSources == 1 -> 3
            else -> 0
        }
        val conflictScore = when {
            hardConflict -> 0
            hasOutlier || nearConflict -> 5
            else -> 10
        }
        return (consistencyScore + relevanceScore + freshnessScore + diversityScore + conflictScore).coerceIn(0, 100)
    }

    // ---------- 主口径 / headline / reasons ----------

    /** 主口径优先级：页面价 > 到手价 > 券后价 > 候选价 > 历史当前价 > 参考值 */
    private fun pickBasis(
        pageEvidence: PriceEvidence?,
        netEvidence: PriceEvidence?,
        couponEvidence: PriceEvidence?,
        candidateEvidence: PriceEvidence?,
        historyCurrentEvidence: PriceEvidence?,
        referenceEvidence: PriceEvidence?
    ): Pair<PriceBasis, String> = when {
        pageEvidence != null -> PriceBasis.PAGE to LABEL_PAGE
        netEvidence != null -> PriceBasis.NET to LABEL_NET
        couponEvidence != null -> PriceBasis.COUPON_AFTER to LABEL_COUPON_AFTER
        candidateEvidence != null -> PriceBasis.UNKNOWN to LABEL_CANDIDATE
        historyCurrentEvidence != null -> PriceBasis.UNKNOWN to LABEL_HISTORY_CURRENT
        referenceEvidence != null -> PriceBasis.REFERENCE to LABEL_HISTORY_LOWEST
        else -> PriceBasis.UNKNOWN to LABEL_NO_PRICE
    }

    /** 人读结论，≤14 字（含分隔符；单测逐档位钉住） */
    private fun buildHeadline(
        level: ConfidenceLevel,
        clusterSize: Int,
        hardConflict: Boolean,
        nearConflict: Boolean,
        basis: PriceBasis,
        onlyHistoryLike: Boolean,
        hasStaleEvidence: Boolean
    ): String = when {
        hardConflict -> "口径冲突 · 请核对规格"
        nearConflict -> "口径略有差异 · 待核"
        level == ConfidenceLevel.UNKNOWN -> "暂无价格 · 无法核价"
        level == ConfidenceLevel.HIGH -> "$clusterSize 源一致 · 可信"
        level == ConfidenceLevel.MEDIUM && clusterSize >= 2 -> "$clusterSize 源一致 · 基本可信"
        level == ConfidenceLevel.MEDIUM && basis == PriceBasis.PAGE -> "页面价 · 单源可信"
        level == ConfidenceLevel.MEDIUM && basis == PriceBasis.NET -> "到手价 · 单源可参考"
        level == ConfidenceLevel.LOW && onlyHistoryLike -> "仅历史参考 · 谨慎下单"
        level == ConfidenceLevel.LOW && hasStaleEvidence -> "数据偏旧 · 建议复核"
        level == ConfidenceLevel.LOW -> "来源不一致 · 谨慎下单"
        else -> "单源数据 · 建议页面核价"
    }

    /**
     * 生成 ≤3 条中文原因，按"最影响决策"排序：冲突 → 离群 → 无价 → 单源/参考值 →
     * 过期 → 时刻未知 → 来源不可达 → 旧数据来源 → 相关性剔除 → 一致来源 → 口径兜底。
     */
    private fun buildReasons(
        input: AccuracyInput,
        bag: EvidenceBag,
        evidence: List<PriceEvidence>,
        independentCurrent: List<PriceEvidence>,
        clusterEvidence: List<PriceEvidence>,
        clusterSize: Int,
        outliers: List<OutlierHit>,
        conflicts: List<AccuracyConflict>,
        priceConflicts: List<AccuracyConflict>,
        referenceEvidence: PriceEvidence?,
        basisLabel: String,
        sourceBoard: List<SourceCell>
    ): List<String> {
        val reasons = mutableListOf<String>()
        val title = input.candidateTitle
        priceConflicts.firstOrNull { it.label == LABEL_HARD_CONFLICT }?.let { reasons += it.detail }
        if (reasons.isEmpty()) {
            priceConflicts.firstOrNull { it.label == LABEL_NEAR_CONFLICT }?.let { reasons += it.detail }
        }
        if (reasons.isEmpty()) {
            conflicts.firstOrNull { it.label == LABEL_BASIS_CONFLICT }?.let { reasons += it.detail }
        }
        if (reasons.size < MAX_REASONS) {
            outliers.firstOrNull()?.let { reasons += outlierReason(it, title) }
        }
        if (reasons.size < MAX_REASONS && evidence.isEmpty()) {
            val keyword = input.keyword.trim().take(20)
            reasons += if (keyword.isEmpty()) {
                "没有拿到任何可用价格"
            } else {
                "关键词「$keyword」没有拿到任何可用价格"
            }
        }
        if (reasons.size < MAX_REASONS && evidence.isNotEmpty() && independentCurrent.isEmpty()) {
            // 可比现价为空 = 只有参考值（历史当前价本身属于可比现价）
            reasons += referenceOnlyReason(referenceEvidence)
        }
        if (reasons.size < MAX_REASONS && independentCurrent.size == 1) {
            reasons += singleSourceReason(independentCurrent.first(), title)
        }
        if (reasons.size < MAX_REASONS) {
            independentCurrent.firstOrNull { it.stale && it.ageMs != null }?.let { stale ->
                val hours = hoursCeil(stale.ageMs ?: 0L)
                reasons += "「${name(stale, title)}」数据已 $hours 小时未更新（超过 24 小时），已降档"
            }
        }
        if (reasons.size < MAX_REASONS) {
            evidence.firstOrNull { it.stale && it.ageMs == null }?.let { stale ->
                reasons += "「${name(stale, title)}」被标记为旧数据（降级缓存），已降档"
            }
        }
        if (reasons.size < MAX_REASONS) {
            val unknownAge = clusterEvidence.firstOrNull { it.ageMs == null }
                ?: independentCurrent.firstOrNull { it.ageMs == null }
            if (unknownAge != null) {
                reasons += "「${name(unknownAge, title)}」数据时刻未知，不按新鲜数据计入"
            }
        }
        val unreachable = sourceBoard.filter { it.state == SourceState.BLOCKED || it.state == SourceState.DEGRADED }
        if (reasons.size < MAX_REASONS && unreachable.isNotEmpty()) {
            reasons += "${unreachable.size} 个来源不可达：${namesOf(unreachable)}"
        }
        val staleSources = sourceBoard.filter { it.state == SourceState.STALE }
        if (reasons.size < MAX_REASONS && staleSources.isNotEmpty()) {
            reasons += "${staleSources.size} 个来源返回旧数据：${namesOf(staleSources)}"
        }
        if (reasons.size < MAX_REASONS && bag.droppedLowRelevance > 0) {
            reasons += "${bag.droppedLowRelevance} 条证据相关度低于 0.6，已剔除"
        }
        if (reasons.size < MAX_REASONS && clusterSize >= 2) {
            val clusterNames = clusterEvidence.joinToString("、") { name(it, title) }
            reasons += "共 $clusterSize 个来源一致：$clusterNames（相对差 ≤5%）"
        }
        if (reasons.size < MAX_REASONS) {
            reasons += "当前口径：$basisLabel"
        }
        return (if (reasons.isEmpty()) listOf("当前口径：$basisLabel") else reasons).take(MAX_REASONS)
    }

    // ---------- 来源健康板 ----------

    /** 归一化：按 sourceId 去重（先出现者为准）、去空白、空名回退 ID、空备注归一成 null */
    private fun normalizeSourceBoard(cells: List<SourceCell>): List<SourceCell> {
        val normalized = LinkedHashMap<String, SourceCell>()
        for (cell in cells) {
            val sourceId = cell.sourceId.trim()
            if (sourceId.isEmpty() || normalized.containsKey(sourceId)) continue
            normalized[sourceId] = SourceCell(
                sourceId = sourceId,
                label = cell.label.trim().ifEmpty { sourceId },
                state = cell.state,
                note = cell.note?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
        return normalized.values.toList()
    }

    // ---------- 纯工具 ----------

    /** 有效价格：非 null、有限、>0。0/负价/NaN/Infinity 一律剔除（不补、不猜） */
    internal fun isUsablePrice(value: Double?): Boolean = value != null && value.isFinite() && value > 0.0

    /** 相对差：|a-b| / max(a,b)（两侧都 >0；占比口径与"差 5%"的说法一致） */
    internal fun relativeDiff(first: Double, second: Double): Double {
        val larger = maxOf(first, second)
        if (larger <= 0.0) return 0.0
        return abs(first - second) / larger
    }

    private fun relDiff(first: Double, second: Double): Double = relativeDiff(first, second)

    private fun isStaleAge(ageMs: Long?): Boolean = ageMs != null && ageMs > STALE_AGE_MS

    /**
     * 某证据默认数据年龄：契约没有逐条时刻字段。
     *  - 页面/候选/额外现价/到手/券后：本轮搜索或本轮检测的内存结果 → 0（本轮新鲜）；
     *  - 历史当前价/历史最低价：来自历史缓存通道，时刻不可知 → null（**不等于**新鲜）。
     */
    private fun ageOf(ageHints: Map<String, Long?>, sourceId: String): Long? {
        if (ageHints.containsKey(sourceId)) return ageHints[sourceId]
        return if (sourceId == SOURCE_HISTORY) null else 0L
    }

    private fun isFresh(evidence: PriceEvidence): Boolean {
        val age = evidence.ageMs ?: return false
        return age <= STALE_AGE_MS && !evidence.stale
    }

    private fun averageRelevance(independent: List<PriceEvidence>, netEvidence: PriceEvidence?): Double = when {
        independent.isNotEmpty() -> independent.sumOf { it.relevance } / independent.size
        netEvidence != null -> netEvidence.relevance
        else -> 0.0
    }

    /** 最大两两一致子集：值两两相对差都 ≤5% 的最大集合（n ≤ 12 用精确枚举，更多走中心启发式） */
    private class ConsistentGroup(val size: Int, val indices: List<Int>)

    private fun largestConsistentGroup(values: List<Double>): ConsistentGroup {
        if (values.isEmpty()) return ConsistentGroup(0, emptyList())
        if (values.size == 1) return ConsistentGroup(1, listOf(0))
        if (values.size > MAX_EXACT_GROUP) {
            var bestIndex = 0
            var bestSize = 1
            for (i in values.indices) {
                var size = 1
                for (j in values.indices) {
                    if (i != j && relDiff(values[i], values[j]) <= CONSISTENT_DIFF + EPS) size++
                }
                if (size > bestSize) {
                    bestSize = size
                    bestIndex = i
                }
            }
            val indices = values.indices.filter {
                it == bestIndex || relDiff(values[bestIndex], values[it]) <= CONSISTENT_DIFF + EPS
            }
            return ConsistentGroup(indices.size, indices)
        }
        var bestMask = 1
        var bestCount = 1
        for (mask in 1 until (1 shl values.size)) {
            val count = Integer.bitCount(mask)
            if (count <= bestCount) continue
            if (isPairwiseConsistent(values, mask)) {
                bestCount = count
                bestMask = mask
            }
        }
        val indices = values.indices.filter { ((bestMask shr it) and 1) == 1 }
        return ConsistentGroup(bestCount, indices)
    }

    private fun isPairwiseConsistent(values: List<Double>, mask: Int): Boolean {
        val picked = values.indices.filter { ((mask shr it) and 1) == 1 }
        for (i in picked.indices) {
            for (j in i + 1 until picked.size) {
                if (relDiff(values[picked[i]], values[picked[j]]) > CONSISTENT_DIFF + EPS) return false
            }
        }
        return true
    }

    private fun medianOf(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** 证据的人读名：候选证据带上标题前 12 字（冲突细节要让用户认得出是哪件商品） */
    private fun name(evidence: PriceEvidence, candidateTitle: String?): String =
        if (evidence.sourceId == SOURCE_CANDIDATE && !candidateTitle.isNullOrBlank()) {
            "商品候选《${candidateTitle.take(HEADLINE_CANDIDATE_TITLE_MAX)}》"
        } else {
            evidence.label
        }

    private fun outlierReason(hit: OutlierHit, candidateTitle: String?): String =
        "「${name(hit.evidence, candidateTitle)}」${money(hit.evidence.price ?: 0.0)} " +
            "明显离群（本轮中位 ${money(hit.median)}），已降权"

    private fun referenceOnlyReason(referenceEvidence: PriceEvidence?): String =
        referenceEvidence?.price?.let { "只有历史最低价 ${money(it)}（参考值，非现价），缺少现价证据" }
            ?: "只有历史/参考值，缺少现价证据"

    private fun singleSourceReason(evidence: PriceEvidence, candidateTitle: String?): String =
        "仅 1 个来源给出可用报价（${name(evidence, candidateTitle)} ${money(evidence.price ?: 0.0)}），" +
            "无第二源交叉验证"

    private fun namesOf(cells: List<SourceCell>): String {
        val shown = cells.take(3).joinToString("、") { it.label }
        return if (cells.size > 3) "$shown 等" else shown
    }

    private fun money(value: Double): String = PriceFormatter.format(value)

    private fun percent(diff: Double): String = "${(diff * 100.0).roundToInt()}%"

    private fun hoursCeil(ageMs: Long): Long = (ageMs + HOUR_MS - 1) / HOUR_MS
}
