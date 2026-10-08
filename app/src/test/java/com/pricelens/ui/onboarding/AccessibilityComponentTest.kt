package com.pricelens.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F16 的钉子：无障碍"开没开"必须按**本包自己的组件**判，不能按子串 `"com.pricelens"` 判。
 *
 * 为什么这组用例值得单独存在：本仓库的验证流程常态是**同一台手机上并装两个包**
 * （正式版 `com.pricelens` + 探针包 `com.pricelens.dev`），而 `com.pricelens.dev`
 * 这个字符串本身就 `contains("com.pricelens")` —— 旧写法下两个包会互相把对方的开关读成自己的。
 * 这类"读起来对、跑起来串味"的判定，靠 review 抓不住，只能有断言。
 *
 * 之所以能纯 JVM 测：判定被抽成了 [accessibilityComponentEnabled]（吃两个字符串），
 * 读 `Settings.Secure` 的那层壳留在 [isPriceLensAccessibilityEnabled] 里。
 */
class AccessibilityComponentTest {

    private val svc = "com.pricelens.accessibility.PriceMonitorService"
    private val rel = "com.pricelens/$svc"
    private val dev = "com.pricelens.dev/$svc"

    @Test
    fun `本包组件在列就是已开启`() {
        assertTrue(accessibilityComponentEnabled(rel, "com.pricelens"))
        assertTrue(accessibilityComponentEnabled(dev, "com.pricelens.dev"))
    }

    /** 这次修复的正身：两个包各看各的，谁也不把对方的开关读成自己的 */
    @Test
    fun `调试包开着不等于正式包开着，反之亦然`() {
        assertFalse("只有 .dev 在列时，正式版必须判 false", accessibilityComponentEnabled(dev, "com.pricelens"))
        assertFalse("只有正式版在列时，.dev 必须判 false", accessibilityComponentEnabled(rel, "com.pricelens.dev"))
    }

    @Test
    fun `冒号分隔的多项列表里能命中自己那一项`() {
        val other = "com.tencent.wework/.WwAccessibilityService"
        assertTrue(accessibilityComponentEnabled("$other:$rel", "com.pricelens"))
        assertTrue(accessibilityComponentEnabled("$rel:$other", "com.pricelens"))
        assertFalse(accessibilityComponentEnabled(other, "com.pricelens"))
    }

    /**
     * 宽容的方向是刻意选边：项内用 contains 而不是全等，因为个别 ROM 会在那串后面挂字段。
     * 判错的两种代价不对称——假阴（明明开着却说要开）会让用户反复去开一个已经开着的开关，
     * 比假阳更难被发现，所以宁可宽容。
     */
    @Test
    fun `项尾挂了额外字段仍然认`() {
        assertTrue(accessibilityComponentEnabled("$rel;flags=1", "com.pricelens"))
    }

    @Test
    fun `别家包的同名服务不算自己的`() {
        assertFalse(
            "只看类名会把别人家的同名服务读成自己",
            accessibilityComponentEnabled("com.other/$svc", "com.pricelens")
        )
    }

    @Test
    fun `没开启时的三种写法都判 false`() {
        assertFalse(accessibilityComponentEnabled(null, "com.pricelens"))
        assertFalse(accessibilityComponentEnabled("", "com.pricelens"))
        // SettingsProvider 在"没设置过"时返回的是字面量 "null" 而不是 null，这条在真机上量到过
        assertFalse(accessibilityComponentEnabled("null", "com.pricelens"))
        assertFalse(accessibilityComponentEnabled(rel, ""))
    }

    @Test
    fun `组件串大小写不敏感`() {
        assertTrue(accessibilityComponentEnabled(rel.uppercase(), "com.pricelens"))
    }
}
