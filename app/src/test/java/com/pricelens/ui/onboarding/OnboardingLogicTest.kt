package com.pricelens.ui.onboarding

import com.pricelens.ui.theme.BadgeTone
import com.pricelens.util.ShizukuHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新手引导的纯逻辑部分（不碰 Compose / Android 框架，可在 JVM 单测跑）。
 *
 * 钉住两条产品约束：
 *  1. 缺权限必须能被 [PermissionStatesHandle.missingEssentials] 如实枚举，
 *     首页 SetupHintBar 才知道该说什么、什么时候消失；
 *  2. 权限判定与 Shizuku 四态的派生逻辑只有一份实现（设置页与引导共用），
 *     这里把它的真值表固定下来，避免后来者各写一份。
 *
 * 说明：四个引导步骤都**没有覆写** `OnboardingStep.isComplete`，用的是接口默认值 true
 * （对标 Mihon 的 `PermissionStep.isComplete = true`）——即"权限步骤永不阻断完成"。
 * 步骤类带 @Composable 成员、依赖 Android 图标资源，不在 JVM 单测里实例化。
 */
class OnboardingLogicTest {

    private fun handle(acc: Boolean, overlay: Boolean, shizuku: ShizukuHelper.ShizukuState = ShizukuHelper.ShizukuState.NOT_INSTALLED) =
        PermissionStatesHandle(
            accessibilityGranted = acc,
            overlayGranted = overlay,
            notificationsGranted = true,
            shizukuState = shizuku,
            refresh = { }
        )

    @Test
    fun `essentials ready only when accessibility and overlay both granted`() {
        assertTrue(handle(true, true).essentialsReady)
        assertFalse(handle(false, true).essentialsReady)
        assertFalse(handle(true, false).essentialsReady)
        assertFalse(handle(false, false).essentialsReady)
    }

    @Test
    fun `missing essentials enumerates exactly what is missing in stable order`() {
        assertTrue(handle(true, true).missingEssentials.isEmpty())
        assertEquals(listOf(MissingEssential.ACCESSIBILITY), handle(false, true).missingEssentials)
        assertEquals(listOf(MissingEssential.OVERLAY), handle(true, false).missingEssentials)
        assertEquals(
            listOf(MissingEssential.ACCESSIBILITY, MissingEssential.OVERLAY),
            handle(false, false).missingEssentials
        )
    }

    @Test
    fun `shizuku availability flags`() {
        assertFalse(handle(true, true).shizukuInstalled)

        val installed = handle(true, true, ShizukuHelper.ShizukuState.INSTALLED_NOT_RUNNING)
        assertTrue(installed.shizukuInstalled)
        assertFalse(installed.shizukuAlive)
        assertFalse(installed.shizukuReady)

        val running = handle(true, true, ShizukuHelper.ShizukuState.RUNNING_NOT_GRANTED)
        assertTrue(running.shizukuAlive)
        assertFalse(running.shizukuReady)

        val ready = handle(true, true, ShizukuHelper.ShizukuState.READY)
        assertTrue(ready.shizukuAlive)
        assertTrue(ready.shizukuReady)
    }

    @Test
    fun `refresh callback is invoked by the caller not spontaneously`() {
        var hits = 0
        val h = PermissionStatesHandle(
            accessibilityGranted = false,
            overlayGranted = false,
            notificationsGranted = false,
            shizukuState = ShizukuHelper.ShizukuState.NOT_INSTALLED,
            refresh = { hits++ }
        )
        assertEquals(0, hits)
        h.refresh()
        h.refresh()
        assertEquals(2, hits)
    }

    @Test
    fun `step status badge tone follows grant state`() {
        assertEquals(BadgeTone.POSITIVE, OnboardingStatus("已开启", granted = true).tone)
        assertEquals(BadgeTone.NEGATIVE, OnboardingStatus("未开启", granted = false).tone)
    }
}
