package com.yunjue.echo.mind
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.AmbientVector
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.buildDailyComposition
import com.yunjue.echo.mind.presence.computeVisualParameters
import com.yunjue.echo.mind.presence.dayBrightnessCurve
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.presence.maturityOpenness
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 2：Visual Profile + EchoSceneModel 回归。
 * 确定性（同一输入同一帧）+ 参数边界 + 低置信度更弥散 + 夜间更暗更慢。
 * V3 §H/§M：computeVisualParameters 不再有 surface 参数——
 * reduceMotion/motionLevel/nightMode 直接进入映射；Surface/Quality 由渲染层正交承载。
 */
class VisualProfileTest {

    private fun state(
        activation: Float = 0.5f,
        density: Float = 0.5f,
        deviation: Float = 0.3f,
        confidence: Float = 0.6f,
        regularity: Float = 0.6f,
        maturity: EchoMaturity = EchoMaturity.KNOWN,
    ) = EchoPresenceState(
        sensingStatus = SensingRuntimeStatus.ACTIVE,
        maturity = maturity,
        rhythmState = RhythmState(activityLevel = activation, rhythmDelta = 0f, coverage = 0.6f),
        behaviorState = BehaviorState(density = density, deviation = deviation),
        confidence = confidence,
        identityGenome = EchoIdentityGenome(seed = 42L, accentHue = 0.6f),
    )

    @Test
    fun dayBrightnessCurveNightDimDayBright() {
        assertTrue("深夜应暗", dayBrightnessCurve(3f) < 0.45f)
        assertTrue("白天应亮", dayBrightnessCurve(13f) > 0.8f)
        assertTrue("黄昏应回落", dayBrightnessCurve(20f) < dayBrightnessCurve(13f))
        // 24h 回绕
        assertEquals(dayBrightnessCurve(2f), dayBrightnessCurve(26f), 1e-6f)
    }

    @Test
    fun parametersStayInBounds() {
        for (hour in listOf(2f, 8f, 13f, 19f, 23f)) {
            val p = computeVisualParameters(state(), hour)
            assertTrue("flowSpeed 越界", p.flowSpeed in 0f..1f)
            assertTrue("coherence 越界", p.coherence in 0f..1f)
            assertTrue("turbulence 越界", p.turbulence in 0f..1f)
            assertTrue("particleDensity 越界", p.particleDensity in 0f..1f)
            assertTrue("coreOpenness 越界", p.coreOpenness in 0f..1f)
            assertTrue("dispersion 越界", p.dispersion in 0f..1f)
            assertTrue("brightness 越界", p.brightness in 0f..1f)
            assertTrue("contrast 越界", p.contrast in 0f..1f)
            assertTrue("accentIntensity 越界", p.accentIntensity in 0f..1f)
            assertTrue("pulsePeriod 越界", p.pulsePeriodSeconds in 3.8f..5.6f)
            // V3 新字段同界
            assertTrue("dataClarity 越界", p.dataClarity in 0f..1f)
            assertTrue("haloIntensity 越界", p.haloIntensity in 0f..1f)
            assertTrue("momentIntensity 越界", p.momentIntensity in 0f..1f)
            assertTrue("filamentDensity 越界", p.filamentDensity in 0f..1f)
            assertTrue("seasonPhase 越界", p.seasonPhase in 0f..1f)
            assertTrue("dayComposition 越界", p.dayComposition in 0f..1f)
        }
    }

    @Test
    fun nightModeDimsAndSlows() {
        val day = computeVisualParameters(state(), 13f, nightMode = false)
        val night = computeVisualParameters(state(), 13f, nightMode = true)
        assertTrue("夜间应更暗", night.brightness < day.brightness)
        assertTrue("夜间应更慢", night.flowSpeed <= day.flowSpeed)
    }

    @Test
    fun reducedMotionStopsFlow() {
        val p = computeVisualParameters(state(), 13f, reduceMotion = true)
        assertEquals("reduceMotion → flowSpeed 归零（无障碍硬契约）", 0f, p.flowSpeed, 1e-6f)
    }

    @Test
    fun quietMotionLevelIsSlowerThanDefault() {
        val base = computeVisualParameters(state(), 13f)
        val quiet = computeVisualParameters(state(), 13f, motionLevel = PresenceMotionLevel.QUIET)
        val lively = computeVisualParameters(state(), 13f, motionLevel = PresenceMotionLevel.LIVELY)
        assertTrue("QUIET 应比 DEFAULT 更慢", quiet.flowSpeed < base.flowSpeed)
        assertTrue("LIVELY 应比 DEFAULT 更快", lively.flowSpeed > base.flowSpeed)
    }

    @Test
    fun newFieldsArePopulatedFromState() {
        val p = computeVisualParameters(state(), 13f)
        // dataClarity ← coverage（coverage>0 → 有值且 > 0）
        assertTrue("coverage=0.6 → dataClarity > 0", p.dataClarity > 0f)
        // haloIntensity ← coherence 调制（0.3 + coherence*0.6 > 0）
        assertTrue(p.haloIntensity > 0f)
        // filamentDensity ← 纹理族 + coherence（> 0）
        assertTrue(p.filamentDensity > 0f)
        // 零覆盖日仍有 dataClarity 兜底（0.3 + confidence*0.7）
        val noData = computeVisualParameters(
            state().copy(rhythmState = RhythmState(activityLevel = 0.5f, rhythmDelta = 0f, coverage = 0f)),
            13f,
        )
        assertTrue("coverage=0 → dataClarity 走 confidence 兜底", noData.dataClarity > 0f)
    }

    @Test
    fun dailyCompositionCoreOpennessFollowsMaturity() {
        // P1-4：日构图 coreOpenness 由真实 maturity 驱动（不再硬编码 SEED 0.15 常数）——
        // Day-0 与 Day-180 用户的日构图开放度不同（PART 66 成长视觉契约）
        val identity = EchoIdentityGenome(seed = 42L, accentHue = 0.6f)
        val vector = AmbientVector(
            activation = 0.5f, regularity = 0.6f, density = 0.5f,
            deviation = 0.3f, confidence = 0.7f,
        )
        val seedDaily = buildDailyComposition(identity, vector, EchoMaturity.SEED)
        val matureDaily = buildDailyComposition(identity, vector, EchoMaturity.MATURE)
        assertTrue("SEED 日构图更闭合", seedDaily.coreOpenness < matureDaily.coreOpenness)
        // hasDaily 分支传导：maturity 经日构图进入视觉参数
        val pSeed = computeVisualParameters(
            state(maturity = EchoMaturity.SEED).copy(
                identityGenome = identity,
                dailyComposition = seedDaily,
            ),
            13f,
        )
        val pMature = computeVisualParameters(
            state(maturity = EchoMaturity.MATURE).copy(
                identityGenome = identity,
                dailyComposition = matureDaily,
            ),
            13f,
        )
        assertTrue(pSeed.coreOpenness < pMature.coreOpenness)
    }

    @Test
    fun maturityOpennessIsMonotonic() {
        val order = listOf(
            EchoMaturity.SEED, EchoMaturity.DISCOVERING, EchoMaturity.EMERGING,
            EchoMaturity.KNOWN, EchoMaturity.MATURE
        )
        for (i in 1 until order.size) {
            assertTrue(
                "成熟度增长应让结构更开放",
                maturityOpenness(order[i]) > maturityOpenness(order[i - 1])
            )
        }
    }

    private fun organismFrameFor(
        params: com.yunjue.echo.mind.presence.EchoVisualParameters,
        seed: Long,
        timeSeconds: Float,
        width: Float,
        height: Float,
    ) = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
        spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
            com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(params, seed),
            com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
            timeSeconds,
        ),
        width = width,
        height = height,
    )

    @Test
    fun frameIsDeterministic() {
        val params = computeVisualParameters(state(), 13f)
        val f1 = organismFrameFor(params, seed = 42L, timeSeconds = 123.4f, width = 1080f, height = 2400f)
        val f2 = organismFrameFor(params, seed = 42L, timeSeconds = 123.4f, width = 1080f, height = 2400f)
        assertEquals(f1, f2)
        // 粒子数在密度决定的范围内（V3：40+120·density 窗内，质量/成熟度另缩放）
        assertTrue("粒子数异常：${f1.particles.size}", f1.particles.size in 10..220)
        // 空心核腔体比在 identity 范围（§18：.29R..43R × 开放度调制）
        assertTrue(f1.coreCavity.radiusFraction in 0.03f..0.4f)
    }

    @Test
    fun differentSeedChangesIdentity() {
        val params = computeVisualParameters(state(), 13f)
        val f1 = organismFrameFor(params, seed = 42L, timeSeconds = 10f, width = 100f, height = 200f)
        val f2 = organismFrameFor(params, seed = 7L, timeSeconds = 10f, width = 100f, height = 200f)
        // 同一用户不同日子有视觉血缘，但不同 identity 的画面不同（§82 几何维度也不同）
        assertNotEquals(f1.ambientField.centerColor, f2.ambientField.centerColor)
    }

    @Test
    fun colorSpaceIsDeterministicAndOpaque() {
        // V3：identity palette 走感知 LCh（ColorSpace.lch），确定性且不透明
        val c1 = ColorSpace.lch(0.72f, 0.118f, 240f)
        val c2 = ColorSpace.lch(0.72f, 0.118f, 240f)
        assertEquals(c1, c2)
        assertEquals(0xFF, c1 ushr 24 and 0xFF)
    }
}
