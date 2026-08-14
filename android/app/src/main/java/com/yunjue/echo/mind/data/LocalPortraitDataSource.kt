package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.localportrait.LocalBaselineCalculator
import com.yunjue.echo.mind.localportrait.LocalBaselineSnapshot
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.localportrait.LocalPortraitEngine
import com.yunjue.echo.mind.localportrait.LocalWindowRow
import com.yunjue.echo.mind.localportrait.computeLocalDayAggregate
import com.yunjue.echo.mind.model.BaselineStatusDto
import com.yunjue.echo.mind.model.DailyPortraitDto
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 端侧画像数据源（离线演示模式 / 离线回退）：
 * 从 Room `feature_vectors`（本就只存端侧派生摘要，不上云）读取历史窗口，
 * 经端侧画像引擎（[LocalPortraitEngine]）确定性重算「今天的你 vs 平常的你」。
 *
 * 与服务端流水线逐语义镜像；服务端可用时以服务端为准（本数据源只做回退）。
 * 隐私：只读本地表，不产生任何新上行。
 */

/** ERA 2：Presence 输入（AmbientEngine 的原始证据，只读本地表；模块内部类型，不跨模块暴露）。 */
internal data class PresenceInputs(
    val today: LocalDayAggregate?,
    val baseline: LocalBaselineSnapshot?,
)
class LocalPortraitDataSource(private val db: EchoDatabase) :
    com.yunjue.echo.mind.ports.ObservationEvidenceSource {

    /** 读取某用户全部 passive-core-v1 窗口行（按窗口起点升序）。 */
    suspend fun passiveCoreRows(userId: String): List<LocalWindowRow> =
        db.dao().allPassiveCoreRows(userId).map { entity ->
            LocalWindowRow(
                windowStartMs = entity.windowStart,
                schemaVersion = entity.schemaVersion,
                source = entity.source,
                vector = parseVectorJson(entity.vector),
                // v7 旧行无该列：回退按 source 单元素集合解释（覆盖度语义不变，confidence 保守）
                sourcesPresent = parseSourcesJson(entity.sourcesPresentJson, entity.source)
            )
        }

    /** 按本地日分组计算聚合（单遍扫描；基线窗口过滤在 buildLocalBaseline 内部完成）。 */
    private fun aggregatesByDay(
        rows: List<LocalWindowRow>,
        zoneId: ZoneId
    ): Map<LocalDate, com.yunjue.echo.mind.localportrait.LocalDayAggregate> {
        val grouped = rows.groupBy { Instant.ofEpochMilli(it.windowStartMs).atZone(zoneId).toLocalDate() }
        return grouped.mapValues { (date, dayRows) -> computeLocalDayAggregate(date, zoneId, dayRows) }
    }

    /** 今日画像（本地确定性重算；无今日数据时仍返回冷启动状态视图）。 */
    override suspend fun computeToday(
        userId: String,
        today: LocalDate,
        zoneId: ZoneId
    ): DailyPortraitDto {
        val rows = passiveCoreRows(userId)
        val aggregates = aggregatesByDay(rows, zoneId)
        return LocalPortraitEngine.generate(today, zoneId, aggregates[today], aggregates.values.toList())
    }

    /** 最近 [days] 天画像时间线（本地确定性重算，按日期升序；与后端 /portraits 语义一致）。 */
    override suspend fun computeTimeline(
        userId: String,
        days: Int,
        endDate: LocalDate,
        zoneId: ZoneId
    ): List<DailyPortraitDto> {
        val rows = passiveCoreRows(userId)
        val aggregates = aggregatesByDay(rows, zoneId)
        return (0 until days).map { i ->
            val date = endDate.minusDays((days - 1 - i).toLong())
            LocalPortraitEngine.generate(date, zoneId, aggregates[date], aggregates.values.toList())
        }
    }

    /** 基线状态（镜像后端 GET /baseline/status 的轻量只读视图；绝不写库）。 */
    suspend fun baselineStatus(
        userId: String,
        today: LocalDate,
        zoneId: ZoneId
    ): BaselineStatusDto {
        val rows = passiveCoreRows(userId)
        val aggregates = aggregatesByDay(rows, zoneId)
        val todayAgg = aggregates[today]
        val todayCoverage = todayAgg?.coverageScore ?: 0.0
        val snapshot = com.yunjue.echo.mind.localportrait.buildLocalBaseline(today, aggregates.values.toList())
        val state = LocalBaselineCalculator.baselineState(snapshot.validDays)
        val confidence = if (state == "BASELINE_READY") {
            LocalPortraitEngine.confidenceFor(
                todayCoverage, snapshot.validDays,
                todayAgg?.missingSources.orEmpty()
            )
        } else {
            "LOW"
        }
        return BaselineStatusDto(
            status = state,
            baselineDays = snapshot.validDays,
            baselineVersion = snapshot.version,
            windowStart = snapshot.windowStart.toString(),
            windowEnd = snapshot.windowEnd.toString(),
            bucketUsage = snapshot.bucket,
            todayCoverage = todayCoverage
        )
    }

    /** ERA 2：Presence 输入（当日聚合 + 基线快照；AmbientEngine 的数据源，只读）。 */
    internal suspend fun presenceInputs(
        userId: String,
        today: LocalDate,
        zoneId: ZoneId
    ): PresenceInputs {
        val rows = passiveCoreRows(userId)
        val aggregates = aggregatesByDay(rows, zoneId)
        return PresenceInputs(
            today = aggregates[today],
            baseline = com.yunjue.echo.mind.localportrait.buildLocalBaseline(today, aggregates.values.toList()),
        )
    }

    companion object {
        /** vector JSON 数组字符串 → List<Float>；解析失败 fail-closed 返回空列表。 */
        internal fun parseVectorJson(json: String): List<Float> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optDouble(it).toFloat() }
        }.getOrDefault(emptyList())

        /** sourcesPresent JSON → List<String>；空/null → 回退 [source]（保守单元素）。 */
        internal fun parseSourcesJson(json: String?, fallbackSource: String): List<String> {
            if (json.isNullOrBlank()) return listOf(fallbackSource)
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(listOf(fallbackSource))
        }
    }
}
