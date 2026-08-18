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
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.EscalationEntity
import com.yunjue.echo.mind.data.EscalationStatus

/**
 * ERA 13.1 §31 — Me → Support：真正的人工支持内容（订阅功能）。
 * 业务（请求/刷新）在 MeViewModel；本节只渲染清单与请求按钮。
 * 用户侧最小状态：未 ACK 前绝不显示「人工已收到」。
 */
@Composable
fun SupportSection(
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
                escalations.take(5).forEach { esc ->
                    EscalationStatusRow(esc)
                }
            }
        }
    }
}

/** 人工支持请求状态行（用户侧最小状态）。 */
@Composable
private fun EscalationStatusRow(esc: EscalationEntity) {
    // 状态字面量唯一事实源 = EscalationStatus 枚举（库内行以 *.name 落盘）。
    val statusText = when (esc.status) {
        EscalationStatus.QUEUED.name -> stringResource(R.string.esc_status_queued)
        EscalationStatus.DELIVERED.name -> stringResource(R.string.esc_status_delivered)
        EscalationStatus.ACKNOWLEDGED.name -> stringResource(R.string.esc_status_acknowledged)
        EscalationStatus.TAKEN_OVER.name -> stringResource(R.string.esc_status_taken_over)
        EscalationStatus.CLOSED.name -> stringResource(R.string.esc_status_closed)
        EscalationStatus.FAILED.name -> stringResource(R.string.esc_status_failed)
        else -> stringResource(R.string.esc_status_unknown)
    }
    Text("• $statusText", style = MaterialTheme.typography.bodyMedium)
}
