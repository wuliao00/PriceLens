package com.pricelens.domain

/**
 * 「这一次商品上下文是哪一次识别带来的」（#73 的 `detectedSignature`）什么时候该作废。
 *
 * 为什么单独抽出来：这条判据原来只写在 `SearchViewModel.search()` 里的一句无条件清零，
 * 于是**任何一次重新搜索**都算"换商品"——包括从概览页点「看详情」进详情页时那一次
 * （`MainActivity` 的详情页入口会带着同一句关键词再搜一轮）。后果在真机上才看得出来：
 * 浮窗 CTA 好不容易把身份带进 App（`focus_signature`），用户点「看详情」的那一瞬间
 * 身份又被自己发起的搜索抹掉，找券段的「页面」芯片于是**结构上永远不可能出现**——
 * 2026-10-06 vivo V2156A 那一轮"链路整条走通、屏上只写『输入：关键词』"量的就是这件事，
 * 当时我把它记成了"正例没拿到"。
 *
 * 判据本身很简单，但它是**方向性**的：只有关键词真的换了，才算离开那一页。
 * 判不准时宁可作废（清掉身份 = 少一路输入，不会把 A 页的券挂到 B 商品上）。
 */
object DetectionContext {

    /**
     * 一次搜索之后，页面树身份还作不作数。
     *
     * @param previousKeyword 搜索前界面上已经是哪一句（空串 = 还没有过关键词）
     * @param newKeyword 这一次要搜的那一句（两侧空白不算差异，与 `search()` 的 trim 同口径）
     * @param matchedTitle 这一轮结果里已经站住脚的那件商品的标题。
     *   概览页的「看详情」传的是**候选标题**而不是关键词（`OverviewScreen` 的
     *   `onOpenProduct(product.title)`），所以"从这一轮结果进详情页"必须算同一件商品 ——
     *   否则身份在进门那一刻又被抹掉，正例照样出不来。
     */
    fun survivesSearch(previousKeyword: String, newKeyword: String, matchedTitle: String?): Boolean {
        val next = newKeyword.trim()
        return next == previousKeyword.trim() || (matchedTitle != null && next == matchedTitle.trim())
    }
}
