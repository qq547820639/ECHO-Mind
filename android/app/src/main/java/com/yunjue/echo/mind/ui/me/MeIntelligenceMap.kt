package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.EchoPresenceState

/**
 * MeIntelligenceMap — Me 首层 Personal Intelligence Map（§12，设计稿 10）。
 *
 * 不是 Settings List：中心 ECHO organism + 五个领域节点（Observation / Intelligence /
 * Memory / Devices / Presence）环绕，每节点可点击进入对应领域。
 * 视觉上沿用同一 organism（SAME ECHO）；节点连线表达「ECHO 如何认识你」的结构。
 *
 * @param presence 当前 EchoPresenceState（中心 organism 用；null → 中性占位）
 * @param organism Composable 槽位（EchoOrganism 注入，避免本层持有渲染细节）
 */
@Composable
fun MeIntelligenceMap(
    presence: EchoPresenceState?,
    onOpenObservation: () -> Unit,
    onOpenIntelligence: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenPresence: () -> Unit,
    organism: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodeColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp)
            .semantics { contentDescription = "ECHO 如何认识你：感知、思考、记忆、设备、存在 五个领域" },
    ) {
        // 连线（中心到五节点；先画线，节点在上层）
        val linePaint = rememberNodeLine(nodeColor)
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.height * 0.36f
                    val targets = listOf(
                        Offset(cx, cy - r), // 顶：Observation
                        Offset(cx - r * 1.1f, cy), // 左：Intelligence
                        Offset(cx + r * 1.1f, cy), // 右：Memory
                        Offset(cx, cy + r * 1.15f), // 下：Devices
                    )
                    targets.forEach { t ->
                        drawLine(linePaint, Offset(cx, cy), t, strokeWidth = 1.2f)
                    }
                },
        )

        // 中心 organism
        Box(Modifier.align(Alignment.Center).size(180.dp)) {
            organism()
        }

        // 五节点（顶 Observation / 左 Intelligence / 右 Memory / 下 Devices / Presence 并入下）
        MapNode("感知世界", "Observation", Modifier.align(Alignment.TopCenter).padding(top = 4.dp), onOpenObservation)
        MapNode("如何思考", "Intelligence", Modifier.align(Alignment.CenterStart).padding(start = 6.dp), onOpenIntelligence)
        MapNode("记住什么", "Memory", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), onOpenMemory)
        MapNode("我的设备", "Devices", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), onOpenDevices)
    }
}

@Composable
private fun MapNode(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title $subtitle" }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun rememberNodeLine(color: Color): Color = color.copy(alpha = 0.35f)
