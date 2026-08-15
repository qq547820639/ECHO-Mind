package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoIdentityGenome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 21 §15 — identityDistance 度量质量：
 * - 同一 genome 距离为 0；七维全覆盖；
 * - 「只改颜色」贡献被限制在颜色权重内（拒绝伪多样性）；
 * - 结构维（topology/symmetry/orbit）差异显著放大距离。
 */
class IdentityDistanceTest {

    private fun genome(
        seed: Long = 1L,
        hue: Float = 0.5f,
        colorFamily: Int = 0,
        textureFamily: Int = 0,
        topology: Float = 0.5f,
        symmetry: Float = 0.5f,
        orbit: Float = 0.5f,
        motion: Float = 0.5f,
    ) = EchoIdentityGenome(
        seed = seed, accentHue = hue, colorFamily = colorFamily, textureFamily = textureFamily,
        coreTopology = topology, symmetryTendency = symmetry, orbitGeometry = orbit,
        motionPersonality = motion,
    )

    @Test
    fun identicalGenomesHaveZeroDistance() {
        assertEquals(0f, identityDistance(genome(), genome()), 1e-6f)
        // 对称性
        assertEquals(identityDistance(genome(hue = 0.1f), genome(hue = 0.9f)),
            identityDistance(genome(hue = 0.9f), genome(hue = 0.1f)), 1e-6f)
    }

    @Test
    fun hueOnlyChangeIsBoundedByColorWeight() {
        val base = genome()
        // hue 反转（最大色相差 0.5）→ 距离贡献 = 0.10 * 0.5 = 0.05
        val hueOnly = identityDistance(base, genome(hue = 0.99f))
        assertTrue("色相差 0.5 的距离应 ≤ 0.10*0.5", hueOnly <= 0.0501f)
        assertTrue(hueOnly > 0f)
    }

    @Test
    fun structuralDimensionsDriveDistance() {
        // 结构维取极值对（delta = 1.0，权重 0.18）
        val topologyFar = identityDistance(genome(topology = 0f), genome(topology = 1f))
        val symmetryFar = identityDistance(genome(symmetry = 0f), genome(symmetry = 1f))
        val orbitFar = identityDistance(genome(orbit = 0f), genome(orbit = 1f))
        assertTrue("topology 维度有效", topologyFar > 0.15f)
        assertTrue("symmetry 维度有效", symmetryFar > 0.15f)
        assertTrue("orbit 维度有效", orbitFar > 0.15f)
        // 结构差异 > 单纯颜色差异
        val hueOnly = identityDistance(genome(), genome(hue = 0.99f))
        assertTrue("结构维差异应显著大于颜色维", topologyFar > hueOnly * 2f)
    }

    @Test
    fun distanceCoversAllSevenDimensions() {
        val base = genome()
        var moved = 0
        if (identityDistance(base, genome(topology = 0.9f)) > 0.01f) moved++
        if (identityDistance(base, genome(symmetry = 0.9f)) > 0.01f) moved++
        if (identityDistance(base, genome(orbit = 0.9f)) > 0.01f) moved++
        if (identityDistance(base, genome(motion = 0.9f)) > 0.01f) moved++
        if (identityDistance(base, genome(textureFamily = 3)) > 0.01f) moved++
        if (identityDistance(base, genome(hue = 0.9f)) > 0.01f) moved++
        if (identityDistance(base, genome(colorFamily = 4)) > 0.01f) moved++
        assertEquals("七维全部参与距离", 7, moved)
    }

    @Test
    fun distanceRangeIsUnit() {
        // 极端相反 genome
        val a = genome(hue = 0f, colorFamily = 0, textureFamily = 0, topology = 0f, symmetry = 0f, orbit = 0f, motion = 0f)
        val b = genome(hue = 0.5f, colorFamily = 4, textureFamily = 3, topology = 1f, symmetry = 1f, orbit = 1f, motion = 1f)
        val d = identityDistance(a, b)
        assertTrue("距离在 0..1", d in 0f..1f)
    }
}
