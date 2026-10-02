package com.pricelens.ui.onboarding

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.ui.settings.ManmanbuyLoginActivity
import com.pricelens.ui.theme.Dims

/**
 * 第 4 步（可选）：慢慢买 Cookie / 星罗好货 apikey。
 * 明示"可跳过、只影响历史价格完整度"，不填也能进主界面；
 * 填了也只是在本机保存（与设置页共用同一存储位，不新增上传链路）。
 */
class OptionalCredsStep(private val settings: SettingsRepository) : OnboardingStep {

    override val key = "credentials"

    private val apiKey = mutableStateOf(settings.linkstarsApiKey)
    private val cookie = mutableStateOf(settings.manmanbuyCookie)

    @Composable
    override fun Content() {
        val context = LocalContext.current

        // 从内置登录页返回时自动回填抓到的 Cookie（与设置页行为一致）
        val lifecycleOwner = LocalLifecycleOwner.current
        var lastStored by remember { mutableStateOf(settings.manmanbuyCookie) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val fetched = settings.manmanbuyCookie
                    if (fetched != lastStored && fetched.isNotBlank()) {
                        cookie.value = fetched
                        lastStored = fetched
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        StepScaffold(
            title = stringResource(R.string.onboarding_creds_title),
            desc = stringResource(R.string.onboarding_creds_desc)
        ) {
            OutlinedTextField(
                value = cookie.value,
                onValueChange = { cookie.value = it },
                label = { Text(stringResource(R.string.onboarding_creds_mmb)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dims.TextAreaTall)
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, ManmanbuyLoginActivity::class.java))
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.onboarding_creds_login))
            }
            OutlinedTextField(
                value = apiKey.value,
                onValueChange = { apiKey.value = it },
                label = { Text(stringResource(R.string.onboarding_creds_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Dims.SpacingXS))
            Column {
                Text(
                    stringResource(R.string.onboarding_creds_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.onboarding_permission_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /** 末步确认时落盘（不新增权限，只写本机 SharedPreferences 的既有键位） */
    override fun onConfirm() {
        settings.setLinkstarsApiKey(apiKey.value)
        settings.setManmanbuyCookie(cookie.value)
    }
}
