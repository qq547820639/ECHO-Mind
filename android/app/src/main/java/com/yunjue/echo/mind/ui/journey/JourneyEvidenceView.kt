package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.coveragePercent
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionTrendSymbol
import com.yunjue.echo.mind.model.portraitStabilitySummary
import com.yunjue.echo.mind.ui.formatTimestamp
import java.time.LocalDate

/**
 * Journey Evidence Layer（ERA 13 拆分自 JourneyScreen）：
 * - 7 日视图：各维度（节律/移动/屏幕）相对趋势符号矩阵（↑↓→~，不做精确数字强调）
 * - 28 日视图：各维度概览 + 确定性综述（最稳定 / 变化较明显）
 * - 不做心理状态解释（TREND_DISCLAIMER 语义保持）
 *
 * §AI/§AQ：[anchor] 由调用方从 state 派生（选中日/最新数据日）后传入，
 * 组合内部不做 LocalDate.now() 窗口计算。
 */
@Composable
internal fun JourneyEvidenceView(
    timeline: PortraitTimelineUiState,
    lastCollectionTs: Long,
    lastSyncTs: Long,
    feedback: (String) -> Boolean?,
    anchor: LocalDate,
) {
    val portraits = timeline.portraits

    // 数据覆盖度（近 N 天窗口）
    Text("数据覆盖度：${coveragePercent(portraits, timeline.days)}%（近 ${timeline.days} 天）", style = MaterialTheme.typography.titleMedium)
    // 日期覆盖条（仅最近 7 天窗口展示单日格子；28 天不逐日铺开）
    if (timeline.days <= 7) {
        val days = (0 until 7).map { anchor.minusDays((7 - 1 - it).toLong()) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                val hasData = portraits.any { it.date == day.toString() }
                val mark = feedback(day.toString())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (hasData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                    // 反馈标记：✓ 你觉得像 / ✗ 你觉得不太像（无反馈留空位保持对齐）
                    Text(
                        when (mark) {
                            true -> "✓"
                            false -> "✗"
                            null -> " "
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Text("✓ 你觉得像 · ✗ 你觉得不太像", style = MaterialTheme.typography.labelSmall)
    }

    // missing window 标注
    if (timeline.isPartial) {
        Text("缺失窗口：${timeline.missingDates.joinToString("、").ifEmpty { "无" }}")
    }

    HorizontalDivider()
    if (timeline.days <= 7) {
        SevenDayTrendMatrix(portraits, timeline.days, anchor)
    } else {
        TwentyEightDayOverview(portraits)
    }

    HorizontalDivider()
    Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
    Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
}

/**
 * 7 日视图：维度（节律/移动/屏幕互动）× 最近 [days] 天相对趋势符号矩阵。
 * 符号来自 [dimensionTrendSymbol]（↑ 偏早/增多、↓ 偏晚/减少、→ 接近、~ 不规律/波动、– 缺失）。
 * [anchor] 为窗口锚点日（§AI/§AQ：由调用方从 state 派生传入）。
 */
@Composable
private fun SevenDayTrendMatrix(portraits: List<DailyPortraitDto>, days: Int, anchor: LocalDate) {
    val dates = (0 until days).map { anchor.minusDays((days - 1 - it).toLong()) }
    val byDate = portraits.associateBy { it.date }
    Text("相对趋势（↑ 偏早/增多 · ↓ 偏晚/减少 · → 接近，仅观察不解释）", style = MaterialTheme.typography.bodySmall)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 表头：维度 + 日期
        Row {
            Box(Modifier.weight(1.6f)) { Text("维度", style = MaterialTheme.typography.labelSmall) }
            dates.forEach { day ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        PORTRAIT_TREND_DIMENSIONS.forEach { dim ->
            Row {
                Box(Modifier.weight(1.6f)) {
                    Text(dimensionDisplayName(dim), style = MaterialTheme.typography.bodySmall)
                }
                dates.forEach { day ->
                    val value = byDate[day.toString()]?.dimensionValue(dim)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(dimensionTrendSymbol(value), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * 28 日视图：各维度概览（接近/偏早增多/偏晚减少天数）+ 确定性综述
 * （最稳定 = SIMILAR 比例最高；变化较明显 = 非 SIMILAR 最多）。
 */
@Composable
private fun TwentyEightDayOverview(portraits: List<DailyPortraitDto>) {
    Text("近 28 天各维度概览（仅观察，不解释）", style = MaterialTheme.typography.titleMedium)
    PORTRAIT_TREND_DIMENSIONS.forEach { dim ->
        val values = portraits.mapNotNull { it.dimensionValue(dim) }
        if (values.isEmpty()) {
            Text("${dimensionDisplayName(dim)}：暂无数据", style = MaterialTheme.typography.bodySmall)
        } else {
            val similar = values.count { it == "SIMILAR" }
            val up = values.count { dimensionTrendSymbol(it) == "↑" }
            val down = values.count { dimensionTrendSymbol(it) == "↓" }
            Text(
                "${dimensionDisplayName(dim)}：$similar 天接近 · $up 天偏早/增多 · $down 天偏晚/减少",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Text(portraitStabilitySummary(portraits), style = MaterialTheme.typography.titleMedium)
}
