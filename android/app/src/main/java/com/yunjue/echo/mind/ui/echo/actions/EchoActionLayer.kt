package com.yunjue.echo.mind.ui.echo.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.actions.EchoActionAvailability
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.ui.SkillSessionCoordinator
import com.yunjue.echo.mind.ui.echo.components.SkillListSection

/**
 * v3 §9/§18/§19 — EchoActionLayer：想做点什么。
 * 基础行动（呼吸/暂停/什么也不做）由 [com.yunjue.echo.mind.actions.EchoActionRuntime] 执行，
 * Scene 内统一视觉；订阅能力在下方分区展开，不与免费行动混排。
 *
 * ERA 38 状态提升：Section 只做装配（订阅能力槽位注入 skillRepository/coordinator）；
 * 纯渲染在 [EchoActionLayerContent]（state-in / event-out）。
 */
@Composable
fun EchoActionLayer(
    availability: EchoActionAvailability,
    skillRepository: SkillRepository,
    coordinator: SkillSessionCoordinator,
    onStartAction: (EchoActionKind) -> Unit,
) {
    EchoActionLayerContent(
        availability = availability,
        onStartAction = onStartAction,
        skillsSection = { SkillListSection(skillRepository, coordinator) },
    )
}

/** ERA 38 — EchoActionLayer 纯状态内容（state-in / event-out + 订阅能力槽位）。 */
@Composable
fun EchoActionLayerContent(
    availability: EchoActionAvailability,
    onStartAction: (EchoActionKind) -> Unit,
    skillsSection: @Composable () -> Unit,
) {
    var skillsExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("想做点什么？", style = MaterialTheme.typography.titleMedium)
        // Intervention Policy L2：打开时建议（运行时统一裁决；L0/L1 不打扰）
        if (availability.suggested) {
            Text(
                "从今天的数据看，让自己慢一点可能有帮助。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onStartAction(EchoActionKind.BREATHING) },
                modifier = Modifier.weight(1f)
            ) { Text("1 分钟呼吸") }
            OutlinedButton(
                onClick = { onStartAction(EchoActionKind.PAUSE) },
                modifier = Modifier.weight(1f)
            ) { Text("短暂离开屏幕") }
        }
        // 「什么也不做」永远是合法选项（产品宪法：不需要喂 ECHO）
        TextButton(
            onClick = { skillsExpanded = false },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("什么也不做") }
        HorizontalDivider()
        // 订阅能力分区（不在免费行动里混排）
        TextButton(
            onClick = { skillsExpanded = !skillsExpanded },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(if (skillsExpanded) "收起更多能力（订阅）" else "更多能力（订阅）")
        }
    }
    if (skillsExpanded) {
        skillsSection()
    }
}
