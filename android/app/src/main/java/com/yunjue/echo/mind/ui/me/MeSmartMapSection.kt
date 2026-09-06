/**
 * 阶段 3B — Me 智能地图（设计稿 10/11）。
 *
 * 中心发光球 ECHO + 四向连线节点：感知世界 / 如何思考 / 记住什么 / 我的设备。
 * 纯 Compose Canvas 绘制（确定性），节点位置根据画布大小动态计算。
 * 三设置卡入口（数据与隐私/通知与提醒/个性化与沟通）映射到现有 Section。
 * 设备列表接真实绑定设备（Wrist/wearable）；ECHO 提供方接 AiProviderManager。
 * 数据不可用时显示 "—" / 状态描述，不编造示例值。
 */
package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjue.echo.mind.ui.artwork.SmartMapIconType
import com.yunjue.echo.mind.ui.artwork.drawSmartMapIcon

/** 智能地图节点。 */
data class SmartMapNode(
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true,
)

/** 智能地图设备/提供方真实数据（默认值为弃权语义；真实值由 MeScreen 从 AiProviderManager/可穿戴状态注入）。 */
data class SmartMapDevices(
    val connectedCount: Int = 0,
    val providerName: String = "未配置",
    val providerLocation: String = "—",
    val privacyNote: String = "未连接外部模型服务",
)

/** 快速管理入口（testTag 承载 me_entry_* 锚点，语义不变）。 */
data class QuickAccessItem(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val testTag: String? = null,
)

@Composable
fun MeSmartMapSection(
    devices: SmartMapDevices,
    quickAccessItems: List<QuickAccessItem>,
    modifier: Modifier = Modifier,
) {
    val nodes = listOf(
        SmartMapNode("感知世界", "Observation", true),
        SmartMapNode("如何思考", "Intelligence", true),
        SmartMapNode("记住什么", "Memory", true),
        SmartMapNode("我的设备", "Devices", devices.connectedCount > 0),
    )
    val cardBg = Color(0xFF0E1426)
    val cardBorder = Color(0xFF1F2A40)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics { contentDescription = "ECHO 智能地图" },
    ) {
        Text(
            text = "ECHO 如何认识你",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "这是一张关于你的智能地图",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // 智能地图（中心 ECHO + 4 节点 + 连线）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cardBg)
                .semantics { contentDescription = "智能地图可视化：中心 ECHO 与四个能力节点" },
        ) {
            SmartMapCanvas(nodes)
        }

        // 三能力卡（OBSERVATION / MEMORY / INTELLIGENCE）
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CapabilityChip("OBSERVATION", "感知", Modifier.weight(1f))
            CapabilityChip("MEMORY", "记忆", Modifier.weight(1f))
            CapabilityChip("INTELLIGENCE", "思考", Modifier.weight(1f))
        }

        // 我的设备（真实数据）
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .padding(16.dp)
                .semantics { contentDescription = "我的设备与 ECHO 提供方" },
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "我的设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (devices.connectedCount > 0) "${devices.connectedCount} 台已连接" else "暂无已连接设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (devices.connectedCount > 0) {
                            Color(0xFF34D399)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "ECHO 提供方",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                        Text(
                            text = devices.providerName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${devices.providerLocation} · ${devices.privacyNote}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }

        // 快速管理 5 入口（来自 quickAccessItems）
        Spacer(Modifier.height(16.dp))
        Text(
            text = "快速管理",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quickAccessItems.take(5).forEach { item ->
                QuickAccessRow(item)
            }
        }
    }
}

@Composable
private fun SmartMapCanvas(nodes: List<SmartMapNode>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .semantics { contentDescription = "智能地图中心与节点连线" },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val orbitRadius = minOf(w, h) * 0.34f
        val accent = Color(0xFF8F7CF0)
        val accentBlue = Color(0xFF38BDF8)
        val centerColor = Color(0xFF7C3AED)

        // 中心球（渐变填充 + 外环）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(centerColor, Color(0xFF1F1147)),
                center = Offset(cx, cy),
                radius = 38f,
            ),
            radius = 38f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = accent.copy(alpha = 0.35f),
            radius = 50f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f),
        )
        drawCircle(
            color = accent.copy(alpha = 0.18f),
            radius = 70f,
            center = Offset(cx, cy),
            style = Stroke(width = 1f),
        )

        // 节点位置（上 / 右 / 下 / 左）；配色对齐设计稿 10（青/品红/紫/橙）
        val nodeOffsets = listOf(
            Offset(cx, cy - orbitRadius), // 上
            Offset(cx + orbitRadius, cy), // 右
            Offset(cx, cy + orbitRadius), // 下
            Offset(cx - orbitRadius, cy), // 左
        )
        val nodeColors = listOf(
            Color(0xFF22D3EE), // 感知世界 — 青
            Color(0xFFE879F9), // 如何思考 — 品红
            Color(0xFFA855F7), // 记住什么 — 紫
            Color(0xFFFB923C), // 我的设备 — 橙
        )
        val nodeIcons = listOf(
            SmartMapIconType.EYE,
            SmartMapIconType.SPARK,
            SmartMapIconType.BOOK,
            SmartMapIconType.GEAR,
        )
        // 弧形轨道连线（设计稿 10：向外弓起的曲线，非直线）
        for (i in nodes.indices) {
            val p = nodeOffsets[i]
            val midX = (cx + p.x) / 2f
            val midY = (cy + p.y) / 2f
            val dx = p.x - cx
            val dy = p.y - cy
            val ctrl = Offset(midX + dy * 0.20f, midY - dx * 0.20f)
            val arcPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy)
                quadraticBezierTo(ctrl.x, ctrl.y, p.x, p.y)
            }
            drawPath(
                arcPath,
                color = nodeColors[i].copy(alpha = if (nodes[i].enabled) 0.40f else 0.18f),
                style = Stroke(width = 1.4f, cap = StrokeCap.Round),
            )
            // 节点：光晕 + 细环 + 图标徽章（启用态实色 / 未启用降透明）
            val nColor = if (nodes[i].enabled) nodeColors[i] else nodeColors[i].copy(alpha = 0.35f)
            drawCircle(
                color = nColor.copy(alpha = 0.20f),
                radius = 24f,
                center = p,
            )
            drawCircle(
                color = nColor,
                radius = 15f,
                center = p,
                style = Stroke(width = 1.6f),
            )
            val iconBox = 18f
            translate(p.x - iconBox / 2f, p.y - iconBox / 2f) {
                drawSmartMapIcon(
                    type = nodeIcons[i],
                    color = nColor,
                    iconBox = androidx.compose.ui.geometry.Size(iconBox, iconBox),
                )
            }
        }
    }

    // 节点标签（覆盖在 Canvas 上方）
    Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
        val positions = listOf(
            Alignment.TopCenter,
            Alignment.CenterEnd,
            Alignment.BottomCenter,
            Alignment.CenterStart,
        )
        nodes.forEachIndexed { i, node ->
            val alignment = positions[i]
            Box(
                modifier = Modifier
                    .align(alignment)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
                    .semantics { contentDescription = "${node.title}节点${if (node.enabled) "已启用" else "未启用"}" },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (node.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = node.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        }
        // 中心 ECHO 标签
        Box(
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = "ECHO",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
        }
    }
}

@Composable
private fun CapabilityChip(label: String, sub: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E1426))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QuickAccessRow(item: QuickAccessItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E1426))
            .clickable(onClick = item.onClick)
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = "${item.title}：${item.description}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}
