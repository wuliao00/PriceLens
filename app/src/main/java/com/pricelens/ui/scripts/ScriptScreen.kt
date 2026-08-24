package com.pricelens.ui.scripts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pricelens.ui.components.PriceCard
import com.pricelens.ui.theme.Dims
import com.pricelens.util.ScriptStore
import com.pricelens.util.ShizukuHelper

/**
 * 自定义脚本页：经 Shizuku（ADB/shell 权限）执行用户脚本。
 * - 预置 3 个安全脚本（不可删除）
 * - 自定义脚本可新建 / 编辑 / 删除，本地持久化
 * - 运行输出实时展示在卡片下方
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var scripts by remember { mutableStateOf(ScriptStore.builtins + ScriptStore.loadCustom(context)) }
    var editing by remember { mutableStateOf<ScriptStore.Script?>(null) }
    var creating by remember { mutableStateOf(false) }
    var runningId by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) } // id to output
    val shizukuState by ShizukuHelper.status.collectAsStateWithLifecycle()
    val shizukuReady = shizukuState == ShizukuHelper.ShizukuState.READY

    fun reload() {
        scripts = ScriptStore.builtins + ScriptStore.loadCustom(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义脚本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建脚本")
            }
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(horizontal = Dims.SpacingL)) {
            Text(
                if (shizukuReady) "Shizuku 已就绪，脚本将以 ADB（shell）权限执行，请只运行你信任的内容。"
                else "需要 Shizuku 已启动并授权后才能运行脚本（设置 → Shizuku 一键授权）。",
                style = MaterialTheme.typography.bodySmall,
                color = if (shizukuReady) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = Dims.SpacingS)
            )

            LazyColumn(Modifier.weight(1f)) {
                items(scripts, key = { it.id }) { script ->
                    ScriptCard(
                        script = script,
                        running = runningId == script.id,
                        enabled = shizukuReady,
                        onRun = {
                            runningId = script.id
                            output = script.id to ""
                            ShizukuHelper.runCustomScript(context, script.content) { ok, out ->
                                runningId = null
                                output = script.id to buildString {
                                    append(if (ok) "" else "[执行失败] ")
                                    append(out.ifEmpty { if (ok) "(无输出)" else "" })
                                }
                                reload()
                            }
                        },
                        onEdit = { editing = script },
                        onDelete = if (script.builtin) null else {
                            { ScriptStore.remove(context, script.id); reload() }
                        }
                    )
                    output?.let { (id, out) ->
                        if (id == script.id && out.isNotEmpty()) {
                            Text(
                                out,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dims.SpacingL)
                            )
                        }
                    }
                    Spacer(Modifier.height(Dims.SpacingS))
                }
            }
        }
    }

    if (creating) {
        ScriptEditor(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, content ->
                ScriptStore.add(context, name, content)
                creating = false
                reload()
            }
        )
    }
    editing?.let { script ->
        ScriptEditor(
            initial = script,
            onDismiss = { editing = null },
            onSave = { name, content ->
                ScriptStore.update(context, script.id, name, content)
                editing = null
                reload()
            }
        )
    }
}

@Composable
private fun ScriptCard(
    script: ScriptStore.Script,
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?
) {
    PriceCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    script.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRun, enabled = enabled && !running) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp).height(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "运行",
                            tint = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                script.content.lineSequence().take(2).joinToString("\n") +
                    if (script.content.lines().size > 2) " …" else "",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ScriptEditor(
    initial: ScriptStore.Script?,
    onDismiss: () -> Unit,
    onSave: (name: String, content: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建脚本" else "编辑脚本") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("脚本名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Dims.SpacingM))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Shell 脚本（以 ADB 权限执行）") },
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, content) }, enabled = content.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
