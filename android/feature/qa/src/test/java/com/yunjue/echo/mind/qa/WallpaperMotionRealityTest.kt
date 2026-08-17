package com.yunjue.echo.mind.qa

import android.graphics.Bitmap
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ERA 31 R20 — Wallpaper 运动现实检查（§16/§17 的真实渲染验证）。
 *
 * R13/R14 把静态期降到 4fps（250ms 帧间隔）。本测试用 production 帧管线实测：
 * 静态期 4fps 的帧间像素差必须小到「看起来是缓慢呼吸，不是跳帧」，
 * 同时 2 秒累计变化必须非零——静态期 ECHO 仍然活着（§14 Ambient Evidence）。
 *
 * 帧模型时间连续（timeSeconds 按真实流逝推进），4fps 只是采样更稀；
 * 这里验证的是：同一运动在 250ms 采样间隔下的单帧跳变幅度，是否仍在「有机」范围。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperMotionRealityTest {

    /** 静置期帧间隔（R13 PresenceSurfacePolicy.IDLE_FRAME_INTERVAL_MS = 250ms）。 */
    private val IDLE_FRAME_MS = 250L

    /** 过渡期帧间隔（R13 TRANSITION_FRAME_INTERVAL_MS = 33ms）。 */
    private val TRANSITION_FRAME_MS = 33L

    /** 静态期单帧跳变上限：超过则 4fps 看起来像跳帧（历轮实测标定）。 */
    private val IDLE_FRAME_CHANGED_MAX = 0.06f

    /** 静态期 2 秒累计变化下限：低于则 ECHO 在静态期「死」了。 */
    private val IDLE_TWO_SECOND_CHANGED_MIN = 0.003f

    private fun frameOf(profile: QaProfileSpec, day: Int, timeSeconds: Float): Bitmap {
        val snap = QaTimeline(profile).snapshot(day)
        val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
            spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                    com.yunjue.echo.mind.presence.EchoVisualMapper.map(
                        snap.presence, VisualReviewRenderer.REVIEW_HOUR,
                    ),
                    snap.presence.identityGenome,
                ),
                com.yunjue.echo.mind.visual.surface.EchoSurface.WALLPAPER_VISUAL_ONLY,
                timeSeconds,
            ),
            width = VisualReviewRenderer.WALLPAPER_WIDTH.toFloat(),
            height = VisualReviewRenderer.WALLPAPER_HEIGHT.toFloat(),
            options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                maturityName = snap.presence.maturity.name,
            ),
        )
        return VisualReviewRenderer.renderFrame(
            frame, VisualReviewRenderer.WALLPAPER_WIDTH, VisualReviewRenderer.WALLPAPER_HEIGHT,
        )
    }

    /** 像素级差异：变化像素占比（逐像素通道差和 > 24 记为变化）。 */
    private fun changedPixelRatio(a: Bitmap, b: Bitmap): Float {
        require(a.width == b.width && a.height == b.height)
        val size = a.width * a.height
        val pa = IntArray(size)
        val pb = IntArray(size)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var changed = 0
        for (i in 0 until size) {
            val ca = pa[i]
            val cb = pb[i]
            val d = abs((ca and 0xFF) - (cb and 0xFF)) +
                abs((ca shr 8 and 0xFF) - (cb shr 8 and 0xFF)) +
                abs((ca shr 16 and 0xFF) - (cb shr 16 and 0xFF))
            if (d > 24) changed++
        }
        return changed.toFloat() / size
    }

    @Test
    fun idleFourFpsMotionIsCalmAndAlive() {
        val profiles = listOf(
            QaProfiles.ALL.first { it.id == "PROFILE_A_STABLE" }, // 稳定用户
            QaProfiles.ALL.first { it.id == "PROFILE_G_WEEKEND_DIFFERENT" }, // 周末差异型
        )
        for (profile in profiles) {
            val day = 90
            val t0 = 600.0f
            val idleFrame = changedPixelRatio(
                frameOf(profile, day, t0),
                frameOf(profile, day, t0 + IDLE_FRAME_MS / 1000f),
            )
            val twoSecond = changedPixelRatio(
                frameOf(profile, day, t0),
                frameOf(profile, day, t0 + 2.0f),
            )
            val transitionFrame = changedPixelRatio(
                frameOf(profile, day, t0),
                frameOf(profile, day, t0 + TRANSITION_FRAME_MS / 1000f),
            )
            println(
                "MOTION ${profile.id} day$day idle250ms=%.4f idle2s=%.4f transition33ms=%.4f"
                    .format(idleFrame, twoSecond, transitionFrame)
            )
            assertTrue(
                "${profile.id} 静态期 4fps 单帧跳变过大（%.4f）：应像缓慢呼吸而非跳帧".format(idleFrame),
                idleFrame < IDLE_FRAME_CHANGED_MAX,
            )
            assertTrue(
                "${profile.id} 静态期 2 秒累计变化过小（%.4f）：ECHO 应仍然活着".format(twoSecond),
                twoSecond > IDLE_TWO_SECOND_CHANGED_MIN,
            )
            assertTrue(
                "${profile.id} 过渡期单帧跳变（%.4f）应小于静态期单帧跳变（%.4f）：流畅窗口真实生效".format(
                    transitionFrame, idleFrame
                ),
                transitionFrame < idleFrame,
            )
        }
    }
}
