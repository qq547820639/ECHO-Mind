package com.yunjue.echo.mind.journey
import com.yunjue.echo.mind.model.EchoMaturity

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.presence.EchoVisualMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ERA 16 §83/§84 — Canonical Daily State 编解码与历史重建：
 * round-trip / fail-closed / 确定性（一年后仍是同一帧）/ 画像 fallback / 无数据不编造。
 */
class JourneyCanonicalTest {

    @Test
    fun codecRoundTripsEveryField() {
        val day = JourneyCanonicalDay(
            date = "2026-08-14",
            visualSeed = 987654321L,
            visualParams = visualParams { flowSpeed = 0.61f; structureComplexity = 0.23f },
            identityReference = identityGenome(seed = 987654321L),
            maturity = EchoMaturity.MATURE,
            keyEvidenceIds = listOf("portrait:2026-08-14", "portrait:2026-08-13"),
            createdAtEpochMs = 1755100800000L,
        )
        assertEquals(day, JourneyCanonicalCodec.decode(JourneyCanonicalCodec.encode(day)))
    }

    @Test
    fun codecFailsClosedOnGarbage() {
        assertNull(JourneyCanonicalCodec.decode(null))
        assertNull(JourneyCanonicalCodec.decode(""))
        assertNull(JourneyCanonicalCodec.decode("garbage"))
        assertNull(JourneyCanonicalCodec.decode("v2|2026-08-14|1|KNOWN"))
        // 字段数不对
        val encoded = JourneyCanonicalCodec.encode(canonicalDay("2026-08-14"))
        assertNull(JourneyCanonicalCodec.decode("$encoded|extra"))
        // 非法日期
        assertNull(
            JourneyCanonicalCodec.decode(
                encoded.replaceFirst("2026-08-14", "14-08-2026")
            )
        )
    }

    @Test
    fun buildCanonicalDayUsesFrozenVisualMapper() {
        val state = presenceState(seed = 7L)
        val day = buildCanonicalDay(
            date = "2026-08-14",
            state = state,
            keyEvidenceIds = listOf("portrait:2026-08-14"),
            createdAtEpochMs = 1L,
        )
        val expected = EchoVisualMapper.map(
            state = state,
            hourOfDay = JOURNEY_CANONICAL_HOUR,
        )
        assertEquals(expected, day.visualParams)
        assertEquals(state.identityGenome, day.identityReference)
        assertEquals(state.maturity, day.maturity)
        assertEquals(7L, day.visualSeed)
    }

    @Test
    fun reconstructIsDeterministicAcrossTime() {
        val canonical = canonicalDay("2026-08-14", seed = 42L)
        val frame1 = reconstructJourneyFrame(canonical, null, 0L, 1080f, 2340f)
        val frame2 = reconstructJourneyFrame(canonical, null, 0L, 1080f, 2340f)
        assertNotNull(frame1)
        // §84：一年以后再渲染，同输入仍同一帧
        assertEquals(frame1, frame2)
    }

    @Test
    fun reconstructPrefersCanonicalOverPortraitFallback() {
        val canonical = canonicalDay("2026-08-14", seed = 42L)
        val portrait = portrait("2026-08-14")
        val fromCanonical = reconstructJourneyFrame(canonical, portrait, 99L, 100f, 100f)
        val fromPortrait = reconstructJourneyFrame(null, portrait, 99L, 100f, 100f)
        assertNotNull(fromCanonical)
        assertNotNull(fromPortrait)
        assertNotEquals(fromCanonical, fromPortrait)
    }

    @Test
    fun reconstructFallsBackToPortraitDerivedParams() {
        val portrait = portrait("2026-08-14")
        val frame = reconstructJourneyFrame(null, portrait, 42L, 100f, 100f)
        assertNotNull(frame)
    }

    @Test
    fun reconstructReturnsNullWhenNothingKnown() {
        assertNull(reconstructJourneyFrame(null, null, 42L, 100f, 100f))
    }

    private fun portrait(date: String) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 10,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.5),
        ),
    )
}
