package com.pricelens.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活引导的「看哪段步骤表、跳哪里」决策（纯逻辑，不碰 Compose/Robolectric）。
 *
 * 锁的红线（文档 §12.3）：
 *  - 五个品牌五段步骤表，不许两段共用一个资源（哪段该显示是纯映射，错不起）；
 *  - 每条跳转候选链的链尾必须是应用详情页（厂商 Activity 改名/缺失时 UI 逐个尝试，
 *    最不济也要落在每家都有的「应用信息」页）；
 *  - 四家国产 ROM 的自启动候选必须指向各自系统的安全中心/管家，认不出的品牌不许乱给候选。
 */
class KeepAlivePlanTest {

    private fun vendorPages(jumps: List<KeepAliveJump>): List<KeepAliveJump.VendorPage> = jumps.filterIsInstance<KeepAliveJump.VendorPage>()

    @Test
    fun `every rom gets its own step table`() {
        val res = Rom.entries.map { KeepAlivePlan.stepsRes(it) }
        res.forEach { assertTrue("步骤表资源缺失", it != 0) }
        assertEquals("五段步骤表必须互不相同", Rom.entries.size, res.toSet().size)
    }

    @Test
    fun `every jump chain ends with app details`() {
        Rom.entries.forEach { rom ->
            assertEquals(KeepAliveJump.AppDetails, KeepAlivePlan.autoStartTargets(rom).last())
            assertEquals(KeepAliveJump.AppDetails, KeepAlivePlan.backgroundPopupTargets(rom).last())
        }
        assertEquals(KeepAliveJump.AppDetails, KeepAlivePlan.batteryTargets().last())
    }

    @Test
    fun `battery chain asks per-app first then falls back to the full list`() {
        assertEquals(
            listOf(
                KeepAliveJump.SystemPage("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", withPackageUri = true),
                KeepAliveJump.SystemPage("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
                KeepAliveJump.AppDetails
            ),
            KeepAlivePlan.batteryTargets()
        )
    }

    @Test
    fun `app details action is the standard settings action`() {
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", KeepAlivePlan.APP_DETAILS_ACTION)
    }

    @Test
    fun `xiaomi autostart first candidate is the miui autostart page`() {
        assertEquals(
            KeepAliveJump.VendorPage("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            KeepAlivePlan.autoStartTargets(Rom.XIAOMI).first()
        )
    }

    @Test
    fun `huawei candidates stay inside the huawei system manager`() {
        val pages = vendorPages(KeepAlivePlan.autoStartTargets(Rom.HUAWEI))
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.pkg == "com.huawei.systemmanager" })
        assertTrue(
            pages.any { it.cls == "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity" }
        )
    }

    @Test
    fun `oppo candidates cover both coloros and oplus safecenter`() {
        val pkgs = vendorPages(KeepAlivePlan.autoStartTargets(Rom.OPPO)).map { it.pkg }.toSet()
        assertTrue("com.coloros.safecenter" in pkgs)
        assertTrue("com.oplus.safecenter" in pkgs)
    }

    @Test
    fun `vivo candidates cover vivo permission manager and iqoo secure`() {
        val pkgs = vendorPages(KeepAlivePlan.autoStartTargets(Rom.VIVO)).map { it.pkg }.toSet()
        assertTrue("com.vivo.permissionmanager" in pkgs)
        assertTrue("com.iqoo.secure" in pkgs)
    }

    @Test
    fun `other rom has no vendor candidates and only reaches app details`() {
        assertEquals(listOf<KeepAliveJump>(KeepAliveJump.AppDetails), KeepAlivePlan.autoStartTargets(Rom.OTHER))
        assertEquals(listOf<KeepAliveJump>(KeepAliveJump.AppDetails), KeepAlivePlan.backgroundPopupTargets(Rom.OTHER))
    }

    @Test
    fun `miui background popup carries the package extra`() {
        val pages = vendorPages(KeepAlivePlan.backgroundPopupTargets(Rom.XIAOMI))
        assertEquals(1, pages.size)
        assertEquals("com.miui.securitycenter", pages.first().pkg)
        assertEquals("com.miui.permcenter.permissions.PermissionsEditorActivity", pages.first().cls)
        assertEquals("extra_pkgname", pages.first().extraPkgNameKey)
    }

    @Test
    fun `non-miui background popup has no vendor candidate`() {
        listOf(Rom.HUAWEI, Rom.OPPO, Rom.VIVO, Rom.OTHER).forEach { rom ->
            assertTrue(vendorPages(KeepAlivePlan.backgroundPopupTargets(rom)).isEmpty())
            assertEquals(listOf<KeepAliveJump>(KeepAliveJump.AppDetails), KeepAlivePlan.backgroundPopupTargets(rom))
        }
    }

    @Test
    fun `all vendor components are well formed and unique`() {
        val all = Rom.entries
            .flatMap { KeepAlivePlan.autoStartTargets(it) + KeepAlivePlan.backgroundPopupTargets(it) }
            .filterIsInstance<KeepAliveJump.VendorPage>()
        assertTrue(all.isNotEmpty())
        all.forEach { page ->
            assertTrue("包名格式不对：${page.pkg}", page.pkg.isNotBlank() && !page.pkg.contains(" "))
            assertTrue("类名格式不对：${page.cls}", page.cls.isNotBlank() && page.cls.contains('.') && !page.cls.startsWith('.'))
        }
        assertEquals("候选不允许重复", all.size, all.toSet().size)
    }
}
