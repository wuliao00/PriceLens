package com.pricelens.coupon.ai

/**
 * 兜底层的输入入口种类。**刻意不与找券流水线的 `ExtractSource` 同名**（那个类型由
 * `com.pricelens.coupon.model` 定义，属另一个任务书 A 的产物），这里的语义差别是：
 * `ExtractSource` 说"这条 Extraction 从哪来"，[ExtractionInputKind] 说"兜底器愿不愿意处理这一类输入"。
 * 接线时由控制面做一次 `when` 映射，不做类型继承，避免两条历史互相绑死。
 */
enum class ExtractionInputKind {
    CLIPBOARD,
    COMMUNITY_POST,
    PAGE_CLAUSES,
}

/**
 * 低置信兜底接缝：V1 只有规则，这一层留给 NER/LLM。实现必须能在没有模型时安全缺席。
 *
 * 契约（三条，接线方与实现方都要认）：
 *  1. [supports] 为 false 的输入**不得**被调用 [extract]；调用方先问再调，实现方不重复判类。
 *  2. [extract] 返回 null 的含义是"我没参与"——上层**原样**沿用规则结果，不是"抽取失败"，
 *     因此实现不许用 null 来表达错误（错误要么抛，要么在 FallbackDraft 的冲突标记里体现）。
 *  3. 实现不许有"必须装模型才能存在"的前置条件：没下载权重时这个对象仍可被构造、
 *     [supports] 可返回 true、[extract] 走确定性路径或返回 null。这是本版只交付接缝与
 *     [ConsensusFallbackExtractor] 的原因（模型实现留给能验机的那一版）。
 *
 * 入参是 `List<String>` 而不是流水线的 `Clause`：分句与角色判定归任务书 A，
 * 兜底层只吃"已经切成句的原文"，这样模型实现换语言/换运行时都不用改签名。
 */
interface FallbackExtractor {

    /** 这一类输入我处理得了吗（模型没装 / 只训过剪贴板文案 → 返回 false） */
    fun supports(input: ExtractionInputKind): Boolean

    /** 对同一句文案的若干分句做兜底；null = 我没参与，上层继续用规则结果 */
    fun extract(clauses: List<String>): FallbackDraft?
}
