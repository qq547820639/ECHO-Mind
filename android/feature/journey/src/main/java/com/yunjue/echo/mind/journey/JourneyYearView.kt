package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.presence.EchoIdentityGenome
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

    // 四季聚合：按季节分桶（year 窗口内可能不足四季——不足四季时只输出有数据的季）
    val seasons = sortedDays.groupBy { seasonOf(it.date) }
        .filterKeys { it != null }
        .map { (season, seasonDays) ->
            val s = season ?: return@map null
            val start = seasonDays.first().date
            val end = seasonDays.last().date
            val seasonShifts = shifts.filter { it.date in start..end }
            val seasonPeriods = periods.filter { it.startDate in start..end || it.endDate in start..end }
            val identity = sortedCanonical.lastOrNull {
                it.date in start..end
            }?.identityReference
            JourneySeasonSummary(
                season = s,
                startDate = start,
                endDate = end,
                dayCount = seasonDays.size,
                visualParams = journeyAggregateOfDays(seasonDays),
                majorShifts = seasonShifts,
                contextPeriods = seasonPeriods,
                identitySnapshot = identity,
            )
        }.filterNotNull().sortedBy { it.startDate }

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
 * 长期转变检测：相邻聚合段的视觉距离超过阈值 → 转变点。
 * 返回的 [JourneyMajorShift.date] 为后段的起始日期。
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
        val distance = visualDistance(before, after)
        if (distance >= threshold) {
            shifts.add(
                JourneyMajorShift(
                    date = chunks[i].first().date,
                    before = before,
                    after = after,
                    distance = distance,
                    changedAspects = changedVisualAspects(before, after),
                )
            )
        }
    }
    return shifts
}
