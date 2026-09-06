package com.yunjue.echo.mind.ui.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.ui.echo.components.GrowthTimelinePoint
import java.time.LocalDate

/**
 * 设计稿 19 成长时间线装配（纯函数，诚实数据源）。
 *
 * 节点标签对齐设计稿叙事（初次相遇 / 开始理解 / 建立节律 / 越来越懂你），
 * 日期只锚定真实记录：Canonical Daily State 日期序列 + 调用方注入的今天。
 * 设计稿示意日期（7月1日等）为视觉示意，不进入实现（数据真实性契约）；
 * 无任何真实记录且无基线 → 空列表（时间线整体隐藏，不渲染空壳）。
 */
fun buildGrowthTimeline(
    canonicalDates: List<String>,
    baselineDays: Int,
    today: LocalDate,
): List<GrowthTimelinePoint> {
    val dates = canonicalDates
        .mapNotNull { date -> runCatching { LocalDate.parse(date) }.getOrNull() }
        .distinct()
        .sorted()
    if (dates.isEmpty() && baselineDays <= 0) return emptyList()
    val points = mutableListOf<GrowthTimelinePoint>()
    if (dates.isNotEmpty()) {
        points += GrowthTimelinePoint(label = "初次相遇", date = monthDay(dates.first()))
        // 开始理解：第 7 个记录日（App 以 7 天为基线形成门槛，见 EchoPortraitStates）
        if (dates.size >= 7) {
            points += GrowthTimelinePoint(label = "开始理解", date = monthDay(dates[6]))
        }
        // 建立节律：第 30 个记录日（一个月节律期，历法可核）
        if (dates.size >= 30) {
            points += GrowthTimelinePoint(label = "建立节律", date = monthDay(dates[29]))
        }
    } else {
        points += GrowthTimelinePoint(label = "基线形成中", date = "第 $baselineDays 天")
    }
    points += GrowthTimelinePoint(label = "越来越懂你", date = monthDay(today), isToday = true)
    return points
}

/**
 * 设计稿 9「本月画像」四维趋势序列（纯函数）。
 *
 * 维度使用真实行为观察键（MOVEMENT / SCREEN_AMOUNT / SCREEN_TIMING / DAY_STRUCTURE），
 * 标签为行为观察语义（活动量/屏幕时长/屏幕节奏/一天结构）；设计稿的「情绪/能量/专注/连接」
 * 是心理词，直接映射会违反 PORTRAIT_CONTRACT §3（行为观察允许 / 心理判断禁止），不采用。
 * STABILITY 为元维度（差异计数），不绘图。数据全缺 → isEmptyData（渲染弃权文案）。
 */
data class MonthTrendSeries(
    val dimensions: List<Pair<String, List<String>>>,
    val xLabels: List<String>,
    val isEmptyData: Boolean,
)

private val MONTH_TREND_DIMENSIONS = listOf(
    "MOVEMENT" to "活动量",
    "SCREEN_AMOUNT" to "屏幕时长",
    "SCREEN_TIMING" to "屏幕节奏",
    "DAY_STRUCTURE" to "一天结构",
)

fun buildMonthTrendSeries(portraits: List<DailyPortraitDto>): MonthTrendSeries {
    val dimensions = MONTH_TREND_DIMENSIONS.map { (key, label) ->
        label to portraits.map { p -> p.dimensions[key]?.value?.takeIf { v -> v.isNotBlank() } ?: "—" }
    }
    val hasData = portraits.any { p ->
        MONTH_TREND_DIMENSIONS.any { (key, _) -> !p.dimensions[key]?.value.isNullOrBlank() }
    }
    val dates = portraits.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val xLabels = if (dates.size >= 2) {
        listOf(0, dates.size / 4, dates.size / 2, dates.size * 3 / 4, dates.size - 1)
            .distinct()
            .map { i -> "${dates[i].monthValue}/${dates[i].dayOfMonth}" }
    } else {
        emptyList()
    }
    return MonthTrendSeries(dimensions = dimensions, xLabels = xLabels, isEmptyData = !hasData)
}

private fun monthDay(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
