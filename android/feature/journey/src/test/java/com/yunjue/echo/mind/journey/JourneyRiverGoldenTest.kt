package com.yunjue.echo.mind.journey
import android.graphics.Bitmap
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.presencevisual.OrganismCanvasRenderer
import com.yunjue.echo.mind.visual.model.PORTRAIT_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Journey Memory River golden：同一 identity 连续多日 portrait（identity evolution 连续可辨）。
 *
 * T8-P2-1 修复：原版仅断言 PNG>1KB + 末尾 assertTrue(true)（永真）。
 * 现参照 VisualReviewRenderTest 补人眼必答题的机器代理：
 * - 跨帧 accent 主色恒定（同 identity seed → 同一个 ECHO，色相不随天变）；
 * - 相邻日帧互不相同（时间流逝可见，不是同一帧复读）；
 * - 像素级结构连续（每天采样 distinct 像素 ≥ 100——不是全黑/纯色图）。
 * PNG 仍写入 qa/visual-review/organism/river 供人眼评审。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JourneyRiverGoldenTest {
    private fun p(date: String, days: Int, mv: String) = DailyPortraitDto(
        date = date, status = "READY", confidence = "HIGH", baselineDays = days, summary = "x",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(mv, z = if (mv == "MORE") 1.0 else 0.0),
            "SCREEN_AMOUNT" to PortraitDimensionDto("SIMILAR"),
            "RHYTHM" to PortraitDimensionDto("SIMILAR"),
        ),
    )

    /** 像素级结构代理：stride 采样 distinct 颜色数（全黑/纯色图会被拦下）。 */
    private fun distinctSampledColors(bmp: Bitmap): Int {
        val sampled = HashMap<Int, Int>()
        val stride = 17
        var x = 0
        while (x < bmp.width) {
            var y = 0
            while (y < bmp.height) {
                sampled[bmp.getPixel(x, y)] = (sampled[bmp.getPixel(x, y)] ?: 0) + 1
                y += stride
            }
            x += stride
        }
        return sampled.size
    }

    @Test
    fun rendersContinuousIdentityRiver() {
        val dir = File("../qa/visual-review/organism/river").also { it.mkdirs() }
        val seed = 7L
        val days = listOf(
            p("2026-08-10", 3, "LESS"), p("2026-08-11", 4, "SIMILAR"),
            p("2026-08-12", 5, "SIMILAR"), p("2026-08-13", 6, "MORE"),
            p("2026-08-14", 7, "SIMILAR"), p("2026-08-15", 8, "MORE"),
            p("2026-08-16", 9, "SIMILAR"),
        )
        // production 链等价装配（frameFor 直连路径已随 T5-P2-4 退役）
        val frames = days.map {
            val genome = JourneyOrganismVisuals.genomeFor(it, seed)!!
            OrganismFrameComputer.compute(
                spec = SurfacePolicy.crop(genome, EchoSurface.APP_PRIVATE, PORTRAIT_CANONICAL_TIME_SECONDS),
                width = 400f,
                height = 400f,
            )
        }

        // 1) 跨帧 accent 主色恒定：identity 色相不随天/成熟度变化（identity evolution ≠ 换色）
        val accent = frames.first().frontMembrane.color and 0xFFFFFF
        frames.forEachIndexed { i, frame ->
            assertEquals(
                "第 $i 天 accent 主色应与首日恒同（同一个 ECHO）",
                accent,
                frame.frontMembrane.color and 0xFFFFFF,
            )
        }

        // 2) 相邻日帧互不相同（连续可辨：成熟度/参数演进必须体现在帧上）
        for (i in 0 until frames.size - 1) {
            assertNotEquals("相邻两日帧应不同（${days[i].date} → ${days[i + 1].date}）", frames[i], frames[i + 1])
        }

        // 3) 像素级结构连续 + PNG 工件落盘（人眼评审输入）
        frames.forEachIndexed { i, frame ->
            val bmp = OrganismCanvasRenderer.renderToBitmap(frame, 400, 400)
            val distinct = distinctSampledColors(bmp)
            assertTrue("第 $i 天帧疑似纯色/全黑（distinct 采样色仅 $distinct 种）", distinct >= 100)
            val out = File(dir, "river_${days[i].date}.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("PNG 工件非空: $out", out.length() > 1000)
        }
    }
}
