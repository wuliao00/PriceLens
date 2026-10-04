package com.pricelens.coupon

import com.pricelens.accessibility.NodeSnapshot
import com.pricelens.accessibility.PriceNodeMatcher
import com.pricelens.accessibility.extractItemId
import com.pricelens.coupon.adapters.NodeAdapter
import com.pricelens.coupon.adapters.PostAdapter
import com.pricelens.coupon.adapters.TextAdapter
import com.pricelens.coupon.ai.FallbackExtractor
import com.pricelens.coupon.model.ExtractSource
import com.pricelens.coupon.model.Extraction
import com.pricelens.coupon.normalize.Links
import com.pricelens.coupon.rules.CouponTemplates
import java.time.LocalDate

/**
 * 找券理解层的唯一门面：三个入口 → 同一个 [Extraction]。
 *
 * 三个方法**只做参数准备**（取句子、判平台、取商品 ID），槽位判定全在
 * [CouponPipeline] —— 这是"统一流水线"的结构保证：想给某个入口开后门都找不到地方。
 * 判别证据见 `CouponExtractorTest."三入口同一句券文案抽出同一张券"`。
 *
 * 置信公式（任务书口径）：`模板置信 × 来源可靠度 × 时效衰减`，clamp 到 0..1。
 * **低置信不丢弃**：分档交给 [Tiers]，由上层按三档展示（丢弃 = "无核验直出"的另一种形态）。
 *
 * 本版不上任何模型：[com.pricelens.coupon.model.ExtractorKind] 恒为 RULE。
 */
object CouponExtractor {

    /** 进程内共享的模板库（命中计数聚在这里；远端规则包到位后由 `CouponTemplates.from(json)` 替换） */
    val templates: CouponTemplates = CouponTemplates.BUILTIN

    /** 剪贴板分享文案 */
    fun fromClipboard(text: String, fallback: List<FallbackExtractor> = emptyList()): Extraction {
        val clauses = TextAdapter.normalize(text)
        val joined = clauses.joinToString(" ") { it.text }
        return CouponPipeline.extract(
            clauses = clauses,
            source = ExtractSource.CLIPBOARD,
            platform = Links.platformHint(joined),
            itemRef = PriceNodeMatcher.findJdSku(joined),
            templates = templates,
            fallback = fallback
        )
    }

    /** 社区帖。[ageDays] 是发帖距今天数（调用方知道就传，>30 天按历史信息衰减） */
    fun fromPost(post: String, ageDays: Long, fallback: List<FallbackExtractor> = emptyList()): Extraction {
        val clauses = PostAdapter.normalize(post, LocalDate.now().toEpochDay())
        val joined = clauses.joinToString(" ") { it.text }
        return CouponPipeline.extract(
            clauses = clauses,
            source = ExtractSource.COMMUNITY,
            platform = Links.platformHint(joined),
            itemRef = PriceNodeMatcher.findJdSku(joined),
            templates = templates,
            ageDays = ageDays,
            fallback = fallback
        )
    }

    /** 商品页无障碍节点树 */
    fun fromPage(root: NodeSnapshot, fallback: List<FallbackExtractor> = emptyList()): Extraction {
        val clauses = NodeAdapter.clauses(root)
        return CouponPipeline.extract(
            clauses = clauses,
            source = ExtractSource.PAGE_NODE,
            platform = NodeAdapter.platform(root),
            itemRef = extractItemId(root),
            templates = templates,
            fallback = fallback
        )
    }
}

/** 置信档位：高（能直接展示）/ 中（要带"待核验"字样）/ 低（只进"可能的券"列表） */
enum class Tier {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * 三档划分（阈值按任务书：≥0.85 与 ≥0.6）。
 *
 * 边界是**闭区间下界**：0.85 归 HIGH、0.60 归 MEDIUM。写成 `>=` 而不是 `>` 是刻意的 ——
 * 置信本身是模板置信 × 可靠度算出来的离散值，恰好落在 0.85 是常态（0.9×0.95≈0.855），
 * 用 `>` 会把这类正常命中降一档，症状是"明明很有把握却标成中档"。
 */
object Tiers {

    const val HIGH_FROM = 0.85

    const val MEDIUM_FROM = 0.6

    fun of(confidence: Double): Tier = when {
        confidence >= HIGH_FROM -> Tier.HIGH
        confidence >= MEDIUM_FROM -> Tier.MEDIUM
        else -> Tier.LOW
    }
}
