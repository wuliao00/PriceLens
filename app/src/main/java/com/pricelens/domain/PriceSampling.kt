package com.pricelens.domain

/**
 * 多来源价格的合成规则。全部纯函数：无 Context、无 Room、无网络，可直接单测
 * （回归测试见 `app/src/test/java/com/pricelens/domain/WatchPriceSourceTest.kt`）。
 *
 * 本文件是 F1（星罗"券后历史低价"被当成现价，2026-09-29 修）的收口点：
 * 取榜单的哪个字段、这个值能不能触发降价通知、能不能进历史曲线，三件事都写在这里，
 * 不再散落在 [com.pricelens.worker.WatchCheckRunner] 与 PriceRepository 里各写一遍。
 *
 * 修掉的缺陷（5c11a46 及之前）：
 *  - `WatchCheckRunner.kt:173` `extra[sku] = deal.couponPrice` —— 拿"券后历史低价"当现价，
 *    价格没降也会发「当前 ¥X ≤ 目标 ¥Y」；真正该用的 `goods_list_money`（在售价）解析了却零消费。
 *  - `PriceRepository.kt:116-119` `points += PricePoint(today, deal.couponPrice)` —— 同一个历史低价
 *    被写成"今天的曲线点"落进历史表，于是曲线的最低点恒 ≤ 它，`judgePrice` 几乎恒判「≈ 历史低价 / 可入」。
 */
object PriceSampling {

    /**
     * 星罗榜单条目 → 价格样本。
     *
     * ① 优先 `goods_list_money`（在售价）：它回答的是"这个 SKU 现在标价多少"；
     *  ② 只有在售价拿不到（缺失/≤0）时才允许退到 `real_money`（券后**历史**低价），
     *     并如实标成 [PriceSource.LINKSTARS_HISTORY_LOW] —— 那是历史位置，不是现价，
     *     [curveWorthy] 与 `WatchTargetPolicy.skipReasonFor` 都会据此把它挡在通知与曲线之外。
     *
     * @return null = 两个字段都没有可用值（本轮这条路没给价）
     */
    fun linkstarsSample(listPrice: Double, couponPrice: Double): PriceSample? = when {
        listPrice > 0 -> PriceSample(listPrice, PriceSource.LINKSTARS_LIST)
        couponPrice > 0 -> PriceSample(couponPrice, PriceSource.LINKSTARS_HISTORY_LOW)
        else -> null
    }

    /**
     * 本轮京东现价：p.3.cn 在售价优先，缺价的 SKU 才由星罗样本补上。
     *
     * @param p3cnFailed true = p.3.cn 整轮失败（旧代码里的 `base == null`）
     * @return null = 两条通道都没有价且 p.3.cn 失败 → 平台级失败，交给 Worker 退避重试；
     *         空 Map = 通道在但一个价都没拿到
     */
    fun composeJd(p3cn: Map<String, PriceSample>, references: Map<String, PriceSample>, p3cnFailed: Boolean): Map<String, PriceSample>? {
        val merged = LinkedHashMap<String, PriceSample>()
        for ((sku, sample) in p3cn) if (sample.hasPrice) merged[sku] = sample
        for ((sku, sample) in references) if (merged[sku]?.hasPrice != true) merged[sku] = sample
        return if (p3cnFailed && merged.isEmpty()) null else merged
    }

    /**
     * 有资格作为"今日的曲线采样点"写进历史价格表的样本。
     *
     * 只有真现价够格：[PriceSource.LINKSTARS_HISTORY_LOW] 是榜单里的历史低价，
     * 把它写成今天的点等于自己造历史（F1 的后果链就是从这一步来的）。
     * 参考值仍然留在本轮结果里，由 UI 作为"参考低价"如实展示来源与时间。
     */
    fun curveWorthy(sample: PriceSample?): PriceSample? = sample?.takeIf { it.isLive }
}
