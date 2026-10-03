package com.pricelens.coupon.model

/**
 * 找券槽位的**唯一真相**（V1 理解层，纯数据、不 import android、不碰网络）。
 *
 * 为什么先有这份枚举再谈抽取：用户抱怨"找券不准确"，三个入口（剪贴板 / 社区帖 / 商品页节点）
 * **都**错，而且错的是同一类 —— 说明病根不在某个适配器，而在没有槽位定义：
 *  1. 金额槽位混淆：券面额 / 用券门槛 / 到手价 / 标价 / 降幅 五种数字混成一锅
 *     （`本单可减1500` 到底是券还是降幅？没有槽位定义时规则和模型都只能猜）；
 *  2. 状态词失明：`已领 / 已抢完 / 过期 / 会员专享 / 限地区` 被忽略，抽出来的券全是"假设能领"；
 *  3. 多券混并：一段文案里店铺券 + 品类券 + 平台券被揉成一张；
 *  4. 无核验直出：本版只把 state / confidence **留出来**，核验由后续任务接。
 *
 * 所以这里最重要的不是字段多全，而是 [Extraction.price] 与 [Extraction.coupons]
 * **分属两个类型**：金额槽位物理分离后，"到手价"再也不可能被当"券面额"展示。
 *
 * 本版**不上任何模型**：[ExtractorKind] 只会产出 RULE，NER/LLM 是留给后续任务的形状。
 */

/** 抽取入口（三入口共用同一套槽位与规则，这是"统一流水线"的前提） */
enum class ExtractSource {
    /** 剪贴板分享文案 */
    CLIPBOARD,

    /** 社区帖（爆料 / 求助 / 闲聊） */
    COMMUNITY,

    /** 商品页无障碍节点 */
    PAGE_NODE
}

/** 槽位由谁产出：V1 只有 [RULE]，其余两值为后续的模型接入预留（不是"已实现"） */
enum class ExtractorKind {
    RULE,

    /** 预留：端侧 NER */
    NER,

    /** 预留：远端大模型 */
    LLM
}

/**
 * 一个数字在文案里**扮演什么角色**（治"金额槽位混淆"的槽位定义）。
 *
 * 判定只看上下文词，判不出返回 null（见 `slots/Roles.kt` 的 [AmountRole] 伴生扩展）：
 * **宁缺毋滥**，绝不默认成 [DISCOUNT]——默认成券面额就是把 5,499 的标价展示成"可减 5499"。
 */
enum class AmountRole {
    /** 券面额 / 可减金额：`减` `立减` `券面` `无门槛` */
    DISCOUNT,

    /** 用券门槛：`满` `满X可用` `门槛` */
    THRESHOLD,

    /** 到手价：`到手` `券后` `领后` `实付` */
    FINAL,

    /** 标价 / 划线价：`原价` `划线` `日常价` `标价` */
    LIST,

    /** 降幅（不是券！）：`降` `跌` `便宜` `比原价` */
    DROP;

    /**
     * 空伴生对象：`AmountRole.of(clause, matchedNumberIndex)` 以**伴生扩展函数**落在
     * `slots/Roles.kt`，本文件保持纯数据（约定：model 不含判定逻辑，判据与词表都在 slots）。
     */
    companion object
}

/** 券的适用范围（多券混并的第二道闸：范围不同的券不该并进一张） */
enum class CouponScope {
    ITEM,
    SHOP,
    CATEGORY,
    PLATFORM,
    MEMBER,
    UNKNOWN
}

/**
 * 券的真实状态（治"状态词失明"）。
 *
 * 优先级（[com.pricelens.coupon.slots.StateWords.of] 按此顺序取第一个命中）：
 * 售罄/过期 > 已领 > 会员/地区门槛 > 可领 —— 弱信号（`立即领`）不许盖掉强信号（`已领`），
 * 否则 "已领完，立即领下一个" 会被展示成能领。
 */
enum class CouponState {
    CLAIMABLE,
    CLAIMED,
    EXPIRED,
    SOLD_OUT,
    MEMBER_ONLY,
    REGION_LIMITED,
    UNKNOWN
}

/**
 * 一张券的槽位。
 *
 * @param nodePath 从无障碍树 root 到该券节点的**子索引链**（只有 PAGE_NODE 入口填）：
 *   核验层要沿路径回点复探（券还在不在、按钮文字变没变），所以这条链必须随槽位一起产出。
 *   文本入口固定为空表 —— 用"只有节点入口非空"这个事实，可以反证三入口确实是同一条流水线。
 * @param sourceText 抽出这张券的**那一句原文**（规整之后、分句那一段本身）。
 *   两个下游离不开它：① 展示层要说"这句里读出来的"，用户才知道自己在否掉什么；
 *   ② 本地错例导出（一键"这条不对"）要写成与 `tools/golden/coupons.jsonl` 同格式的 JSONL，
 *     而那份格式的第一字段就是原文 —— 没有这个字段，导出的错例回灌不进评测集，
 *     整条"用户纠错 → golden → 回归门禁"的链子就断在这里。
 *   注意存的是**规整后**的文本（全角已转半角、零宽已删），与 golden 里逐字节可溯源的 raw 不同，
 *   导出时要连同入口与 nodePath 一起存，便于回查原树。
 * @param expiry `yyyy-MM-dd`，解析不出来就是 null（**不许猜年份**：真机文案只写"10月8日"时，
 *   补 2026 还是 2027 都是编造，展示层按"期限未知"处理）
 */
data class CouponSlot(
    val discount: Double?,
    val threshold: Double?,
    val scope: CouponScope,
    val state: CouponState,
    val expiry: String?,
    val code: String?,
    val url: String?,
    val sourceText: String,
    val nodePath: List<Int>
)

/**
 * 商品本身的价格槽位（与 [CouponSlot] **物理分离**，这是本版的核心）。
 *
 * 三个字段都允许 null：读不出就是读不出，绝不拿券面额凑数、也绝不拿标价充到手价。
 */
data class PriceSlots(
    val finalPrice: Double?,
    val listPrice: Double?,
    val drop: Double?
)

/**
 * 一次抽取的完整结果（三入口同一个类型 ⇒ 上层只写一份展示逻辑）。
 *
 * @param platform `jd|taobao|pdd|unknown`（字符串而非枚举：宿主集合由远端规则包决定，不在编译期钉死）
 * @param confidence 模板置信 × 来源可靠度 × 时效衰减，clamp 到 0..1；**低置信不丢弃**，
 *   由 [com.pricelens.coupon.Tiers] 分三档展示（丢弃 = 无核验直出的另一种表现形式）
 */
data class Extraction(
    val source: ExtractSource,
    val extractor: ExtractorKind,
    val platform: String,
    val itemRef: String?,
    val coupons: List<CouponSlot>,
    val price: PriceSlots,
    val stackNote: String?,
    val confidence: Double
)
