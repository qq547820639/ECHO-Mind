/**
 * 阶段 2C — Journey 月画像卡（设计稿 9「本月画像」，纯确定性渲染）。
 *
 * 结构对齐设计稿：卡片标题 + 叙事文字 + 四线趋势图 + 维度图例 + 日期轴。
 * 数据全部来自真实画像维度（MOVEMENT / SCREEN_AMOUNT / SCREEN_TIMING / DAY_STRUCTURE，
 * 见 buildMonthTrendSeries）；标签为行为观察语义，不使用设计稿示意值与心理词。
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 月画像四维趋势卡。
 *
 * @param dimensions 四条线的相对取值序列：[(维度名, 序列值)]，序列值用相对标签（"SIMILAR"/"MORE"/"LESS" 等）表达
 * @param isEmptyData 当数据完全为空时，显示 "数据尚不足以形成趋势" 文案
 * @param title 卡片标题（默认设计稿 9 的「本月画像」）
 * @param narrativeLines 本月叙事行（来自真实 narrative 结果；空 → 弃权文案，不编造）
 * @param xLabels 日期轴标签（自画像日期等距取样，≤5 个；空 → 不渲染轴行）
 */
@Composable
fun JourneyMonthTrendChart(
    dimensions: List<Pair<String, List<String>>>,
    modifier: Modifier = Modifier,
    isEmptyData: Boolean = false,
    title: String = "本月画像",
    narrativeLines: List<String> = emptyList(),
    xLabels: List<String> = emptyList(),
) {
    val cardBg = Color(0xFF0E1426)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val mutedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    // 无障碍：构造可朗读的趋势摘要（按维度统计 SIMILAR/差异 段数）
    val summary = if (isEmptyData) {
        "数据尚不足以形成趋势"
    } else {
        val parts = dimensions.take(4).map { (name, values) ->
            val clean = values.filter { it.isNotBlank() && it != "—" }
            val similar = clean.count { it == "SIMILAR" }
            val diff = clean.size - similar
            "$name：相似 $similar 段、差异 $diff 段"
        }
        "四维趋势：" + parts.joinToString("；")
    }
    val lines = if (narrativeLines.isEmpty()) {
        listOf("记录尚少，暂未形成本月画像叙事。")
    } else {
        narrativeLines
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .padding(16.dp)
            .semantics { contentDescription = "$title（$summary）" },
    ) {
        // 卡片标题（设计稿 9：本月画像）
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // 本月叙事（真实 narrative；缺省为弃权文案）
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A1020))
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
        // 日期轴（等距取样；设计稿 9 的 8/1…8/31 结构）
        if (xLabels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                xLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColor,
                    )
                }
            }
        }
        // 维度图例（与序列一一对应；配色对齐设计稿 9：蓝/橙/紫/绿）
        val legendColors = listOf(
            Color(0xFF38BDF8),
            Color(0xFFF59E0B),
            Color(0xFF8F7CF0),
            Color(0xFF34D399),
        )
        Row(
            modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            dimensions.take(4).forEachIndexed { i, (name, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(legendColors[i % legendColors.size], shape = CircleShape),
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        // 屏读器摘要（不可见文本，语义锚点）
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor,
            modifier = Modifier
                .padding(vertical = 2.dp)
                .semantics { contentDescription = summary },
        )
    }
}

@Composable
private fun TrendLines(dimensions: List<Pair<String, List<String>>>) {
    // 设计稿 9 配色：情绪位=蓝 / 能量位=橙 / 专注位=紫 / 连接位=绿
    val colors = listOf(
        Color(0xFF38BDF8),
        Color(0xFFF59E0B),
        Color(0xFF8F7CF0),
        Color(0xFF34D399),
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
                        // 中性：与基线相似
                        "SIMILAR", "STABLE", "VERY_SIMILAR", "SLIGHTLY_DIFFERENT" -> padding + chartH * 0.5f
                        // 相对上行：更多 / 更集中 / 更早
                        "MORE", "UP", "MORE_CONCENTRATED", "CLEARLY_DIFFERENT", "EARLIER" -> padding + chartH * 0.2f
                        // 相对下行：更少 / 更碎 / 更晚
                        "LESS", "DOWN", "MORE_FRAGMENTED", "LATER" -> padding + chartH * 0.8f
                        "IRREGULAR" -> padding + chartH * 0.35f
                        else -> padding + chartH * 0.5f
                    }
                    Offset(x, y)
                }
            if (points.isNotEmpty()) {
                val color = colors[idx % colors.size]
                // 设计稿 9：平滑曲线（水平单调三次贝塞尔）+ 数据点发光圆点
                val path = Path()
                path.moveTo(points.first().x, points.first().y)
                points.drop(1).forEachIndexed { i, p ->
                    val prev = points[i]
                    val midX = (prev.x + p.x) / 2f
                    path.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                    alpha = 0.9f,
                )
                points.forEach { p ->
                    // 外圈光晕 + 内点
                    drawCircle(color = color.copy(alpha = 0.22f), radius = 6.5f, center = p)
                    drawCircle(color = color, radius = 3f, center = p)
                }
            }
        }
    }
}
