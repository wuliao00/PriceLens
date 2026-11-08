package com.pricelens.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WatchTargetPolicy] 盯价链路纯校验基线（A3：修"跟踪错误"）。
 *
 * 覆盖用户反馈链路上的每个真实缺陷：
 *  1. 空 SKU / 非数字 SKU / 过短 SKU 不得入库（旧实现会拼出主键恒为 "jd:" 的目标，
 *     第二个目标 REPLACE 掉第一个 → 标题与目标价被偷换）。
 *  2. 淘宝/拼多多/当当/识货候选绝不再被写成 platform="jd"；2026-11 起这些平台改为
 *     KEYWORD_SEARCH 通道（可盯），但平台/主键必须来自链接解析，且认不出平台的链接仍拒绝。
 *  3. 同 ID 不同标题必须走确认，不静默覆盖。
 *  4. p.3.cn 不可达（查价返回空表）时的轮次记账：全部计为"取不到现价"，
 *     本轮判为 stalled（旧实现既不计 checked 也不计失败 → 永远静默）。
 *  5. 目标价预填只认同 SKU 的历史曲线。
 *  6. 2026-11：关键词同款样本必须过严格相关性 + 价格比值才配当"本轮现价"（否则宁可无价）。
 */
class WatchTargetPolicyTest {

    private val sku = "100012043978"

    // ---------- 1. 入口判定：能不能盯 ----------

    @Test
    fun `valid jd sku becomes watchable with prefixed primary key`() {
        val decision = WatchTargetPolicy.decide(skuId = sku, url = "https://item.jd.com/$sku.html")
        assertTrue(decision is WatchDecision.Watchable)
        decision as WatchDecision.Watchable
        assertEquals(WatchTargetPolicy.PLATFORM_JD, decision.platform)
        assertEquals(sku, decision.externalId)
        assertEquals("jd:$sku", decision.productId)
        assertTrue(WatchTargetPolicy.isValidTargetId(decision.productId))
    }

    @Test
    fun `empty sku is rejected instead of producing the shared jd colon key`() {
        // 旧行为：productId = "jd:" → 所有坏目标共用主键，互相覆盖
        val decision = WatchTargetPolicy.decide(skuId = "", url = "")
        assertTrue(decision is WatchDecision.Rejected)
        decision as WatchDecision.Rejected
        assertEquals(RejectReason.MISSING_ID, decision.reason)
        assertFalse(WatchTargetPolicy.isValidTargetId("jd:"))
        assertFalse(WatchTargetPolicy.isValidTargetId("jd"))
        assertFalse(WatchTargetPolicy.isValidTargetId("100012043978"))
    }

    @Test
    fun `blank-only sku is treated as missing id`() {
        val decision = WatchTargetPolicy.decide(skuId = "   ", url = null)
        decision as WatchDecision.Rejected
        assertEquals(RejectReason.MISSING_ID, decision.reason)
    }

    @Test
    fun `non numeric and too short skus are rejected as malformed`() {
        for (bad in listOf("abc123456", "12345", "100012043978X", "1.00012E11")) {
            val decision = WatchTargetPolicy.decide(skuId = bad, url = null)
            decision as WatchDecision.Rejected
            assertEquals("SKU [$bad] 应判为 MALFORMED_ID", RejectReason.MALFORMED_ID, decision.reason)
            assertEquals(WatchTargetPolicy.PLATFORM_JD, decision.platform)
            assertEquals(bad.trim(), decision.displayId)
        }
    }

    @Test
    fun `six digit sku is the accepted lower bound`() {
        assertTrue(WatchTargetPolicy.decide("100000", null) is WatchDecision.Watchable)
        assertTrue(WatchTargetPolicy.decide("99999", null) is WatchDecision.Rejected)
    }

    @Test
    fun `J prefixed sku from the price api is normalized before validation`() {
        val decision = WatchTargetPolicy.decide(skuId = "J_$sku", url = null)
        decision as WatchDecision.Watchable
        assertEquals(sku, decision.externalId)
    }

    // ---------- 2. 非京东平台：改为关键词搜索通道（KEYWORD_SEARCH），但仍然绝不冒充京东 ----------

    @Test
    fun `non jd candidates are watchable through the keyword channel with their own platform`() {
        val cases = mapOf(
            "https://item.taobao.com/item.htm?id=654321098765" to WatchTargetPolicy.PLATFORM_TAOBAO,
            "https://detail.tmall.com/item.htm?id=654321098765" to WatchTargetPolicy.PLATFORM_TAOBAO,
            "https://mobile.yangkeduo.com/goods.html?goods_id=112233445566" to WatchTargetPolicy.PLATFORM_PDD,
            "https://product.dangdang.com/29704890.html" to WatchTargetPolicy.PLATFORM_DANGDANG,
            "https://m.shihuo.cn/page/goodsDetail?goodsId=1122334455" to WatchTargetPolicy.PLATFORM_SHIHUO
        )
        for ((url, platform) in cases) {
            val decision = WatchTargetPolicy.decide(skuId = null, url = url)
            decision as WatchDecision.Watchable
            assertEquals("链接 $url 应识别为 $platform", platform, decision.platform)
            assertNotEquals("绝不把非京东候选写成京东", WatchTargetPolicy.PLATFORM_JD, decision.platform)
            assertEquals(
                "非京东平台走关键词自动搜索通道",
                WatchChannel.KEYWORD_SEARCH,
                WatchTargetPolicy.channelOf(decision.platform)
            )
            assertTrue("非京东平台现在可盯", WatchTargetPolicy.isTrackablePlatform(decision.platform))
            assertTrue("主键必须是 <platform>:<id>", decision.productId.startsWith("$platform:"))
            assertTrue("主键必须合法（防 REPLACE 静默覆盖）", WatchTargetPolicy.isValidTargetId(decision.productId))
        }
    }

    @Test
    fun `unknown platform still has no channel and is rejected`() {
        // 认不出平台的链接没有任何通道：不能被"关键词可盯"顺手放进来
        val decision = WatchTargetPolicy.decide(null, "https://example.com/goods/123456")
        decision as WatchDecision.Rejected
        assertEquals(RejectReason.MISSING_ID, decision.reason)
        assertEquals(WatchChannel.NONE, WatchTargetPolicy.channelOf(WatchTargetPolicy.PLATFORM_UNKNOWN))
        assertFalse(WatchTargetPolicy.isTrackablePlatform(WatchTargetPolicy.PLATFORM_UNKNOWN))
    }

    @Test
    fun `jd sku attached to a taobao link is refused as platform conflict`() {
        // 跨平台冒用的直接证据：SKU 说是京东、链接却是淘宝 → 旧实现会照样写成 jd 入库
        val decision = WatchTargetPolicy.decide(skuId = sku, url = "https://item.taobao.com/item.htm?id=654321098765")
        decision as WatchDecision.Rejected
        assertEquals(RejectReason.PLATFORM_CONFLICT, decision.reason)
        assertEquals(WatchTargetPolicy.PLATFORM_TAOBAO, decision.platform)
        assertEquals(sku, decision.displayId)
    }

    @Test
    fun `recognized external id is carried in the watchable decision for transparency`() {
        // 2026-11：当当链接现在可盯（KEYWORD_SEARCH 通道），商品号如实进主键
        val decision = WatchTargetPolicy.decide(null, "https://product.dangdang.com/29704890.html")
        decision as WatchDecision.Watchable
        assertEquals(WatchTargetPolicy.PLATFORM_DANGDANG, decision.platform)
        assertEquals("29704890", decision.externalId)
        assertEquals("dangdang:29704890", decision.productId)
    }

    @Test
    fun `smzdm post pointing at a jd product is watchable as jd`() {
        val decision = WatchTargetPolicy.decide(null, "https://item.jd.com/$sku.html?source=some")
        decision as WatchDecision.Watchable
        assertEquals(WatchTargetPolicy.PLATFORM_JD, decision.platform)
        assertEquals(sku, decision.externalId)
    }

    @Test
    fun `jd mobile site url yields the same sku as the desktop url`() {
        val decision = WatchTargetPolicy.decide(null, "https://item.m.jd.com/product/$sku.html")
        decision as WatchDecision.Watchable
        assertEquals(sku, decision.externalId)
    }

    @Test
    fun `share link with sku query param yields the same sku`() {
        val decision = WatchTargetPolicy.decide(null, "https://item.m.jd.com/product/detail?sku=$sku&other=1")
        decision as WatchDecision.Watchable
        assertEquals(sku, decision.externalId)
        // 非京东域名上的 sku= 参数不得被当成京东商品号（现在会按该平台自己的关键词通道可盯）
        val pdd = WatchTargetPolicy.decide(null, "https://mobile.yangkeduo.com/goods.html?sku=$sku")
        pdd as WatchDecision.Watchable
        assertEquals(WatchTargetPolicy.PLATFORM_PDD, pdd.platform)
        assertEquals(WatchChannel.KEYWORD_SEARCH, WatchTargetPolicy.channelOf(pdd.platform))
    }

    @Test
    fun `smzdm article url without a durable product id is rejected as missing id`() {
        // 值得买是爆料文章号（/p/98765432/），不是稳定的商品主键：只能找同款，不能建盯价目标
        val decision = WatchTargetPolicy.decide(null, "https://www.smzdm.com/p/98765432/")
        decision as WatchDecision.Rejected
        assertEquals(WatchTargetPolicy.PLATFORM_SMZDM, decision.platform)
        assertEquals(RejectReason.MISSING_ID, decision.reason)
    }

    @Test
    fun `unknown source yields missing id reason`() {
        for (url in listOf("", "   ", "javascript:void(0)")) {
            val decision = WatchTargetPolicy.decide(null, url)
            decision as WatchDecision.Rejected
            assertEquals(RejectReason.MISSING_ID, decision.reason)
        }
    }

    // ---------- 3. 主键防撞 ----------

    @Test
    fun `external id extraction rejects legacy broken rows`() {
        assertEquals(sku, WatchTargetPolicy.externalIdOf("jd:$sku", "jd"))
        assertNull(WatchTargetPolicy.externalIdOf("jd:", "jd"))
        assertNull(WatchTargetPolicy.externalIdOf("jd:12345", "jd"))
        assertNull(WatchTargetPolicy.externalIdOf("taobao:654321098765", "jd"))
        assertFalse(WatchTargetPolicy.isTrackableTarget("jd:", "jd"))
        assertTrue(WatchTargetPolicy.isTrackableTarget("jd:$sku", "jd"))
        // 2026-11：非京东平台现在可盯（关键词搜索通道），但主键前缀必须与平台一致
        assertTrue(WatchTargetPolicy.isTrackableTarget("taobao:654321098765", "taobao"))
        assertFalse(WatchTargetPolicy.isTrackableTarget("jd:654321098765", "taobao"))
        assertFalse(WatchTargetPolicy.isTrackableTarget("unknown:654321098765", "unknown"))
    }

    @Test
    fun `first save of a valid id creates a new target`() {
        val plan = WatchTargetPolicy.planSave(emptyList(), watchable(), "Apple iPhone 15")
        assertTrue(plan is SavePlan.Create)
        assertEquals("jd:$sku", (plan as SavePlan.Create).productId)
    }

    @Test
    fun `illegal primary key is blocked before touching room`() {
        val broken = WatchDecision.Watchable(WatchTargetPolicy.PLATFORM_JD, "")
        assertTrue(WatchTargetPolicy.planSave(emptyList(), broken, "任意标题") is SavePlan.Blocked)
        val shortId = WatchDecision.Watchable(WatchTargetPolicy.PLATFORM_JD, "12345")
        assertTrue(WatchTargetPolicy.planSave(emptyList(), shortId, "任意标题") is SavePlan.Blocked)
    }

    @Test
    fun `same id with a refreshed but recognizable title updates in place`() {
        val existing = listOf(ExistingTarget("jd:$sku", "Apple iPhone 15 128GB 黑色", true))
        val plan = WatchTargetPolicy.planSave(existing, watchable(), "Apple iPhone 15 128GB 黑色 手机")
        assertTrue(plan is SavePlan.UpdateSameProduct)
        plan as SavePlan.UpdateSameProduct
        assertEquals("Apple iPhone 15 128GB 黑色", plan.existingTitle)
    }

    @Test
    fun `same id with a clearly different title needs explicit confirm`() {
        // 这正是旧实现静默 REPLACE 掉用户另一个目标的场景
        val existing = listOf(ExistingTarget("jd:$sku", "戴森 V12 吸尘器", true))
        val plan = WatchTargetPolicy.planSave(existing, watchable(), "小米 14 Ultra 摄影套装")
        assertTrue(plan is SavePlan.NeedConfirm)
        plan as SavePlan.NeedConfirm
        assertEquals("戴森 V12 吸尘器", plan.existingTitle)
        assertEquals("小米 14 Ultra 摄影套装", plan.newTitle)
    }

    @Test
    fun `inactive row still occupies the primary key so it must be confirmed too`() {
        val existing = listOf(ExistingTarget("jd:$sku", "戴森 V12 吸尘器", active = false))
        assertTrue(WatchTargetPolicy.planSave(existing, watchable(), "小米 14 Ultra") is SavePlan.NeedConfirm)
    }

    @Test
    fun `title similarity ignores punctuation decoration and self-operated prefix`() {
        assertTrue(WatchTargetPolicy.titlesLikelySameProduct("【自营】Apple iPhone15", "Apple iPhone 15 手机"))
        assertTrue(WatchTargetPolicy.titlesLikelySameProduct("戴森V12", "戴森 V12 吸尘器"))
        assertFalse(WatchTargetPolicy.titlesLikelySameProduct("戴森 V12 吸尘器", "小米 14 Ultra"))
        assertFalse(WatchTargetPolicy.titlesLikelySameProduct("", "小米 14 Ultra"))
        assertTrue(WatchTargetPolicy.titlesLikelySameProduct("  ", ""))
    }

    // ---------- 4. 检查轮次如实记账（含 p.3.cn 不可达） ----------

    /** 便捷构造：本轮现价的默认来源是京东 p.3.cn（这些用例只验记账，不验来源） */
    private fun jdPrice(price: Double) = PriceSample(price, PriceSource.JD_P3CN)

    private fun refs() = listOf(
        WatchTargetRef("jd:$sku", "jd", 3999.0),
        WatchTargetRef("jd:", "jd", 100.0),
        WatchTargetRef("taobao:654321098765", "taobao", 100.0)
    )

    @Test
    fun `unreachable p3cn yields all targets as price unavailable and round stalled`() {
        // JdApi.getPrices 在 DNS 不可达时返回空 Map（不抛异常）→ 旧实现完全静默
        val report = WatchTargetPolicy.classifyRound(
            listOf(WatchTargetRef("jd:$sku", "jd", 3999.0)),
            pricesByPlatform = mapOf("jd" to emptyMap()),
            failedPlatforms = emptySet()
        )
        assertEquals(0, report.checked)
        assertEquals(1, report.skipped.noPrice)
        assertEquals(0, report.skipped.noChannel)
        assertTrue(report.stalled)
        assertEquals(listOf(SkipReason.PRICE_UNAVAILABLE), report.skipped.reasons())
    }

    @Test
    fun `lookup exception counts every trackable target of that platform as skipped`() {
        val report = WatchTargetPolicy.classifyRound(
            listOf(WatchTargetRef("jd:$sku", "jd", 3999.0)),
            pricesByPlatform = emptyMap(),
            failedPlatforms = setOf("jd")
        )
        assertEquals(0, report.checked)
        assertEquals(1, report.skipped.noPrice)
        assertTrue(report.stalled)
    }

    @Test
    fun `mixed targets are accounted by reason without double counting`() {
        val report = WatchTargetPolicy.classifyRound(
            refs(),
            pricesByPlatform = mapOf("jd" to mapOf(sku to jdPrice(3599.0))),
            failedPlatforms = emptySet()
        )
        assertEquals(3, report.total)
        assertEquals(1, report.checked)
        assertEquals(1, report.skipped.badTargetId)
        // 2026-11：淘宝目标不再是"无通道"，本轮没拿到关键词样本 → 计入"取不到现价"
        assertEquals(0, report.skipped.noChannel)
        assertEquals(1, report.skipped.noPrice)
        assertEquals(2, report.skipped.total)
        assertEquals(listOf("jd:$sku"), report.triggeredProductIds)
        assertFalse(report.stalled)
    }

    @Test
    fun `price above target does not trigger a notification`() {
        val report = WatchTargetPolicy.classifyRound(
            listOf(WatchTargetRef("jd:$sku", "jd", 3000.0)),
            pricesByPlatform = mapOf("jd" to mapOf(sku to jdPrice(3599.0)))
        )
        assertEquals(1, report.checked)
        assertTrue(report.triggeredProductIds.isEmpty())
    }

    @Test
    fun `zero priced response is treated as no price rather than a drop`() {
        val report = WatchTargetPolicy.classifyRound(
            listOf(WatchTargetRef("jd:$sku", "jd", 3999.0)),
            pricesByPlatform = mapOf("jd" to mapOf(sku to jdPrice(0.0)))
        )
        assertEquals(0, report.checked)
        assertEquals(1, report.skipped.noPrice)
        assertTrue(report.triggeredProductIds.isEmpty())
    }

    @Test
    fun `empty target list is not a stalled round`() {
        val report = WatchTargetPolicy.classifyRound(emptyList(), emptyMap())
        assertEquals(0, report.total)
        assertFalse(report.stalled)
    }

    @Test
    fun `skip counts add up and keep reason priority`() {
        val sum = WatchSkipCounts(noChannel = 2, badTargetId = 1) + WatchSkipCounts(noPrice = 3)
        assertEquals(2, sum.noChannel)
        assertEquals(1, sum.badTargetId)
        assertEquals(3, sum.noPrice)
        assertEquals(6, sum.total)
        assertEquals(
            listOf(SkipReason.NO_PRICE_CHANNEL, SkipReason.INVALID_TARGET_ID, SkipReason.PRICE_UNAVAILABLE),
            sum.reasons()
        )
        assertTrue(WatchSkipCounts().reasons().isEmpty())
    }

    @Test
    fun `skip reason per target distinguishes channel id and price problems`() {
        val prices = mapOf(sku to jdPrice(3599.0))
        assertNull(WatchTargetPolicy.skipReasonFor(WatchTargetRef("jd:$sku", "jd", 3999.0), prices, false))
        assertEquals(
            SkipReason.INVALID_TARGET_ID,
            WatchTargetPolicy.skipReasonFor(WatchTargetRef("jd:", "jd", 3999.0), prices, false)
        )
        // 非京东平台现在有通道，但拿不到样本时如实判"取不到现价"（不是"没通道"）
        assertEquals(
            SkipReason.PRICE_UNAVAILABLE,
            WatchTargetPolicy.skipReasonFor(WatchTargetRef("taobao:654321098765", "taobao", 99.0), prices, false)
        )
        // 认不出平台的坏行才是真正的"无通道"
        assertEquals(
            SkipReason.NO_PRICE_CHANNEL,
            WatchTargetPolicy.skipReasonFor(WatchTargetRef("unknown:654321098765", "unknown", 99.0), prices, false)
        )
        assertEquals(
            SkipReason.PRICE_UNAVAILABLE,
            WatchTargetPolicy.skipReasonFor(WatchTargetRef("jd:$sku", "jd", 3999.0), emptyMap(), false)
        )
    }

    // ---------- 5. 目标价预填依据 ----------

    @Test
    fun `history from the same sku prefills the target price`() {
        val prefill = WatchTargetPolicy.prefillTargetPrice(
            skuId = sku,
            keyword = "https://item.jd.com/$sku.html",
            candidateUrl = "https://item.m.jd.com/product/$sku.html",
            historyCurrent = 4999.0,
            historyLowest = 4299.0
        )
        assertTrue(prefill is TargetPrefill.Prefilled)
        prefill as TargetPrefill.Prefilled
        assertEquals(4999.0, prefill.price, 0.001)
        assertEquals(PrefillSource.HISTORY_CURRENT, prefill.source)
    }

    @Test
    fun `pure numeric keyword is also recognized as the same sku`() {
        val prefill = WatchTargetPolicy.prefillTargetPrice(sku, sku, null, 0.0, 4299.0)
        prefill as TargetPrefill.Prefilled
        assertEquals(4299.0, prefill.price, 0.001)
        assertEquals(PrefillSource.HISTORY_LOWEST, prefill.source)
    }

    @Test
    fun `history pulled from another deal link is never prefilled`() {
        // 旧行为：无条件取 history.current → 给 A 商品设目标价被预填成 B 商品的价格
        val prefill = WatchTargetPolicy.prefillTargetPrice(
            skuId = sku,
            keyword = "iPhone 15 手机",
            candidateUrl = "https://item.m.jd.com/product/$sku.html",
            historyCurrent = 199.0,
            historyLowest = 99.0
        )
        assertTrue(prefill is TargetPrefill.NeedsManual)
        assertEquals(PrefillReason.CROSS_SOURCE_HISTORY, (prefill as TargetPrefill.NeedsManual).reason)
    }

    @Test
    fun `candidate url of a different sku blocks prefill even when keyword matches`() {
        val prefill = WatchTargetPolicy.prefillTargetPrice(
            skuId = sku,
            keyword = sku,
            candidateUrl = "https://item.m.jd.com/product/999999999999.html",
            historyCurrent = 4999.0,
            historyLowest = 4299.0
        )
        assertTrue(prefill is TargetPrefill.NeedsManual)
    }

    @Test
    fun `no usable history value yields the no history reason`() {
        val prefill = WatchTargetPolicy.prefillTargetPrice(sku, sku, null, 0.0, 0.0)
        prefill as TargetPrefill.NeedsManual
        assertEquals(PrefillReason.NO_HISTORY, prefill.reason)
    }

    @Test
    fun `keyword sku parsing accepts links and bare digits only`() {
        assertEquals(sku, WatchTargetPolicy.jdSkuFromKeyword("https://item.jd.com/$sku.html"))
        assertEquals(sku, WatchTargetPolicy.jdSkuFromKeyword(sku))
        assertEquals(sku, WatchTargetPolicy.jdSkuFromKeyword("  " + sku + "  "))
        assertNull(WatchTargetPolicy.jdSkuFromKeyword("戴森吸尘器"))
        assertNull(WatchTargetPolicy.jdSkuFromKeyword(""))
    }

    // ---------- 平台通道判定 ----------

    @Test
    fun `jd keeps direct lookup while other known platforms use the keyword channel`() {
        assertEquals(WatchChannel.JD_LOOKUP, WatchTargetPolicy.channelOf("jd"))
        for (platform in listOf("taobao", "pdd", "dangdang", "shihuo", "smzdm")) {
            assertEquals("$platform 应走关键词自动搜索", WatchChannel.KEYWORD_SEARCH, WatchTargetPolicy.channelOf(platform))
            assertTrue("$platform 的旧行必须可被追踪", WatchTargetPolicy.isTrackablePlatform(platform))
            assertTrue(WatchTargetPolicy.supportsKeywordSearch(platform))
        }
        assertEquals(WatchChannel.NONE, WatchTargetPolicy.channelOf("unknown"))
        assertFalse(WatchTargetPolicy.isTrackablePlatform("unknown"))
        assertFalse(WatchTargetPolicy.supportsKeywordSearch("jd"))
    }

    // ---------- 关键词同款样本：必须过严格相关性才配当"本轮现价" ----------

    @Test
    fun `keyword search sample is live only after strict relevance and ratio checks`() {
        // 相关候选 → 过 matchOffer + 比值检查 → 才能当现价、进曲线
        val keyword = "iPhone 15 128GB"
        val good = AutoTrackPolicy.matchOffer(keyword, "Apple iPhone 15 128GB 黑色 手机", 4999.0)
        assertTrue("同款标题应通过", good.accepted)
        assertTrue("与历史参考价的比值应合理", AutoTrackPolicy.isPriceRatioReasonable(4999.0, 5200.0))
        val sample = PriceSample(4999.0, PriceSource.KEYWORD_SEARCH)
        assertTrue("同款搜索价是真现价（isLive）", sample.isLive)
        assertNotNull("同款搜索价够格写曲线", PriceSampling.curveWorthy(sample))

        // 配件/不相关候选 → 直接挡下，哪怕价格更低也不得冒充现价
        assertFalse(AutoTrackPolicy.matchOffer(keyword, "适用 iPhone 15 手机壳 保护套", 9.9).accepted)
        assertFalse(AutoTrackPolicy.matchOffer(keyword, "iPhone 15 128GB", 0.0).accepted)
        // 价位完全不在同一档（错规格/错商品）→ 比值检查挡下
        assertFalse(AutoTrackPolicy.isPriceRatioReasonable(99.0, 5200.0))

        // 记账层：淘宝目标拿到同款搜索样本、且价格 ≤ 目标价 → 才计入 checked / hits 名单
        val target = WatchTargetRef("taobao:654321098765", "taobao", 5000.0)
        val hit = WatchTargetPolicy.classifyRound(
            listOf(target),
            mapOf("taobao" to mapOf("654321098765" to PriceSample(4999.0, PriceSource.KEYWORD_SEARCH)))
        )
        assertEquals(1, hit.checked)
        assertEquals(listOf("taobao:654321098765"), hit.triggeredProductIds)
        val miss = WatchTargetPolicy.classifyRound(
            listOf(target),
            mapOf("taobao" to mapOf("654321098765" to PriceSample(5399.0, PriceSource.KEYWORD_SEARCH)))
        )
        assertEquals(1, miss.checked)
        assertTrue("高于目标价不得进通知名单", miss.triggeredProductIds.isEmpty())
    }

    private fun watchable() = WatchDecision.Watchable(WatchTargetPolicy.PLATFORM_JD, sku)
}
