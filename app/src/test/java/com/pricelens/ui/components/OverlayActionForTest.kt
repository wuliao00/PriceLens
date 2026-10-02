package com.pricelens.ui.components

import com.pricelens.accessibility.PriceEvents
import com.pricelens.accessibility.ShopPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CTA 区按钮分组的分支规格（模板：OverlayHistoryLineHonestyTest，本项目单测不写 UI 测试，
 * 只钉"哪个分支出现哪组按钮"这条判定本身）。
 */
class OverlayActionForTest {

    private fun detected(itemId: String?) = PriceEvents.Detected(
        price = 1299.0,
        rawPriceText = "1299",
        title = "Apple iPhone 15 128G",
        packageName = "com.jingdong.app.mall",
        platform = ShopPlatform.JD,
        itemId = itemId
    )

    @Test
    fun `deterministic item id keeps the single history CTA`() {
        assertEquals(OverlayAction.VIEW_HISTORY, overlayActionFor(detected("100012043978"), null, true))
    }

    @Test
    fun `confirmed identity shows the confirmed line instead of the confirm button`() {
        assertEquals(OverlayAction.CONFIRMED, overlayActionFor(detected(null), Any(), true))
    }

    @Test
    fun `unconfirmed detection with usable material offers confirmation`() {
        assertEquals(OverlayAction.CONFIRM_AND_COMPARE, overlayActionFor(detected(null), null, true))
    }

    @Test
    fun `without material confirmation is not offered at all`() {
        // 没标题 / 平台未知时 canConfirm=false：保持旧的降级搜索，绝不出``确认``按钮诱签
        assertEquals(OverlayAction.COMPARE_ONLY, overlayActionFor(detected(null), null, false))
    }
}
