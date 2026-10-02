package com.pricelens.domain

import com.pricelens.accessibility.PriceBasis
import com.pricelens.accessibility.ShopPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免凭证曲线（浮窗身份确认闸门）的行为规格。分节编号对应设计书 §4.3 的钉子；
 * 结构镜像 `WatchTargetPolicyTest`（每节锁一类真实缺陷，防止方案退化）。
 */
class OverlayIdentityPolicyTest {

    private val title = "Apple iPhone 15 128G 黑色 5G手机"

    private fun ref(pid: String, platform: String, title: String) = OverlayIdentityPolicy.IdentityRef(pid, platform, title)

    // -- 1. 键生成：稳定、隔离、命名空间 --

    @Test
    fun `identity id is deterministic for same platform and title`() {
        val a = OverlayIdentityPolicy.identityId("jd", title)
        val b = OverlayIdentityPolicy.identityId("jd", title)
        assertEquals("同平台同标题重启后必须同键（幂等是每天一点的前提）", a, b)
        assertTrue(a.startsWith("ovl:"))
    }

    @Test
    fun `same title on different platforms must not collide`() {
        assertNotEquals(
            OverlayIdentityPolicy.identityId("jd", title),
            OverlayIdentityPolicy.identityId("taobao", title)
        )
    }

    @Test
    fun `decorations and punctuation do not change the key`() {
        // 与 WatchTargetPolicy.normalizeTitle 同一套规则：只留字母数字
        assertEquals(
            OverlayIdentityPolicy.identityId("jd", "Apple iPhone 15（128G）黑色/5G 手机~"),
            OverlayIdentityPolicy.identityId("jd", title)
        )
    }

    @Test
    fun `key satisfies target id shape but is never trackable by watch rounds`() {
        // 反向钉（附录 B 反例二）：ovl: 键形态合法（能当 Room 主键），
        // 但盯价轮次绝不该把它当目标抓走 —— 它没有查价通道，抓走就是拿假 SKU 打网
        val id = OverlayIdentityPolicy.identityId("jd", title)
        assertTrue("键必须满足 TARGET_ID 形态才允许入库", WatchTargetPolicy.isValidTargetId(id))
        assertFalse(WatchTargetPolicy.isTrackableTarget(id, WatchTargetPolicy.PLATFORM_JD))
        assertFalse(WatchTargetPolicy.isTrackableTarget(id, WatchTargetPolicy.PLATFORM_TAOBAO))
        assertNull(WatchTargetPolicy.externalIdOf(id, WatchTargetPolicy.PLATFORM_JD))
    }

    // -- 2. 读价资格：只有页面价够格写日点（§0.7 诚实性闸门） --

    @Test
    fun `zero price is not curve worthy`() {
        assertEquals(
            OverlayIdentityPolicy.SampleSkip.NO_PRICE,
            OverlayIdentityPolicy.skipFor(0.0, PriceBasis.PAGE, title, ShopPlatform.JD)
        )
        assertNull(OverlayIdentityPolicy.sampleFor(0.0, PriceBasis.PAGE, title, ShopPlatform.JD))
    }

    @Test
    fun `after-coupon and net prices never write day points`() {
        // 与 F1 同形错误：券后价混进曲线，``历史最低``就成了假摔
        assertEquals(
            OverlayIdentityPolicy.SampleSkip.NON_PAGE_BASIS,
            OverlayIdentityPolicy.skipFor(80.0, PriceBasis.AFTER_COUPON, title, ShopPlatform.JD)
        )
        assertEquals(
            OverlayIdentityPolicy.SampleSkip.NON_PAGE_BASIS,
            OverlayIdentityPolicy.skipFor(75.0, PriceBasis.NET, title, ShopPlatform.JD)
        )
        assertNull(OverlayIdentityPolicy.sampleFor(80.0, PriceBasis.AFTER_COUPON, title, ShopPlatform.JD))
    }

    @Test
    fun `missing title or unknown platform cannot record`() {
        assertEquals(
            OverlayIdentityPolicy.SampleSkip.NO_TITLE,
            OverlayIdentityPolicy.skipFor(10.0, PriceBasis.PAGE, null, ShopPlatform.JD)
        )
        assertEquals(
            OverlayIdentityPolicy.SampleSkip.UNKNOWN_PLATFORM,
            OverlayIdentityPolicy.skipFor(10.0, PriceBasis.PAGE, title, ShopPlatform.UNKNOWN)
        )
        assertFalse(OverlayIdentityPolicy.canConfirm(null, ShopPlatform.JD))
        assertFalse(OverlayIdentityPolicy.canConfirm(title, ShopPlatform.UNKNOWN))
        assertTrue(OverlayIdentityPolicy.canConfirm(title, ShopPlatform.PDD))
    }

    @Test
    fun `page price passes every gate and is stored under self watch channel`() {
        val sample = OverlayIdentityPolicy.sampleFor(1299.0, PriceBasis.PAGE, title, ShopPlatform.JD)
        assertNotNull(sample)
        assertEquals(PriceSource.SELF_WATCH, sample!!.source)
    }

    // -- 3. match / planConfirm：重复确认不得建第二行（防点分裂的核心） --

    @Test
    fun `renamed marketing title is claimed by the existing identity`() {
        val rows = listOf(ref(OverlayIdentityPolicy.identityId("jd", title), "jd", title))
        // 营销改名（只多一个`【自营】`装饰前缀）规范化后 bigram Dice = 0.957 ≥ 0.9 → 必须认领
        val hit = OverlayIdentityPolicy.match(rows, "jd", "【自营】" + title)
        assertNotNull("同商品改名要认领既有身份，不能再开新键把点分裂到两条曲线", hit)
        assertEquals(rows[0].productId, hit!!.productId)
    }

    @Test
    fun `marketing suffix rename is still claimed`() {
        val rows = listOf(ref(OverlayIdentityPolicy.identityId("jd", title), "jd", title))
        assertNotNull(
            OverlayIdentityPolicy.match(rows, "jd", "Apple iPhone 15 128G 黑色 5G手机 官方标配")
        )
    }

    @Test
    fun `different capacity must not share one curve`() {
        // C2 钉子：旧规则（阈值 0.4 + contains）会把容量差异判成同一商品，
        // 于是 256G 的价串进 128G 的曲线 —— 用户没确认过的 SKU 也在涨点，就是曲线造假
        val rows = listOf(ref("ovl:cap128", "jd", title))
        assertNull(OverlayIdentityPolicy.match(rows, "jd", "Apple iPhone 15 256G 黑色 5G手机"))
        // 下面两个单靠 Dice 拦不住（512G = 0.909、iPhone 16 = 0.909，都越过 0.9），
        // 靠的是数字串逐段全等这道额外闸门
        assertNull(OverlayIdentityPolicy.match(rows, "jd", "Apple iPhone 15 512G 黑色 5G手机"))
        assertNull(OverlayIdentityPolicy.match(rows, "jd", "Apple iPhone 16 128G 黑色 5G手机"))
    }

    @Test
    fun `pro variant must not claim the base model identity`() {
        val rows = listOf(ref("ovl:mi14", "jd", "小米14 12GB+256GB 黑色"))
        assertNull(
            "小米14 与 小米14Pro 是两个商品（Dice 0.84），不得共用一条曲线",
            OverlayIdentityPolicy.match(rows, "jd", "小米14Pro 12GB+256GB 黑色")
        )
        assertNull(OverlayIdentityPolicy.match(rows, "jd", "小米14Ultra 16GB+512GB 黑色"))
    }

    @Test
    fun `decoration prefix rename claims identity through planConfirm`() {
        val plan = OverlayIdentityPolicy.planConfirm(emptyList(), ShopPlatform.JD, title)
        val created = plan as OverlayIdentityPolicy.ConfirmPlan.Create
        val second = OverlayIdentityPolicy.planConfirm(
            listOf(ref(created.productId, created.platform, "【自营】" + title)),
            ShopPlatform.JD,
            title
        )
        assertTrue("旧行带`【自营】`前缀、新读价没带：第二次确认仍要落在同一行", second is OverlayIdentityPolicy.ConfirmPlan.Already)
    }

    @Test
    fun `different products do not share one identity`() {
        val rows = listOf(ref("ovl:aaa111", "jd", "小米 14 Ultra 16G+512G 钛金属版"))
        assertNull(OverlayIdentityPolicy.match(rows, "jd", "戴森 V12 Detect Slim 无线吸尘器"))
    }

    @Test
    fun `short marketing title is no longer claimed by the full one`() {
        // 钉子反向：旧规则的 contains 分支（"iPhone 15 128G 黑色" ⊂ 全标题）会认领，
        // 而浮窗短标题到底是不是同一个 SKU 证明不了 → 宁可开新键（Dice = 0.74 < 0.9）
        val rows = listOf(ref("ovl:full1", "jd", title))
        assertNull(OverlayIdentityPolicy.match(rows, "jd", "iPhone 15 128G 黑色"))
    }

    @Test
    fun `plan confirm creates once then already`() {
        val plan = OverlayIdentityPolicy.planConfirm(emptyList(), ShopPlatform.JD, title)
        assertTrue(plan is OverlayIdentityPolicy.ConfirmPlan.Create)
        val created = plan as OverlayIdentityPolicy.ConfirmPlan.Create
        assertEquals(OverlayIdentityPolicy.identityId("jd", title), created.productId)
        val again = OverlayIdentityPolicy.planConfirm(
            listOf(ref(created.productId, created.platform, created.title)),
            ShopPlatform.JD,
            "【自营】" + title
        )
        assertTrue("第二次确认同一商品必须是 Already，绝不插第二行", again is OverlayIdentityPolicy.ConfirmPlan.Already)
    }

    @Test
    fun `plan confirm refuses beyond max identities`() {
        val rows = (1..OverlayIdentityPolicy.MAX_IDENTITIES).map { ref("ovl:pad" + it, "jd", "独占商品 $it 型号X$it") }
        val plan = OverlayIdentityPolicy.planConfirm(rows, ShopPlatform.JD, "完全不相干的新型号Z9")
        assertTrue("满 20 条要拒绝新建（防读到不同 banner 就碎一条身份）", plan is OverlayIdentityPolicy.ConfirmPlan.Full)
    }

    @Test
    fun `unknown platform or blank title is not eligible to confirm`() {
        assertTrue(
            OverlayIdentityPolicy.planConfirm(emptyList(), ShopPlatform.UNKNOWN, title) is OverlayIdentityPolicy.ConfirmPlan.NotEligible
        )
        assertTrue(OverlayIdentityPolicy.planConfirm(emptyList(), ShopPlatform.JD, "  ") is OverlayIdentityPolicy.ConfirmPlan.NotEligible)
    }
}
