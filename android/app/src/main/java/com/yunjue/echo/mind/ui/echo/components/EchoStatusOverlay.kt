package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.model.sensingRuntimeStatusText

/**
 * v3 §9 — EchoStatusOverlay：感知六态的可信呈现（非 ACTIVE 才可见，非工程噪音）。
 * §AC：AI provider 提示分支已移除（home 唯一 transient = wallpaper pill）。
 */
@Composable
fun EchoStatusOverlay(
    sensing: SensingRuntimeStatus,
    onGoToMe: () -> Unit,
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
}
