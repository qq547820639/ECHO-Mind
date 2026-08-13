package com.yunjue.echo.mind

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunjue.echo.mind.localportrait.LocalPortraitEngine
import com.yunjue.echo.mind.localportrait.LocalWindowRow
import com.yunjue.echo.mind.localportrait.computeLocalDayAggregate
import com.yunjue.echo.mind.localportrait.computeLocalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * 端侧画像引擎设备烟测（v0.7.2）：纯 Kotlin 逻辑在真实 Android 运行时上的
 * golden 场景执行（镜像后端 test_portrait_golden 场景 001：偏晚/移动减少/屏幕接近）。
 */
@RunWith(AndroidJUnit4::class)
class LocalPortraitEngineInstrumentedTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 8, 10)

    private fun utcStartMs(): Long = today.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun windowRow(
        source: String,
        localMinute: Int,
        vector: Map<Int, Double> = emptyMap(),
        sources: List<String> = listOf(source)
    ): LocalWindowRow {
        val v = MutableList(22) { 0f }
        vector.forEach { (idx, value) -> v[idx] = value.toFloat() }
        return LocalWindowRow(
            windowStartMs = utcStartMs() + localMinute * 60_000L,
            schemaVersion = "passive-core-v1",
            source = source,
            vector = v,
            sourcesPresent = sources
        )
    }

    @Test
    fun goldenScenario001ReadyLaterOnDevice() {
        val rows = mutableListOf<LocalWindowRow>()
        for (i in 0 until 240) rows.add(windowRow("screen", 5 * i, sources = emptyList()))
        rows.add(windowRow("screen", 540, mapOf(14 to 1.0, 16 to 103 * 60000.0), listOf("screen")))
        rows.add(windowRow("screen", 22 * 60, mapOf(14 to 1.0, 16 to 23 * 60000.0), listOf("screen")))
        rows.add(windowRow("accel", 545, mapOf(7 to 0.5), listOf("accel")))
        rows.add(windowRow("notification", 9 * 60, mapOf(17 to 13.0), listOf("notification")))
        rows.add(windowRow("app_activity", 10 * 60, mapOf(20 to 43.0), listOf("app_activity")))

        // 28 天阶梯基线（i%7，与后端 make_baseline_rows 一致）
        val baselineRows = (0 until 28).map { i ->
            val k = i % 7
            com.yunjue.echo.mind.localportrait.LocalDayAggregate(
                localDate = today.minusDays(28).plusDays(i.toLong()),
                timezone = "Asia/Shanghai",
                coverageScore = 0.8, validWindowCount = 230, expectedWindowCount = 288,
                movementIndex = 1.0 + k * 0.02, movementVariability = 0.02,
                screenOnMinutes = 120.0 + k * 2.0, screenOpenCount = 30,
                lateScreenMinutes = 20.0 + k, appSwitchCount = 40 + k, notificationCount = 10 + k,
                activeStartMinute = 480 + k * 5, activeEndMinute = 1320 + k * 5,
                activeHourSpread = 0.16 + k * 0.005,
                sourcesPresent = listOf("accel", "gyro", "screen", "notification", "app_activity"),
                missingSources = emptyList()
            )
        }

        val dto = LocalPortraitEngine.generate(
            localDate = today,
            zoneId = zone,
            todayAggregate = computeLocalDayAggregate(today, zone, rows),
            pastAggregates = baselineRows
        )
        assertEquals("READY", dto.status)
        assertEquals("LATER", dto.dimensions["RHYTHM"]?.value)
        assertEquals("LESS", dto.dimensions["MOVEMENT"]?.value)
        assertTrue(dto.summary.contains("稍晚"))
        assertTrue(dto.headline.contains("偏晚"))
        assertTrue(dto.baselineDays >= 7)
    }
}
