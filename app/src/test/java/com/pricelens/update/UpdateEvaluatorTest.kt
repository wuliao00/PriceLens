package com.pricelens.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateEvaluator] 判定基线。
 *
 * 核心不变式（对标 Mihon GetApplicationRelease 的 fail-open）：
 * **除了"清单可信 + 当前版本确实低于 minSupportedVersionCode"这一条路径，
 * 其它任何情况都必须落在 [UpdateDecision.Silent]，绝不能把用户关在应用外。**
 */
class UpdateEvaluatorTest {

    private val now = 1_800_000_000_000L
    private val fresh = now - HOUR

    private fun manifest(
        latestCode: Int = 14,
        minSupported: Int = 14,
        forceBelow: Int = 13,
        rollout: Int = 100,
        sha: String = SHA,
        generatedAt: Long = fresh,
        targets: List<ApkTarget> = DEFAULT_TARGETS,
        cooldown: Int = 24
    ) = UpdateManifest(
        schemaVersion = MANIFEST_SCHEMA_VERSION,
        generatedAtMs = generatedAt,
        channel = "stable",
        latest = Latest(latestCode, "2.6.0", "2026-09-26"),
        minSupportedVersionCode = minSupported,
        forceBelow = forceBelow,
        rolloutPercent = rollout,
        apkUrls = targets,
        sha256 = sha,
        sizeBytes = 18_000_000L,
        notes = listOf("比价更准"),
        cooldownHours = cooldown
    )

    private fun valid(manifest: UpdateManifest) = ManifestResult.Valid(manifest)

    private fun decide(
        current: Int,
        result: ManifestResult,
        bucket: Int = 50,
        lastShown: Long = 0L,
        skipUntil: Long = 0L,
        ignoreSilence: Boolean = false,
        nowMs: Long = now
    ) = UpdateEvaluator.evaluate(
        currentVersionCode = current,
        result = result,
        nowMs = nowMs,
        installBucket = bucket,
        lastAcceptedGeneratedAtMs = lastShown,
        skipUntilMs = skipUntil,
        ignoreSilence = ignoreSilence
    )

    // ---------- 只有这一条路径允许阻断 ----------

    @Test
    fun `below minSupported with trustworthy manifest blocks`() {
        val decision = decide(13, valid(manifest()))
        assertTrue("应阻断，实际=$decision", decision is UpdateDecision.Forced)
        val offer = (decision as UpdateDecision.Forced).offer
        assertEquals(14, offer.versionCode)
        assertEquals("2.6.0", offer.versionName)
        assertEquals(24, offer.cooldownHours)
    }

    @Test
    fun `equal to minSupported never blocks`() {
        assertFalse(decide(14, valid(manifest())) is UpdateDecision.Forced)
        assertTrue(decide(14, valid(manifest())) is UpdateDecision.UpToDate)
    }

    @Test
    fun `blocking compares integer versionCode not versionName string`() {
        // versionName "9.9.9" 字符串比 "10.0.0" 大；整数比才正确
        val tenOhOh = manifest(latestCode = 100, minSupported = 100)
            .copy(latest = Latest(100, "10.0.0", "2026-09-26"))
        assertTrue(decide(99, valid(tenOhOh)) is UpdateDecision.Forced)
        val nineNineNine = manifest(latestCode = 99, minSupported = 99)
            .copy(latest = Latest(99, "9.9.9", "2026-09-26"))
        assertTrue(decide(100, valid(nineNineNine)) is UpdateDecision.UpToDate)
    }

    // ---------- 闸门不满足 → 一律静默（fail-open） ----------

    @Test
    fun `rejected manifest fails open even when below minSupported`() {
        val decision = decide(1, UpdateManifest.parse("{ broken"))
        assertTrue(decision is UpdateDecision.Silent)
        assertTrue((decision as UpdateDecision.Silent).reason.startsWith(SilentReason.MANIFEST_REJECTED))
    }

    @Test
    fun `null result object also fails open`() {
        val decision = decide(1, ManifestResult.Rejected("network"))
        assertTrue(decision is UpdateDecision.Silent)
    }

    @Test
    fun `blank sha256 is never a reason to block`() {
        val silent = decide(13, valid(manifest(sha = ""))) as UpdateDecision.Silent
        assertEquals(SilentReason.SHA_MISSING, silent.reason)
    }

    @Test
    fun `placeholder sha256 does not block either`() {
        // 发布前 update.json 里留的 TO_FILL_... 占位符：非空但不可校验，一律不阻断
        val silent = decide(
            13,
            valid(manifest(sha = "TO_FILL_64_HEX_LOWERCASE_SHA256_OF_RELEASE_APK"))
        ) as UpdateDecision.Silent
        assertEquals(SilentReason.SHA_MISSING, silent.reason)
        // 长度不对/含非十六进制字符同样视为不可校验
        val short = decide(13, valid(manifest(sha = "abc123"))) as UpdateDecision.Silent
        assertEquals(SilentReason.SHA_MISSING, short.reason)
    }

    @Test
    fun `manifest older than 30 days is ignored`() {
        val stale = decide(
            13,
            valid(manifest(generatedAt = now - UpdateEvaluator.MAX_MANIFEST_AGE_MS - 1)),
            nowMs = now
        ) as UpdateDecision.Silent
        assertEquals(SilentReason.MANIFEST_STALE, stale.reason)
        // 正好 30 天以内仍允许阻断
        assertTrue(
            decide(
                13,
                valid(manifest(generatedAt = now - UpdateEvaluator.MAX_MANIFEST_AGE_MS + 1))
            ) is UpdateDecision.Forced
        )
    }

    @Test
    fun `far future generatedAt is ignored but small clock skew is tolerated`() {
        val future = decide(
            13,
            valid(manifest(generatedAt = now + UpdateEvaluator.FUTURE_TOLERANCE_MS + 1))
        ) as UpdateDecision.Silent
        assertEquals(SilentReason.MANIFEST_FROM_FUTURE, future.reason)
        assertTrue(decide(13, valid(manifest(generatedAt = now + MINUTE))) is UpdateDecision.Forced)
    }

    @Test
    fun `same generatedAt is announced only once`() {
        val shown = decide(13, valid(manifest()), lastShown = fresh) as UpdateDecision.Silent
        assertEquals(SilentReason.ALREADY_SHOWN, shown.reason)
        // 手动检查必须无视"已提示过"
        assertTrue(decide(13, valid(manifest()), lastShown = fresh, ignoreSilence = true) is UpdateDecision.Forced)
        // 换了新清单（generatedAt 不同）又要提示
        assertTrue(decide(13, valid(manifest(generatedAt = fresh + MINUTE)), lastShown = fresh) is UpdateDecision.Forced)
    }

    @Test
    fun `escape hatch silences the blocking layer until it expires`() {
        val skipped = decide(13, valid(manifest()), skipUntil = now + HOUR) as UpdateDecision.Silent
        assertEquals(SilentReason.SKIPPED, skipped.reason)
        assertTrue(decide(13, valid(manifest()), skipUntil = now - 1) is UpdateDecision.Forced)
        assertTrue(decide(13, valid(manifest()), skipUntil = now + HOUR, ignoreSilence = true) is UpdateDecision.Forced)
    }

    @Test
    fun `rollout percentage never weakens the safety floor`() {
        // 灰度 0% 也不能让"必须更新"的人群漏掉，否则底线形同虚设
        assertTrue(decide(13, valid(manifest(rollout = 0)), bucket = 0) is UpdateDecision.Forced)
    }

    // ---------- 两级可跳过提示 ----------

    @Test
    fun `below forceBelow but not below min gives skippable strong hint`() {
        val decision = decide(13, valid(manifest(minSupported = 10, forceBelow = 14)))
        assertTrue("应强提示，实际=$decision", decision is UpdateDecision.StrongHint)
    }

    @Test
    fun `strong hint respects rollout bucket`() {
        val m = manifest(minSupported = 10, forceBelow = 14, rollout = 30)
        assertTrue(decide(13, valid(m), bucket = 29) is UpdateDecision.StrongHint)
        val out = decide(13, valid(m), bucket = 30) as UpdateDecision.Silent
        assertEquals(SilentReason.ROLLOUT_OUT, out.reason)
    }

    @Test
    fun `optional update is offered within rollout and cooldown honoured`() {
        val m = manifest(latestCode = 14, minSupported = 1, forceBelow = 1, rollout = 100)
        assertTrue(decide(13, valid(m)) is UpdateDecision.Optional)
        val offBucket = decide(13, valid(m.copy(rolloutPercent = 10)), bucket = 10) as UpdateDecision.Silent
        assertEquals(SilentReason.ROLLOUT_OUT, offBucket.reason)
        val snoozed = decide(13, valid(m), skipUntil = now + MINUTE) as UpdateDecision.Silent
        assertEquals(SilentReason.SKIPPED, snoozed.reason)
    }

    @Test
    fun `newer than latest reports up to date`() {
        val decision = decide(20, valid(manifest(latestCode = 14, minSupported = 1, forceBelow = 1)))
        assertTrue(decision is UpdateDecision.UpToDate)
        assertEquals(14, (decision as UpdateDecision.UpToDate).offer.versionCode)
    }

    // ---------- 灰度桶 ----------

    @Test
    fun `inRollout boundaries`() {
        assertTrue(UpdateEvaluator.inRollout(0, 100))
        assertTrue(UpdateEvaluator.inRollout(99, 100))
        assertFalse(UpdateEvaluator.inRollout(0, 0))
        assertFalse("0% 灰度永不命中", UpdateEvaluator.inRollout(1, 0))
        assertTrue(UpdateEvaluator.inRollout(0, 1))
        assertFalse(UpdateEvaluator.inRollout(1, 1))
        assertFalse("桶号越界不应命中", UpdateEvaluator.inRollout(100, 100))
        assertFalse(UpdateEvaluator.inRollout(-1, 100))
        assertTrue("越界百分比要按 100 处理", UpdateEvaluator.inRollout(99, 500))
    }

    @Test
    fun `bucketOf stays inside 0 to 99 for any seed`() {
        val seeds = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 99L, 100L, 101L, -100L)
        val buckets = seeds.map { UpdateEvaluator.bucketOf(it) }
        buckets.forEach { assertTrue("桶号越界：$it", it in 0..99) }
        assertEquals(0, UpdateEvaluator.bucketOf(0L))
        assertEquals(99, UpdateEvaluator.bucketOf(99L))
        assertEquals(0, UpdateEvaluator.bucketOf(100L))
        assertEquals(0, UpdateEvaluator.bucketOf(-100L))
        assertEquals(99, UpdateEvaluator.bucketOf(-1L))
    }

    // ---------- 下载能力与 URL 规约 ----------

    @Test
    fun `offer exposes verifiable download only with real sha and apk target`() {
        val offer = UpdateOffer.from(manifest())
        assertTrue(offer.hasVerifiableDownload)
        assertEquals("https://gitee.com/a.apk", offer.apkTarget?.url)
        assertEquals("https://example.com/dl", offer.pageTarget?.url)
        assertEquals("https://gitee.com/a.apk", offer.copyableUrl)

        val placeholder = UpdateOffer.from(manifest(sha = "TO_FILL_64_HEX_LOWERCASE_SHA256_OF_RELEASE_APK"))
        assertFalse("占位 sha 不可用于安装", placeholder.hasVerifiableDownload)

        val pageOnly = UpdateOffer.from(manifest(targets = listOf(ApkTarget("页", "https://example.com/dl", ApkTargetKind.PAGE))))
        assertFalse("没有 APK 直链只能走下载页", pageOnly.hasVerifiableDownload)
        assertEquals("https://example.com/dl", pageOnly.copyableUrl)
    }

    @Test
    fun `manifest url is cache busted by version and one minute window`() {
        val base = "https://gitee.com/wuliao11541/PriceLens/raw/main/update.json"
        val url = UpdateSources.cacheBusted(base, 13, 1_800_000_000_000L)
        // t 是"分钟桶"（nowMs / 60000），与 Gitee CDN 的 60s max-age 同粒度
        assertEquals("$base?v=13&t=30000000", url)
        // 同一分钟桶内 URL 稳定（可复用连接），跨桶即绕开服务端缓存
        assertEquals(
            UpdateSources.cacheBusted(base, 13, 1_800_000_000_000L + 30_000L),
            UpdateSources.cacheBusted(base, 13, 1_800_000_000_000L)
        )
        assertTrue(
            UpdateSources.cacheBusted(base, 13, 1_800_000_000_000L + 61_000L) !=
                UpdateSources.cacheBusted(base, 13, 1_800_000_000_000L)
        )
        // 版本变了 URL 也变：老客户端不会命中新客户端留下的缓存
        assertTrue(UpdateSources.cacheBusted(base, 14, 1_800_000_000_000L) != url)
    }

    @Test
    fun `gitee is the primary manifest source`() {
        assertTrue(UpdateSources.giteeManifest.startsWith("https://gitee.com/"))
        assertTrue(UpdateSources.githubManifest.startsWith("https://raw.githubusercontent.com/"))
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val MINUTE = 60_000L
        private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private val DEFAULT_TARGETS = listOf(
            ApkTarget("Gitee 发行版", "https://gitee.com/a.apk", ApkTargetKind.APK),
            ApkTarget("手动下载页", "https://example.com/dl", ApkTargetKind.PAGE)
        )
    }
}
