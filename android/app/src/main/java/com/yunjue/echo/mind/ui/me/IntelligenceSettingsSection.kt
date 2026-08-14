package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.intelligence.ProviderConfigDraft
import com.yunjue.echo.mind.intelligence.ProviderCredentialStore
import com.yunjue.echo.mind.intelligence.ProviderStatus
import com.yunjue.echo.mind.intelligence.ProviderType
import com.yunjue.echo.mind.intelligence.normalizeBaseUrl
import com.yunjue.echo.mind.intelligence.overall
import com.yunjue.echo.mind.intelligence.providerStatusText
import com.yunjue.echo.mind.intelligence.testConnectionDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v3 §28/§29 — Me → Intelligence：AI Provider 独立页面。
 *
 * Current provider / Model / Status / 测试连接（四步）/ 更换 Provider / 断开连接。
 * API Key 仅加密保存在本机；「使用自定义 AI 服务」数据发送提示必须可见。
 */
@Composable
fun IntelligenceSettingsSection(container: AppContainer, scope: CoroutineScope) {
    val storedConfig = remember { container.aiProviderManager.stored() }
    var baseUrl by remember { mutableStateOf(storedConfig?.baseUrl ?: "") }
    var modelName by remember { mutableStateOf(storedConfig?.model ?: "") }
    var apiKey by remember { mutableStateOf(storedConfig?.apiKey ?: "") }
    var aiStatus by remember { mutableStateOf<ProviderStatus?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var changeExpanded by remember { mutableStateOf(false) }
    var testDetail by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI Intelligence", style = MaterialTheme.typography.titleMedium)
            Text(
                "模型不是 ECHO。连接你自己的 AI（OpenAI 兼容服务、自建网关或局域网端点均可），" +
                    "ECHO 的记忆与人格不会因换模型而改变。API Key 仅加密保存在本机，不会上传。",
                style = MaterialTheme.typography.bodySmall
            )
            val configured = container.aiProviderManager.stored()
            if (configured != null) {
                Text("Current provider：OpenAI Compatible", style = MaterialTheme.typography.titleSmall)
                Text("Model：${configured.model}", style = MaterialTheme.typography.bodySmall)
                Text("Base URL：${configured.baseUrl}", style = MaterialTheme.typography.bodySmall)
            }
            val currentStatus = aiStatus ?: if (storedConfig != null) ProviderStatus.READY else null
            if (currentStatus != null) {
                Text(
                    "Status：${providerStatusText(currentStatus)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (currentStatus == ProviderStatus.READY)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            testDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            aiBusy = true
                            aiStatus = ProviderStatus.VALIDATING
                            val result = container.aiProviderManager.testConnection(
                                if (changeExpanded) ProviderConfigDraft(
                                    providerType = ProviderType.OPENAI_COMPATIBLE,
                                    baseUrl = baseUrl,
                                    model = modelName,
                                    apiKey = apiKey,
                                ) else null
                            )
                            aiStatus = result.overall
                            testDetail = testConnectionDetail(result)
                            aiBusy = false
                        }
                    },
                    enabled = !aiBusy
                ) { Text(if (aiBusy) "正在测试…" else "测试连接") }
                OutlinedButton(onClick = { changeExpanded = !changeExpanded }) {
                    Text(if (changeExpanded) "收起设置" else "更换 Provider")
                }
                if (container.aiProviderManager.hasProvider()) {
                    OutlinedButton(onClick = {
                        container.aiProviderManager.clear()
                        apiKey = ""
                        aiStatus = ProviderStatus.NOT_CONFIGURED
                        testDetail = null
                    }) { Text("断开连接") }
                }
            }
            if (changeExpanded) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL（如 https://api.openai.com）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            aiBusy = true
                            aiStatus = ProviderStatus.VALIDATING
                            val health = container.aiProviderManager.validate(
                                ProviderConfigDraft(
                                    providerType = ProviderType.OPENAI_COMPATIBLE,
                                    baseUrl = baseUrl,
                                    model = modelName,
                                    apiKey = apiKey,
                                )
                            )
                            aiStatus = health.status
                            if (health.status == ProviderStatus.READY) {
                                container.aiProviderManager.save(
                                    ProviderCredentialStore.Stored(
                                        type = ProviderType.OPENAI_COMPATIBLE,
                                        displayName = "My AI",
                                        baseUrl = normalizeBaseUrl(baseUrl),
                                        model = modelName.trim(),
                                        apiKey = apiKey.trim(),
                                    )
                                )
                            }
                            aiBusy = false
                            changeExpanded = false
                        }
                    },
                    enabled = !aiBusy
                ) { Text(if (aiBusy) "正在验证…" else "保存并连接") }
            }
            Text(
                "使用自定义 AI 服务时，ECHO 为完成请求而选择的数据会发送给该服务商，其数据处理规则由该服务商决定。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
