package com.yunjue.echo.mind.ui.journey

import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyUiState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Journey 纯函数日历数学（Organism Quality §32 拆分自 JourneyScreen；可单测，
 * 组合内不做 LocalDate.now() 窗口计算——§AI/§AQ）。
 */

/** 月历单元：date=null 且 inMonth=false 为月前/月后惰性空位。 */
internal data class JourneyMonthCell(
    val date: LocalDate?,
    val inMonth: Boolean,
)

/**
 * §AN 真实月历网格（周一为首列，表头 一二三四五六日）：
 * leadingBlanks = 该月 1 号前的空位数；天数 = [YearMonth.lengthOfMonth]（28/29/30/31）；
 * 补尾空位使每行恰好 7 列。
 */
internal fun journeyMonthGrid(yearMonth: YearMonth): List<JourneyMonthCell> {
    val leadingBlanks = (yearMonth.atDay(1).dayOfWeek.value + 6) % 7
    val daysInMonth = yearMonth.lengthOfMonth()
    val cells = mutableListOf<JourneyMonthCell>()
    repeat(leadingBlanks) { cells += JourneyMonthCell(date = null, inMonth = false) }
    for (day in 1..daysInMonth) {
        cells += JourneyMonthCell(date = yearMonth.atDay(day), inMonth = true)
    }
    val trailingBlanks = (7 - cells.size % 7) % 7
    repeat(trailingBlanks) { cells += JourneyMonthCell(date = null, inMonth = false) }
    return cells
}

/** §AO 季/年尺度：按自然月分组（旧 → 新）。 */
internal data class JourneyMonthGroup(
    val year: Int,
    val month: Int,
    val days: List<JourneyDay>,
)

internal fun journeyMonthGroups(days: List<JourneyDay>): List<JourneyMonthGroup> =
    days.groupBy { it.date.take(7) }
        .toSortedMap()
        .mapNotNull { (monthKey, monthDays) ->
            runCatching {
                JourneyMonthGroup(
                    year = monthKey.substring(0, 4).toInt(),
                    month = monthKey.substring(5, 7).toInt(),
                    days = monthDays,
                )
            }.getOrNull()
        }

/** §AQ 锚点日：选中日 → 最新数据日 → 调用方传入的 today（组合内不取 now()）。 */
internal fun journeyAnchorDate(state: JourneyUiState, today: LocalDate): LocalDate {
    state.selectedDay?.date?.let { selected ->
        runCatching { LocalDate.parse(selected) }.getOrNull()?.let { return it }
    }
    state.visualDays.lastOrNull()?.date?.let { latest ->
        runCatching { LocalDate.parse(latest) }.getOrNull()?.let { return it }
    }
    return today
}

/** 星期短标签（周一…周日）。 */
internal fun weekDayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** "2026-08-14" → "8月14日 · 周五"（解析失败原样返回）。 */
internal fun journeyDateLabel(date: String): String {
    val localDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return "${localDate.monthValue}月${localDate.dayOfMonth}日 · ${weekDayLabel(localDate.dayOfWeek)}"
}

/** 月历表头（周一为首列）。 */
internal val MONTH_WEEKDAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 尺度短标签（天/周/月/季/年）。 */
internal fun scaleLabel(scale: JourneyScale): String = when (scale) {
    JourneyScale.DAY -> "天"
    JourneyScale.WEEK -> "周"
    JourneyScale.MONTH -> "月"
    JourneyScale.SEASON -> "季"
    JourneyScale.YEAR -> "年"
}

/** Journey canonical 确定性时钟（纳秒；JOURNEY_CANONICAL_TIME_SECONDS 固定相位）。 */
internal val JOURNEY_CANONICAL_NANOS: Long = (JOURNEY_CANONICAL_TIME_SECONDS * 1_000_000_000f).toLong()
