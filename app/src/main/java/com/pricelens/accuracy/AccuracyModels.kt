package com.pricelens.accuracy

/**
 * 「信息源准确性」契约模型（2026-11 大更新）。
 *
 * 这组类型的唯一出口是 [PriceAccuracyEngine.evaluate]：UI 只消费不可变的 [AccuracySnapshot]，
 * 不自己再算置信度、口径或冲突（避免界面上出现"两套口径各说各话"）。
 *
 * 数据流：
 * ```
 * SearchViewModel（本轮真实搜索结果 + 来源健康板 + 反馈仓）
 *   └─ AccuracyInput ──► PriceAccuracyEngine.evaluate ──► AccuracySnapshot ──► UI
 * ```
 *
 * 约定：所有价格字段只接受**真实拿到**的数字；null / 0 / 负数 / NaN / Infinity 一律视为
 * "没拿到"，引擎不会补 0、补旧价，也不按"看起来差不多"猜一个值。
 */

/**
 * 置信档位。
 *
 *  - [HIGH]   多源（≥2 条新鲜现价）互相印证，且没有硬冲突/离群；
 *  - [MEDIUM] 两源一致但时鲜度或相关性不足、单源新鲜、或存在可解释的口径差异；
 *  - [LOW]    只有历史/参考值，或来源之间互不一致，或数据过期；
 *  - [UNKNOWN] 本轮没有任何可用价格。
 */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW, UNKNOWN }

/**
 * 价格口径（"这个数字是哪一种价"）。
 *
 * 同一商品的多口径**不得混为一谈**：页面价是用户当前页面看到的价格，到手价是减券后的价格，
 * 参考值是历史最低价这类非现价。口径不同造成的差额是信息（有券可用），不是数据错误。
 */
enum class PriceBasis {
    /** 页面价：无障碍从用户当前购物 App 页面读到的实时价（最高信任） */
    PAGE,

    /** 券后价：候选价减券面额（>0 才算），券尚未叠加/未确认可用时单列 */
    COUPON_AFTER,

    /** 到手价：候选价减本轮可用券后的实付价 */
    NET,

    /** 参考值：历史最低价等非现价，只作参考，不参与现价一致性判定 */
    REFERENCE,

    /** 口径未知：候选价/历史当前价等没有明确口径声明的现价 */
    UNKNOWN
}

/**
 * 单个数据源的归一状态（来源健康板）。
 *
 * 与 `ui/components/SourceStatusRow.kt` 的展示口径同源，但这里只表达"能不能信"：
 *  - [OK]       本轮够着了该源（空结果也算够着）
 *  - [DEGRADED] 网络失败/源处于降级冷却期，本轮没拿到
 *  - [BLOCKED]  被反爬拦截（403/412、挑战页、熔断）
 *  - [STALE]    拿到了，但来自降级缓存（旧数据）
 *  - [UNKNOWN]  本轮未查询 / 仍在加载 / 时刻不可知
 */
enum class SourceState { OK, DEGRADED, BLOCKED, STALE, UNKNOWN }

/**
 * 来源健康板的一格。
 *
 * @param sourceId 稳定 ID（与 [PriceEvidence.sourceId]、ApiClient 域名诊断键保持一一对应）
 * @param label 人读来源名（如"什么值得买"），引擎负责去空白/空值回退为 [sourceId]
 * @param state 归一状态，见 [SourceState]
 * @param note 可选的一句话说明（反爬原因 / 旧数据 / 未查询）；空串会被归一成 null
 */
data class SourceCell(
    val sourceId: String,
    val label: String,
    val state: SourceState,
    val note: String? = null
)

/**
 * 一条价格证据：引擎做共识/离群/时鲜判定的最小单元。
 *
 * @param sourceId 来源稳定 ID：页面 `page`、候选 `candidate`、历史 `history`、
 *                 到手 `net`、券后 `coupon-after`，或远端源自己的 ID（如 `shihuo`）
 * @param label 人读来源名或口径名（"本机京东 App 登录账号""识货""历史当前价"）
 * @param price 价格；null 表示该证据没有可用价格（引擎只对非空价格做计算）
 * @param basis 口径，见 [PriceBasis]；越界比较会按口径区别对待
 * @param ageMs 数据年龄（毫秒）；null = **时刻未知**，不得当成新鲜数据
 * @param stale 是否已过期（>24h、历史降级缓存或被调用方标记）
 * @param relevance 与当前关键词/商品的相关度 0..1；<0.6 的证据会被剔除
 */
data class PriceEvidence(
    val sourceId: String,
    val label: String,
    val price: Double?,
    val basis: PriceBasis,
    val ageMs: Long?,
    val stale: Boolean,
    val relevance: Double
)

/**
 * 一条需要用户知道的口径差异/冲突。
 *
 * @param label 分类标签（"价格冲突""口径差异""券口径""到手价"）
 * @param detail 中文细节：必须带具体数字与来源名，例如
 *               "页面价 ¥5,999 与 商品候选 ¥5,699 相差 5%：可能是不同规格/券后，建议核对"
 */
data class AccuracyConflict(val label: String, val detail: String)

/**
 * 一轮搜索的准确性快照（UI 唯一数据源，不可变）。
 *
 * @param level 置信档位；UI 用它选徽标颜色/文案
 * @param score 0..100 可解释评分：一致性 40 + 相关性 20 + 时鲜 20 + 来源多样 10 + 无冲突 10
 * @param headline ≤14 字的人读结论，如"2 源一致 · 可信""单源数据 · 建议页面核价"
 * @param reasons ≤3 条中文原因，带具体数字与来源名
 * @param basis 主口径：页面价 > 到手价 > 券后价 > 候选价 > 历史当前价 > 参考值
 * @param basisLabel 主口径的人读名（"页面价""到手价""候选价"…）
 * @param evidence 证据列表（已剔除 0/负价/NaN/低相关证据），UI 展开面板逐条展示
 * @param conflicts 需要提示的口径差异/冲突（≤3 条）
 * @param sourceBoard 归一后的来源健康板（去重、去空白）
 * @param generatedAtMs 本快照生成时刻（毫秒）
 */
data class AccuracySnapshot(
    val level: ConfidenceLevel,
    val score: Int,
    val headline: String,
    val reasons: List<String>,
    val basis: PriceBasis,
    val basisLabel: String,
    val evidence: List<PriceEvidence>,
    val conflicts: List<AccuracyConflict>,
    val sourceBoard: List<SourceCell>,
    val generatedAtMs: Long
)

/**
 * 额外远端现价（识货/值得买等本轮列表里与关键词相关的同款报价）。
 *
 * 由调用方（SearchViewModel）从**已经加载到内存**的列表构造：只放过度
 * `QueryRelevance.isRelevant` 且 price>0 的条目，不得臆造价格、不得把同一条候选抄成第二源。
 *
 * @param sourceId 来源稳定 ID（`shihuo` / `smzdm` …）
 * @param label 人读来源名
 * @param price 现价（>0）
 * @param relevance 与关键词的相关度 0..1（调用方已知该条已过相关性过滤时给 1.0）
 */
data class RemotePrice(
    val sourceId: String,
    val label: String,
    val price: Double,
    val relevance: Double
)

/**
 * 准确性引擎的输入（由 SearchViewModel 在并行任务全部 join 后组装一次）。
 *
 * 字段语义（引擎按此解释，不越界猜）：
 *  - 页面价 [pagePrice]/[pageSourceLabel]：无障碍实时价 + 来源名（如"本机京东 App 登录账号"）；
 *  - 候选价 [candidatePrice]/[candidateTitle]/[candidateRelevance]：本轮商品候选及其相关度；
 *  - 历史 [historyCurrent]/[historyLowest]/[historyStale]：历史当前价、历史最低价（参考值）、
 *    历史通道是否命中降级缓存；
 *  - 券 [couponTotal]：本轮对候选价可用的券面额（券后价 = 候选价 − 券面额，>0 才算）；
 *  - [netPrice]：到手价（候选价减券后的实付价）；
 *  - [extraRemotePrices]：额外远端现价（只放真实价格）；
 *  - [sourceCells]：来源健康板（每源归一状态）；
 *  - [nowMs]：生成本轮快照的时刻（注入以便单测；不在引擎内部读时钟）。
 */
data class AccuracyInput(
    val keyword: String,
    val pagePrice: Double?,
    val pageSourceLabel: String?,
    val candidatePrice: Double?,
    val candidateTitle: String?,
    val candidateRelevance: Double?,
    val historyCurrent: Double?,
    val historyLowest: Double?,
    val historyStale: Boolean,
    val couponTotal: Double?,
    val netPrice: Double?,
    val extraRemotePrices: List<RemotePrice>,
    val sourceCells: List<SourceCell>,
    val nowMs: Long
)

/**
 * 用户纠错类型（「这条不对」）。
 *
 * 反馈只写本机 JSONL（[AccuracyFeedbackStore]），不上传、不参与在线模型。
 */
enum class AccuracyIssueKind {
    /** 价格数字不对 */
    WRONG_PRICE,

    /** 匹配到的不是同一件商品 */
    WRONG_PRODUCT,

    /** 数据过时 */
    STALE,

    /** 口径不对（例如把券后价当成页面价） */
    WRONG_BASIS
}
