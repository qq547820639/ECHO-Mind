package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.model.EchoVisualParameters
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
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
 * DAY / NIGHT / REDUCED / WALLPAPER / DREAM / WRIST。
 * PNG 是人看工件（不拿 AI 设计稿做 pixel-perfect golden）；确定性由「同 fixture 同哈希」保证。
 * V3 §H：presencevisual 不再解释 Presence——fixture 直接构造 EchoVisualParameters
 * 经 VisualGenomeCompiler 编译（与生产同一编译器）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganismGoldenRenderTest {

    private val W = 1080
    private val H = 1920

    private data class Fixture(
        val identity: EchoIdentityGenome,
        val params: EchoVisualParameters,
        val maturity: EchoMaturity,
    )

    private fun fixture(
        seed: Long,
        maturity: EchoMaturity,
        confidence: Float,
        activity: Float,
        coverage: Float,
        noise: Float,
    ): Fixture {
        val openness = when (maturity) {
            EchoMaturity.SEED -> 0.15f
            EchoMaturity.DISCOVERING -> 0.3f
            EchoMaturity.EMERGING -> 0.5f
            EchoMaturity.KNOWN -> 0.75f
            EchoMaturity.MATURE -> 0.9f
        }
        return Fixture(
            identity = EchoIdentityGenome(
                seed = seed, accentHue = 0.62f, colorFamily = 1, textureFamily = 2,
                coreTopology = 0.7f, symmetryTendency = 0.6f, orbitGeometry = 0.45f,
                motionPersonality = 0.5f,
            ),
            params = EchoVisualParameters(
                flowSpeed = activity,
                coherence = confidence,
                turbulence = noise,
                particleDensity = coverage,
                coreOpenness = openness,
                dispersion = (1f - confidence) * 0.5f + 0.2f,
                pulsePeriodSeconds = (5.6f - activity * 1.8f).coerceIn(3.6f, 6f),
                depth = 0.6f,
                brightness = 0.7f,
                contrast = 0.45f,
                accentIntensity = 0.6f,
                structureComplexity = openness,
                dataClarity = coverage,
                haloIntensity = 0.3f + confidence * 0.6f,
                momentIntensity = noise,
                filamentDensity = 0.45f + confidence * 0.3f,
            ),
            maturity = maturity,
        )
    }

    private fun render(f: Fixture, surface: EchoSurface, clock: Float): Bitmap {
        val spec = SurfacePolicy.crop(
            VisualGenomeCompiler.compile(f.params, f.identity), surface, clock,
        )
        val frame = OrganismFrameComputer.compute(
            spec, W.toFloat(), H.toFloat(),
            OrganismFrameComputer.EchoRenderOptions(maturityName = f.maturity.name),
        )
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
            "seed_day" to Pair(
                fixture(7L, EchoMaturity.SEED, 0.2f, 0.3f, 0.3f, 0.1f), EchoSurface.APP_PRIVATE,
            ),
            "known_day" to Pair(
                fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.APP_PRIVATE,
            ),
            "quiet" to Pair(
                fixture(7L, EchoMaturity.MATURE, 0.9f, 0.2f, 0.9f, 0.02f), EchoSurface.APP_PRIVATE,
            ),
            "low_data" to Pair(
                fixture(7L, EchoMaturity.EMERGING, 0.3f, 0.4f, 0.2f, 0.2f), EchoSurface.APP_PRIVATE,
            ),
            "night" to Pair(
                fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.3f, 0.85f, 0.2f), EchoSurface.APP_PRIVATE,
            ),
            "wallpaper" to Pair(
                fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f),
                EchoSurface.WALLPAPER_VISUAL_ONLY,
            ),
            "dream" to Pair(
                fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.3f, 0.85f, 0.1f), EchoSurface.DREAM_AMBIENT,
            ),
            "wrist" to Pair(
                fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f), EchoSurface.WRIST_PUBLIC_SAFE,
            ),
        )

        cases.forEach { (name, pair) ->
            val (f, surface) = pair
            val bmp = render(f, surface, clock)
            val out = save(bmp, name)
            assertTrue("$name 应产出非空 PNG", out.exists() && out.length() > 1000)
        }

        // 确定性：同 fixture 同哈希
        val a = render(
            fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f),
            EchoSurface.APP_PRIVATE, clock,
        )
        val b = render(
            fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f),
            EchoSurface.APP_PRIVATE, clock,
        )
        assertEquals("同 fixture 位图哈希一致", hash(a), hash(b))
    }

    @Test
    fun identityIsStableAcrossSurfaces() {
        val f = fixture(7L, EchoMaturity.KNOWN, 0.8f, 0.6f, 0.85f, 0.3f)
        val genome: EchoVisualGenome = VisualGenomeCompiler.compile(f.params, f.identity)
        EchoSurface.entries.forEach { surface ->
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
