package com.yunjue.echo.mind.ui.me

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.me.SubscriptionEvent
import com.yunjue.echo.mind.me.SubscriptionUiState
import com.yunjue.echo.mind.model.subscriptionStatusText

/**
 * v3 §28 — Me → Subscription：订阅开通（可选付费能力，非门槛）。
 * 免费本地版 = 完整产品；订阅 = 云端同步备份 + 长周期分析 + 专业支持（ADR-020 最终边界）。
 *
 * ERA 33 状态提升：Section 只做 VM 收集 + 路由；纯渲染在 SubscriptionContent
 * （state-in / event-out），激活码验证/同步编排全部在 SubscriptionViewModel（§31 收口）。
 */
@Composable
fun SubscriptionSection(container: AppContainer) {
    val vm: SubscriptionViewModel = viewModel(factory = SubscriptionViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    SubscriptionContent(state = state, onEvent = vm::onEvent)
}

/** ERA 33 — Subscription 纯状态内容（state-in / event-out；无容器/Repository/SyncWorker）。 */
@Composable
fun SubscriptionContent(
    state: SubscriptionUiState,
    onEvent: (SubscriptionEvent) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("开通订阅（可选）", style = MaterialTheme.typography.titleMedium)
            if (!state.localMode) {
                val statusText = subscriptionStatusText(state.subscriptionExpiresAt)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.subscriptionExpired) {
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
                state.bindCode,
                { onEvent(SubscriptionEvent.UpdateBindCode(it)) },
                label = { Text("订阅激活码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            state.bindMessage?.let {
                // 2026-08-29 可用性自测（A1）：成功/失败信息用不同颜色渲染，
                // 用户一眼可辨开通是否成功（此前一律 primary 色）。
                Text(
                    it,
                    color = if (state.bindError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { onEvent(SubscriptionEvent.Bind) },
                enabled = state.bindCode.isNotBlank() && !state.binding,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.binding) "正在验证…" else "开通订阅")
            }
        }
    }
}
