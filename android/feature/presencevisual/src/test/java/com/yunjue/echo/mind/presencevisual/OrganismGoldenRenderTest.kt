package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoMomentState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.BehaviorState
import com.yunjue.echo.mind.visual.model.GenomeDeriver
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Organism Golden Render — 用真实渲染管线产出 PNG 工件供人眼验证 + 确定性哈希回归。
 *
 * 覆盖 ECHO_VISUAL_ACCEPTANCE §一 的关键 fixture：SEED / KNOWN / QUIET / LOW_DATA /
 * DAY / NIGHT / REDUCED_MOTION / WALLPAPER / DREAM / WRIST。
 * PNG 是人看工件（不拿 AI 设计稿做 pixel-perfect golden）；确定性由「同 fixture 同哈希」保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganismGoldenRenderTest {

    private val W = 1080
    private val H = 1920

    private fun fixture(
        seed: Long,
        maturity: EchoMaturity,
        confidence: Float,
        activity: Float,
        coverage: Float,
        noise: Float,
    ) = EchoPresenceState(
        maturity = maturity,
        confidence = confidence,
        rhythmState = RhythmState(activityLevel = activity, coverage = coverage),
        behaviorState = BehaviorState(density = activity, deviation = noise),
        identityGenome = EchoIdentityGenome(
            seed = seed, accentHue = 0.62f, colorFamily = 1, textureFamily = 2,
            coreTopology = 0.7f, symmetryTendency = 0.6f, orbitGeometry = 0.45f,
            motionPersonality = 0.5f,
        ),
        momentState = EchoMomentState(breathingPeriod = 4.5f, noiseScale = noise),
    )

    private fun render(state: EchoPresenceState, surface: EchoSurface, clock: Float, hour: Float): Bitmap {
        val genome = GenomeDeriver.derive(state, hour)
        val spec = SurfacePolicy.crop(genome, surface, clock)
        val frame = OrganismFrameComputer.compute(spec, W.toFloat(), H.toFloat())
        return OrganismCanvasRenderer.renderToBitmap(frame, W, H)
    }

    private fun save(bitmap: Bitmap, name: String): File {
        val dir = File("../qa/visual-review/organism").also { it.mkdirs() }
        val out = File(dir, "$name.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    @Test
    fun rendersKeyFixturesAndIsDeterministic() {
        val clock = 8f
        val cases = linkedMapOf(
            "seed_day" to Triple(fixture(7L, EchoMaturity.SEED, 0.2f, 0.3f, 0.3f, 0.1f), EchoSurface.APP_PRIVATE, 14f),
            "known_day" to Triple(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.APP_PRIVATE, 14f),
            "quiet" to Triple(fixture(7L, EchoMaturity.MATURE, 0.9f, 0.2f, 0.9f, 0.02f), EchoSurface.APP_PRIVATE, 21f),
            "low_data" to Triple(fixture(7L, EchoMaturity.EMERGING, 0.3f, 0.4f, 0.2f, 0.2f), EchoSurface.APP_PRIVATE, 10f),
            "night" to Triple(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.3f, 0.85f, 0.2f), EchoSurface.APP_PRIVATE, 2f),
            "wallpaper" to Triple(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.WALLPAPER_VISUAL_ONLY, 15f),
            "dream" to Triple(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.3f, 0.85f, 0.1f), EchoSurface.DREAM_AMBIENT, 23f),
            "wrist" to Triple(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.WRIST_PUBLIC_SAFE, 14f),
        )

        cases.forEach { (name, triple) ->
            val (state, surface, hour) = triple
            val bmp = render(state, surface, clock, hour)
            val out = save(bmp, name)
            assertTrue("$name 应产出非空 PNG", out.exists() && out.length() > 1000)
        }

        // 确定性：同 fixture 同哈希
        val a = render(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.APP_PRIVATE, clock, 14f)
        val b = render(fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.APP_PRIVATE, clock, 14f)
        assertEquals("同 fixture 位图哈希一致", hash(a), hash(b))
    }

    @Test
    fun identityIsStableAcrossSurfaces() {
        val state = fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f)
        val genome = GenomeDeriver.derive(state, 14f)
        EchoSurface.values().forEach { surface ->
            val spec = SurfacePolicy.crop(genome, surface, 0f)
            assertEquals("identity seed 跨 surface 恒定", genome.identitySeed, spec.genome.identitySeed)
            assertEquals(genome.identityPhase, spec.genome.identityPhase, 1e-6f)
        }
    }

    private fun hash(bitmap: Bitmap): Int {
        var h = 1
        val step = 17 // 抽样哈希，避免全图遍历过慢
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                h = 31 * h + bitmap.getPixel(x, y)
                x += step
            }
            y += step
        }
        return h
    }
}
