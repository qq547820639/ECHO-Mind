package com.yunjue.echo.mind.ui.me

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.OnboardingVerifyException
import com.yunjue.echo.mind.model.subscriptionStatusText
import kotlinx.coroutines.launch

/**
 * v3 §28 — Me → Subscription：订阅开通（可选付费能力，非门槛）。
 * 免费本地版 = 完整产品；订阅 = 云端同步备份 + 长周期分析 + 专业支持（ADR-020 最终边界）。
 */
@Composable
fun SubscriptionSection(container: AppContainer, context: Context) {
    val scope = rememberCoroutineScope()
    var bindCode by remember { mutableStateOf("") }
    var binding by remember { mutableStateOf(false) }
    var bindMessage by remember { mutableStateOf<String?>(null) }
    fun bindInstitution() {
        val code = bindCode.trim()
        if (code.length < 8) {
            bindMessage = "激活码格式不正确，请检查后重试。"
            return
        }
        binding = true
        bindMessage = null
        scope.launch {
            try {
                val res = container.onboardingRepository.verifyOnboardingCode(code)
                binding = false
                if (res.restricted) {
                    bindMessage = "该激活码已受限，请联系客服。"
                } else {
                    bindMessage = "订阅已开通。云端同步与专业支持现在可用。"
                    bindCode = ""
                    runCatching { container.featureFlagRepository.fetchFeatureFlags() }
                    SyncWorker.enqueue(context)
                }
            } catch (e: Exception) {
                binding = false
                bindMessage = when ((e as? OnboardingVerifyException)?.reason) {
                    "invalid_code" -> "激活码无效，请检查后重试，或联系客服获取订阅激活码。"
                    "restricted" -> "该激活码已受限，请联系客服。"
                    else -> "暂时无法验证激活信息，请检查网络后重试。"
                }
            }
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("开通订阅（可选）", style = MaterialTheme.typography.titleMedium)
            if (!container.preferences.localMode) {
                val statusText = subscriptionStatusText(container.preferences.subscriptionExpiresAt)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (container.preferences.subscriptionExpired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            } else {
                Text(
                    "默认本地使用，数据只保存在本机。订阅后可获得云端同步备份、长周期分析与专业支持，输入订阅激活码完成开通。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedTextField(
                bindCode,
                { bindCode = it },
                label = { Text("订阅激活码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            bindMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { bindInstitution() },
                enabled = bindCode.isNotBlank() && !binding,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (binding) "正在验证…" else "开通订阅")
            }
        }
    }
}
