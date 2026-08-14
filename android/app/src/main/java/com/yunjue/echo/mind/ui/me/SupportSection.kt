package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.EscalationEntity

/**
 * v3 §28/§69 — Me → Support：真正的人工支持内容（订阅功能）。
 * 其余旧 SupportScreen 内容已迁至各自子领域（Data & Sensing / Presence / Intelligence / Memory）。
 * 用户侧最小状态：未 ACK 前绝不显示「人工已收到」。
 */
@Composable
fun SupportSection(
    container: AppContainer,
    escalations: List<EscalationEntity>,
    onRequestSupport: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.support_request_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.support_request_hint))
            Button(onClick = onRequestSupport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.support_request_button))
            }
            if (escalations.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.support_recent_requests), style = MaterialTheme.typography.titleSmall)
                escalations.take(3).forEach { esc ->
                    EscalationStatusRow(esc)
                }
            }
        }
    }
}

/** 人工支持请求状态行（用户侧最小状态）。 */
@Composable
private fun EscalationStatusRow(esc: EscalationEntity) {
    val statusText = when (esc.status) {
        "QUEUED" -> stringResource(R.string.esc_status_queued)
        "DELIVERED" -> stringResource(R.string.esc_status_delivered)
        "ACKNOWLEDGED" -> stringResource(R.string.esc_status_acknowledged)
        "TAKEN_OVER" -> stringResource(R.string.esc_status_taken_over)
        "CLOSED" -> stringResource(R.string.esc_status_closed)
        "FAILED" -> stringResource(R.string.esc_status_failed)
        else -> stringResource(R.string.esc_status_unknown)
    }
    Text("• $statusText", style = MaterialTheme.typography.bodyMedium)
}
