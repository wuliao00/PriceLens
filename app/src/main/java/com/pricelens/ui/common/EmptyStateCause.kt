package com.pricelens.ui.common

/**
 * 列表页"一条可展示的内容都没有"时的**成因**（F4，2026-09-29）。
 *
 * 修复前这三种结局被三个页面渲染成同一句话：
 *  - 概览：「未匹配到与「关键词」直接相关的商品，试试更完整的型号名」
 *  - 社区/B站：「请先搜索商品」+「搜索后聚合…」
 * 于是断网或被反爬拦住时，App 断言了一个**没有发生过的原因**（用户关键词写错了 / 用户还没搜），
 * 真机 2026-09-29 的 `mate 80` 复现即是：全机无网络，徽标写「正常」、文案写「未匹配到」。
 *
 * 判定做成纯函数（不碰 Compose / Android 框架）⇒ 可直接 JVM 单测，
 * 也让"空态文案必须携带它声称的成因"这条规则只有一份实现（审计 G4/G5）。
 */
enum class EmptyStateCause {
    /** 还没发起过搜索：此时说"未匹配到/未发现"都是假因果 */
    NOT_SEARCHED,

    /** 搜过了，但本轮没有任何一个数据源够得着 → 该说"取不到数据"，不该评价关键词 */
    UNREACHABLE,

    /** 至少一个源够着了、也确实没给出可用条目 → 该说"未匹配到/未发现" */
    NO_MATCH
}

/** 成因判定入口（概览 / 社区 / B站 共用同一规则） */
object EmptyStateCauseOf {

    /**
     * [sources] 传本轮被查询过的源状态（爆料/当当、优惠券、B站、识货、历史价…）。
     * 只有当**至少一个是 [AsyncValue.Success]** 时才允许把空结果归因到关键词上：
     * 仓储层已保证"空表 = 够着了且 0 条"、"没够着 = Error"（见 `PriceRepository` 的 F4 说明）。
     */
    fun of(searched: Boolean, sources: List<AsyncValue<*>>): EmptyStateCause {
        if (!searched) return EmptyStateCause.NOT_SEARCHED
        if (sources.any { it is AsyncValue.Success }) return EmptyStateCause.NO_MATCH
        if (sources.any { it is AsyncValue.Error }) return EmptyStateCause.UNREACHABLE
        // 查过了却一个源都没发起（全 Idle）：既不能宣称"没匹配上"，也不能编造"不可达"
        return EmptyStateCause.NOT_SEARCHED
    }
}
