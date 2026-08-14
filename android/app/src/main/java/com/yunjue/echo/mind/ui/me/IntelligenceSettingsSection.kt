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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.me.IntelligenceSettingsEvent

/**
 * ERA 13.1 §34 — Me → Intelligence：AI Provider 独立页面。
 *
 * Current provider / Model / Status / 测试连接（四步）/ 更换 Provider / 断开连接。
 * 业务全部在 IntelligenceSettingsViewModel；本节只渲染。
 */
@Composable
fun IntelligenceSettingsSection(container: AppContainer) {
    val vm: IntelligenceSettingsViewModel = viewModel(factory = IntelligenceSettingsViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI Intelligence", style = MaterialTheme.typography.titleMedium)
            Text(
                "模型不是 ECHO。连接你自己的 AI（OpenAI 兼容服务、自建网关或局域网端点均可），" +
                    "ECHO 的记忆与人格不会因换模型而改变。API Key 仅加密保存在本机，不会上传。",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.providerConfigured && state.model != null) {
                Text("Current provider：OpenAI Compatible", style = MaterialTheme.typography.titleSmall)
                Text("Model：${state.model}", style = MaterialTheme.typography.bodySmall)
                Text("Base URL：${state.baseUrl}", style = MaterialTheme.typography.bodySmall)
            }
            state.statusText?.let {
                Text(
                    "Status：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status == com.yunjue.echo.mind.intelligence.ProviderStatus.READY)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            state.testDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.onEvent(IntelligenceSettingsEvent.TestConnection) },
                    enabled = !state.busy
                ) { Text(if (state.busy) "正在测试…" else "测试连接") }
                OutlinedButton(onClick = { vm.onEvent(IntelligenceSettingsEvent.ToggleChangeExpanded) }) {
                    Text(if (state.changeExpanded) "收起设置" else "更换 Provider")
                }
                if (state.providerConfigured) {
                    OutlinedButton(onClick = { vm.onEvent(IntelligenceSettingsEvent.Disconnect) }) {
                        Text("断开连接")
                    }
                }
            }
            if (state.changeExpanded) {
                OutlinedTextField(
                    value = state.draftBaseUrl,
                    onValueChange = { vm.onEvent(IntelligenceSettingsEvent.UpdateDraftBaseUrl(it)) },
                    label = { Text("Base URL（如 https://api.openai.com）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.draftModel,
                    onValueChange = { vm.onEvent(IntelligenceSettingsEvent.UpdateDraftModel(it)) },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.draftApiKey,
                    onValueChange = { vm.onEvent(IntelligenceSettingsEvent.UpdateDraftApiKey(it)) },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.onEvent(IntelligenceSettingsEvent.SaveAndConnect) },
                    enabled = !state.busy
                ) { Text(if (state.busy) "正在验证…" else "保存并连接") }
            }
            Text(
                "使用自定义 AI 服务时，ECHO 为完成请求而选择的数据会发送给该服务商，其数据处理规则由该服务商决定。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
