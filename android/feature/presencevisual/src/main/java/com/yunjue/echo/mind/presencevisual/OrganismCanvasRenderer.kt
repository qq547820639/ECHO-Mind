package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.OrganismFrame
import kotlin.math.min

/**
 * OrganismCanvasRenderer — android.graphics.Canvas 渲染器（V3 LEGACY/Canvas fallback 正式后端）。
 *
 * 供 Wallpaper / Dream / 离屏 golden 截图共用；与 Compose 渲染器消费同一 [OrganismFrame]。
 * V3 §22：API 26–32 完整可用——同一 Identity/Topology/Motion/SceneCompiler/Palette，
 * 仅 Material Backend 不同（multi-stroke / radial gradient / restrained halo / depth alpha）。
 */
object OrganismCanvasRenderer {

    fun renderToBitmap(frame: OrganismFrame, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, frame, widthPx.toFloat(), heightPx.toFloat())
        return bitmap
    }

    fun draw(canvas: Canvas, frame: OrganismFrame, widthPx: Float, heightPx: Float) {
        val minDim = min(widthPx, heightPx)
        val cx = widthPx / 2f
        val cy = heightPx / 2f

        // 1. Ambient field（近黑径向衰减）
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, minDim * 1.15f,
                frame.ambientField.centerColor, frame.ambientField.edgeColor, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, widthPx, heightPx, bgPaint)

        // 2. Halo（远层）
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = frame.frontMembrane.color
        }
        frame.halos.forEach { halo ->
            haloPaint.strokeWidth = halo.widthFraction * minDim
            haloPaint.alpha = (halo.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(cx, cy, halo.radiusFraction * minDim, haloPaint)
        }

        // 3-5. 三层丝（结构环 / 长丝 + 辉光 / 局部碎片）
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        frame.structuralRings.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }
        frame.longFilaments.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = true) }
        frame.localFragments.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }

        // 6. 空心核：暗腔 + 内部大气（§18；禁止实心白球）
        val cavityR = frame.coreCavity.radiusFraction * minDim
        val atmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, cavityR * 1.35f,
                frame.coreCavity.atmosphereColor, frame.coreCavity.darkColor, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, cavityR * 1.35f, atmPaint)
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = frame.coreCavity.darkColor }
        canvas.drawCircle(cx, cy, cavityR, darkPaint)

        // 7. 核心细缕 + 稳定结
        frame.coreStrands.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }
        frame.coreKnots.forEach { k ->
            val kx = k.x * widthPx
            val ky = k.y * heightPx
            val knotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    kx, ky, k.radiusFraction * minDim * 2.2f,
                    withAlpha(k.color, k.alpha), withAlpha(k.color, 0f), Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(kx, ky, k.radiusFraction * minDim * 2.2f, knotPaint)
        }

        // 8. 粒子
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        frame.particles.forEach { p ->
            dotPaint.color = p.color
            dotPaint.alpha = (p.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(p.x * widthPx, p.y * heightPx, p.radiusFraction * minDim, dotPaint)
        }

        // 9. 前膜
        val memPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = frame.frontMembrane.color
            strokeWidth = minDim * 0.0016f
            alpha = (frame.frontMembrane.alpha.coerceIn(0f, 1f) * 255f).toInt()
        }
        canvas.drawCircle(cx, cy, frame.frontMembrane.radiusFraction * minDim, memPaint)

        // 10. 涟漪
        val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = frame.frontMembrane.color
            strokeWidth = minDim * 0.002f
        }
        frame.ripples.forEach { r ->
            ripplePaint.alpha = (r.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(r.x * widthPx, r.y * heightPx, r.radiusFraction * minDim, ripplePaint)
        }

        // 11. 暖金高光（极少量）
        frame.warmAccents.forEach { w ->
            val wx = w.x * widthPx
            val wy = w.y * heightPx
            val warmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    wx, wy, w.radiusFraction * minDim * 2f,
                    withAlpha(ColorSpace.WARM_GOLD, w.alpha), withAlpha(ColorSpace.WARM_GOLD, 0f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(wx, wy, w.radiusFraction * minDim * 2f, warmPaint)
        }
    }

    /** 逐段描边（per-point alpha 已烘焙 3D 深度/遮挡）；glow 追加一次更宽更淡的辉光。 */
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
            paint.strokeWidth = w
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawLine(a.x * canvas.width, a.y * canvas.height, b.x * canvas.width, b.y * canvas.height, paint)
            if (glow && stroke.glow > 0f) {
                paint.strokeWidth = w * 3.2f
                paint.alpha = (alpha * stroke.glow * 0.4f * 255f).toInt()
                canvas.drawLine(
                    a.x * canvas.width, a.y * canvas.height, b.x * canvas.width, b.y * canvas.height, paint,
                )
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return color and 0x00FFFFFF or (a shl 24)
    }
}
