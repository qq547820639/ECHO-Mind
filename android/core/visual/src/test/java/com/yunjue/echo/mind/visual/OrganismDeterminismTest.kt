package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.model.GenomeDeriver
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
 * 3. surface 裁剪 — identity 不被缩放，表现强度被缩放；
 * 4. 无心理语义 — genome 字段不含 emotion/health 语义（结构性审查）。
 */
class OrganismDeterminismTest {

    private fun presence(
        seed: Long = 42L,
        confidence: Float = 0.7f,
        activity: Float = 0.6f,
        noiseScale: Float = 0.3f,
    ) = EchoPresenceState(
        maturity = EchoMaturity.KNOWN,
        confidence = confidence,
        rhythmState = RhythmState(activityLevel = activity, coverage = 0.8f),
        identityGenome = EchoIdentityGenome(
            seed = seed, accentHue = 0.6f, colorFamily = 1, textureFamily = 2,
            coreTopology = 0.7f, symmetryTendency = 0.6f, orbitGeometry = 0.4f,
            motionPersonality = 0.5f,
        ),
        momentState = com.yunjue.echo.mind.model.EchoMomentState(
            breathingPeriod = 4.5f, noiseScale = noiseScale,
        ),
    )

    private fun frame(genome: EchoVisualGenome, surface: EchoSurface, clock: Float) =
        OrganismFrameComputer.compute(SurfacePolicy.crop(genome, surface, clock), 1080f, 2400f)

    @Test
    fun sameFixtureProducesIdenticalFrame() {
        val genome = GenomeDeriver.derive(presence(), hourOfDay = 14f)
        val a = frame(genome, EchoSurface.APP_PRIVATE, clock = 10f)
        val b = frame(genome, EchoSurface.APP_PRIVATE, clock = 10f)
        assertEquals("同一 fixture 帧必须逐值相同", a, b)
    }

    @Test
    fun differentSeedYieldsDifferentIdentity() {
        val g1 = GenomeDeriver.derive(presence(seed = 1L), 14f)
        val g2 = GenomeDeriver.derive(presence(seed = 999L), 14f)
        assertNotEquals("不同 seed → 不同 identity 相位", g1.identityPhase, g2.identityPhase)
        assertNotEquals(g1.identitySeed, g2.identitySeed)
    }

    @Test
    fun momentChangeDoesNotAlterIdentity() {
        val calm = GenomeDeriver.derive(presence(noiseScale = 0.1f), 14f)
        val active = GenomeDeriver.derive(presence(noiseScale = 0.9f), 14f)
        // identity 字段必须一致
        assertEquals(calm.identitySeed, active.identitySeed)
        assertEquals(calm.identityTopology, active.identityTopology, 1e-6f)
        assertEquals(calm.identityPhase, active.identityPhase, 1e-6f)
        assertEquals(calm.spectralBias, active.spectralBias, 1e-6f)
        // 但 moment 强度可变
        assertTrue(active.momentIntensity > calm.momentIntensity)
    }

    @Test
    fun surfaceCropPreservesIdentityButScalesExpression() {
        val genome = GenomeDeriver.derive(presence(), 14f)
        val app = SurfacePolicy.crop(genome, EchoSurface.APP_PRIVATE, 0f)
        val wrist = SurfacePolicy.crop(genome, EchoSurface.WRIST_PUBLIC_SAFE, 0f)
        // identity 不被裁剪
        assertEquals(app.genome.identitySeed, wrist.genome.identitySeed)
        assertEquals(app.genome.identityTopology, wrist.genome.identityTopology, 1e-6f)
        assertEquals(app.genome.spectralBias, wrist.genome.spectralBias, 1e-6f)
        // 表现强度被缩放（wrist 动效复杂度 0.25）
        assertTrue(wrist.genome.particleDensity <= app.genome.particleDensity + 1e-6f)
        assertTrue(wrist.genome.filamentDensity <= app.genome.filamentDensity + 1e-6f)
        assertTrue(wrist.genome.luminance <= app.genome.luminance + 1e-6f)
    }

    @Test
    fun publicSafeSurfacesDisallowText() {
        assertFalse(capabilitiesFor(EchoSurface.WALLPAPER_VISUAL_ONLY).allowText)
        assertFalse(capabilitiesFor(EchoSurface.LOCK_PUBLIC_SAFE).allowText)
        assertFalse(capabilitiesFor(EchoSurface.WRIST_PUBLIC_SAFE).allowText)
        assertTrue(capabilitiesFor(EchoSurface.APP_PRIVATE).allowText)
        assertTrue(capabilitiesFor(EchoSurface.APP_EVIDENCE).allowText)
    }

    @Test
    fun onlyDreamAllowsWarmAccent() {
        assertTrue(capabilitiesFor(EchoSurface.DREAM_AMBIENT).allowWarmAccent)
        EchoSurface.values().filter { it != EchoSurface.DREAM_AMBIENT }.forEach {
            assertFalse("$it 不应允许暖金高光", capabilitiesFor(it).allowWarmAccent)
        }
    }

    @Test
    fun frameStructureReflectsSurfaceComplexity() {
        val genome = GenomeDeriver.derive(presence(), 14f)
        val app = frame(genome, EchoSurface.APP_PRIVATE, 5f)
        val wrist = frame(genome, EchoSurface.WRIST_PUBLIC_SAFE, 5f)
        // wrist 动效低 → 粒子/filament 更少
        assertTrue("wrist 粒子应 ≤ app", wrist.particles.size <= app.particles.size)
        assertTrue("wrist filament 应 ≤ app", wrist.filaments.size <= app.filaments.size)
        // wrist 无暖金
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
        val genome = GenomeDeriver.derive(presence(), 14f)
        assertEquals(EchoVisualGenome.CURRENT_REVISION, genome.revision)
        assertTrue(genome.revision >= 1)
    }
}
