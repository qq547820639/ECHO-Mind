package com.yunjue.echo.mind.localportrait

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 端侧个人基线（离线画像引擎，Milestone C 镜像）。
 *
 * 与后端 `app/services/baseline/calculator.py` **逐语义镜像**：
 * - 有效日 = 存在聚合且 coverage_score >= 0.25 且 local_date 在 [today-28, today-1]；
 * - weekday（周一至周五）/ weekend（周六日）分桶；某桶有效日 < 2 则 fallback all_days；
 * - baseline_state：0-2 → WARMING_UP，3-6 → EARLY_BASELINE，>=7 → BASELINE_READY；
 * - active_start_minute / active_end_minute 使用圆周 median/MAD（跨午夜正确）；
 * - baseline_version 固定 "base-v1"。
 *
 * 纯 Kotlin 无 Android 依赖。
 */
internal object LocalBaselineCalculator {

    /** 基线统计窗口：近 28 天。 */
    const val WINDOW_DAYS = 28L

    /** 有效日覆盖阈值。 */
    const val MIN_COVERAGE = 0.25

    /** 桶内最少有效日（不足则 fallback all_days）。 */
    const val MIN_BUCKET_DAYS = 2

    const val BASELINE_VERSION = "base-v1"

    /** 参与基线统计的行为指标（active_end_minute 仅供 explain/事实使用）。 */
    val BASELINE_METRICS: List<String> = listOf(
        "movement_index",
        "screen_on_minutes",
        "screen_open_count",
        "late_screen_minutes",
        "app_switch_count",
        "notification_count",
        "active_start_minute",
        "active_end_minute",
        "active_hour_spread"
    )

    /** 时间类分钟指标：使用圆周 median/MAD（23:55 与 00:05 正确接近）。 */
    val CIRCULAR_METRICS: Set<String> = setOf("active_start_minute", "active_end_minute")

    /** 冷启动状态机：0-2 WARMING_UP / 3-6 EARLY_BASELINE / >=7 BASELINE_READY。 */
    fun baselineState(validDays: Int): String = when {
        validDays <= 2 -> "WARMING_UP"
        validDays <= 6 -> "EARLY_BASELINE"
        else -> "BASELINE_READY"
    }
}

/** 基线快照（镜像 backend baseline/models.py BaselineSnapshot）。 */
internal data class LocalBaselineSnapshot(
    val bucket: String,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val validDays: Int,
    val metrics: Map<String, LocalMetricStats>,
    val version: String = LocalBaselineCalculator.BASELINE_VERSION
)

internal fun isWeekendLocal(date: LocalDate): Boolean =
    date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

internal fun bucketForDateLocal(date: LocalDate): String =
    if (isWeekendLocal(date)) "weekend" else "weekday"

internal fun LocalDayAggregate.metricValue(metric: String): Double? = when (metric) {
    "movement_index" -> movementIndex
    "screen_on_minutes" -> screenOnMinutes
    "screen_open_count" -> screenOpenCount.toDouble()
    "late_screen_minutes" -> lateScreenMinutes
    "app_switch_count" -> appSwitchCount.toDouble()
    "notification_count" -> notificationCount.toDouble()
    "active_start_minute" -> activeStartMinute?.toDouble()
    "active_end_minute" -> activeEndMinute?.toDouble()
    "active_hour_spread" -> activeHourSpread
    else -> null
}

/**
 * 构建基线快照（镜像 backend baseline.calculator.build_baseline）。
 *
 * @param pastAggregates 该用户 [today-28, today-1] 窗口内的聚合行（含 coverage 过滤前的全量；
 *                       函数内部按 MIN_COVERAGE 过滤，与后端 SQL 过滤语义一致）。
 */
internal fun buildLocalBaseline(
    todayLocal: LocalDate,
    pastAggregates: List<LocalDayAggregate>
): LocalBaselineSnapshot {
    val start = todayLocal.minusDays(LocalBaselineCalculator.WINDOW_DAYS)
    val end = todayLocal.minusDays(1)

    val aggs = pastAggregates.filter {
        it.localDate in start..end && it.coverageScore >= LocalBaselineCalculator.MIN_COVERAGE
    }

    val weekdayAggs = aggs.filter { !isWeekendLocal(it.localDate) }
    val weekendAggs = aggs.filter { isWeekendLocal(it.localDate) }
    val weekdayDays = weekdayAggs.map { it.localDate }.toSet().size
    val weekendDays = weekendAggs.map { it.localDate }.toSet().size
    val allDays = aggs.map { it.localDate }.toSet().size

    val todayBucket = bucketForDateLocal(todayLocal)
    val bucket: String
    val chosen: List<LocalDayAggregate>
    val validDays: Int
    if (todayBucket == "weekday") {
        if (weekdayDays >= LocalBaselineCalculator.MIN_BUCKET_DAYS) {
            bucket = "weekday"; chosen = weekdayAggs; validDays = weekdayDays
        } else {
            bucket = "all_days"; chosen = aggs; validDays = allDays
        }
    } else {
        if (weekendDays >= LocalBaselineCalculator.MIN_BUCKET_DAYS) {
            bucket = "weekend"; chosen = weekendAggs; validDays = weekendDays
        } else {
            bucket = "all_days"; chosen = aggs; validDays = allDays
        }
    }

    val metrics = LinkedHashMap<String, LocalMetricStats>()
    for (name in LocalBaselineCalculator.BASELINE_METRICS) {
        val values = chosen.mapNotNull { it.metricValue(name) }
        metrics[name] = computeLocalStats(values, circular = name in LocalBaselineCalculator.CIRCULAR_METRICS)
    }

    return LocalBaselineSnapshot(
        bucket = bucket,
        windowStart = start,
        windowEnd = end,
        validDays = validDays,
        metrics = metrics
    )
}
