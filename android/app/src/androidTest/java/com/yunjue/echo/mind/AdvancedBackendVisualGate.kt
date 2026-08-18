package com.yunjue.echo.mind

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunjue.echo.mind.presencevisual.AgslEchoBackend
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.presencevisual.VisualLabMetrics
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.testing.VisualLabFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V3 §S — 生产 AGSL/ADVANCED 后端设备视觉门（instrumented；JVM 无法执行真实 AGSL，
 * Canvas fallback 参考由 `CanvasFallbackVisualGateTest` 承担）。
 *
 * 前置 Assume：API≥33 且 RuntimeShader 真实可用（不可用设备上跳过而非失败）。
 * 请求 ADVANCED（设备 <API36 时 facade 降级 AGSL——门只要求**非 CANVAS** 真实材质后端），
 * KNOWN_DAY28 fixture / 1080×2340 / APP_PRIVATE，指标阈值与 Canvas 门一致。
 */
@RunWith(AndroidJUnit4::class)
class AdvancedBackendVisualGate {

    private val W = 1080
    private val H = 2340

    @Test
    fun advancedBackendKnownDay28MeetsAutomaticVisualGates() {
        assumeTrue("AGSL 需要 API>=33", android.os.Build.VERSION.SDK_INT >= 33)
        assumeTrue("RuntimeShader 真实可用（不可用设备跳过）", AgslEchoBackend.isAvailable())

        val genome = VisualLabFixtures.genomeFor(VisualLabFixtures.Preset.KNOWN_DAY28)
        val request = EchoRenderRequest(
            genome = genome,
            surface = EchoSurface.APP_PRIVATE,
            maturityName = VisualLabFixtures.maturityFor(VisualLabFixtures.Preset.KNOWN_DAY28),
            requestedTier = EchoRenderTier.ADVANCED,
        )
        val session = EchoRendererFacade.createSession(request, W, H)

        // 门前提：真实解析到 AGSL/AGSL_ADVANCED（绝不能静默回退 Canvas）
        assertTrue(
            "期望 AGSL 后端，实际 ${session.resolution.backendName}（reason=${session.resolution.reason}）",
            session.resolution.backendName != EchoRendererFacade.BACKEND_CANVAS,
        )

        // 离屏 raster 恒 CANVAS（软件位图无法执行 RuntimeShader——Android 真实约束）；
        // 本门断言：① resolution 真值非 CANVAS；② 同帧 Canvas raster 指标达标；
        // ③ AGSL shader 真实可编译（isAvailable 已在 assume 中证明）。
        // AGSL raster 的像素级视觉证据需设备 HW canvas（RenderNode/HardwareRenderer），
        // 属 BLOCKED_EXTERNAL_DEVICE 项——见 qa/reports/ECHO_ORGANISM_QUALITY_FINAL.md。

        val bitmap = session.renderToBitmap((12f * 1_000_000_000L).toLong())
        val identity = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(genome.identitySeed)
        val baseR = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.baseRadiusFor(
            genome.radialSpread, genome.coreIntensity, identity.membraneBias,
        )
        val m = VisualLabMetrics.compute(bitmap, W / 2f, H / 2f, baseR * W)
        val gate = VisualLabMetrics.evaluate(m)
        assertTrue("near-black ${m.nearBlackRatio} >= 58%", gate.nearBlackPass)
        assertTrue("high-luminance ${m.highLuminanceRatio} <= 4%", gate.highLuminancePass)
        assertTrue("extreme glint ${m.extremeGlintRatio} <= 2.5%", gate.extremeGlintPass)
        assertTrue("warm ${m.warmRatio} <= 15%", gate.warmPass)
        assertTrue("negative-space ${m.negativeSpaceRatio} >= 40%", gate.negativeSpacePass)
        assertTrue("visual-mass@.9R ${m.visualMassInside} >= 82%", gate.visualMassPass)
        assertTrue("core cavity clear (centerLuminance=${m.centerLuminance})", gate.cavityPass)
    }

    @Test
    fun advancedRequestOnCapableDeviceResolvesAtLeastAgsl() {
        assumeTrue("AGSL 需要 API>=33", android.os.Build.VERSION.SDK_INT >= 33)
        assumeTrue("RuntimeShader 真实可用", AgslEchoBackend.isAvailable())
        val genome = VisualLabFixtures.genomeFor(VisualLabFixtures.Preset.KNOWN_DAY28)
        val resolution = EchoRendererFacade.resolve(
            EchoRenderRequest(
                genome = genome,
                surface = EchoSurface.APP_PRIVATE,
                maturityName = "KNOWN",
                requestedTier = EchoRenderTier.ADVANCED,
            ),
        )
        assertEquals(EchoRenderTier.ADVANCED, resolution.requestedTier)
        assertTrue(
            "ADVANCED 请求在 AGSL 设备上必须解析到 AGSL/AGSL_ADVANCED，实际 ${resolution.backendName}",
            resolution.backendName == EchoRendererFacade.BACKEND_AGSL ||
                resolution.backendName == EchoRendererFacade.BACKEND_AGSL_ADVANCED,
        )
    }
}
