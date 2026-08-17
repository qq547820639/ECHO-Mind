package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.model.EchoVisualParameters
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import com.yunjue.echo.mind.visual.surface.capabilitiesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visual Runtime 黄金法则测试：
 * 1. 确定性 — 同一 fixture 逐值相同；
 * 2. identity 稳定 — moment 变化不改 identity 字段；
 * 3. surface 裁剪 — identity 不被缩放，亮度按 maxLuminance 裁剪（V3 §M：只裁隐私约束）；
 * 4. 无心理语义 — genome 字段不含 emotion/health 语义（结构性审查）。
 */
class OrganismDeterminismTest {

    private fun identity(seed: Long = 42L) = EchoIdentityGenome(
        seed = seed, accentHue = 0.6f, colorFamily = 1, textureFamily = 2,
        coreTopology = 0.7f, symmetryTendency = 0.6f, orbitGeometry = 0.4f,
        motionPersonality = 0.5f,
    )

    private fun params(momentIntensity: Float = 0.3f) = EchoVisualParameters(
        flowSpeed = 0.5f,
        coherence = 0.7f,
        turbulence = 0.2f,
        particleDensity = 0.6f,
        coreOpenness = 0.75f,
        dispersion = 0.35f,
        pulsePeriodSeconds = 4.5f,
        depth = 0.6f,
        brightness = 0.8f,
        contrast = 0.45f,
        accentIntensity = 0.7f,
        structureComplexity = 0.75f,
        momentIntensity = momentIntensity,
    )

    private fun genome(seed: Long = 42L, momentIntensity: Float = 0.3f) =
        VisualGenomeCompiler.compile(params(momentIntensity), identity(seed))

    private fun frame(genome: EchoVisualGenome, surface: EchoSurface, clock: Float) =
        OrganismFrameComputer.compute(SurfacePolicy.crop(genome, surface, clock), 1080f, 2400f)

    @Test
    fun sameFixtureProducesIdenticalFrame() {
        val genome = genome()
        val a = frame(genome, EchoSurface.APP_PRIVATE, clock = 10f)
        val b = frame(genome, EchoSurface.APP_PRIVATE, clock = 10f)
        assertEquals("同一 fixture 帧必须逐值相同", a, b)
    }

    @Test
    fun differentSeedYieldsDifferentIdentity() {
        val g1 = genome(seed = 1L)
        val g2 = genome(seed = 999L)
        assertNotEquals("不同 seed → 不同 identity 相位", g1.identityPhase, g2.identityPhase)
        assertNotEquals(g1.identitySeed, g2.identitySeed)
    }

    @Test
    fun momentChangeDoesNotAlterIdentity() {
        val calm = genome(momentIntensity = 0.1f)
        val active = genome(momentIntensity = 0.9f)
        // identity 字段必须一致
        assertEquals(calm.identitySeed, active.identitySeed)
        assertEquals(calm.identityTopology, active.identityTopology, 1e-6f)
        assertEquals(calm.identityPhase, active.identityPhase, 1e-6f)
        assertEquals(calm.spectralBias, active.spectralBias, 1e-6f)
        // 但 moment 强度可变
        assertTrue(active.momentIntensity > calm.momentIntensity)
    }

    @Test
    fun surfaceCropPreservesIdentityButClampsLuminance() {
        val genome = genome().copy(luminance = 1f)
        val app = SurfacePolicy.crop(genome, EchoSurface.APP_PRIVATE, 0f)
        val wrist = SurfacePolicy.crop(genome, EchoSurface.WRIST_PUBLIC_SAFE, 0f)
        // identity 不被裁剪
        assertEquals(app.genome.identitySeed, wrist.genome.identitySeed)
        assertEquals(app.genome.identityTopology, wrist.genome.identityTopology, 1e-6f)
        assertEquals(app.genome.spectralBias, wrist.genome.spectralBias, 1e-6f)
        // V3 §M：crop 只裁隐私约束——亮度按 maxLuminance 上限裁剪（APP 1f / WRIST 0.5f）
        assertEquals(1f, app.genome.luminance, 1e-6f)
        assertEquals(
            capabilitiesFor(EchoSurface.WRIST_PUBLIC_SAFE).maxLuminance,
            wrist.genome.luminance,
            1e-6f,
        )
        assertTrue(wrist.genome.luminance <= app.genome.luminance + 1e-6f)
    }

    @Test
    fun publicSafeSurfacesDisallowText() {
        assertFalse(capabilitiesFor(EchoSurface.WALLPAPER_VISUAL_ONLY).allowText)
        assertFalse(capabilitiesFor(EchoSurface.LOCK_PUBLIC_SAFE).allowText)
        assertFalse(capabilitiesFor(EchoSurface.WRIST_PUBLIC_SAFE).allowText)
        assertFalse(capabilitiesFor(EchoSurface.JOURNEY_PRIVATE).allowText)
        assertTrue(capabilitiesFor(EchoSurface.APP_PRIVATE).allowText)
        assertTrue(capabilitiesFor(EchoSurface.APP_EVIDENCE).allowText)
    }

    @Test
    fun onlyDreamAllowsWarmAccent() {
        assertTrue(capabilitiesFor(EchoSurface.DREAM_AMBIENT).allowWarmAccent)
        EchoSurface.entries.filter { it != EchoSurface.DREAM_AMBIENT }.forEach {
            assertFalse("$it 不应允许暖金高光", capabilitiesFor(it).allowWarmAccent)
        }
    }

    @Test
    fun frameStructureReflectsQualityScaling() {
        val genome = genome()
        val app = OrganismFrameComputer.compute(
            SurfacePolicy.crop(genome, EchoSurface.APP_PRIVATE, 5f), 1080f, 2400f,
            OrganismFrameComputer.EchoRenderOptions(quality = EchoRenderQuality.NORMAL),
        )
        val wrist = OrganismFrameComputer.compute(
            SurfacePolicy.crop(genome, EchoSurface.WRIST_PUBLIC_SAFE, 5f), 1080f, 2400f,
            OrganismFrameComputer.EchoRenderOptions(quality = EchoRenderQuality.MINIMAL),
        )
        // V3 §M：数量/光晕由 RenderQuality 承载——MINIMAL 粒子/filament 更少
        assertTrue("低质量粒子应 ≤ 正常", wrist.particles.size <= app.particles.size)
        val appStrokes = app.structuralRings.size + app.longFilaments.size + app.localFragments.size
        val wristStrokes = wrist.structuralRings.size + wrist.longFilaments.size + wrist.localFragments.size
        assertTrue("低质量 filament 应 ≤ 正常", wristStrokes <= appStrokes)
        // WRIST 无暖金（capabilities.allowWarmAccent = false）
        assertTrue(wrist.warmAccents.isEmpty())
    }

    @Test
    fun genomeHasNoPsychologicalSemantics() {
        // 结构性审查：字段名不得含心理/健康语义
        val fields = EchoVisualGenome::class.java.declaredFields.map { it.name.lowercase() }
        val banned = listOf("emotion", "depress", "anxiet", "health", "mood", "happy", "sad", "stress")
        fields.forEach { f ->
            banned.forEach { b ->
                assertFalse("genome 字段不得含心理语义: $f", f.contains(b))
            }
        }
    }

    @Test
    fun revisionIsStable() {
        val genome = genome()
        assertEquals(EchoVisualGenome.CURRENT_REVISION, genome.revision)
        assertTrue(genome.revision >= 1)
    }
}
