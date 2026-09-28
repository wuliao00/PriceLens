package com.pricelens.update

/**
 * 更新判定（纯函数，可单测）。
 *
 * 对标 Mihon `GetApplicationRelease.kt`：**服务失败一律按"无更新"静默处理（fail-open）**，
 * 绝不允许"清单拉不到 / 解析失败"变成"用户被锁在应用外"。
 * 只有同时满足下列全部条件，才允许出现**阻断式**弹窗：
 *  1. HTTP 200 且拿到响应体（由调用方保证，Rejected 直接走静默分支）
 *  2. JSON 解析成功
 *  3. `schemaVersion` 已知
 *  4. `sha256` 非空（无可校验产物 = 不能保证"升级后能装上"，不配阻断用户）
 *  5. `generatedAt` 距今 < [MAX_MANIFEST_AGE_MS]（防止陈旧/弃更仓库永久锁死安装量）
 *  6. `currentVersionCode < minSupportedVersionCode`
 *
 * 任一不满足 → 静默（[UpdateDecision.Silent]）。
 * 额外两个**只用于收窄弹窗**（不放宽）的闸门：同一 `generatedAt` 只提示一次、
 * 逃生口/以后再说的静默期未到。
 */
object UpdateEvaluator {

    /** 清单新鲜度上限：超过 30 天视为陈旧 → 不据此阻断 */
    const val MAX_MANIFEST_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** 时钟超前容差：generatedAt 比本机时间晚超过 24h → 视为异常清单，静默 */
    const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000

    /** "我已升级仍提示我"逃生口的静默时长 */
    const val ESCAPE_HATCH_MS = 24L * 60 * 60 * 1000

    /**
     * @param currentVersionCode 当前安装包 versionCode —— **参数传入**，不读 BuildConfig，便于单测
     * @param installBucket 首启生成后永久固定的灰度桶（0..99）
     * @param lastAcceptedGeneratedAtMs 上次已经提示过的清单 generatedAt（同一次发布只提示一次）
     * @param skipUntilMs 静默截止时间（逃生口 / 以后再说）
     * @param ignoreSilence 手动"检查更新"时忽略静默与"已提示过"
     */
    fun evaluate(
        currentVersionCode: Int,
        result: ManifestResult,
        nowMs: Long,
        installBucket: Int,
        lastAcceptedGeneratedAtMs: Long = 0L,
        skipUntilMs: Long = 0L,
        ignoreSilence: Boolean = false
    ): UpdateDecision {
        if (result !is ManifestResult.Valid) {
            val reason = (result as? ManifestResult.Rejected)?.reason ?: RejectReason.JSON_UNPARSEABLE
            return UpdateDecision.Silent(SilentReason.MANIFEST_REJECTED + ":" + reason)
        }
        val manifest = result.manifest

        // 闸门 4：sha256 缺失或不是合法十六进制摘要（例如发布前留的占位符）
        // → 清单不足以支撑一次可信安装，按注释口径一律不阻断（fail-open）。
        if (!UpdateManifest.isSha256Hex(manifest.sha256)) {
            return UpdateDecision.Silent(SilentReason.SHA_MISSING)
        }
        // 闸门 5：新鲜度
        val age = nowMs - manifest.generatedAtMs
        if (age < -FUTURE_TOLERANCE_MS) {
            return UpdateDecision.Silent(SilentReason.MANIFEST_FROM_FUTURE)
        }
        if (age > MAX_MANIFEST_AGE_MS) {
            return UpdateDecision.Silent(SilentReason.MANIFEST_STALE)
        }

        val offer = UpdateOffer.from(manifest)
        val alreadyShown = !ignoreSilence && manifest.generatedAtMs == lastAcceptedGeneratedAtMs
        val silenced = !ignoreSilence && skipUntilMs > nowMs

        // 1) 唯一阻断层：低于 minSupportedVersionCode。
        //    灰度百分比**不作用于该层**——安全底线不该被桶号绕过，但"只提示一次"和逃生口仍然生效。
        if (currentVersionCode < manifest.minSupportedVersionCode) {
            if (alreadyShown) return UpdateDecision.Silent(SilentReason.ALREADY_SHOWN)
            if (silenced) return UpdateDecision.Silent(SilentReason.SKIPPED)
            return UpdateDecision.Forced(offer)
        }

        // 2) 次级强提示：低于 forceBelow，可跳过
        if (currentVersionCode < manifest.forceBelow) {
            if (alreadyShown) return UpdateDecision.Silent(SilentReason.ALREADY_SHOWN)
            if (silenced) return UpdateDecision.Silent(SilentReason.SKIPPED)
            if (!inRollout(installBucket, manifest.rolloutPercent)) {
                return UpdateDecision.Silent(SilentReason.ROLLOUT_OUT)
            }
            return UpdateDecision.StrongHint(offer)
        }

        // 3) 常规可选更新：受灰度控制
        if (currentVersionCode < manifest.latest.versionCode) {
            if (silenced) return UpdateDecision.Silent(SilentReason.SKIPPED)
            if (alreadyShown) return UpdateDecision.Silent(SilentReason.ALREADY_SHOWN)
            if (!inRollout(installBucket, manifest.rolloutPercent)) {
                return UpdateDecision.Silent(SilentReason.ROLLOUT_OUT)
            }
            return UpdateDecision.Optional(offer)
        }

        return UpdateDecision.UpToDate(offer)
    }

    /** 灰度命中判定：桶号严格小于百分比即命中（percent=100 全量、percent=0 全不命中） */
    fun inRollout(installBucket: Int, rolloutPercent: Int): Boolean = installBucket in 0 until rolloutPercent.coerceIn(0, ROLLOUT_MAX)

    /** 灰度桶生成：首启调用一次后永久固定（避免"今天提示明天不提示"的抖动） */
    fun bucketOf(seed: Long): Int {
        val positive = ((seed % ROLLOUT_MAX) + ROLLOUT_MAX) % ROLLOUT_MAX
        return positive.toInt()
    }

    const val ROLLOUT_MAX = 100
}

/** 给用户看的更新要约（清单的子集，避免 UI 直接依赖解析层） */
data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val releaseDate: String,
    val generatedAtMs: Long,
    val notes: List<String>,
    val apkUrls: List<ApkTarget>,
    val sha256: String,
    val sizeBytes: Long,
    val cooldownHours: Int
) {

    /** 应用内可下载的直链（按清单顺序，第一个为准，失败由 ApkInstaller 逐级降级） */
    val apkTarget: ApkTarget? = apkUrls.firstOrNull { it.kind == ApkTargetKind.APK }

    /** 人工兜底下载页 */
    val pageTarget: ApkTarget? = apkUrls.firstOrNull { it.kind == ApkTargetKind.PAGE }

    /**
     * 能否在应用内安全下载：既要有一条 apk 直链，也要有形如 64 位十六进制的 sha256。
     * 清单里 sha256 写成占位符时，UI 自动退化为"打开下载页 / 复制链接"，不做不可校验的安装。
     */
    val hasVerifiableDownload: Boolean = apkTarget != null && UpdateManifest.isSha256Hex(sha256)

    /** "复制下载链接"优先复制 APK 直链，没有则复制下载页 */
    val copyableUrl: String? = apkUrls.firstOrNull()?.url

    companion object {
        fun from(manifest: UpdateManifest): UpdateOffer = UpdateOffer(
            versionCode = manifest.latest.versionCode,
            versionName = manifest.latest.versionName,
            releaseDate = manifest.latest.releaseDate,
            generatedAtMs = manifest.generatedAtMs,
            notes = manifest.notes,
            apkUrls = manifest.apkUrls,
            sha256 = manifest.sha256,
            sizeBytes = manifest.sizeBytes,
            cooldownHours = manifest.cooldownHours
        )
    }
}

/** 判定结果（[Silent] 携带机器可读原因，便于日志与单测断言） */
sealed interface UpdateDecision {
    /** 什么都不弹（含全部 fail-open 分支） */
    data class Silent(val reason: String) : UpdateDecision

    /** 阻断式：只有"立即更新 / 复制下载链接 / 我已升级仍提示我" */
    data class Forced(val offer: UpdateOffer) : UpdateDecision

    /** 强提示但可跳过 */
    data class StrongHint(val offer: UpdateOffer) : UpdateDecision

    /** 常规可选更新 */
    data class Optional(val offer: UpdateOffer) : UpdateDecision

    /** 已是最新（仅手动检查时展示） */
    data class UpToDate(val offer: UpdateOffer) : UpdateDecision
}

/** 静默原因码（不展示给用户） */
object SilentReason {
    const val MANIFEST_REJECTED = "manifest_rejected"
    const val SHA_MISSING = "sha256_missing"
    const val MANIFEST_STALE = "manifest_stale"
    const val MANIFEST_FROM_FUTURE = "manifest_from_future"
    const val ALREADY_SHOWN = "already_shown"
    const val SKIPPED = "skipped"
    const val ROLLOUT_OUT = "rollout_out"
}
