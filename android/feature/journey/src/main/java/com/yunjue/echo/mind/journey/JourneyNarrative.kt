package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_DIMENSIONS
import com.yunjue.echo.mind.model.PORTRAIT_SUMMARY_NO_DATA
import com.yunjue.echo.mind.model.dimensionDisplayName

/**
 * ERA 31 R19 — Journey 确定性叙事（无 AI 免费用户的 Journey 长期叙事）。
 *
 * 与 [com.yunjue.echo.mind.model.portraitStabilitySummary] 同源统计
 * （最接近 = SIMILAR 比例最高；变化较明显 = 非 SIMILAR 最多），但说成人话：
 * 不是指标行「最接近：作息；变化较明显：屏幕总量」，而是「过去 N 天里，你的作息
 * 最接近平常，变化较明显的是屏幕总量。」——§30「看见自己的时间」而非阅读报告。
 * Evidence Layer 仍保留指标行（定量依据在那里才合适）。
 */
fun journeyNaturalSummary(portraits: List<DailyPortraitDto>): String {
    val stats = PORTRAIT_DIMENSIONS.associateWith { dim ->
        val values = portraits.mapNotNull { it.dimensionValue(dim) }
        values.count { it == "SIMILAR" } to values.size
    }.filterValues { (_, total) -> total > 0 }
    if (stats.isEmpty()) return PORTRAIT_SUMMARY_NO_DATA
    val stable = stats.maxByOrNull { (_, v) -> v.first.toFloat() / v.second }!!.key
    val changed = stats.maxByOrNull { (_, v) -> v.second - v.first }!!.key
    val days = portraits.size
    return when {
        stable == changed -> "过去 $days 天里，你的节奏整体比较平稳。"
        else -> "过去 $days 天里，你的${dimensionDisplayName(stable)}最接近平常，变化较明显的是${dimensionDisplayName(changed)}。"
    }
}
