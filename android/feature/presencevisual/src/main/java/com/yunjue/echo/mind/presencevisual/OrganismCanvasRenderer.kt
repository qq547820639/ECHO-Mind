package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.yunjue.echo.mind.visual.render.OrganismFrame
import kotlin.math.min

/**
 * OrganismCanvasRenderer — android.graphics.Canvas 渲染器。
 *
 * 供 Wallpaper / Dream / 离屏 golden 截图共用；与 Compose 渲染器消费同一 [OrganismFrame]。
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

        // 1. Ambient field
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, minDim * 1.15f,
                intArrayOf(frame.ambientField.centerColor, frame.ambientField.edgeColor),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, widthPx, heightPx, bgPaint)

        val accent = frame.membrane.strokeColor

        // 7. Halo
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
        }
        frame.halos.forEach { halo ->
            haloPaint.strokeWidth = halo.widthFraction * minDim
            haloPaint.alpha = (halo.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, halo.radiusFraction * minDim, haloPaint)
        }

        // 4. Orbital（椭圆）
        val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
        }
        frame.orbitals.forEach { orb ->
            orbPaint.strokeWidth = orb.widthFraction * minDim
            orbPaint.alpha = (orb.alpha * 255f).toInt().coerceIn(0, 255)
            val r = orb.radiusFraction * minDim
            canvas.save()
            canvas.rotate(Math.toDegrees(orb.rotationRadians.toDouble()).toFloat(), cx, cy)
            canvas.drawOval(
                cx - r, cy - r * (1f - orb.eccentricity),
                cx + r, cy + r * (1f - orb.eccentricity),
                orbPaint,
            )
            canvas.restore()
        }

        // 3. Filament
        val filPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        frame.filaments.forEach { f ->
            filPaint.strokeWidth = f.widthFraction * minDim
            filPaint.alpha = (f.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawLine(f.x1 * widthPx, f.y1 * heightPx, f.x2 * widthPx, f.y2 * heightPx, filPaint)
        }

        // 2. 主膜轮廓
        if (frame.membrane.outline.size >= 3) {
            val path = Path()
            val first = frame.membrane.outline[0]
            path.moveTo(first.x * widthPx, first.y * heightPx)
            for (i in 1 until frame.membrane.outline.size) {
                val p = frame.membrane.outline[i]
                path.lineTo(p.x * widthPx, p.y * heightPx)
            }
            path.close()
            val memPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = accent
                strokeWidth = frame.membrane.strokeWidthFraction * minDim
                alpha = (frame.membrane.strokeAlpha * 255f).toInt().coerceIn(0, 255)
            }
            canvas.drawPath(path, memPaint)
        }

        // 5. 粒子
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            strokeCap = Paint.Cap.ROUND
        }
        frame.particles.forEach { p ->
            val px = p.x * widthPx
            val py = p.y * heightPx
            if (p.streakLength > 0f) {
                val len = p.streakLength * minDim
                streakPaint.alpha = (p.alpha * 255f).toInt().coerceIn(0, 255)
                streakPaint.strokeWidth = p.radiusFraction * minDim
                canvas.drawLine(
                    px - p.streakDirX * len / 2f, py - p.streakDirY * len / 2f,
                    px + p.streakDirX * len / 2f, py + p.streakDirY * len / 2f,
                    streakPaint,
                )
            } else {
                dotPaint.alpha = (p.alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, p.radiusFraction * minDim, dotPaint)
            }
        }

        // 8. 涟漪
        val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
            strokeWidth = minDim * 0.002f
        }
        frame.ripples.forEach { r ->
            ripplePaint.alpha = (r.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r.radiusFraction * minDim, ripplePaint)
        }

        // 6. 核心光斑
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, frame.coreGlow.radiusFraction * minDim * 2.4f,
                intArrayOf(withAlpha(frame.coreGlow.color, frame.coreGlow.intensity),
                    withAlpha(frame.coreGlow.color, 0f)),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, frame.coreGlow.radiusFraction * minDim * 2.4f, corePaint)

        // 9. 暖金高光
        frame.warmAccents.forEach { w ->
            val wx = w.x * widthPx
            val wy = w.y * heightPx
            val warmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    wx, wy, w.radiusFraction * minDim * 2f,
                    intArrayOf(withAlpha(com.yunjue.echo.mind.visual.render.ColorSpace.WARM_GOLD, w.alpha),
                        withAlpha(com.yunjue.echo.mind.visual.render.ColorSpace.WARM_GOLD, 0f)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(wx, wy, w.radiusFraction * minDim * 2f, warmPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return color and 0x00FFFFFF or (a shl 24)
    }
}
