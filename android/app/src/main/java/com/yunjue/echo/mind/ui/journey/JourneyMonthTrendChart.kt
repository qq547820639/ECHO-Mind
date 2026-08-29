/**
 * 阶段 2C — Journey 月画像四维趋势折线图（设计稿 9，纯确定性渲染）。
 *
 * 来源：相对维度序列（emotion / energy / focus / connection）。
 * 渲染：Compose Canvas 纯绘制（无 AI 生图），确定性输出。
 * 当数据不足时显示 "数据尚不足以形成趋势"，不编造示例值。
 */
package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 月画像四维趋势折线图。
 *
 * @param dimensions 四条线的相对取值序列：[(维度名, 序列值)]，序列值用相对标签（"SIMILAR"/"MORE"/"LESS"）表达
 * @param isEmptyData 当数据完全为空时，显示 "数据尚不足以形成趋势" 文案
 */
@Composable
fun JourneyMonthTrendChart(
    dimensions: List<Pair<String, List<String>>>,
    isEmptyData: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cardBg = Color(0xFF0E1426)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val mutedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    // 无障碍：构造可朗读的趋势摘要（按维度统计 SIMILAR/MORE/LESS 数量）
    val summary = if (isEmptyData) {
        "数据尚不足以形成趋势"
    } else {
        val parts = dimensions.take(4).map { (name, values) ->
            val clean = values.filter { it.isNotBlank() && it != "—" }
            val more = clean.count { it == "MORE" || it == "UP" || it == "MORE_CONCENTRATED" || it == "CLEARLY_DIFFERENT" }
            val less = clean.count { it == "LESS" || it == "DOWN" || it == "MORE_FRAGMENTED" }
            val similar = clean.size - more - less
            "$name：相似 $similar 段、偏高 $more 段、偏低 $less 段"
        }
        "四维趋势：" + parts.joinToString("；")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 屏读器先读摘要文字（design 9 趋势图用语言化摘要表达非文字信息）
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = summary },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .semantics { contentDescription = "月画像四维趋势折线图（同上文字摘要）" },
        ) {
            if (isEmptyData) {
                Text(
                    text = "数据尚不足以形成趋势",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = "数据不足，无法绘制趋势" },
                )
            } else {
                TrendLines(dimensions)
            }
        }
        // 维度图例（4 个）
        val legendLabels = listOf("情绪", "能量", "专注", "连接")
        val legendColors = listOf(
            Color(0xFF38BDF8),
            Color(0xFF34D399),
            Color(0xFF8F7CF0),
            Color(0xFF6EE7B7),
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            legendLabels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(legendColors[i], shape = CircleShape),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendLines(dimensions: List<Pair<String, List<String>>>) {
    val colors = listOf(
        Color(0xFF38BDF8), // 情绪 — 青色
        Color(0xFF34D399), // 能量 — 绿
        Color(0xFF8F7CF0), // 专注 — 紫
        Color(0xFF6EE7B7), // 连接 — 浅绿
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(136.dp)) {
        val w = size.width
        val h = size.height
        val padding = 24f
        val chartW = w - padding * 2
        val chartH = h - padding * 2

        // 网格背景（4 段）
        for (g in 0..3) {
            val gy = padding + chartH * g.toFloat() / 3
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(padding, gy),
                end = Offset(w - padding, gy),
                strokeWidth = 1f,
            )
        }

        val lines = dimensions.take(4)
        val maxPoints = lines.maxOfOrNull { it.second.count { it.isNotBlank() && it != "—" } } ?: 0
        if (maxPoints < 2) return@Canvas

        val stepX = chartW / (maxPoints - 1)
        lines.forEachIndexed { idx, (_, values) ->
            val points = values
                .filter { it.isNotBlank() && it != "—" }
                .mapIndexed { i, v ->
                    val x = padding + i * stepX
                    val y = when (v) {
                        "SIMILAR", "STABLE", "VERY_SIMILAR", "SLIGHTLY_DIFFERENT" -> padding + chartH * 0.5f
                        "MORE", "UP", "MORE_CONCENTRATED", "CLEARLY_DIFFERENT" -> padding + chartH * 0.2f
                        "LESS", "DOWN", "MORE_FRAGMENTED" -> padding + chartH * 0.8f
                        "IRREGULAR" -> padding + chartH * 0.35f
                        else -> padding + chartH * 0.5f
                    }
                    Offset(x, y)
                }
            if (points.isNotEmpty()) {
                val path = Path()
                path.moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { p -> path.lineTo(p.x, p.y) }
                drawPath(
                    path = path,
                    color = colors[idx % colors.size],
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                    alpha = 0.9f,
                )
            }
        }
    }
}
