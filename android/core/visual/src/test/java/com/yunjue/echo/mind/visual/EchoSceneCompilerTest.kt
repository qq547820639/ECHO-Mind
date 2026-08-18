package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.visual.model.EchoVisualParameters
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.DeviceRenderCapabilities
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.render.EchoSceneCompiler
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §7/§9/§13/§27/§28/§30/§31 — SceneCompiler / RenderPacket / Capability Router 测试。
 */
class EchoSceneCompilerTest {

    private val params = EchoVisualParameters(
        flowSpeed = 0.5f,
        coherence = 0.7f,
        turbulence = 0.2f,
        particleDensity = 0.6f,
        coreOpenness = 0.75f,
        dispersion = 0.35f,
        pulsePeriodSeconds = 4.6f,
        depth = 0.6f,
        brightness = 0.8f,
        contrast = 0.45f,
        accentIntensity = 0.7f,
        structureComplexity = 0.75f,
    )

    private fun specFor(seed: Long, surface: EchoSurface, clock: Float = 12f) =
        SurfacePolicy.crop(
            VisualGenomeCompiler.compile(
                params,
                EchoIdentityGenome(seed = seed, coreTopology = 0.7f, orbitGeometry = 0.4f),
            ),
            surface,
            clock,
        )

    @Test
    fun packetIsDeterministicForSameInputs() {
        val spec = specFor(99L, EchoSurface.APP_PRIVATE)
        val a = EchoSceneCompiler.compile(spec, 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD)
        val b = EchoSceneCompiler.compile(spec, 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD)
        assertEquals(a, b)
    }

    @Test
    fun identityIsStableAcrossDailyAndMomentChanges() {
        // 同一 seed 不同时钟/surface → identity 完全一致（§11：Daily/Moment 不重新生成 Identity）
        val day1 = EchoSceneCompiler.compile(
            specFor(99L, EchoSurface.APP_PRIVATE, clock = 12f), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        val day2 = EchoSceneCompiler.compile(
            specFor(99L, EchoSurface.WALLPAPER_VISUAL_ONLY, clock = 9999f), 1080f, 2340f, "MATURE", EchoRenderTier.LEGACY,
        )
        assertEquals(day1.identity, day2.identity)
    }

    @Test
    fun breathWindowAndSurfaceAmplitudes() {
        val app = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.APP_PRIVATE), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        val wallpaper = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.WALLPAPER_VISUAL_ONLY), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        val dream = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.DREAM_AMBIENT), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        // §27 + Quality §18：period 8.2–10.2s（Dream ×1.18 放宽上限）；幅度 App 2.4% / Wallpaper 1.6% / Dream 2.0%
        assertTrue(app.motion.breathPeriodSeconds in 8.2f..10.2f)
        assertEquals(0.024f, app.motion.breathAmplitude, 1e-4f)
        assertEquals(0.016f, wallpaper.motion.breathAmplitude, 1e-4f)
        assertEquals(0.020f, dream.motion.breathAmplitude, 1e-4f)
        assertTrue(dream.motion.breathPeriodSeconds > app.motion.breathPeriodSeconds)
        assertTrue("brightness pulse <= ±3%", app.motion.brightnessPulse <= 0.03f)
    }

    @Test
    fun breathInputDomainMapsOntoPresentationWindow() {
        // P1-3 量纲锁定：pulseRate 输入域 3.6–6.0s 线性映射到 8.2–10.2s 呈现窗口；
        // 超域输入 coerce 饱和到窗口端点（旧 fixtures 6.8–10.8 曾全落上饱和 → 呼吸维度失效）
        fun breathOf(pulse: Float): Float {
            val spec = SurfacePolicy.crop(
                VisualGenomeCompiler.compile(
                    params.copy(pulsePeriodSeconds = pulse),
                    EchoIdentityGenome(seed = 5L, coreTopology = 0.7f, orbitGeometry = 0.4f),
                ),
                EchoSurface.APP_PRIVATE,
                12f,
            )
            return EchoSceneCompiler.compile(spec, 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD)
                .motion.breathPeriodSeconds
        }
        assertEquals(8.2f, breathOf(3.6f), 1e-4f)
        assertEquals(10.2f, breathOf(6.0f), 1e-4f)
        assertEquals("下饱和", 8.2f, breathOf(0f), 1e-4f)
        assertEquals("上饱和（旧 fixture 域 6.8–10.8 全落此端）", 10.2f, breathOf(8.2f), 1e-4f)
        assertEquals(
            "fixtures base 中点 4.6 → 呈现 ~9.03s",
            8.2f + 2.0f * (4.6f - 3.6f) / 2.4f,
            breathOf(4.6f),
            1e-3f,
        )
    }

    @Test
    fun reducedMotionExactFactors() {
        val normal = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.APP_PRIVATE), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        val reduced = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.APP_PRIVATE), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
            reducedMotion = true,
        )
        // §30：particle velocity ×.08 / orbit ×.06 / filament phase ×.12 / breath amplitude .007 / period ×1.45
        assertEquals(0.08f, reduced.motion.particleVelocity, 1e-4f)
        assertEquals(0.06f, reduced.motion.orbitVelocity, 1e-4f)
        assertEquals(0.12f, reduced.motion.filamentPhaseScale, 1e-4f)
        assertEquals(0.007f, reduced.motion.breathAmplitude, 1e-4f)
        assertEquals(normal.motion.breathPeriodSeconds * 1.45f, reduced.motion.breathPeriodSeconds, 1e-3f)
        // identity 不因 Reduced Motion 改变
        assertEquals(normal.identity, reduced.identity)
    }

    @Test
    fun dreamMotionFactors() {
        val dream = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.DREAM_AMBIENT), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        // §71：particle ×.55 / orbit ×.45 / filament ×.60
        assertEquals(0.55f, dream.motion.particleVelocity, 1e-4f)
        assertEquals(0.45f, dream.motion.orbitVelocity, 1e-4f)
        assertEquals(0.60f, dream.motion.filamentPhaseScale, 1e-4f)
    }

    @Test
    fun orbitAndFilamentPhaseWindows() {
        val packet = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.APP_PRIVATE), 1080f, 2340f, "KNOWN", EchoRenderTier.STANDARD,
        )
        // §28 + Quality §18：主自转 30–55 分钟；filament 相位 35–55 秒
        assertTrue(packet.motion.orbitPeriodSeconds in 30f * 60f..55f * 60f)
        assertTrue(packet.motion.filamentPhaseSeconds in 35f..55f)
    }

    @Test
    fun maturityMultipliers() {
        assertEquals(.42f, EchoSceneCompiler.maturityMultiplier("SEED"), 1e-4f)
        assertEquals(.58f, EchoSceneCompiler.maturityMultiplier("DISCOVERING"), 1e-4f)
        assertEquals(.74f, EchoSceneCompiler.maturityMultiplier("EMERGING"), 1e-4f)
        assertEquals(.90f, EchoSceneCompiler.maturityMultiplier("KNOWN"), 1e-4f)
        assertEquals(1.00f, EchoSceneCompiler.maturityMultiplier("MATURE"), 1e-4f)
    }

    @Test
    fun qualityProfiles() {
        val normal = EchoSceneCompiler.qualityProfile(EchoRenderQuality.NORMAL)
        val conserve = EchoSceneCompiler.qualityProfile(EchoRenderQuality.CONSERVE)
        val minimal = EchoSceneCompiler.qualityProfile(EchoRenderQuality.MINIMAL)
        assertEquals(1f, normal.particleScale, 1e-4f)
        assertEquals(.68f, conserve.particleScale, 1e-4f)
        assertEquals(.76f, conserve.filamentScale, 1e-4f)
        assertFalse(conserve.secondaryGlintsEnabled)
        assertEquals(.34f, minimal.particleScale, 1e-4f)
        assertEquals(.50f, minimal.filamentScale, 1e-4f)
        assertFalse(minimal.glintsEnabled)
        assertFalse(minimal.farHaloEnabled)
    }

    @Test
    fun capabilityRouterTiers() {
        // §9：LEGACY / STANDARD / ADVANCED / ULTRA 选择矩阵
        assertEquals(
            EchoRenderTier.LEGACY,
            com.yunjue.echo.mind.visual.render.selectTier(DeviceRenderCapabilities(api = 28, runtimeShader = false)),
        )
        assertEquals(
            EchoRenderTier.STANDARD,
            com.yunjue.echo.mind.visual.render.selectTier(DeviceRenderCapabilities(api = 34, runtimeShader = true)),
        )
        assertEquals(
            EchoRenderTier.LEGACY,
            com.yunjue.echo.mind.visual.render.selectTier(DeviceRenderCapabilities(api = 34, runtimeShader = false)),
        )
        assertEquals(
            EchoRenderTier.ADVANCED,
            com.yunjue.echo.mind.visual.render.selectTier(DeviceRenderCapabilities(api = 36, runtimeShader = true)),
        )
        // ULTRA 永不只因 API>=37 自动启用：四个硬门缺一不可
        assertEquals(
            EchoRenderTier.ADVANCED,
            com.yunjue.echo.mind.visual.render.selectTier(DeviceRenderCapabilities(api = 37, runtimeShader = true)),
        )
        assertEquals(
            EchoRenderTier.ADVANCED,
            com.yunjue.echo.mind.visual.render.selectTier(
                DeviceRenderCapabilities(api = 37, runtimeShader = true, avp2025 = true, ultraBenchmarkPassed = true),
            ),
        )
        assertEquals(
            EchoRenderTier.ULTRA,
            com.yunjue.echo.mind.visual.render.selectTier(
                DeviceRenderCapabilities(
                    api = 37, runtimeShader = true, avp2025 = true,
                    ultraBenchmarkPassed = true, ultraFlag = true,
                ),
            ),
        )
    }

    @Test
    fun hdrNeverAllowedOnWallpaper() {
        val wallpaper = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.WALLPAPER_VISUAL_ONLY), 1080f, 2340f, "KNOWN", EchoRenderTier.ADVANCED,
            hdrEligible = true,
        )
        // §23：Wallpaper 默认 HDR OFF（即使显示链路合格）
        assertFalse(wallpaper.material.hdrAllowed)
        val app = EchoSceneCompiler.compile(
            specFor(5L, EchoSurface.APP_PRIVATE), 1080f, 2340f, "KNOWN", EchoRenderTier.ADVANCED,
            hdrEligible = true,
        )
        assertTrue(app.material.hdrAllowed)
        assertTrue("HDR glint cap <= 3%", app.material.hdrGlintCap <= 0.03f)
    }
}
