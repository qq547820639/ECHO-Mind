package com.yunjue.echo.mind.presencevisual

import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §Q — EchoRendererFacade 后端解析规则（纯函数；env 注入可确定性覆盖能力探测）。
 */
class EchoRendererFacadeResolutionTest {

    private fun genome() = EchoVisualGenome(
        identitySeed = 7L,
        identityTopology = 0.65f,
        identityPhase = 0.3f,
        seasonPhase = 0.3f,
        dayComposition = 0.47f,
        coherence = 0.68f,
        radialSpread = 0.34f,
        orbitalEccentricity = 0.45f,
        particleDensity = 0.64f,
        filamentDensity = 0.72f,
        driftRate = 0.54f,
        pulseRate = 8.2f,
        turbulence = 0.31f,
        luminance = 0.70f,
        spectralBias = 0.4f,
        coreIntensity = 0.58f,
        haloIntensity = 0.55f,
        dataClarity = 0.95f,
        momentIntensity = 0f,
    )

    private fun env(runtimeShader: Boolean, quality: EchoRenderQuality = EchoRenderQuality.NORMAL) =
        EchoEnvironmentSnapshot(
            quality = quality,
            powerSave = false,
            thermalSevereOrWorse = false,
            tier = if (runtimeShader) EchoRenderTier.STANDARD else EchoRenderTier.LEGACY,
            hdrEligible = false,
            wideGamut = false,
            runtimeShader = runtimeShader,
        )

    private fun request(
        surface: EchoSurface = EchoSurface.APP_PRIVATE,
        tier: EchoRenderTier = EchoRenderTier.LEGACY,
        motion: MotionPolicy = MotionPolicy.NORMAL,
        quality: EchoRenderQuality? = null,
    ) = EchoRenderRequest(
        genome = genome(),
        surface = surface,
        motion = motion,
        maturityName = "KNOWN",
        requestedTier = tier,
        quality = quality,
    )

    @Test
    fun legacyRequestResolvesCanvasBackend() {
        val r = EchoRendererFacade.resolve(request(tier = EchoRenderTier.LEGACY), env(runtimeShader = true))
        assertEquals(EchoRendererFacade.BACKEND_CANVAS, r.backendName)
        assertEquals(EchoRenderTier.LEGACY, r.resolvedTier)
        assertNull("LEGACY 按请求解析，无降级 reason", r.reason)
    }

    @Test
    fun standardWithoutAgslFallsBackToCanvasWithReason() {
        val r = EchoRendererFacade.resolve(request(tier = EchoRenderTier.STANDARD), env(runtimeShader = false))
        assertEquals(EchoRendererFacade.BACKEND_CANVAS, r.backendName)
        assertEquals(EchoRenderTier.LEGACY, r.resolvedTier)
        assertNotNull("AGSL 不可用必须给 reason", r.reason)
        assertTrue(r.reason!!.contains("AGSL unavailable"))
    }

    @Test
    fun standardWithAgslResolvesAgslBackend() {
        val r = EchoRendererFacade.resolve(request(tier = EchoRenderTier.STANDARD), env(runtimeShader = true))
        assertEquals(EchoRendererFacade.BACKEND_AGSL, r.backendName)
        assertEquals(EchoRenderTier.STANDARD, r.resolvedTier)
        assertNull(r.reason)
    }

    @Test
    fun advancedWithoutAdvancedCapabilityDowngradesWithReason() {
        // Robolectric/JVM：API<36 → isAdvancedAvailable()=false → 降级 AGSL + reason
        val r = EchoRendererFacade.resolve(request(tier = EchoRenderTier.ADVANCED), env(runtimeShader = true))
        assertEquals(EchoRendererFacade.BACKEND_AGSL, r.backendName)
        assertEquals(EchoRenderTier.STANDARD, r.resolvedTier)
        assertNotNull("ADVANCED 降级必须给 reason", r.reason)
        assertTrue(r.reason!!.contains("ADVANCED unavailable"))
    }

    @Test
    fun agslSessionExposureIsSurfaceCropped() {
        // P1-1：非 Compose AGSL 会话的 exposure 必须来自 SurfacePolicy.crop 后 spec
        // （Wallpaper ×0.7 上限）——与 Compose 路径（EchoOrganismRenderer 的 spec.genome.luminance）同源；
        // dispatch(canvas, frame, spec) 消费 computeFrame 返回的同一裁剪 spec（源码级断言：不存在第二个未裁剪输入）
        val req = request(surface = EchoSurface.WALLPAPER_VISUAL_ONLY, tier = EchoRenderTier.STANDARD)
        val session = EchoRendererFacade.createSession(req, 108, 234)
        val computed = session.computeFrame(0L, req.interaction)
        assertEquals(
            "Wallpaper exposure 已裁剪 ×0.7（AGSL 与 Compose 同源）",
            req.genome.luminance * 0.7f,
            computed.spec.genome.luminance,
            1e-6f,
        )
        assertEquals(req.genome.identitySeed, computed.spec.genome.identitySeed)
    }

    @Test
    fun qualityDefaultsPerSurfaceAndEnvMayDowngrade() {
        // 默认质量预算：WALLPAPER→CONSERVE / WRIST→MINIMAL / APP→NORMAL
        assertEquals(
            EchoRenderQuality.CONSERVE,
            EchoRendererFacade.resolve(
                request(surface = EchoSurface.WALLPAPER_VISUAL_ONLY, tier = EchoRenderTier.LEGACY),
                env(runtimeShader = false),
            ).quality,
        )
        assertEquals(
            EchoRenderQuality.MINIMAL,
            EchoRendererFacade.resolve(
                request(surface = EchoSurface.WRIST_PUBLIC_SAFE, tier = EchoRenderTier.LEGACY),
                env(runtimeShader = false),
            ).quality,
        )
        assertEquals(
            EchoRenderQuality.NORMAL,
            EchoRendererFacade.resolve(
                request(surface = EchoSurface.APP_PRIVATE, tier = EchoRenderTier.LEGACY),
                env(runtimeShader = false),
            ).quality,
        )
        // 环境降级（worseOf）：APP 默认 NORMAL + 环境热态 CONSERVE → CONSERVE
        assertEquals(
            EchoRenderQuality.CONSERVE,
            EchoRendererFacade.resolve(
                request(surface = EchoSurface.APP_PRIVATE, tier = EchoRenderTier.LEGACY),
                env(runtimeShader = false, quality = EchoRenderQuality.CONSERVE),
            ).quality,
        )
    }

    @Test
    fun hdrIsAlwaysFalseUntilRealCapabilityExists() {
        // §T：即使 env 声称 hdrEligible（防御），resolution.hdr 也恒 false
        val honestEnv = env(runtimeShader = false).copy(hdrEligible = true)
        val r = EchoRendererFacade.resolve(request(tier = EchoRenderTier.LEGACY), honestEnv)
        assertFalse("§T：真实 Display.HdrCapabilities 能力存在前 HDR 恒 false", r.hdr)
    }

    @Test
    fun reducedMotionMapsToReducedFlagAndHalfScale() {
        val r = EchoRendererFacade.resolve(
            request(tier = EchoRenderTier.LEGACY, motion = MotionPolicy.REDUCED),
            env(runtimeShader = false),
        )
        assertTrue(r.reducedMotion)
        assertEquals(0.5f, r.motionScale, 1e-6f)
    }

    @Test
    fun journeyThumbnailRequestIsLowBudgetJourneyPrivate() {
        // §P：Journey minis 命名低预算预设（LEGACY / MINIMAL / JOURNEY_PRIVATE）
        val req = EchoRenderSession.journeyThumbnailRequest(genome(), "KNOWN")
        assertEquals(EchoRenderTier.LEGACY, req.requestedTier)
        assertEquals(EchoRenderQuality.MINIMAL, req.quality)
        assertEquals(EchoSurface.JOURNEY_PRIVATE, req.surface)
    }
}
