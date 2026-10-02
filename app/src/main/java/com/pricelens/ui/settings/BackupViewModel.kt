package com.pricelens.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pricelens.data.backup.BackupRepository
import com.pricelens.data.backup.DavItem
import com.pricelens.data.backup.WebDavProbe
import com.pricelens.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页「备份与恢复」的状态机：进行中标记 + 最近一次结论 + 远端文件选择列表。
 *
 * 可判定逻辑全在 data 层（WebDavClient / BackupCodec / BackupMerge，纯函数或 JVM 可测），
 * 这里只做"进行中 / 有结论"的流转 —— 与 [CookieProbeViewModel] 同一惯例。
 * 失败原因（含 schema 不认识的拒绝理由）原样透传给 UI，不吞成"操作失败"。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repo: BackupRepository
) : ViewModel() {

    sealed interface Message {
        data class Tested(val probe: WebDavProbe) : Message
        data class BackupOk(val name: String) : Message
        data class RestoreOk(val favorites: Int, val targets: Int) : Message
        data class Exported(val favorites: Int, val targets: Int) : Message
        data class Imported(val favorites: Int, val targets: Int) : Message
        data class Failed(val reason: String) : Message
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    /** 非 null = 弹出"选一份远端备份"对话框（空列表也会弹，如实说"远端还没有备份"） */
    private val _remoteFiles = MutableStateFlow<List<DavItem>?>(null)
    val remoteFiles: StateFlow<List<DavItem>?> = _remoteFiles.asStateFlow()

    /** 保存凭证（密文进 SecretStore）；保存即清旧结论，避免"改了地址还挂着旧测试结果" */
    fun save(url: String, user: String, password: String) {
        settings.setWebdavUrl(url)
        settings.setWebdavUser(user)
        settings.setWebdavPassword(password)
        _message.value = null
    }

    /** 测试连接：用输入框当前值（未保存也能先试，跟「检测 Cookie」同一惯例） */
    fun testConnection(url: String, user: String, password: String) = launchBusy {
        repo.testConnection(url, user, password).fold(
            onSuccess = { probe -> _message.value = Message.Tested(probe) },
            onFailure = { error -> _message.value = failMessage(error) }
        )
    }

    /** 立即备份（不走每周 Worker，直接跑同一仓储方法） */
    fun backupNow() = launchBusy {
        repo.backupNow().fold(
            onSuccess = { name -> _message.value = Message.BackupOk(name) },
            onFailure = { error -> _message.value = failMessage(error) }
        )
    }

    fun openRestorePicker() = launchBusy {
        repo.listRemote().fold(
            onSuccess = { files -> _remoteFiles.value = files },
            onFailure = { error -> _message.value = failMessage(error) }
        )
    }

    fun dismissPicker() {
        _remoteFiles.value = null
    }

    fun restore(name: String) {
        _remoteFiles.value = null
        launchBusy {
            repo.restore(name).fold(
                onSuccess = { counts -> _message.value = Message.RestoreOk(counts.favorites, counts.targets) },
                onFailure = { error -> _message.value = failMessage(error) }
            )
        }
    }

    /** SAF 兜底 · 导出到本机（uri 来自 ACTION_CREATE_DOCUMENT） */
    fun exportSaf(uri: Uri) = launchBusy {
        repo.exportTo(uri).fold(
            onSuccess = { counts -> _message.value = Message.Exported(counts.favorites, counts.targets) },
            onFailure = { error -> _message.value = failMessage(error) }
        )
    }

    /** SAF 兜底 · 从本机导入（uri 来自 ACTION_OPEN_DOCUMENT） */
    fun importSaf(uri: Uri) = launchBusy {
        repo.importFrom(uri).fold(
            onSuccess = { counts -> _message.value = Message.Imported(counts.favorites, counts.targets) },
            onFailure = { error -> _message.value = failMessage(error) }
        )
    }

    /** 同屏只跑一个操作：进行中再点直接忽略（按钮也会置灰，双保险） */
    private fun launchBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun failMessage(error: Throwable): Message = Message.Failed(error.message ?: error.javaClass.simpleName)
}
