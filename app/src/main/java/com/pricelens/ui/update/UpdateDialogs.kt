package com.pricelens.ui.update

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pricelens.R
import com.pricelens.ui.theme.Dims
import com.pricelens.update.ApkInstaller
import com.pricelens.update.DownloadState
import com.pricelens.update.UpdateOffer
import com.pricelens.update.UpdateRepository

/**
 * 更新弹窗（对标 XUpdate / azhon AppUpdate 的三层交互，形态自研不引库）。
 *
 *  - [ForcedUpdateDialog]：**唯一阻断层**。只有「立即更新 / 复制下载链接」，
 *    外加一个「我已升级仍提示我」逃生口（本地静默 24h）——
 *    没有它，用户手动升级后会被永久锁在门外。
 *  - [SkippableUpdateDialog]：强提示与可选更新共用，可跳过 +「以后再说」（清单 cooldownHours）。
 *
 * 所有弹窗都预告知厂商 ROM 的「风险应用/纯净模式」拦截属正常现象（真正的安装失败源不在 AOSP）。
 */

@Composable
fun ForcedUpdateDialog(offer: UpdateOffer, download: DownloadState, repository: UpdateRepository) {
    AlertDialog(
        onDismissRequest = { /* 阻断层：点外部不关闭，只能选一个动作 */ },
        title = { Text(stringResource(R.string.update_forced_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.update_forced_body, offer.versionName),
                    style = MaterialTheme.typography.bodyMedium
                )
                NotesBlock(offer)
                DownloadBlock(offer, download, repository)
                RomRiskHint()
                Spacer(Modifier.height(Dims.SpacingS))
                TextButton(
                    onClick = { repository.acknowledgeUpgradeStillPrompting() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.update_escape_still_prompting))
                }
                Text(
                    stringResource(R.string.update_escape_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            PrimaryActionButton(offer, download, repository)
        },
        dismissButton = {
            CopyLinkButton(offer, repository)
        }
    )
}

/**
 * 可跳过的两级提示：[strong] 为 true 时是"低于 forceBelow"的强提示，
 * 否则是常规可选更新。两者都给「以后再说」，按清单 cooldownHours 静默；
 * 点弹窗外部等价于「以后再说」（不做"假装关闭实则还在"的假动作）。
 */
@Composable
fun SkippableUpdateDialog(offer: UpdateOffer, strong: Boolean, download: DownloadState, repository: UpdateRepository) {
    AlertDialog(
        onDismissRequest = { repository.snooze(offer.cooldownHours) },
        title = {
            Text(
                if (strong) {
                    stringResource(R.string.update_strong_title, offer.versionName)
                } else {
                    stringResource(R.string.update_optional_title, offer.versionName)
                }
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (strong) {
                    Text(
                        stringResource(R.string.update_strong_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                NotesBlock(offer)
                DownloadBlock(offer, download, repository)
                RomRiskHint()
                Text(
                    stringResource(R.string.update_cooldown_hint, offer.cooldownHours),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            PrimaryActionButton(
                offer = offer,
                download = download,
                repository = repository
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Dims.SpacingXS)) {
                CopyLinkButton(offer, repository)
                TextButton(onClick = { repository.snooze(offer.cooldownHours) }) {
                    Text(stringResource(R.string.update_action_later))
                }
            }
        }
    )
}

/** 更新说明（对应清单 notes 数组，逐条列出，对应 XUpdate updateContent） */
@Composable
private fun NotesBlock(offer: UpdateOffer) {
    if (offer.notes.isEmpty()) return
    Spacer(Modifier.height(Dims.SpacingM))
    Text(
        stringResource(R.string.update_notes_title),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(Dims.SpacingXS))
    offer.notes.forEach { note ->
        Text(
            "· $note",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = Dims.SpacingXS)
        )
    }
}

/** 下载/安装状态区：进度、失败原因、未知来源授权引导 */
@Composable
private fun DownloadBlock(offer: UpdateOffer, download: DownloadState, repository: UpdateRepository) {
    Spacer(Modifier.height(Dims.SpacingM))
    when (download) {
        is DownloadState.Downloading -> {
            val total = if (download.totalBytes > 0L) download.totalBytes else offer.sizeBytes
            LinearProgressIndicator(
                progress = { if (total > 0L) (download.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(
                    R.string.update_downloading,
                    offer.versionName,
                    formatBytes(download.downloadedBytes),
                    if (total > 0L) formatBytes(total) else "?"
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Dims.SpacingXS)
            )
        }
        DownloadState.NeedsInstallPermission -> {
            Text(
                stringResource(R.string.update_need_unknown_source_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                stringResource(R.string.update_need_unknown_source_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is DownloadState.Failed -> {
            Text(
                stringResource(R.string.update_download_failed, download.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                if (download.failCount >= ApkInstaller.MAX_CONSECUTIVE_FAILURES) {
                    stringResource(R.string.update_degraded_hint, download.failCount)
                } else {
                    stringResource(R.string.update_download_failed_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DownloadState.Unverifiable -> Text(
            stringResource(R.string.update_unverifiable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is DownloadState.Launched -> Text(
            stringResource(R.string.update_installer_launched),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        else -> {}
    }
}

/** 主按钮：可信直链 → 应用内下载；否则 → 打开下载页；已下载完成 → 继续安装 */
@Composable
private fun PrimaryActionButton(offer: UpdateOffer, download: DownloadState, repository: UpdateRepository) {
    val busy = download is DownloadState.Downloading
    val installReady = download is DownloadState.Ready || download is DownloadState.NeedsInstallPermission
    val label = when {
        installReady -> stringResource(R.string.update_action_resume_install)
        !offer.hasVerifiableDownload -> stringResource(R.string.update_action_open_page)
        else -> stringResource(R.string.update_action_now)
    }
    Button(
        enabled = !busy,
        shape = MaterialTheme.shapes.small,
        onClick = {
            when {
                installReady -> repository.resumePendingInstall()
                !offer.hasVerifiableDownload -> repository.openDownloadPage(offer)
                else -> repository.startDownload(offer)
            }
        }
    ) {
        Text(label)
    }
}

@Composable
private fun CopyLinkButton(offer: UpdateOffer, repository: UpdateRepository) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        val url = repository.copyableUrl(offer)
        if (url != null) {
            copyToClipboard(context, url)
            copied = true
        } else {
            repository.openDownloadPage(offer)
        }
    }) {
        Text(
            if (copied) {
                stringResource(R.string.update_copied)
            } else {
                stringResource(R.string.update_action_copy_link)
            }
        )
    }
}

/** 厂商 ROM 拦截预告知：真正的安装失败源通常不是 AOSP，而是"纯净模式/风险应用" */
@Composable
private fun RomRiskHint() {
    Spacer(Modifier.height(Dims.SpacingM))
    Text(
        stringResource(R.string.update_rom_risk_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .heightIn(max = 96.dp)
            .padding(bottom = Dims.SpacingS)
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("PriceLens", text))
}

/** 体积友好显示（不引第三方格式化库） */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
