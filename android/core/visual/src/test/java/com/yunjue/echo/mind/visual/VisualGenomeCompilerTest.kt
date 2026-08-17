package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.model.EchoVisualParameters
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §H/§J — VisualGenomeCompiler 机械编译锚点：
 * - compile() 把 params 字段 1:1 映射到 genome 字段（不重解释、不新增语义）；
 * - identity 字段（seed/topology/accentHue/orbitGeometry）透传；
 * - identityPhase 由 seed 确定性派生；neutralIdentity 同 seed 恒定；
 * - 输出只依赖（params, identity）输入——同输入恒同输出。
 */
class VisualGenomeCompilerTest {

    private val params = EchoVisualParameters(
        flowSpeed = 0.42f,
        coherence = 0.61f,
        turbulence = 0.33f,
        particleDensity = 0.55f,
        coreOpenness = 0.75f,
        dispersion = 0.38f,
        pulsePeriodSeconds = 4.9f,
        depth = 0.66f,
        brightness = 0.82f,
        contrast = 0.47f,
        accentIntensity = 0.71f,
        structureComplexity = 0.75f,
        dataClarity = 0.88f,
        haloIntensity = 0.64f,
        momentIntensity = 0.21f,
        filamentDensity = 0.57f,
        seasonPhase = 0.35f,
        dayComposition = 0.49f,
    )

    private val identity = EchoIdentityGenome(
        seed = 7710L,
        accentHue = 0.62f,
        colorFamily = 1,
        textureFamily = 2,
        coreTopology = 0.72f,
        symmetryTendency = 0.58f,
        orbitGeometry = 0.41f,
        motionPersonality = 0.5f,
    )

    @Test
    fun compileMapsEveryParamsFieldOneToOne() {
        val g = VisualGenomeCompiler.compile(params, identity)
        assertEquals("flowSpeed → driftRate", params.flowSpeed, g.driftRate, 1e-6f)
        assertEquals("coherence → coherence", params.coherence, g.coherence, 1e-6f)
        assertEquals("turbulence → turbulence", params.turbulence, g.turbulence, 1e-6f)
        assertEquals("particleDensity → particleDensity", params.particleDensity, g.particleDensity, 1e-6f)
        assertEquals("coreOpenness → coreIntensity", params.coreOpenness, g.coreIntensity, 1e-6f)
        assertEquals("dispersion → radialSpread", params.dispersion, g.radialSpread, 1e-6f)
        assertEquals("pulsePeriodSeconds → pulseRate", params.pulsePeriodSeconds, g.pulseRate, 1e-6f)
        assertEquals("depth → depth", params.depth, g.depth, 1e-6f)
        assertEquals("brightness → luminance", params.brightness, g.luminance, 1e-6f)
        assertEquals("dataClarity → dataClarity", params.dataClarity, g.dataClarity, 1e-6f)
        assertEquals("haloIntensity → haloIntensity", params.haloIntensity, g.haloIntensity, 1e-6f)
        assertEquals("momentIntensity → momentIntensity", params.momentIntensity, g.momentIntensity, 1e-6f)
        assertEquals("filamentDensity → filamentDensity", params.filamentDensity, g.filamentDensity, 1e-6f)
        assertEquals("seasonPhase → seasonPhase", params.seasonPhase, g.seasonPhase, 1e-6f)
        assertEquals("dayComposition → dayComposition", params.dayComposition, g.dayComposition, 1e-6f)
        assertEquals(EchoVisualGenome.CURRENT_REVISION, g.revision)
    }

    @Test
    fun identityFieldsPassThrough() {
        val g = VisualGenomeCompiler.compile(params, identity)
        assertEquals("seed 透传", identity.seed, g.identitySeed)
        assertEquals("coreTopology → identityTopology", identity.coreTopology, g.identityTopology, 1e-6f)
        assertEquals("accentHue → spectralBias", identity.accentHue, g.spectralBias, 1e-6f)
        assertEquals("orbitGeometry → orbitalEccentricity", identity.orbitGeometry, g.orbitalEccentricity, 1e-6f)
    }

    @Test
    fun identityPhaseIsDeterministicPerSeed() {
        val a = VisualGenomeCompiler.compile(params, identity)
        val b = VisualGenomeCompiler.compile(params, identity)
        assertEquals(a.identityPhase, b.identityPhase, 1e-7f)
        val other = VisualGenomeCompiler.compile(params, identity.copy(seed = 999L))
        assertNotEquals("不同 seed → 不同 identity 相位", a.identityPhase, other.identityPhase)
        assertTrue(a.identityPhase in 0f..1f)
    }

    @Test
    fun neutralIdentityIsStableAndSeedDeterministic() {
        val n1 = VisualGenomeCompiler.neutralIdentity(42L)
        val n2 = VisualGenomeCompiler.neutralIdentity(42L)
        assertEquals("同 seed 恒定同一中性身份", n1, n2)
        assertEquals(42L, n1.seed)
        assertNotEquals(VisualGenomeCompiler.neutralIdentity(1L), VisualGenomeCompiler.neutralIdentity(2L))
    }

    @Test
    fun compileOutputDependsOnlyOnInputs() {
        // 同 params + 同 identity → 逐字段相同 genome（输出只依赖输入，不读其他状态）
        val a = VisualGenomeCompiler.compile(params, identity)
        val b = VisualGenomeCompiler.compile(params, identity)
        assertEquals(a, b)
        // params 变化只改变对应语义字段，identity 不变（moment 不改 identity）
        val calmer = a.copy(driftRate = a.driftRate * 0.5f)
        assertEquals(a.identitySeed, calmer.identitySeed)
        assertEquals(a.identityPhase, calmer.identityPhase, 1e-6f)
        assertNotEquals(a.driftRate, calmer.driftRate)
    }

    @Test
    fun compileClampsOutOfRangeParams() {
        val outOfRange = params.copy(
            flowSpeed = 1.7f,
            brightness = 1.9f,
            turbulence = -0.5f,
        )
        val g = VisualGenomeCompiler.compile(outOfRange, identity)
        assertEquals(1f, g.driftRate, 1e-6f)
        assertEquals(1f, g.luminance, 1e-6f)
        assertEquals(0f, g.turbulence, 1e-6f)
    }
}
