package com.pricelens.ui.components

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import com.pricelens.ui.theme.Elevations
import com.pricelens.ui.theme.MotionDurations
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §2 卡片按压浮起的纯判据（真机上看不出"没点击的卡片在偷按压"，所以把它抽成纯函数钉住）。
 *
 * 核心用例是 [cardWithoutClickNeverShowsPressState]：没有 onClick 的卡片
 * （例：盯价页曲线卡只声明 onLongClick）被按住时也必须保持 1f / CardRest，
 * 否则"点不动却在缩"的卡片会骗用户。
 */
class PriceCardMotionTest {

    /** 非按压交互（Interaction 是公开标记接口，可以自己造一个假的） */
    private object SomeOtherInteraction : Interaction

    private val pressed: Set<Interaction> = setOf(PressInteraction.Press(Offset.Zero))

    @Test
    fun `clickable card dips to 0_99 while pressed`() {
        assertEquals(0.99f, CardMotion.pressedScale(pressed, hasClick = true), 1e-6f)
    }

    @Test
    fun `card without onClick never shows press state`() {
        // 同一次按压、同一条判据：唯一差别是没有点击能力
        assertEquals(1f, CardMotion.pressedScale(pressed, hasClick = false), 1e-6f)
    }

    @Test
    fun `no interaction means no scaling for either kind of card`() {
        assertEquals(1f, CardMotion.pressedScale(emptySet(), hasClick = true), 1e-6f)
        assertEquals(1f, CardMotion.pressedScale(emptySet(), hasClick = false), 1e-6f)
    }

    @Test
    fun `interactions other than press do not trigger the press state`() {
        assertEquals(1f, CardMotion.pressedScale(setOf(SomeOtherInteraction), hasClick = true), 1e-6f)
    }

    @Test
    fun `elevation rises on press only when clickable and always returns to rest`() {
        assertEquals(Elevations.CardPressed, CardMotion.cardElevation(pressed, hasClick = true, lifted = false))
        assertEquals(Elevations.CardRest, CardMotion.cardElevation(pressed, hasClick = false, lifted = false))
        assertEquals(Elevations.CardRest, CardMotion.cardElevation(emptySet(), hasClick = true, lifted = false))
    }

    @Test
    fun `long press lift raises elevation without any click ability`() {
        assertEquals(Elevations.CardPressed, CardMotion.cardElevation(emptySet(), hasClick = false, lifted = true))
    }

    @Test
    fun `press feedback uses the fast duration token`() {
        // 按压/浮起反馈必须是 MotionDurations.Fast（150ms），不许悄悄改用 Standard/Slow
        assertEquals(MotionDurations.Fast, CardMotion.PressDurationMillis)
    }

    @Test
    fun `lift scale is a plain grow with no overshoot past the token`() {
        assertEquals(1.02f, CardMotion.LiftScale, 1e-6f)
        assertEquals(0.99f, CardMotion.PressScale, 1e-6f)
    }
}
