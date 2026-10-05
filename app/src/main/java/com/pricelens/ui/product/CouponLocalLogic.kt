package com.pricelens.ui.product

import com.pricelens.R
import com.pricelens.coupon.Tier
import com.pricelens.coupon.Tiers
import com.pricelens.coupon.model.CouponScope
import com.pricelens.coupon.model.CouponSlot
import com.pricelens.coupon.model.CouponState
import com.pricelens.coupon.model.Extraction
import com.pricelens.ui.theme.BadgeTone
import com.pricelens.util.PriceFormatter

/**
 * 详情页「本机识别券组」的纯展示模型（任务书 B2 交付一）。
 *
 * 这里刻意不放任何 Android 依赖，所以每条行为都能在 JVM 单测里直接断言
 * （见 `CouponLocalLogicTest`），Compose 侧只负责把资源 id 与槽位值画出来：
 *  - 三档措辞由 [Tiers.of] 决定（阈值 0.85 / 0.6，**闭区间下界**：0.85 归 HIGH，
 *    这是理解层刻意的选择，展示层原样透传，不许自己再调阈值）；
 *  - 每张券固定七格逐槽展示（面额 / 门槛 / 范围 / 状态 / 有效期 / 券码 / 链接），
 *    读不出的槽位单独标"未识别"（`unknown=true` 且 `args` 为空）——
 *    **绝不拿别的槽位的数凑**：门槛读不出时这一格不携带任何数字，测试用这条断言钉死；
 *  - 状态不是 UNKNOWN 就一定要显示（"已领完""限地区"这类状态失明正是用户报的病根之一）；
 *  - 每张券带自己的 [CouponSlot.sourceText]（这句里读出来的），用户没有它就无法判断识别错在哪。
 *
 * 本版**没有核验层**：措辞只用"置信/可直接看/点进去核/可能是券"这套口径，
 * 声称"核过/确认过"的词在 `strings_coupon.xml` 里是被 `CouponStringsGuardTest` 禁掉的。
 */

/**
 * 一个槽位的显示单元。
 *
 * 约定：`unknown=true` ⇔ [valueRes] 指向"未识别"且 [args] 为空。
 * Compose 侧规则：args 空 → `stringResource(valueRes)`，args 非空 → `stringResource(valueRes, *args)`。
 */
data class SlotCell(val labelRes: Int, val valueRes: Int, val args: List<String> = emptyList(), val unknown: Boolean = false)

/** 一张本机识别券的展示行（index 是它在 [Extraction.coupons] 里的下标，标记/导出都按它对齐） */
data class LocalCouponRow(val index: Int, val tier: Tier, val confidence: Double, val cells: List<SlotCell>, val sourceText: String)

/** 三档措辞（高=可直接看、中=要自己点进去核、低=只是"可能是券"），语义边界同步写在 strings_coupon.xml 的注释里 */
fun tierLabelRes(tier: Tier): Int = when (tier) {
    Tier.HIGH -> R.string.coupon_local_tier_high
    Tier.MEDIUM -> R.string.coupon_local_tier_medium
    Tier.LOW -> R.string.coupon_local_tier_low
}

/** 三档的视觉色调：高=利好、中=中性、低=风险提醒（与 [BadgeTone] 语义一致，测试钉三档互不相同） */
fun tierTone(tier: Tier): BadgeTone = when (tier) {
    Tier.HIGH -> BadgeTone.POSITIVE
    Tier.MEDIUM -> BadgeTone.NEUTRAL
    Tier.LOW -> BadgeTone.NEGATIVE
}

/** Extraction → 展示行。置信只算一次（整组同一次抽取共享 confidence），逐槽按固定七格顺序展开 */
fun localCouponRows(extraction: Extraction): List<LocalCouponRow> {
    val tier = Tiers.of(extraction.confidence)
    return extraction.coupons.mapIndexed { index, coupon ->
        val cells = slotCellsOf(coupon)
        LocalCouponRow(index = index, tier = tier, confidence = extraction.confidence, cells = cells, sourceText = coupon.sourceText)
    }
}

private fun slotCellsOf(coupon: CouponSlot): List<SlotCell> = listOf(
    amountCell(R.string.coupon_local_slot_discount, coupon.discount, isThreshold = false),
    amountCell(R.string.coupon_local_slot_threshold, coupon.threshold, isThreshold = true),
    enumCell(R.string.coupon_local_slot_scope, scopeLabelRes(coupon.scope), coupon.scope == CouponScope.UNKNOWN),
    enumCell(R.string.coupon_local_slot_state, stateLabelRes(coupon.state), coupon.state == CouponState.UNKNOWN),
    textCell(R.string.coupon_local_slot_expiry, coupon.expiry),
    textCell(R.string.coupon_local_slot_code, coupon.code),
    textCell(R.string.coupon_local_slot_link, coupon.url)
)

/**
 * 面额 / 门槛格。门槛有特殊口径：`0.0` 是文案**显式写了**"无门槛"（与 null=没写 是两件事，
 * 理解层与 eval 判据都分开记账），其余读不出一律 [R.string.coupon_local_unrecognized]。
 */
private fun amountCell(labelRes: Int, value: Double?, isThreshold: Boolean): SlotCell {
    if (value == null) {
        return SlotCell(labelRes, R.string.coupon_local_unrecognized, emptyList(), unknown = true)
    }
    if (isThreshold && value == 0.0) {
        return SlotCell(labelRes, R.string.coupon_no_threshold)
    }
    val valueRes = if (isThreshold) R.string.coupon_local_value_threshold else R.string.coupon_local_value_amount
    return SlotCell(labelRes, valueRes, listOf(PriceFormatter.formatRaw(value)))
}

private fun enumCell(labelRes: Int, valueRes: Int?, notReadable: Boolean): SlotCell {
    if (notReadable || valueRes == null) {
        return SlotCell(labelRes, R.string.coupon_local_unrecognized, emptyList(), unknown = true)
    }
    return SlotCell(labelRes, valueRes)
}

/** 字符串槽（有效期/券码/链接）：null 或空白都是"读不出"，不许显示成空串糊过去 */
private fun textCell(labelRes: Int, value: String?): SlotCell {
    if (value == null || value.isBlank()) {
        return SlotCell(labelRes, R.string.coupon_local_unrecognized, emptyList(), unknown = true)
    }
    return SlotCell(labelRes, R.string.coupon_local_value_text, listOf(value))
}

private fun scopeLabelRes(scope: CouponScope): Int? = when (scope) {
    CouponScope.ITEM -> R.string.coupon_local_scope_item
    CouponScope.SHOP -> R.string.coupon_local_scope_shop
    CouponScope.CATEGORY -> R.string.coupon_local_scope_category
    CouponScope.PLATFORM -> R.string.coupon_local_scope_platform
    CouponScope.MEMBER -> R.string.coupon_local_scope_member
    CouponScope.UNKNOWN -> null
}

private fun stateLabelRes(state: CouponState): Int? = when (state) {
    CouponState.CLAIMABLE -> R.string.coupon_local_state_claimable
    CouponState.CLAIMED -> R.string.coupon_local_state_claimed
    CouponState.EXPIRED -> R.string.coupon_local_state_expired
    CouponState.SOLD_OUT -> R.string.coupon_local_state_sold_out
    CouponState.MEMBER_ONLY -> R.string.coupon_local_state_member_only
    CouponState.REGION_LIMITED -> R.string.coupon_local_state_region_limited
    CouponState.UNKNOWN -> null
}

/**
 * 找券段"这一屏该出现哪几块"的判据（纯函数，Compose 侧只照着渲染）。
 *
 * 病根（2026-10-05 真机，从京东商详页回到 App 时抓到，截图 `61-local-group-missing.png`）：
 * 远端券列表为空**且**本机这次也没抽出券时，旧代码在 `when` 里直接 `return`，
 * 于是本机组的标题与空态**整段消失** —— 屏幕上只剩一张"请先搜索商品"的大空卡，
 * 用户分不清"这功能没做 / 我没开 / 这次没识别到"。那正是 2026-10-04 已经立过规矩的形状
 * （"标题与空态不许一起藏起来"），只是这次是**远端那一侧**把它挡住了。
 *
 * 反面一起修：本机抽出券、远端为空时，旧代码**不**显示远端空态，用户不知道远端那条路查过没有。
 * 合起来就一句话：**远端为空要明说，本机组永远在**。
 */
object CouponSectionShape {

    /**
     * @param loading 远端在途（骨架屏期间不评价"空不空"）
     * @param failed 远端请求失败 —— 错误块自己会说话，**不许**再叠一条"没有券"
     *   （那是把"没连上"说成"查过了确实没有"，同一个病根的另一种说法）
     * @param remoteCouponCount 远端返回的券条数
     */
    fun of(loading: Boolean, failed: Boolean, remoteCouponCount: Int): Blocks = Blocks(
        shimmer = loading,
        remoteEmpty = !loading && !failed && remoteCouponCount == 0,
        localGroup = true
    )

    /**
     * 三块各自要不要渲染。
     *
     * [localGroup] 恒真是刻意的：它不是一个"条件"，而是这条判据的**结论**
     * （本机组读的是页面树/剪贴板/关键词，与网络无关，没有任何一种远端状态该把它藏掉）。
     * 写成字段而不是删掉，是为了让"哪天有人加 return 把它藏了"变成一次测试失败而不是一次静默。
     */
    data class Blocks(val shimmer: Boolean, val remoteEmpty: Boolean, val localGroup: Boolean)
}
