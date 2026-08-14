package com.yunjue.echo.mind.localportrait

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 端侧当日行为聚合（离线画像引擎，Milestone B 镜像）。
 *
 * 与后端 `app/services/aggregates/calculator.py` **逐语义镜像**：
 * - coverage 只统计 unique (windowStart, schema, source) 窗口（retry/重复不重复计数）；
 * - expected_window_count 由本地日窗口的 UTC 时长动态计算（24h→288、23h→276、25h→300，DST）；
 * - 只消费 aggregate_eligible schema（passive-core-v1）+ 有统计槽位的 source；
 * - movement_index = vector[7] accel_magnitude_std 均值（活动变异度/活动量，与后端一致）；
 * - late_screen：窗口起点换算用户本地时区后 hour >= 21；
 * - active 窗口：screen_on / notification / app_switch 任一非零；
 * - 产品契约：绝不产出任何情绪/心理语义字段（PRD 契约点 2）。
 *
 * 纯 Kotlin 无 Android 依赖。
 */

/** 22 维 passive-core-v1 向量布局（与后端 schema_registry 一致）。 */
object LocalVectorIndices {
    const val ACCEL_MAGNITUDE_STD = 7
    const val SCREEN_ON_COUNT = 14
    const val SCREEN_ON_DURATION_MS = 16
    const val NOTIFICATION_TOTAL = 17
    const val APP_SWITCH_COUNT = 20
}

const val LOCAL_WINDOW_SECONDS = 300L
const val LOCAL_AGGREGATE_SCHEMA = "passive-core-v1"

/** 参与聚合统计的信号源（health 仅参与覆盖度、无统计槽位，不进统计值计算）。 */
val LOCAL_AGGREGATE_SOURCES: Set<String> =
    setOf("accel", "gyro", "screen", "notification", "app_activity")

/** 期望存在的信号源集合（missing_sources 计算用；health 不强制）。 */
val LOCAL_EXPECTED_SOURCES: List<String> =
    listOf("accel", "gyro", "screen", "notification", "app_activity")

/** 本地窗口行（feature_vectors 表中 passive-core-v1 行在内存中的投影）。 */
data class LocalWindowRow(
    val windowStartMs: Long,
    val schemaVersion: String,
    val source: String,
    val vector: List<Float>,
    val sourcesPresent: List<String>
)

/** 单日行为聚合结果（镜像 DailyBehaviorAggregate 的统计字段）。 */
data class LocalDayAggregate(
    val localDate: LocalDate,
    val timezone: String,
    val coverageScore: Double,
    val validWindowCount: Int,
    val expectedWindowCount: Int,
    val movementIndex: Double?,
    val movementVariability: Double?,
    val screenOnMinutes: Double,
    val screenOpenCount: Int,
    val lateScreenMinutes: Double,
    val appSwitchCount: Int,
    val notificationCount: Int,
    val activeStartMinute: Int?,
    val activeEndMinute: Int?,
    val activeHourSpread: Double?,
    val sourcesPresent: List<String>,
    val missingSources: List<String>
)

/** 向量下标防御读：越界（缺省 0 填充的窗口）返回 0.0。 */
fun LocalWindowRow.vectorAt(idx: Int): Double {
    if (idx < 0) return 0.0
    return if (vector.size > idx) vector[idx].toDouble() else 0.0
}

/** schema/source 是否参与聚合统计（镜像 calculator._is_aggregate_feature）。 */
fun isAggregateWindow(row: LocalWindowRow): Boolean =
    row.schemaVersion == LOCAL_AGGREGATE_SCHEMA && row.source in LOCAL_AGGREGATE_SOURCES

/** 本地日 5 分钟窗口数：UTC 窗口时长 / 300（DST 动态 276/288/300）。 */
fun expectedWindowCountForDay(zoneId: ZoneId, localDate: LocalDate): Int {
    val startUtc: Instant = localDate.atStartOfDay(zoneId).toInstant()
    val endUtc: Instant = localDate.plusDays(1).atStartOfDay(zoneId).toInstant()
    val seconds = Duration.between(startUtc, endUtc).seconds
    return maxOf((seconds / LOCAL_WINDOW_SECONDS).toInt(), 1)
}

/** 窗口起点换算到用户本地时区的小时数（late screen 判定用）。 */
fun windowStartLocalHour(windowStartMs: Long, zoneId: ZoneId): Int =
    Instant.ofEpochMilli(windowStartMs).atZone(zoneId).hour

/** 由本地窗口行计算单日聚合（镜像 calculator.compute_daily_aggregate）。 */
fun computeLocalDayAggregate(
    localDate: LocalDate,
    zoneId: ZoneId,
    rows: List<LocalWindowRow>
): LocalDayAggregate {
    val tzName = zoneId.id
    val eligible = rows.filter { isAggregateWindow(it) }

    // coverage：unique (window_start, schema_version, source) 窗口；重复窗口不重复计数
    val validWindowCount = eligible.map { Triple(it.windowStartMs, it.schemaVersion, it.source) }.toSet().size
    val expected = expectedWindowCountForDay(zoneId, localDate)
    val coverageScore = round4(minOf(validWindowCount.toDouble() / expected, 1.0))

    // 移动：产品指标 = vector[7] accel_magnitude_std（活动量）；movement_variability 为 MAD。
    val accelVals = eligible.map { it.vectorAt(LocalVectorIndices.ACCEL_MAGNITUDE_STD) }
        .filter { it > 0 }
    val movementIndex = if (accelVals.isEmpty()) null else accelVals.average()
    val movementVariability = if (accelVals.isEmpty()) null else LocalPortraitMath.mad(accelVals)

    val screenOnMinutes = eligible.sumOf { it.vectorAt(LocalVectorIndices.SCREEN_ON_DURATION_MS) } / 60000.0
    val screenOpenCount = eligible.sumOf { it.vectorAt(LocalVectorIndices.SCREEN_ON_COUNT) }.toInt()
    val late = eligible.filter { windowStartLocalHour(it.windowStartMs, zoneId) >= 21 }
    val lateScreenMinutes = late.sumOf { it.vectorAt(LocalVectorIndices.SCREEN_ON_DURATION_MS) } / 60000.0
    val appSwitchCount = eligible.sumOf { it.vectorAt(LocalVectorIndices.APP_SWITCH_COUNT) }.toInt()
    val notificationCount = eligible.sumOf { it.vectorAt(LocalVectorIndices.NOTIFICATION_TOTAL) }.toInt()

    // active 窗口：screen on / notification / app switch 任一非零
    val active = eligible.filter {
        it.vectorAt(LocalVectorIndices.SCREEN_ON_COUNT) > 0 ||
            it.vectorAt(LocalVectorIndices.NOTIFICATION_TOTAL) > 0 ||
            it.vectorAt(LocalVectorIndices.APP_SWITCH_COUNT) > 0
    }.sortedBy { it.windowStartMs }

    val activeStartMinute: Int?
    val activeEndMinute: Int?
    val activeHourSpread: Double?
    if (active.isNotEmpty()) {
        val first = Instant.ofEpochMilli(active.first().windowStartMs).atZone(zoneId)
        val lastEnd = Instant.ofEpochMilli(active.last().windowStartMs).plusSeconds(LOCAL_WINDOW_SECONDS).atZone(zoneId)
        activeStartMinute = first.hour * 60 + first.minute
        activeEndMinute = lastEnd.hour * 60 + lastEnd.minute
        val hours = active.map { Instant.ofEpochMilli(it.windowStartMs).atZone(zoneId).hour }.toSet()
        activeHourSpread = round4(hours.size / 24.0)
    } else {
        activeStartMinute = null
        activeEndMinute = null
        activeHourSpread = null
    }

    val sources = sortedSetOf<String>()
    eligible.forEach { sources.addAll(it.sourcesPresent) }
    val sourcesPresent = sources.toList()
    val missingSources = LOCAL_EXPECTED_SOURCES.filter { it !in sources }

    return LocalDayAggregate(
        localDate = localDate,
        timezone = tzName,
        coverageScore = coverageScore,
        validWindowCount = validWindowCount,
        expectedWindowCount = expected,
        movementIndex = movementIndex,
        movementVariability = movementVariability,
        screenOnMinutes = screenOnMinutes,
        screenOpenCount = screenOpenCount,
        lateScreenMinutes = lateScreenMinutes,
        appSwitchCount = appSwitchCount,
        notificationCount = notificationCount,
        activeStartMinute = activeStartMinute,
        activeEndMinute = activeEndMinute,
        activeHourSpread = activeHourSpread,
        sourcesPresent = sourcesPresent,
        missingSources = missingSources
    )
}

fun round4(value: Double): Double = kotlin.math.round(value * 10000.0) / 10000.0
