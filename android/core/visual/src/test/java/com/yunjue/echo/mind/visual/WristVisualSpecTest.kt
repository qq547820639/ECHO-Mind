package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.visual.model.GenomeDeriver
import com.yunjue.echo.mind.visual.surface.WristVisualProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WristVisualSpec 下采样测试（§18 SAME ECHO / SECOND BODY）：
 * - deterministic downsample（同 genome 同 spec）；
 * - identity 保留（hueParam/shapeMode 同源）；
 * - 断连 quietFallback 保留 identity、降 moment，不显示 ERROR；
 * - 不同 identity → 不同腕上 spec（SAME ECHO 但不换皮）。
 */
class WristVisualSpecTest {

    private fun genome(seed: Long, hue: Float, topology: Float, core: Float) = GenomeDeriver.derive(
        EchoPresenceState(
            maturity = EchoMaturity.KNOWN,
            confidence = 0.8f,
            rhythmState = RhythmState(activityLevel = 0.6f, coverage = 0.85f),
            identityGenome = EchoIdentityGenome(
                seed = seed, accentHue = hue, coreTopology = topology,
                textureFamily = 2, colorFamily = 1, symmetryTendency = 0.6f,
                orbitGeometry = 0.45f, motionPersonality = 0.5f,
            ),
        ),
        hourOfDay = 14f,
    ).let { it.copy(coreIntensity = core) }

    @Test
    fun downsampleIsDeterministic() {
        val g = genome(7L, 0.6f, 0.7f, 0.75f)
        assertEquals(WristVisualProjector.downsample(g), WristVisualProjector.downsample(g))
    }

    @Test
    fun identityIsPreserved() {
        val g = genome(7L, 0.6f, 0.7f, 0.75f)
        val spec = WristVisualProjector.downsample(g)
        assertEquals(g.spectralBias, spec.hueParam, 1e-6f)
        assertTrue(spec.shapeMode in 0..3)
        assertTrue(spec.privacy) // 腕上恒 PUBLIC_SAFE
    }

    @Test
    fun differentIdentityYieldsDifferentSpec() {
        val a = WristVisualProjector.downsample(genome(7L, 0.55f, 0.3f, 0.7f))
        val b = WristVisualProjector.downsample(genome(99L, 0.7f, 0.9f, 0.7f))
        assertNotEquals(a.hueParam, b.hueParam)
        assertNotEquals(a.identitySeedSubset, b.identitySeedSubset)
    }

    @Test
    fun quietFallbackKeepsIdentityLowersMoment() {
        val g = genome(7L, 0.6f, 0.7f, 0.8f)
        val normal = WristVisualProjector.downsample(g)
        val quiet = WristVisualProjector.quietFallback(g)
        // identity 保留
        assertEquals(normal.hueParam, quiet.hueParam, 1e-6f)
        assertEquals(normal.shapeMode, quiet.shapeMode)
        assertEquals(normal.identitySeedSubset, quiet.identitySeedSubset)
        // moment 降低（QUIET），不显示 ERROR
        assertTrue(quiet.intensity < normal.intensity)
    }
}
