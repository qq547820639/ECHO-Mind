package com.yunjue.echo.mind
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.computeEchoSceneFrame
import com.yunjue.echo.mind.presence.computeVisualParameters
import com.yunjue.echo.mind.presence.dayBrightnessCurve
import com.yunjue.echo.mind.presence.hsvToArgb
import com.yunjue.echo.mind.presence.maturityOpenness
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 2：Visual Profile + EchoSceneModel 回归。
 * 确定性（同一输入同一帧）+ 参数边界 + 低置信度更弥散 + 夜间更暗更慢。
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
            val p = computeVisualParameters(state(), hour, SurfaceMode.APP)
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
        }
    }

    @Test
    fun nightModeDimsAndSlows() {
        val day = computeVisualParameters(state(), 13f, SurfaceMode.APP, nightMode = false)
        val night = computeVisualParameters(state(), 13f, SurfaceMode.APP, nightMode = true)
        assertTrue("夜间应更暗", night.brightness < day.brightness)
        assertTrue("夜间应更慢", night.flowSpeed <= day.flowSpeed)
    }

    @Test
    fun reducedMotionStopsFlow() {
        val p = computeVisualParameters(state(), 13f, SurfaceMode.REDUCED_MOTION)
        assertEquals(0f, p.flowSpeed)
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

    @Test
    fun frameIsDeterministic() {
        val params = computeVisualParameters(state(), 13f, SurfaceMode.APP)
        val f1 = computeEchoSceneFrame(params, seed = 42L, timeSeconds = 123.4f, width = 1080f, height = 2400f)
        val f2 = computeEchoSceneFrame(params, seed = 42L, timeSeconds = 123.4f, width = 1080f, height = 2400f)
        assertEquals(f1, f2)
        // 粒子数在密度决定的范围内
        assertTrue("粒子数异常：${f1.particles.size}", f1.particles.size in 14..60)
        // 核心半径在合法范围
        assertTrue(f1.coreRadiusFraction in 0.05f..0.4f)
    }

    @Test
    fun differentSeedChangesIdentity() {
        val params = computeVisualParameters(state(), 13f, SurfaceMode.APP)
        val f1 = computeEchoSceneFrame(params, seed = 42L, timeSeconds = 10f, width = 100f, height = 200f)
        val f2 = computeEchoSceneFrame(params, seed = 7L, timeSeconds = 10f, width = 100f, height = 200f)
        // 同一用户不同日子有视觉血缘（粒子轨道同源），但不同 identity 的画面不同
        assertNotEquals(f1.backgroundCenterColor, f2.backgroundCenterColor)
    }

    @Test
    fun hsvToArgbIsDeterministicAndOpaque() {
        val c1 = hsvToArgb(0.6f, 0.7f, 0.75f)
        val c2 = hsvToArgb(0.6f, 0.7f, 0.75f)
        assertEquals(c1, c2)
        assertEquals(0xFF, c1 ushr 24 and 0xFF)
    }
}
