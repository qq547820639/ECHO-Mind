package com.yunjue.echo.mind.presencevisual

import com.yunjue.echo.mind.visual.render.AmbientField
import com.yunjue.echo.mind.visual.render.Atmosphere
import com.yunjue.echo.mind.visual.render.CoreCavity
import com.yunjue.echo.mind.visual.render.FrontMembrane
import com.yunjue.echo.mind.visual.render.Halo
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.Ripple
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T2-P2-5 / UX-B1 — AGSL vector mask 层对齐测试（JVM 可跑：mask 栅格化用软件 Canvas，
 * RuntimeShader 只在 draw 消费 mask；证据路径同 rasterizeMaskForInspection）。
 *
 * 断言 ripples（§29 触摸涟漪描边）/ halos（远层光环）/ frontMembrane（前膜细环）
 * 不再被 AGSL 后端静默丢弃：三层进 mask R 通道，与 Canvas 后端行为对齐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgslMaskLayerAlignmentTest {

    private val W = 540
    private val H = 540
    private val warmColor = 0xFFC8963C.toInt()

    /** 只含待测层的最小干净帧（其余层全空——环上 R 值只可能来自被测层）。 */
    private fun bareFrame(
        halos: List<Halo> = emptyList(),
        frontMembraneAlpha: Float = 0f,
        ripples: List<Ripple> = emptyList(),
    ): OrganismFrame = OrganismFrame(
        ambientField = AmbientField(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0f),
        atmosphere = Atmosphere(1.5f, 0f, 0xFF000000.toInt(), 0.9f, 0f, 0f, 0xFF000000.toInt()),
        halos = halos,
        structuralRings = emptyList(),
        longFilaments = emptyList(),
        localFragments = emptyList(),
        coreStrands = emptyList(),
        coreCavity = CoreCavity(0.35f, 0xFF000000.toInt(), 0xFF000000.toInt()),
        coreKnots = emptyList(),
        particles = emptyList(),
        frontMembrane = FrontMembrane(0.45f, 0xFF4488CC.toInt(), frontMembraneAlpha),
        ripples = ripples,
        warmAccents = emptyList(),
    )

    private fun maskR(frame: OrganismFrame, xPx: Int, yPx: Int): Int {
        val bmp = AgslEchoBackend.rasterizeMaskForInspection(frame, W, H, warmColor)
        val c = bmp.getPixel(xPx, yPx)
        return c shr 16 and 0xFF
    }

    @Test
    fun touchRippleStrokeEntersMaskRChannel() {
        // 触点 (0.5, 0.5)、半径 0.2·minDim 的涟漪描边：环上采样点 R>0，环内（中心）R=0
        val frame = bareFrame(ripples = listOf(Ripple(0.5f, 0.5f, 0.2f, 0.8f)))
        val onRing = maskR(frame, W / 2 + (0.2f * W).toInt(), H / 2)
        assertTrue("涟漪环上 mask R>0（实际 $onRing）——AGSL 后端不得静默丢弃触摸涟漪", onRing > 0)
        val center = maskR(frame, W / 2, H / 2)
        assertTrue("环内描边未覆盖（中心 R=0，实际 $center）", center == 0)
        // 基线：无涟漪帧同位置 R=0（增量只来自 ripple 层）
        val bare = maskR(bareFrame(), W / 2 + (0.2f * W).toInt(), H / 2)
        assertTrue("干净基线环上 R=0（实际 $bare）", bare == 0)
    }

    @Test
    fun haloRingEntersMaskRChannel() {
        val frame = bareFrame(halos = listOf(Halo(0.4f, 0.6f, 0.01f, 0xFF4488CC.toInt())))
        val onRing = maskR(frame, W / 2 + (0.4f * W).toInt(), H / 2)
        assertTrue("halo 环上 mask R>0（实际 $onRing）——AGSL 后端不得静默丢弃远层光环", onRing > 0)
    }

    @Test
    fun frontMembraneRingEntersMaskRChannel() {
        val frame = bareFrame(frontMembraneAlpha = 0.6f)
        val onRing = maskR(frame, W / 2 + (0.45f * W).toInt(), H / 2)
        assertTrue("前膜环上 mask R>0（实际 $onRing）——AGSL 后端不得静默丢弃前膜", onRing > 0)
    }
}
