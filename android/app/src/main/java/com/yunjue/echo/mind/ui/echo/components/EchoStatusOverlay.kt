package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import com.yunjue.echo.mind.sensing.sensingRuntimeStatusText

/**
 * v3 §9 — EchoStatusOverlay：感知六态的可信呈现（非 ACTIVE 才可见，非工程噪音）。
 * v2 §35：初次 AI 非阻塞提示（未配置 + 未 dismiss → 轻量卡片）。
 */
@Composable
fun EchoStatusOverlay(
    sensing: SensingRuntimeStatus,
    intelligenceAvailable: Boolean,
    aiPromptDismissed: Boolean,
    onGoToMe: () -> Unit,
    onDismissAiPrompt: () -> Unit,
) {
    when (sensing) {
        SensingRuntimeStatus.STARTING -> Text(
            sensingRuntimeStatusText(SensingRuntimeStatus.STARTING),
            style = MaterialTheme.typography.bodySmall,
        )
        SensingRuntimeStatus.SYSTEM_PAUSED -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                sensingRuntimeStatusText(SensingRuntimeStatus.SYSTEM_PAUSED),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onGoToMe) { Text("查看原因") }
        }
        else -> Unit
    }
    if (!intelligenceAvailable && !aiPromptDismissed) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("连接一个 AI，让 ECHO 更深入地理解你的变化。", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGoToMe) { Text("连接 AI") }
                    OutlinedButton(onClick = onDismissAiPrompt) { Text("以后再说") }
                }
            }
        }
    }
}
