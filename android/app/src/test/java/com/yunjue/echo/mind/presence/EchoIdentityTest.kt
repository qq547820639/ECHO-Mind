package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.EchoMomentState
import com.yunjue.echo.mind.model.EchoDailyComposition

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 14 §106 — Presence Identity 测试：
 * identity determinism / daily composition / moment modulation / life season /
 * surface consistency（同一 state 同一映射）/ smoothing（§60 无瞬切）。
 */
class EchoIdentityTest {

    private fun portrait(
        date: String,
        baselineDays: Int = 30,
        movement: String = "SIMILAR",
        rhythm: String = "SIMILAR",
        screen: String = "SIMILAR",
        z: Double = 0.5,
    ) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = baselineDays,
        headline = listOf("接近"),
        summary = "接近平常。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = movement, metric = "m", z = z),
            "RHYTHM" to PortraitDimensionDto(value = rhythm, metric = "r", z = z),
            "SCREEN_AMOUNT" to PortraitDimensionDto(value = screen, metric = "s", z = z),
        ),
    )

    private fun vector(activation: Float = 0.5f, regularity: Float = 0.6f) =
        AmbientVector(activation = activation, regularity = regularity, density = 0.5f, deviation = 0.3f, confidence = 0.7f)

    // ===== §106 identity determinism =====

    @Test
    fun identityIsDeterministicAndStable() {
        val a = deriveIdentityGenome(12345L, 0.6f, PresenceMotionLevel.DEFAULT)
        val b = deriveIdentityGenome(12345L, 0.6f, PresenceMotionLevel.DEFAULT)
        assertEquals(a, b) // 同 seed 同输出（Day 1 = Day 180）
        assertNotEquals(a, deriveIdentityGenome(54321L, 0.6f, PresenceMotionLevel.DEFAULT))
        assertTrue(a.accentHue in 0f..1f)
        assertTrue(a.coreTopology in 0f..1f)
        assertTrue(a.symmetryTendency in 0f..1f)
        assertTrue(a.motionPersonality in 0f..1f)
        assertTrue(a.colorFamily in 0..4)
        assertTrue(a.textureFamily in 0..3)
    }

    @Test
    fun identityShapedByPreferenceButNotReset() {
        val quiet = deriveIdentityGenome(999L, 0.5f, PresenceMotionLevel.QUIET)
        val lively = deriveIdentityGenome(999L, 0.5f, PresenceMotionLevel.LIVELY)
        assertTrue(quiet.motionPersonality < lively.motionPersonality) // 偏好塑形
        assertTrue(quiet.motionPersonality > 0.05f) // 但不重置（§55）
        assertTrue(lively.motionPersonality < 0.95f)
    }

    // ===== §106 life season =====

    @Test
    fun emptySeasonIsNeutral() {
        val season = computeLifeSeason(emptyList())
        assertEquals(0, season.phaseIndex)
        assertEquals(0f, season.drift)
        assertEquals("stable", season.rhythmShift)
        assertEquals("stable", season.mobilityTrend)
    }

    @Test
    fun seasonDetectsNeutralDriftOnly() {
        // 前 30 天 EARLIER → 后 30 天 LATER：节律后移（中性词表，无心理结论）
        val base = java.time.LocalDate.parse("2026-01-01")
        val portraits = (0 until 30).map { i ->
            portrait(base.plusDays(i.toLong()).toString(), rhythm = "EARLIER")
        } + (30 until 60).map { i ->
            portrait(base.plusDays(i.toLong()).toString(), rhythm = "LATER")
        }
        val season = computeLifeSeason(portraits)
        assertEquals("later", season.rhythmShift)
        assertTrue(season.drift > 0f) // 真实漂移（§61 非 0）
        // §57：禁止医学/心理结论词
        val vocabulary = listOf(season.rhythmShift, season.screenFragmentation, season.activityVariability,
            season.mobilityTrend, season.regularityTrend).joinToString(" ")
        for (banned in listOf("depressed", "anxious", "burned", "burnout", "抑郁", "焦虑")) {
            assertFalse("禁词 $banned", vocabulary.lowercase().contains(banned.lowercase()))
        }
    }

    @Test
    fun seasonPhaseBucketsByBaseline() {
        val early = computeLifeSeason(listOf(portrait("2026-01-01", baselineDays = 3)))
        val mid = computeLifeSeason(listOf(portrait("2026-01-01", baselineDays = 20)))
        val mature = computeLifeSeason(listOf(portrait("2026-01-01", baselineDays = 100)))
        assertEquals(0, early.phaseIndex)
        assertEquals(1, mid.phaseIndex)
        assertEquals(3, mature.phaseIndex)
    }

    // ===== §106 daily composition + moment =====

    @Test
    fun dailyCompositionRangesValid() {
        val identity = deriveIdentityGenome(42L, 0.6f, PresenceMotionLevel.DEFAULT)
        val daily = buildDailyComposition(identity, vector())
        for (v in listOf(daily.flowSpeed, daily.coherence, daily.turbulence, daily.particleDensity,
                daily.coreOpenness, daily.dispersion, daily.depth, daily.brightness,
                daily.contrast, daily.accentIntensity, daily.structureComplexity)) {
            assertTrue(v in 0f..1f)
        }
        assertTrue(daily.pulsePeriod in 3.8f..5.6f)
    }

    @Test
    fun momentModulationFollowsVector() {
        val calm = buildMomentState(vector(activation = 0.2f, regularity = 0.8f), hourOfDay = 12f)
        val active = buildMomentState(vector(activation = 0.9f, regularity = 0.3f), hourOfDay = 12f)
        assertTrue(calm.breathingPeriod > active.breathingPeriod) // 平静 = 更长呼吸周期
        assertTrue(active.noiseScale >= 0f)
        assertTrue(active.noiseScale <= 1f)
    }

    // ===== §106 smoothing =====

    @Test
    fun smoothingPreventsJumps() {
        val base = EchoPresenceState(
            dailyComposition = EchoDailyComposition(flowSpeed = 0.2f, coherence = 0.3f),
            momentState = EchoMomentState(breathingPeriod = 5.5f, noiseScale = 0.1f),
            confidence = 0.4f,
        )
        val target = EchoPresenceState(
            dailyComposition = EchoDailyComposition(flowSpeed = 0.9f, coherence = 0.9f),
            momentState = EchoMomentState(breathingPeriod = 3.9f, noiseScale = 0.9f),
            confidence = 0.9f,
        )
        val smoothed = smoothPresenceState(base, target, alpha = 0.35f)
        // 单步变化幅度 < 目标差（interpolation，不瞬切）
        assertTrue(kotlin.math.abs(smoothed.dailyComposition.flowSpeed - base.dailyComposition.flowSpeed) <
            kotlin.math.abs(target.dailyComposition.flowSpeed - base.dailyComposition.flowSpeed))
        assertTrue(smoothed.momentState.breathingPeriod in 3.9f..5.5f)
        // 空 previous → 直接采用新值
        assertEquals(target, smoothPresenceState(null, target, 0.35f))
    }

    // ===== §106 surface consistency =====

    @Test
    fun sameStateSameMappingAcrossSurfaces() {
        val state = EchoPresenceState(
            identityGenome = deriveIdentityGenome(7L, 0.5f, PresenceMotionLevel.DEFAULT),
            dailyComposition = buildDailyComposition(deriveIdentityGenome(7L, 0.5f, PresenceMotionLevel.DEFAULT), vector()),
            momentState = buildMomentState(vector(), 12f),
        )
        // 同一 state 映射两次结果一致（确定性；§63 One ECHO / Multiple Surfaces）
        val app1 = EchoVisualMapper.map(state, 12f, SurfaceMode.APP)
        val app2 = EchoVisualMapper.map(state, 12f, SurfaceMode.APP)
        assertEquals(app1, app2)
        // 不同 Surface 只改强度，不改身份（色相/纹理族不变；参数同构）
        val lock = EchoVisualMapper.map(state, 12f, SurfaceMode.LOCK_SAFE)
        assertTrue(lock.flowSpeed <= app1.flowSpeed) // LOCK_SAFE 更静（§64）
        // §64 lock-safe privacy：视觉参数不含任何文字（无 narrative 字段）
        assertFalse(app1.toString().contains("narrative"))
    }
}
