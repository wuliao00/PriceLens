package com.pricelens.coupon.rules

import com.pricelens.coupon.model.AmountRole
import com.pricelens.coupon.model.CouponState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板库的**版本号 + 命中计数**（线上"找券不准"时唯一能区分"模板没写对"与"模板从没被命中"的证据）。
 *
 * 计数是有状态的，所以用例一律拿 `builtin()` 的干净实例（共享的 BUILTIN 会随测试顺序漂）。
 */
class CouponTemplatesTest {

    private val numberPattern = "券([0-9]+)"
    private val pairPattern = "满([0-9]+)减([0-9]+)"
    private val discountRoles = "[\"DISCOUNT\"]"
    private val pairRoles = "[\"THRESHOLD\",\"DISCOUNT\"]"

    private fun json(version: Int = 1, vararg items: String): String = """{"version":$version,"templates":[${items.joinToString(",")}]}"""

    private fun item(id: String, pattern: String, roles: String = "[]", confidence: String = "0.5", state: String? = null): String {
        val tail = if (state == null) "" else ",\"state\":\"$state\""
        return "{\"id\":\"$id\",\"pattern\":\"$pattern\",\"roles\":$roles,\"confidence\":$confidence$tail}"
    }

    @Test
    fun `命中计数按模板累加并且零命中也留在表里`() {
        val templates = CouponTemplates.builtin()
        assertEquals(emptyMap<String, Int>(), templates.hits().filterValues { it > 0 })
        templates.match("满199减50")
        templates.match("满300减50")
        assertEquals(2, templates.hits()["man-jian-pair"])
        // "零命中"本身就是排查信息：从没起作用的模板要能被看见
        assertEquals(0, templates.hits()["no-threshold"])
        assertEquals(templates.templates.size, templates.hits().size)
    }

    @Test
    fun `版本号跟着模板库走而不是跟着命中走`() {
        assertEquals(CouponTemplates.BUILTIN_VERSION, CouponTemplates.builtin().version)
        val hit = CouponTemplates.builtin().match("满199减50").single()
        assertEquals(CouponTemplates.BUILTIN_VERSION, hit.version)
        assertEquals(0.95, hit.confidence, 0.0001)
        assertEquals("满199减50", hit.text)
        assertEquals(0, hit.start)
    }

    @Test
    fun `roles 只对得上数字捕获组`() {
        val pair = CouponTemplates.builtin().match("满199减50").single()
        assertEquals(listOf(AmountRole.THRESHOLD, AmountRole.DISCOUNT), pair.amounts.map { it.role })
        assertEquals(listOf(199.0, 50.0), pair.amounts.map { it.value })
        assertEquals(listOf(1, 5), pair.amounts.map { it.start })
        // 关键词 + 数字那种写法：第 1 组是 `到手`（不是数字）⇒ 不占角色位，只有一个 FINAL
        val finalHit = CouponTemplates.builtin().match("到手¥92.9").single()
        assertEquals(listOf(AmountRole.FINAL), finalHit.amounts.map { it.role })
        assertEquals(92.9, finalHit.amounts[0].value, 0.0001)
        assertEquals(3, finalHit.amounts[0].start)
    }

    @Test
    fun `同一句被多套模板命中时产出多个候选`() {
        val hits = CouponTemplates.builtin().match("无门槛领50元券")
        assertEquals(2, hits.size)
        assertEquals(setOf("ling-yuan-quan", "no-threshold"), hits.map { it.templateId }.toSet())
        // 反例：只该命中一条的句子不许被算成两个候选（否则跨候选共识会自己跟自己投票）
        assertEquals(listOf("man-jian-pair"), CouponTemplates.builtin().match("满199减50").map { it.templateId })
    }

    @Test
    fun `状态词组模板带状态金额模板留 UNKNOWN`() {
        val templates = CouponTemplates.builtin()
        assertEquals(CouponState.SOLD_OUT, templates.match("已抢完").single().state)
        assertTrue(templates.match("已抢完").single().amounts.isEmpty())
        assertEquals(CouponState.MEMBER_ONLY, templates.match("88VIP专享").single().state)
        assertEquals(CouponState.UNKNOWN, templates.match("满199减50").single().state)
        // `已领完` 同时撞上 state-sold-out 与 state-claimed（"已领"是它的子串）⇒ 两个候选都留着，
        // 谁赢由 STATE_PRIORITY 在管线里判（SOLD_OUT 在前），不在模板层偷偷去重
        assertEquals(2, templates.match("已领完").size)
    }

    @Test
    fun `远端规则包只接受整体合法的模板库`() {
        val good = CouponTemplates.from(json(7, item("custom-man-jian", pairPattern, pairRoles, "0.9")))
        assertNotNull(good)
        assertEquals(7, good!!.version)
        val amounts = good.match("满199减50").single().amounts
        assertEquals(listOf(AmountRole.THRESHOLD, AmountRole.DISCOUNT), amounts.map { it.role })
        // 状态模板不写 roles 也能解析（roles 缺省当空表）
        val stateRule = CouponTemplates.from(json(2, item("s", "抢光", confidence = "0.8", state = "SOLD_OUT")))
        assertNotNull(stateRule)
        assertEquals(CouponState.SOLD_OUT, stateRule!!.match("已抢光").single().state)
    }

    @Test
    fun `可疑的远端规则整体拒绝不半懂`() {
        assertNull(CouponTemplates.from("not json at all"))
        assertNull(CouponTemplates.from("""{"templates":[${item("a", numberPattern, discountRoles)}]}"""))
        assertNull(CouponTemplates.from(json(0, item("a", numberPattern))))
        assertNull(CouponTemplates.from(json(1)))
        // 未知角色名 / 未知状态名：猜一个默认值就是"展示一张不存在的券"
        assertNull(CouponTemplates.from(json(1, item("a", numberPattern, "[\"COUPON\"]"))))
        assertNull(CouponTemplates.from(json(1, item("a", numberPattern, state = "ALMOST"))))
        // 置信越界（含 0 与 1.5）
        assertNull(CouponTemplates.from(json(1, item("a", numberPattern, confidence = "1.5"))))
        assertNull(CouponTemplates.from(json(1, item("a", numberPattern, confidence = "0.0"))))
        // id 非法 / 重复
        assertNull(CouponTemplates.from(json(1, item("A B", numberPattern))))
        assertNull(CouponTemplates.from(json(1, item("a", numberPattern), item("a", "领([0-9]+)"))))
        // 正则编译失败
        assertNull(CouponTemplates.from(json(1, item("a", "券(([0-9]+)"))))
        // 正例对照：同一份 JSON 改回合法就必须通过（否则"拒绝"其实是"永远拒绝"）
        assertNotNull(CouponTemplates.from(json(1, item("a", numberPattern, discountRoles))))
    }

    /**
     * `subsidy-tail`：数字在前的替身说法（`17元外卖餐补`）。
     * 命中的是**数字组**，替身词组（餐补/补贴…）不是数字 ⇒ 按 roles 对齐规则被跳过。
     */
    @Test
    fun `数字在前的补贴形状命中为面额`() {
        val hits = CouponTemplates.builtin().match("17元外卖餐补")
        val tail = hits.first { it.templateId == "subsidy-tail" }
        assertEquals(listOf(AmountRole.DISCOUNT), tail.amounts.map { it.role })
        assertEquals(17.0, tail.amounts[0].value, 0.0)
        assertEquals("命中的必须是那个数字而不是替身词", 0, tail.amounts[0].start)
        // 反例（PLB110 的真样本）：`活动售价5998元，参与官方限时补贴…` —— 5998 是售价。
        // 逗号在间隙里是被排除的字符，所以这条**不许**命中；把 {0,4} 改成 .{0,4} 就会在这里红。
        assertTrue(CouponTemplates.builtin().match("活动售价5998元，参与官方限时补贴").none { it.templateId == "subsidy-tail" })
        // 反例：间隙超过 4 个字（跨进别人的半句）不认
        assertTrue(CouponTemplates.builtin().match("17元无门槛限时外卖大额餐补").none { it.templateId == "subsidy-tail" })
        // 反例（2026-10-05 那条真误抽）：规整层把全角逗号换成**半角**，
        // "排除全角标点"的负向类会在半角逗号上放行 ⇒ `3499元,参与补贴` 把售价认领成面额。
        // 间隙类必须是正向汉字类，这条直接在半角形态上重跑一次。
        assertTrue(CouponTemplates.builtin().match("活动售价3499元,参与补贴15%起减500元").none { it.templateId == "subsidy-tail" })
        // 反例：空格也不是合法间隙（`…500元 晒单返红包` 的空格来自节点拼接）
        assertTrue(CouponTemplates.builtin().match("至高减500元 晒单返红包").none { it.templateId == "subsidy-tail" })
        // 正例对照：紧贴的写法也认（「20元补贴」没有品类词）
        assertEquals(20.0, CouponTemplates.builtin().match("20元补贴").first { it.templateId == "subsidy-tail" }.amounts[0].value, 0.0)
    }
}
