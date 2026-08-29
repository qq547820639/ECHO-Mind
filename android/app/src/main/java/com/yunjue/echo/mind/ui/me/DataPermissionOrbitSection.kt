/**
 * 阶段 3C — 数据与权限星球轨道图（设计稿 12）。
 *
 * 中心 ECHO 发光球 + 5 节点（屏幕节律/通知/位置/可穿戴/活动）以椭圆轨道分布。
 * 纯 Compose Canvas 绘制（确定性），节点位置按画布大小自适应。
 * 顶部声明：原始数据仅在本机处理，不上传、不读取、不分享。
 * 4 设置卡：数据来源 / 权限管理 / 本地处理 / 数据保留，各卡下方灰色补充说明。
 * 数据从 AppPreferences / 真实能力状态读取；不可用时显示 "—" / 状态描述，不编造示例值。
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 5 个感知节点。 */
data class DataOrbitNode(
    val name: String,
    val enabled: Boolean,
    val detail: String,
)

/** 数据/权限设置卡片。 */
data class DataPermissionCard(
    val title: String,
    val description: String,
    val supplementary: String,
    val onClick: () -> Unit,
)

@Composable
fun DataPermissionOrbitSection(
    nodes: List<DataOrbitNode>,
    cards: List<DataPermissionCard>,
    modifier: Modifier = Modifier,
) {
    val cardBg = Color(0xFF0E1426)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = "数据与权限页面" },
    ) {
        Text(
            text = "数据与权限",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "原始数据仅在本机处理，不上传、不读取、不分享",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "权限可随时撤销",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF34D399),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // 5 节点轨道图
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cardBg)
                .semantics { contentDescription = "5 节点数据轨道：屏幕节律/通知/位置/可穿戴/活动" },
        ) {
            DataOrbitCanvas(nodes.take(5))
        }

        // 4 设置卡
        Spacer(Modifier.height(16.dp))
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.take(4).forEach { card -> PermissionCardRow(card) }
        }
    }
}

@Composable
private fun DataOrbitCanvas(nodes: List<DataOrbitNode>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .semantics { contentDescription = "数据轨道可视化" },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val accent = Color(0xFF8F7CF0)
        val nodeColor = Color(0xFF38BDF8)
        val centerColor = Color(0xFF7C3AED)

        // 椭圆轨道（更宽更扁 = 设计稿氛围）
        val rx = minOf(w, h) * 0.40f
        val ry = minOf(w, h) * 0.32f
        drawOval(
            color = accent.copy(alpha = 0.18f),
            topLeft = Offset(cx - rx, cy - ry),
            size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
            style = Stroke(width = 1.2f),
        )
        drawOval(
            color = accent.copy(alpha = 0.10f),
            topLeft = Offset(cx - rx * 0.7f, cy - ry * 0.7f),
            size = androidx.compose.ui.geometry.Size(rx * 1.4f, ry * 1.4f),
            style = Stroke(width = 1f),
        )

        // 中心球（径向渐变）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(centerColor, Color(0xFF1F1147)),
                center = Offset(cx, cy),
                radius = 40f,
            ),
            radius = 40f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = accent.copy(alpha = 0.30f),
            radius = 52f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.2f),
        )

        // 5 节点（5 个等角分布，第 1 个放在正上方）
        val n = nodes.size.coerceAtMost(5).coerceAtLeast(1)
        val angle0 = -Math.PI.toFloat() / 2f // 起始：正上方
        for (i in 0 until n) {
            val angle = angle0 + 2 * Math.PI.toFloat() * i / n
            val px = cx + rx * kotlin.math.cos(angle)
            val py = cy + ry * kotlin.math.sin(angle)
            val enabled = nodes[i].enabled
            val nc = if (enabled) nodeColor else nodeColor.copy(alpha = 0.35f)
            // 连线（中心 → 节点）
            drawLine(
                color = accent.copy(alpha = 0.30f),
                start = Offset(cx, cy),
                end = Offset(px, py),
                strokeWidth = 1f,
                cap = StrokeCap.Round,
            )
            // 节点外光晕
            drawCircle(
                color = nc.copy(alpha = 0.20f),
                radius = 18f,
                center = Offset(px, py),
            )
            // 节点核心
            drawCircle(
                color = nc,
                radius = 9f,
                center = Offset(px, py),
            )
        }
    }

    // 节点名称标签（覆盖在 Canvas 上方）
    Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
        val cx = 0.5f
        val cy = 0.5f
        val n = nodes.size.coerceAtMost(5).coerceAtLeast(1)
        val angle0 = -Math.PI.toFloat() / 2f
        for (i in 0 until n) {
            val angle = angle0 + 2 * Math.PI.toFloat() * i / n
            // 节点在画布中的相对位置（粗略估算 280dp × 280dp）
            val rxRel = 0.40f
            val ryRel = 0.32f
            val xRel = cx + rxRel * kotlin.math.cos(angle)
            val yRel = cy + ryRel * kotlin.math.sin(angle)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(0.dp),
            ) {
                // 用 offset 模拟位置（避免 Alignment 自带 0/中心化）
                val xDp = (xRel * 280).dp
                val yDp = (yRel * 280).dp
                Box(
                    modifier = Modifier
                        .padding(start = xDp, top = yDp)
                        .semantics { contentDescription = "${nodes[i].name}节点${if (nodes[i].enabled) "已启用" else "未启用"}" },
                ) {
                    Text(
                        text = nodes[i].name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (nodes[i].enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        // 中心标签
        Box(
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = "ECHO",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PermissionCardRow(card: DataPermissionCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E1426))
            .clickable(onClick = card.onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "${card.title}：${card.description}" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = card.supplementary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
