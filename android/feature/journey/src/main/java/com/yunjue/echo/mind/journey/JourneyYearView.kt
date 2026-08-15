package com.yunjue.echo.mind.journey
import com.yunjue.echo.mind.model.EchoIdentityGenome

import com.yunjue.echo.mind.presence.EchoVisualParameters
import java.time.LocalDate

/**
 * ERA 16 §86 — Year View。
 *
 * 年视图不是 365 个小点，而是：
 * - season aggregation（四季视觉聚合 + 趋势 + 上下文时期）；
 * - major shifts（长期视觉转变点及其变化维度）；
 * - context periods（用户自述特殊阶段的时间范围）；
 * - long-term identity evolution（Canonical Daily State 里的身份快照序列）。
 *
 * 全部确定性纯函数；身份演化只依据已落盘的身份快照（没有快照 = 不编造）。
 */

/** 上下文时期（§78 特殊日期聚合为连续区间；同 kind 相邻日期合并）。 */
data class JourneyContextPeriod(
    val startDate: String,
    val endDate: String,
    val kind: String,
)

/** 长期转变点：前后聚合视觉 + 距离 + 变化维度（§87 解释锚点）。 */
data class JourneyMajorShift(
    val date: String,
    /** 转变前聚合段的最后一天（§87 解释行的时间范围起点）。 */
    val beforeDate: String? = null,
    val before: EchoVisualParameters?,
    val after: EchoVisualParameters?,
    val distance: Float,
    val changedAspects: List<String>,
)

/** 身份演化点：某月最近一次 Canonical 身份快照。 */
data class JourneyIdentityPoint(
    val date: String,
    val identity: EchoIdentityGenome,
)

/** 单季摘要（§86 season aggregation）。 */
data class JourneySeasonSummary(
    val season: String,
    val startDate: String,
    val endDate: String,
    val dayCount: Int,
    val visualParams: EchoVisualParameters?,
    val majorShifts: List<JourneyMajorShift>,
    val contextPeriods: List<JourneyContextPeriod>,
    val identitySnapshot: EchoIdentityGenome?,
)

/** 年度视图（§86：四季 + 转变 + 上下文时期 + 身份演化）。 */
data class JourneyYearView(
    val seasons: List<JourneySeasonSummary>,
    val majorShifts: List<JourneyMajorShift>,
    val contextPeriods: List<JourneyContextPeriod>,
    val identityEvolution: List<JourneyIdentityPoint>,
)

/** 季节桶（北半球月序；仅做视觉聚合分组，不承载任何判断语义）。 */
const val SEASON_SPRING = "SPRING"
const val SEASON_SUMMER = "SUMMER"
const val SEASON_AUTUMN = "AUTUMN"
const val SEASON_WINTER = "WINTER"

/** 日期（yyyy-MM-dd）→ 季节；解析失败返回 null。 */
fun seasonOf(date: String): String? = runCatching {
    val month = date.substring(5, 7).toInt()
    when (month) {
        3, 4, 5 -> SEASON_SPRING
        6, 7, 8 -> SEASON_SUMMER
        9, 10, 11 -> SEASON_AUTUMN
        12, 1, 2 -> SEASON_WINTER
        else -> null
    }
}.getOrNull()

/**
 * 季节桶键（标签 + 冬季起始年，形如 "SUMMER-2026"）：
 * 12 月属于当年冬季；1/2 月属于上一年 12 月开始的冬季。
 * 滚动 365 天窗口跨日历年时，同标签不同年份不得合并（§86 season aggregation 真值）。
 */
fun seasonKeyOf(date: String): String? = runCatching {
    val year = date.take(4).toInt()
    val month = date.substring(5, 7).toInt()
    when (month) {
        3, 4, 5 -> "$SEASON_SPRING-$year"
        6, 7, 8 -> "$SEASON_SUMMER-$year"
        9, 10, 11 -> "$SEASON_AUTUMN-$year"
        12 -> "$SEASON_WINTER-$year"
        1, 2 -> "$SEASON_WINTER-${year - 1}"
        else -> null
    }
}.getOrNull()

/** 季节 → 用户可读标签。 */
fun seasonLabel(season: String): String = when (season) {
    SEASON_SPRING -> "春"
    SEASON_SUMMER -> "夏"
    SEASON_AUTUMN -> "秋"
    SEASON_WINTER -> "冬"
    else -> season
}

/** §86 — 构建年度视图（确定性；输入为窗口内的每日单元 + Canonical 快照 + 上下文例外）。 */
fun buildYearView(
    days: List<JourneyDay>,
    canonicalDays: List<JourneyCanonicalDay>,
    contextExceptions: Map<String, String>,
    chunkDays: Int = 30,
): JourneyYearView {
    val sortedDays = days.sortedBy { it.date }
    val sortedCanonical = canonicalDays.sortedBy { it.date }
    val periods = buildContextPeriods(contextExceptions)
    val shifts = detectMajorShifts(sortedDays, chunkDays)

    // 四季聚合：按季节桶（标签 + 冬季起始年）分桶；跨年窗口同标签不同年份分开
    val seasons = sortedDays.groupBy { seasonKeyOf(it.date) }
        .filterKeys { it != null }
        .mapNotNull { (key, seasonDays) ->
            val season = key?.substringBefore('-') ?: return@mapNotNull null
            val start = seasonDays.first().date
            val end = seasonDays.last().date
            val seasonShifts = shifts.filter { it.date in start..end }
            val seasonPeriods = periods.filter { it.startDate in start..end || it.endDate in start..end }
            val identity = sortedCanonical.lastOrNull {
                it.date in start..end
            }?.identityReference
            JourneySeasonSummary(
                season = season,
                startDate = start,
                endDate = end,
                dayCount = seasonDays.size,
                visualParams = journeyAggregateOfDays(seasonDays),
                majorShifts = seasonShifts,
                contextPeriods = seasonPeriods,
                identitySnapshot = identity,
            )
        }.sortedBy { it.startDate }

    // 身份演化：每月最近一次 Canonical 身份快照（无快照的月跳过——不编造）
    val identityPoints = sortedCanonical
        .groupBy { it.date.take(7) }
        .mapNotNull { (month, monthDays) ->
            monthDays.maxByOrNull { it.createdAtEpochMs }?.let { latest ->
                JourneyIdentityPoint(date = latest.date, identity = latest.identityReference)
            }
        }
        .sortedBy { it.date }

    return JourneyYearView(
        seasons = seasons,
        majorShifts = shifts,
        contextPeriods = periods,
        identityEvolution = identityPoints,
    )
}

/** 上下文例外（date → kind）→ 连续时期（同 kind、日期相邻或间隔 1 天合并）。 */
fun buildContextPeriods(contextExceptions: Map<String, String>): List<JourneyContextPeriod> {
    val dated = contextExceptions.entries
        .mapNotNull { (date, kind) -> runCatching { LocalDate.parse(date) to kind }.getOrNull() }
        .sortedBy { it.first }
    if (dated.isEmpty()) return emptyList()
    val periods = mutableListOf<Pair<String, MutableList<LocalDate>>>()
    for ((date, kind) in dated) {
        val last = periods.lastOrNull()
        if (last != null && last.first == kind && !last.second.last().plusDays(2).isBefore(date)) {
            last.second.add(date)
        } else {
            periods.add(kind to mutableListOf(date))
        }
    }
    return periods.map { (kind, dates) ->
        JourneyContextPeriod(
            startDate = dates.first().toString(),
            endDate = dates.last().toString(),
            kind = kind,
        )
    }
}

/**
 * 长期转变检测：相邻聚合段的视觉距离 + 行为方向性偏差（§40）超过阈值 → 转变点。
 * 返回的 [JourneyMajorShift.date] 为后段的起始日期。
 * 同一变化在滑动窗口下产生的相邻转变点合并（间隔 ≤ chunkDays 且上下文一致）。
 */
fun detectMajorShifts(
    days: List<JourneyDay>,
    chunkDays: Int = 30,
    threshold: Float = RIVER_TRANSITION_DISTANCE,
): List<JourneyMajorShift> {
    val sorted = days.sortedBy { it.date }
    if (sorted.isEmpty() || chunkDays <= 0) return emptyList()
    val chunks = sorted.chunked(chunkDays)
    val shifts = mutableListOf<JourneyMajorShift>()
    for (i in 1 until chunks.size) {
        val before = journeyAggregateOfDays(chunks[i - 1])
        val after = journeyAggregateOfDays(chunks[i])
        if (before == null || after == null) continue
        val distance = maxOf(visualDistance(before, after), directionalDeviation(chunks[i - 1], chunks[i]))
        if (distance >= threshold) {
            shifts.add(
                JourneyMajorShift(
                    date = chunks[i].first().date,
                    beforeDate = chunks[i - 1].last().date,
                    before = before,
                    after = after,
                    distance = distance,
                    changedAspects = changedVisualAspects(before, after),
                )
            )
        }
    }
    // 同一变化相邻块重复命中 → 合并（保留最早日期与最大距离）
    val merged = mutableListOf<JourneyMajorShift>()
    for (shift in shifts) {
        val last = merged.lastOrNull()
        val close = last != null && runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(last.date), java.time.LocalDate.parse(shift.date),
            )
        }.getOrDefault(Long.MAX_VALUE) <= chunkDays
        if (close) {
            if (shift.distance > last.distance) merged[merged.size - 1] = shift.copy(date = last.date)
        } else {
            merged.add(shift)
        }
    }
    return merged
}

/**
 * §86 — 身份演化逐点说明：每月快照日期 + 与上一记录的一致性。
 * 只比较基因组字段是否相等（稳定/细微调整），不做任何推断（§55/§57）。
 */
fun identityEvolutionLines(points: List<JourneyIdentityPoint>): List<String> {
    if (points.isEmpty()) return emptyList()
    return points.mapIndexed { index, point ->
        val prev = points.getOrNull(index - 1)
        val annotation = when {
            prev == null -> ""
            prev.identity == point.identity -> "（与上一记录一致）"
            else -> "（较上一记录有细微调整）"
        }
        point.date + annotation
    }
}
