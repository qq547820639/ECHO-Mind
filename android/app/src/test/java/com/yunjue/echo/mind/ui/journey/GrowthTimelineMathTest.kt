package com.yunjue.echo.mind.ui.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 成长页真实数据装配纯函数测试（设计稿 19 时间线 + 设计稿 9 月画像序列）。
 *
 * 契约：日期只锚定真实记录；设计稿示意值不进入实现；无数据 → 空序列/弃权。
 */
class GrowthTimelineMathTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 6)

    private fun dates(n: Int, start: LocalDate = LocalDate.of(2026, 6, 1)): List<String> =
        (0 until n).map { start.plusDays(it.toLong()).toString() }

    // ===== buildGrowthTimeline =====

    @Test
    fun emptyWhenNoRecordsAndNoBaseline() {
        assertTrue(buildGrowthTimeline(canonicalDates = emptyList(), baselineDays = 0, today = today).isEmpty())
    }

    @Test
    fun firstRecordAndTodayWhenFewRecords() {
        val points = buildGrowthTimeline(dates(3), baselineDays = 0, today = today)
        assertEquals(2, points.size)
        assertEquals("初次相遇", points[0].label)
        assertEquals("6月1日", points[0].date)
        assertEquals("越来越懂你", points[1].label)
        assertTrue(points[1].isToday)
        assertEquals("9月6日", points[1].date)
    }

    @Test
    fun understandingAtSeventhRecord() {
        val points = buildGrowthTimeline(dates(7), baselineDays = 7, today = today)
        assertEquals(3, points.size)
        assertEquals("开始理解", points[1].label)
        assertEquals("6月7日", points[1].date)
    }

    @Test
    fun rhythmAtThirtiethRecord() {
        val points = buildGrowthTimeline(dates(30), baselineDays = 30, today = today)
        assertEquals(4, points.size)
        assertEquals("建立节律", points[2].label)
        assertEquals("6月30日", points[2].date)
        assertEquals("越来越懂你", points[3].label)
    }

    @Test
    fun baselineFallbackWhenNoRecords() {
        val points = buildGrowthTimeline(emptyList(), baselineDays = 10, today = today)
        assertEquals(2, points.size)
        assertEquals("基线形成中", points[0].label)
        assertEquals("第 10 天", points[0].date)
        assertEquals("越来越懂你", points[1].label)
    }

    @Test
    fun invalidDatesIgnored() {
        val points = buildGrowthTimeline(listOf("not-a-date", "2026-08-01"), baselineDays = 0, today = today)
        assertEquals(2, points.size)
        assertEquals("初次相遇", points[0].label)
        assertEquals("8月1日", points[0].date)
    }

    // ===== buildMonthTrendSeries =====

    private fun portrait(date: String, vararg dims: Pair<String, String>) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 30,
        dimensions = dims.toMap().mapValues { PortraitDimensionDto(value = it.value) },
    )

    @Test
    fun monthSeriesEmptyWhenNoPortraits() {
        val series = buildMonthTrendSeries(emptyList())
        assertTrue(series.isEmptyData)
        assertTrue(series.xLabels.isEmpty())
        assertEquals(4, series.dimensions.size)
    }

    @Test
    fun monthSeriesHonestLabelsAndMissingAsDash() {
        val series = buildMonthTrendSeries(
            listOf(portrait("2026-08-01", "MOVEMENT" to "MORE")),
        )
        assertFalse(series.isEmptyData)
        assertEquals(
            listOf("活动量", "屏幕时长", "屏幕节奏", "一天结构"),
            series.dimensions.map { it.first },
        )
        assertEquals(listOf("MORE"), series.dimensions[0].second)
        assertEquals(listOf("—"), series.dimensions[1].second)
        assertTrue(series.xLabels.isEmpty()) // 单日不足成轴
    }

    @Test
    fun monthSeriesXLabelsSampledAtFivePoints() {
        val portraits = (0 until 20).map { i ->
            portrait(String.format("2026-08-%02d", i + 1), "MOVEMENT" to "SIMILAR")
        }
        val series = buildMonthTrendSeries(portraits)
        assertFalse(series.isEmptyData)
        assertEquals(listOf("8/1", "8/6", "8/11", "8/16", "8/20"), series.xLabels)
    }

    @Test
    fun monthSeriesEmptyWhenOnlyBlankValues() {
        val series = buildMonthTrendSeries(listOf(portrait("2026-08-01")))
        assertTrue(series.isEmptyData)
    }
}
