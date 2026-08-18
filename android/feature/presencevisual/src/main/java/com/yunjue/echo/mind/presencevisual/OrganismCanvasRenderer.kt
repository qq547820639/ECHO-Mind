package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.yunjue.echo.mind.visual.render.CoreCavity
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.OrganismFrame
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * OrganismCanvasRenderer — android.graphics.Canvas 渲染器（V3 LEGACY/Canvas fallback 正式后端）。
 *
 * 供 Wallpaper / Dream / 离屏 golden 截图共用；与 Compose 渲染器消费同一 [OrganismFrame]。
 * V3 §22：API 26–32 完整可用——同一 Identity/Topology/Motion/SceneCompiler/Palette，
 * 仅 Material Backend 不同（multi-stroke / radial gradient / restrained halo / depth alpha）。
 *
 * Organism Quality Pass：体积大气（haze+rim，§13）、碎片辉光（§7）、
 * 有机形变暗腔路径（§8）、ambient 半径随身体（1.6R）。
 *
 * §32 热路径纪律（参照 AgslSession 会话复用）：Paint/Path 提为 object 级成员，
 * draw 时重置参数复用（消除逐帧 12+ Paint / 2 Path 分配）；RadialGradient 等带参
 * shader 仍按帧构建（shader 对象无法跨参数复用）。object 单例可能被 Wallpaper/Dream
 * 多会话线程并发调用 → draw 整体持锁串行化（无竞争时锁成本可忽略）。
 */
object OrganismCanvasRenderer {

    // ---- §32 复用成员（帧路径零 Paint 分配）----
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val atmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val memPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val warmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cavityPath = Path()

    fun renderToBitmap(frame: OrganismFrame, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, frame, widthPx.toFloat(), heightPx.toFloat())
        return bitmap
    }

    fun draw(canvas: Canvas, frame: OrganismFrame, widthPx: Float, heightPx: Float) {
        synchronized(this) { drawFrame(canvas, frame, widthPx, heightPx) }
    }

    private fun drawFrame(canvas: Canvas, frame: OrganismFrame, widthPx: Float, heightPx: Float) {
        val minDim = min(widthPx, heightPx)
        val cx = widthPx / 2f
        val cy = heightPx / 2f

        // 1. Ambient field（近黑径向衰减；半径随身体 ≈1.6R——大气包裹而非整屏）
        bgPaint.shader = RadialGradient(
            cx, cy, (frame.ambientField.radiusFraction * minDim).coerceAtLeast(minDim * 0.4f),
            intArrayOf(
                frame.ambientField.centerColor,
                frame.ambientField.midColor,
                frame.ambientField.edgeColor,
            ),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, widthPx, heightPx, bgPaint)

        // 1b. Atmosphere（§13：volume haze 环形分布 + rim 膜散射；克制——禁整屏 bloom）
        val atm = frame.atmosphere
        val hazeR = atm.hazeRadiusFraction * minDim
        hazePaint.shader = RadialGradient(
            cx, cy, hazeR,
            intArrayOf(
                withAlpha(atm.hazeColor, 0f),
                withAlpha(atm.hazeColor, atm.hazeAlpha * 0.45f),
                withAlpha(atm.hazeColor, atm.hazeAlpha),
                withAlpha(atm.hazeColor, 0f),
            ),
            floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, hazeR, hazePaint)
        rimPaint.color = atm.rimColor
        rimPaint.strokeWidth = atm.rimWidthFraction * minDim
        rimPaint.alpha = (atm.rimAlpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawCircle(cx, cy, atm.rimRadiusFraction * minDim, rimPaint)

        // 2. Halo（远层；自带颜色——不再借 frontMembrane.color）
        frame.halos.forEach { halo ->
            haloPaint.color = halo.color
            haloPaint.strokeWidth = halo.widthFraction * minDim
            haloPaint.alpha = (halo.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(cx, cy, halo.radiusFraction * minDim, haloPaint)
        }

        // 3-5. 三层丝（结构环 / 长丝 + 辉光 / 局部碎片 + 辉光——§7 碎片可见性）
        frame.structuralRings.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }
        frame.longFilaments.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = true) }
        frame.localFragments.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = true) }

        // 6. 空心核：暗腔 + 内部大气（§8；禁止实心白球；有机形变边缘）
        val cavityR = frame.coreCavity.radiusFraction * minDim
        atmPaint.shader = RadialGradient(
            cx, cy, cavityR * 1.35f,
            frame.coreCavity.atmosphereColor, frame.coreCavity.darkColor, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(cavityPathOf(frame.coreCavity, cx, cy, cavityR * 1.35f), atmPaint)
        darkPaint.color = frame.coreCavity.darkColor
        canvas.drawPath(cavityPathOf(frame.coreCavity, cx, cy, cavityR), darkPaint)

        // 7. 核心细缕 + 稳定结
        frame.coreStrands.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }
        frame.coreKnots.forEach { k ->
            val kx = k.x * widthPx
            val ky = k.y * heightPx
            knotPaint.shader = RadialGradient(
                kx, ky, k.radiusFraction * minDim * 1.4f,
                withAlpha(k.color, k.alpha), withAlpha(k.color, 0f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(kx, ky, k.radiusFraction * minDim * 1.4f, knotPaint)
        }

        // 8. 粒子
        frame.particles.forEach { p ->
            dotPaint.color = p.color
            dotPaint.alpha = (p.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(p.x * widthPx, p.y * heightPx, p.radiusFraction * minDim, dotPaint)
        }

        // 9. 前膜
        memPaint.color = frame.frontMembrane.color
        memPaint.strokeWidth = minDim * 0.0016f
        memPaint.alpha = (frame.frontMembrane.alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawCircle(cx, cy, frame.frontMembrane.radiusFraction * minDim, memPaint)

        // 10. 涟漪
        ripplePaint.color = frame.frontMembrane.color
        ripplePaint.strokeWidth = minDim * 0.002f
        frame.ripples.forEach { r ->
            ripplePaint.alpha = (r.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(r.x * widthPx, r.y * heightPx, r.radiusFraction * minDim, ripplePaint)
        }

        // 11. 暖金高光（极少量；颜色 = identity palette.warm 单源——与 AGSL iWarm 同流）
        frame.warmAccents.forEach { w ->
            val wx = w.x * widthPx
            val wy = w.y * heightPx
            warmPaint.shader = RadialGradient(
                wx, wy, w.radiusFraction * minDim * 2f,
                withAlpha(w.color, w.alpha), withAlpha(w.color, 0f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(wx, wy, w.radiusFraction * minDim * 2f, warmPaint)
        }
    }

    /**
     * §8 有机暗腔路径：radius(θ) = R·(1 + Σ amp·cos(order·θ + phase))——
     * identity 恒定的 2/3 阶谐波形变，非机械完美圆。
     * §32：填充复用成员 [cavityPath]（rewind 重置；调用方按序消费）。
     */
    private fun cavityPathOf(cavity: CoreCavity, cx: Float, cy: Float, radiusPx: Float): Path {
        cavityPath.rewind()
        if (cavity.harmonics.isEmpty()) {
            cavityPath.addCircle(cx, cy, radiusPx, Path.Direction.CW)
            return cavityPath
        }
        val steps = 48
        for (i in 0..steps) {
            val theta = i.toFloat() / steps * 2f * Math.PI.toFloat()
            var r = 1f
            cavity.harmonics.forEach { h ->
                r += h.amplitude * cos(h.order * theta + h.phase)
            }
            val x = cx + cos(theta) * radiusPx * r
            val y = cy + sin(theta) * radiusPx * r
            if (i == 0) cavityPath.moveTo(x, y) else cavityPath.lineTo(x, y)
        }
        cavityPath.close()
        return cavityPath
    }

    /**
     * 逐段描边（per-point alpha 已烘焙 3D 深度/遮挡）。
     * §Y：每段先画宽而淡的 GLOW，再画细而实的 core（与 Compose 渲染器同序）。
     */
    private fun drawStroke(
        canvas: Canvas,
        stroke: FilamentStroke,
        minDim: Float,
        paint: Paint,
        glow: Boolean,
    ) {
        val pts = stroke.points
        if (pts.size < 2) return
        val w = stroke.widthFraction * minDim
        paint.color = stroke.color
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val alpha = ((a.alpha + b.alpha) * 0.5f).coerceIn(0f, 1f)
            if (alpha <= 0.004f) continue
            if (glow && stroke.glow > 0f) {
                paint.strokeWidth = w * 3.2f
                paint.alpha = (alpha * stroke.glow * 0.4f * 255f).toInt()
                canvas.drawLine(
                    a.x * canvas.width, a.y * canvas.height, b.x * canvas.width, b.y * canvas.height, paint,
                )
            }
            paint.strokeWidth = w
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawLine(a.x * canvas.width, a.y * canvas.height, b.x * canvas.width, b.y * canvas.height, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return color and 0x00FFFFFF or (a shl 24)
    }
}
