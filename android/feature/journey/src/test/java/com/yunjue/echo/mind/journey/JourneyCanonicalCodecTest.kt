package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.presence.EchoVisualParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ERA 16 §83 — JourneyCanonicalCodec v1（历史快照）解码：
 * v1 = `v1|date|seed|maturity|params(12)|identity(8)|evidenceIdsCsv|createdAtEpochMs`（26 段）；
 * v2 增量字段解析为默认 0f（fail-closed 向后兼容）；v2 roundtrip 不回归。
 */
class JourneyCanonicalCodecTest {

    /** 按 v1 历史 encode 布局构造 26 段串（params 4..15 / identity 16..23 / evidence 24 / createdAt 25）。 */
    private fun v1Snapshot(
        evidenceCsv: String = "portrait:2026-01-05,portrait:2026-01-04",
        maturity: String = "MATURE",
    ): String = listOf(
        "v1",
        "2026-01-05",
        "987654321",
        maturity,
        // params(12)
        "0.61", "0.72", "0.13", "0.44", "0.55", "0.26",
        "4.8", "0.67", "0.58", "0.39", "0.31", "0.22",
        // identity(8)
        "987654321", "0.45", "2", "1", "0.55", "0.6", "0.5", "0.4",
        // evidence / createdAt
        evidenceCsv,
        "1755100800000",
    ).joinToString("|")

    @Test
    fun decodesV1SnapshotWithSharedFieldsIntact() {
        val day = JourneyCanonicalCodec.decode(v1Snapshot())
        assertNotNull("v1 历史快照必须可解码（KDoc 向后兼容承诺）", day)
        day!!
        assertEquals("2026-01-05", day.date)
        assertEquals(987654321L, day.visualSeed)
        assertEquals(EchoMaturity.MATURE, day.maturity)
        assertEquals(0.61f, day.visualParams.flowSpeed, 0f)
        assertEquals(0.72f, day.visualParams.coherence, 0f)
        assertEquals(0.13f, day.visualParams.turbulence, 0f)
        assertEquals(0.44f, day.visualParams.particleDensity, 0f)
        assertEquals(0.55f, day.visualParams.coreOpenness, 0f)
        assertEquals(0.26f, day.visualParams.dispersion, 0f)
        assertEquals(4.8f, day.visualParams.pulsePeriodSeconds, 0f)
        assertEquals(0.67f, day.visualParams.depth, 0f)
        assertEquals(0.58f, day.visualParams.brightness, 0f)
        assertEquals(0.39f, day.visualParams.contrast, 0f)
        assertEquals(0.31f, day.visualParams.accentIntensity, 0f)
        assertEquals(0.22f, day.visualParams.structureComplexity, 0f)
        assertEquals(
            EchoIdentityGenome(
                seed = 987654321L,
                accentHue = 0.45f,
                colorFamily = 2,
                textureFamily = 1,
                coreTopology = 0.55f,
                symmetryTendency = 0.6f,
                orbitGeometry = 0.5f,
                motionPersonality = 0.4f,
            ),
            day.identityReference,
        )
        assertEquals(listOf("portrait:2026-01-05", "portrait:2026-01-04"), day.keyEvidenceIds)
        assertEquals(1755100800000L, day.createdAtEpochMs)
    }

    @Test
    fun decodesV1SnapshotWithV2FieldsDefaultingToZero() {
        val day = JourneyCanonicalCodec.decode(v1Snapshot())!!
        assertEquals(0f, day.visualParams.dataClarity, 0f)
        assertEquals(0f, day.visualParams.haloIntensity, 0f)
        assertEquals(0f, day.visualParams.momentIntensity, 0f)
        assertEquals(0f, day.visualParams.filamentDensity, 0f)
        assertEquals(0f, day.visualParams.seasonPhase, 0f)
        assertEquals(0f, day.visualParams.dayComposition, 0f)
    }

    @Test
    fun decodesV1SnapshotWithEmptyEvidence() {
        val day = JourneyCanonicalCodec.decode(v1Snapshot(evidenceCsv = ""))
        assertNotNull(day)
        assertEquals(emptyList<String>(), day!!.keyEvidenceIds)
    }

    @Test
    fun v1MalformedStillFailsClosed() {
        // 段数错误（26 段 + extra → 非 v1 非 v2 布局）
        assertNull(JourneyCanonicalCodec.decode(v1Snapshot() + "|extra"))
        // 非法日期
        assertNull(JourneyCanonicalCodec.decode(v1Snapshot().replaceFirst("2026-01-05", "05-01-2026")))
        // 非法 maturity
        assertNull(JourneyCanonicalCodec.decode(v1Snapshot(maturity = "NOT_A_MATURITY")))
        // v1 头 + v2 段数（32 段）→ 不匹配任一布局
        assertNull(JourneyCanonicalCodec.decode("v1" + JourneyCanonicalCodec.encode(canonicalV2Day()).drop(2)))
    }

    @Test
    fun codecV2RoundTripStaysLossless() {
        val day = canonicalV2Day()
        assertEquals(day, JourneyCanonicalCodec.decode(JourneyCanonicalCodec.encode(day)))
    }

    private fun canonicalV2Day() = JourneyCanonicalDay(
        date = "2026-08-14",
        visualSeed = 987654321L,
        visualParams = EchoVisualParameters(
            flowSpeed = 0.61f,
            coherence = 0.72f,
            turbulence = 0.13f,
            particleDensity = 0.44f,
            coreOpenness = 0.55f,
            dispersion = 0.26f,
            pulsePeriodSeconds = 4.8f,
            depth = 0.67f,
            brightness = 0.58f,
            contrast = 0.39f,
            accentIntensity = 0.31f,
            structureComplexity = 0.22f,
            dataClarity = 0.71f,
            haloIntensity = 0.12f,
            momentIntensity = 0.33f,
            filamentDensity = 0.24f,
            seasonPhase = 0.5f,
            dayComposition = 0.47f,
        ),
        identityReference = EchoIdentityGenome(seed = 987654321L),
        maturity = EchoMaturity.KNOWN,
        keyEvidenceIds = listOf("portrait:2026-08-14"),
        createdAtEpochMs = 1755100800000L,
    )
}
