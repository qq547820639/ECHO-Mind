package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T8-P2-3 补缺：portraitMaturityProxy 显式代理语义测试（此前零测试引用）。
 *
 * Journey 历史重建无苏醒锚点：earliestDate 提供时按「画像日期相对时间线最早日期的
 * 日历跨度」代理（单调、可越过 28，与 Presence 日历钟同构）；earliestDate 缺失/
 * 不可解析时退化为 baselineDays（28 天窗口分桶有效日，≤20，长期用户低估——仅为兜底）。
 */
class JourneyVisualsTest {

    private fun portrait(date: String, baselineDays: Int) = DailyPortraitDto(
        date = date, status = "READY", confidence = "HIGH",
        baselineDays = baselineDays, summary = "测试画像",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto("SIMILAR"),
            "SCREEN_AMOUNT" to PortraitDimensionDto("SIMILAR"),
            "RHYTHM" to PortraitDimensionDto("SIMILAR"),
        ),
    )

    @Test
    fun earliestDateProvidedUsesCalendarSpanSemantics() {
        val earliest = "2026-01-01"
        assertEquals(EchoMaturity.SEED, portraitMaturityProxy(portrait(earliest, 0), earliest))
        assertEquals(EchoMaturity.DISCOVERING, portraitMaturityProxy(portrait("2026-01-02", 1), earliest))
        assertEquals(EchoMaturity.EMERGING, portraitMaturityProxy(portrait("2026-01-04", 1), earliest))
        assertEquals(EchoMaturity.KNOWN, portraitMaturityProxy(portrait("2026-01-08", 1), earliest))
        // 可越过 28：长期用户不因 baselineDays 窗口低估（baselineDays=5 也照常 MATURE）
        assertEquals(EchoMaturity.MATURE, portraitMaturityProxy(portrait("2026-02-15", 5), earliest))
    }

    @Test
    fun nullEarliestDateFallsBackToBaselineDays() {
        assertEquals(EchoMaturity.MATURE, portraitMaturityProxy(portrait("2026-08-18", 28), null))
        assertEquals(EchoMaturity.KNOWN, portraitMaturityProxy(portrait("2026-08-18", 20), null))
        assertEquals(EchoMaturity.SEED, portraitMaturityProxy(portrait("2026-08-18", 0), null))
    }

    @Test
    fun malformedEarliestDateDegradesToBaselineDaysFallback() {
        assertEquals(
            EchoMaturity.KNOWN,
            portraitMaturityProxy(portrait("2026-08-18", 20), "not-a-date"),
        )
    }

    @Test
    fun malformedPortraitDateDegradesToBaselineDaysFallback() {
        // earliest 可解析但画像日期不可解析 → 无法计算跨度 → 同样退 baselineDays
        assertEquals(
            EchoMaturity.KNOWN,
            portraitMaturityProxy(portrait("bad-date", 20), "2026-01-01"),
        )
    }

    @Test
    fun calendarSpanDominatesBaselineDaysWhenBothAvailable() {
        // 日历跨度与 baselineDays 不一致时，earliestDate 语义优先（20 有效日但仅过 2 天 → DISCOVERING）
        assertEquals(
            EchoMaturity.DISCOVERING,
            portraitMaturityProxy(portrait("2026-01-03", 20), "2026-01-01"),
        )
    }
}
