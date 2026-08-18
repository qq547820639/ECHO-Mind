package com.yunjue.echo.mind.presencevisual

import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import com.yunjue.echo.mind.visual.testing.VisualLabFixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V3 §35/§82 — Reference KNOWN Day28 自动视觉门（Canvas fallback 后端，V3 §S）。
 *
 * 本门只锚定 **Canvas fallback** 参考帧（Robolectric JVM 无法执行真实 AGSL）；
 * 生产 AGSL/ADVANCED 后端由设备 instrumented 视觉门
 * （app androidTest `AdvancedBackendVisualGate`）承担。
 *
 * APP / 412×915 基准（这里用 1080×2340 等比）/ normal motion / Canvas 后端。
 * 指标阈值：near-black ≥58% / highlight ≤4% / warm ≤15% / negative-space ≥40% /
 * visual-mass@.9R ≥82% / core cavity 清楚（中心非高亮）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasFallbackVisualGateTest {

    private val W = 1080
    private val H = 2340

    private fun renderReference(): Pair<android.graphics.Bitmap, VisualLabMetrics.Metrics> {
        val genome = VisualLabFixtures.genomeFor(VisualLabFixtures.Preset.KNOWN_DAY28)
        val spec = SurfacePolicy.crop(genome, EchoSurface.APP_PRIVATE, 12f)
        val frame = OrganismFrameComputer.compute(
            spec, W.toFloat(), H.toFloat(),
            OrganismFrameComputer.EchoRenderOptions(
                maturityName = VisualLabFixtures.maturityFor(VisualLabFixtures.Preset.KNOWN_DAY28),
                tier = EchoRenderTier.LEGACY,
            ),
        )
        val bitmap = OrganismCanvasRenderer.renderToBitmap(frame, W, H)
        // R 与帧计算机同公式（单一事实源 baseRadiusFor）
        val identity = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(genome.identitySeed)
        val baseR = OrganismFrameComputer.baseRadiusFor(
            genome.radialSpread, genome.coreIntensity, identity.membraneBias,
        )
        val metrics = VisualLabMetrics.compute(bitmap, W / 2f, H / 2f, baseR * W)
        return bitmap to metrics
    }

    @Test
    fun referenceKnownDay28MeetsAutomaticVisualGates() {
        val (_, m) = renderReference()
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
    fun metricsAreStableForSameFixture() {
        val a = renderReference().second
        val b = renderReference().second
        assertTrue(a == b)
    }
}
