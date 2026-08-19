package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.yunjue.echo.mind.visual.render.CoreCavity
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.MembraneSpec
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.ParticleKind
import com.yunjue.echo.mind.visual.render.VolumeLobeV
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * OrganismCanvasRenderer — android.graphics.Canvas 渲染器（V3 LEGACY/Canvas fallback 正式后端）。
 *
 * 供 Wallpaper / Dream / 离屏 golden 截图共用；与 Compose 渲染器消费同一 [OrganismFrame]。
 * V3 §22：API 26–32 完整可用——同一 Identity/Topology/Motion/SceneCompiler/Palette，
 * 仅 Material Backend 不同。
 *
 * Organism Visual Breakthrough 分层（§25 渲染顺序；线框球 → 体积生命体）：
 *  01 ambient field（近黑 deep-navy 径向场）
 *  02 atmosphere（volume haze + rim scattering）
 *  03 far halos
 *  04 lower spatial rings + reflection glow（§26 ECHO「存在于空间」）
 *  05 back volume lobes（depth < .45 的 nebula 云底）
 *  06 core glow（心脏光——暗腔嵌入组织）
 *  07 structural rings / long filaments / local fragments（3-pass 丝材质）
 *  08 mid volume lobes（.45–.82 主云体）
 *  09 cavity absorption（小而柔的有机暗腔）
 *  10 front volume lobes（> .82 前景云）
 *  11 core strands + knots（bright nodes）
 *  12 particles（BRIGHT/GLINT 带辉光晕）
 *  13 organic membrane（fill + edge scattering + rim glow + outer haze）
 *  14 ripples / warm accents
 *
 * §32 热路径纪律：Paint/Path/Matrix 提为 object 级成员复用；体积叶经
 * [NebulaTextureCache]（runtime procedural、确定性、缓存复用）drawBitmap 零分配。
 * object 单例可能被 Wallpaper/Dream 多会话线程并发调用 → draw 整体持锁串行化。
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
    private val lobePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cavityPath = Path()
    private val membranePath = Path()
    private val lobeMatrix = Matrix()
    private val groundOval = RectF()

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

        // ---- 01. Ambient field（近黑径向衰减；半径随身体 ≈1.6R——大气包裹而非整屏）----
        bgPaint.shader = RadialGradient(
            cx, cy, (frame.ambientField.radiusFraction * minDim).coerceAtLeast(minDim * 0.4f),
            intArrayOf(
                frame.ambientField.centerColor,
                frame.ambientField.midColor,
                frame.ambientField.edgeColor,
            ),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, widthPx, heightPx, bgPaint)

        // ---- 02. Atmosphere（§13：volume haze 环形分布 + rim 膜散射；克制——禁整屏 bloom）----
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

        // ---- 03. Halo（远层；自带颜色——不再借 frontMembrane.color）----
        frame.halos.forEach { halo ->
            haloPaint.color = halo.color
            haloPaint.strokeWidth = halo.widthFraction * minDim
            haloPaint.alpha = (halo.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(cx, cy, halo.radiusFraction * minDim, haloPaint)
        }

        // ---- 04. 下方空间能量环 + 反射辉光（Breakthrough §26；Wrist 空）----
        drawGroundRings(canvas, frame, widthPx, heightPx, minDim)

        // ---- 05. 后层体积叶（nebula 云底）----
        drawVolumeLobes(canvas, frame, widthPx, heightPx, minDim, maxDepth = 0.45f)

        // ---- 06. 核心辉光（心脏光；随后被暗腔中心压暗 → 形成「嵌入组织」的深度）----
        frame.coreGlow?.let { glow ->
            val tex = NebulaTextureCache.textureFor(glow.color)
            drawScaledTexture(
                canvas, tex,
                cx = cx, cy = cy,
                rx = glow.radiusFraction * minDim, ry = glow.radiusFraction * minDim,
                rotationDeg = 0f, alpha = glow.alpha,
            )
        }

        // ---- 07. 三层丝（结构环 / 长丝 / 局部碎片——3-pass 丝材质：宽辉光 + 中间体 + 细亮芯）----
        frame.structuralRings.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }
        frame.longFilaments.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = true) }
        frame.localFragments.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = true) }

        // ---- 08. 中层体积叶（主云体——承担彩色发光面积的主体）----
        drawVolumeLobes(canvas, frame, widthPx, heightPx, minDim, minDepth = 0.45f, maxDepth = 0.82f)

        // ---- 09. 空心核：暗腔 + 内部大气（§14 重平衡：小而柔，嵌入云组织中）----
        val cavityR = frame.coreCavity.radiusFraction * minDim
        atmPaint.shader = RadialGradient(
            cx, cy, cavityR * 1.35f,
            frame.coreCavity.atmosphereColor, frame.coreCavity.darkColor, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(cavityPathOf(frame.coreCavity, cx, cy, cavityR * 1.35f), atmPaint)
        darkPaint.color = frame.coreCavity.darkColor
        darkPaint.alpha = 235 // 柔边缘：不完全 255——暗腔边界融进云
        canvas.drawPath(cavityPathOf(frame.coreCavity, cx, cy, cavityR), darkPaint)

        // ---- 10. 前层体积叶（前景云——部分遮暗腔，制造深度）----
        drawVolumeLobes(canvas, frame, widthPx, heightPx, minDim, minDepth = 0.82f)

        // ---- 11. 核心细缕 + 稳定结（bright nodes）----
        frame.coreStrands.forEach { drawStroke(canvas, it, minDim, strokePaint, glow = false) }
        frame.coreKnots.forEach { k ->
            val kx = k.x * widthPx
            val ky = k.y * heightPx
            val kr = k.radiusFraction * minDim * 1.4f
            // Breakthrough §28：平坦中心剖面（0–30% 半径保持 ~90% alpha）——
            // 发射结内部形成真正 >0.8 luma 的「生命火种」区（线性衰减只有 ~2px 亮核）
            knotPaint.shader = RadialGradient(
                kx, ky, kr,
                intArrayOf(
                    withAlpha(k.color, k.alpha),
                    withAlpha(k.color, k.alpha * 0.88f),
                    withAlpha(k.color, 0f),
                ),
                floatArrayOf(0f, 0.30f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(kx, ky, kr, knotPaint)
        }

        // ---- 12. 粒子（BRIGHT/GLINT 带辉光晕——嵌在 volume 中的光尘）----
        frame.particles.forEach { p ->
            val px = p.x * widthPx
            val py = p.y * heightPx
            if (p.kind != ParticleKind.AMBIENT) {
                val haloScale = if (p.kind == ParticleKind.GLINT) 7.5f else 5.0f
                val haloAlpha = if (p.kind == ParticleKind.GLINT) 0.42f else 0.26f
                val tex = NebulaTextureCache.textureFor(p.color)
                drawScaledTexture(
                    canvas, tex, px, py,
                    rx = p.radiusFraction * minDim * haloScale,
                    ry = p.radiusFraction * minDim * haloScale,
                    rotationDeg = 0f, alpha = p.alpha * haloAlpha,
                )
            }
            dotPaint.color = p.color
            dotPaint.alpha = (p.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(px, py, p.radiusFraction * minDim, dotPaint)
        }

        // ---- 13. 有机生命膜（fill + edge scattering + rim glow + outer haze）----
        val membrane = frame.membrane
        if (membrane != null) {
            drawMembrane(canvas, membrane, cx, cy, minDim)
        } else {
            // 旧帧兼容：退回 thin-ring 表达
            memPaint.color = frame.frontMembrane.color
            memPaint.strokeWidth = minDim * 0.0016f
            memPaint.alpha = (frame.frontMembrane.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(cx, cy, frame.frontMembrane.radiusFraction * minDim, memPaint)
        }

        // ---- 14. 涟漪 ----
        ripplePaint.color = frame.frontMembrane.color
        ripplePaint.strokeWidth = minDim * 0.002f
        frame.ripples.forEach { r ->
            ripplePaint.alpha = (r.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(r.x * widthPx, r.y * heightPx, r.radiusFraction * minDim, ripplePaint)
        }

        // ---- 15. 暖金高光（极少量；颜色 = identity palette.warm 单源——与 AGSL iWarm 同流）----
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

    // ===== 体积叶（§10/§11：多层低 alpha 叠加成云）=====

    private fun drawVolumeLobes(
        canvas: Canvas,
        frame: OrganismFrame,
        widthPx: Float,
        heightPx: Float,
        minDim: Float,
        minDepth: Float = 0f,
        maxDepth: Float = 1f,
    ) {
        val lobes = frame.volumeLobes
        if (lobes.isEmpty()) return
        for (i in lobes.indices) {
            val lobe = lobes[i]
            if (lobe.depth < minDepth || lobe.depth >= maxDepth) continue
            drawLobe(canvas, lobe, widthPx, heightPx, minDim)
        }
    }

    private fun drawLobe(canvas: Canvas, lobe: VolumeLobeV, widthPx: Float, heightPx: Float, minDim: Float) {
        val tex = NebulaTextureCache.textureFor(lobe.color)
        drawScaledTexture(
            canvas, tex,
            cx = lobe.x * widthPx,
            cy = lobe.y * heightPx,
            rx = lobe.radiusX * minDim,
            ry = lobe.radiusY * minDim,
            rotationDeg = Math.toDegrees(lobe.rotation.toDouble()).toFloat(),
            alpha = lobe.alpha,
        )
    }

    /** 纹理按 (rx, ry) 缩放 + 旋转 + 平移绘制（§32：Matrix 复用零分配）。 */
    private fun drawScaledTexture(
        canvas: Canvas,
        tex: Bitmap,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        rotationDeg: Float,
        alpha: Float,
    ) {
        if (rx <= 0f || ry <= 0f || alpha <= 0.003f) return
        val half = tex.width / 2f
        lobeMatrix.reset()
        lobeMatrix.postTranslate(-half, -half)
        lobeMatrix.postScale(2f * rx / tex.width, 2f * ry / tex.height)
        if (rotationDeg != 0f) lobeMatrix.postRotate(rotationDeg)
        lobeMatrix.postTranslate(cx, cy)
        lobePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(tex, lobeMatrix, lobePaint)
    }

    // ===== 下方空间能量环（§26）=====

    private fun drawGroundRings(
        canvas: Canvas,
        frame: OrganismFrame,
        widthPx: Float,
        heightPx: Float,
        minDim: Float,
    ) {
        val rings = frame.groundRings
        if (rings.isEmpty()) return
        // 反射辉光：organism 下方一块被压扁的柔和光池
        val first = rings.first()
        val glowTex = NebulaTextureCache.textureFor(first.color)
        drawScaledTexture(
            canvas, glowTex,
            cx = widthPx / 2f,
            cy = (first.yCenter + 0.02f) * heightPx,
            rx = first.radiusXFraction * minDim * 0.9f,
            ry = first.radiusYFraction * minDim * 2.6f,
            rotationDeg = 0f,
            alpha = first.alpha * 0.55f,
        )
        // 极淡椭圆能量环（宽度不一；部分被雾吞没）
        rings.forEach { ring ->
            val rcx = widthPx / 2f
            val rcy = ring.yCenter * heightPx
            val rx = ring.radiusXFraction * minDim
            val ry = ring.radiusYFraction * minDim
            groundOval.set(rcx - rx, rcy - ry, rcx + rx, rcy + ry)
            groundPaint.color = ring.color
            groundPaint.strokeWidth = ring.widthFraction * minDim
            groundPaint.alpha = (ring.alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawOval(groundOval, groundPaint)
        }
    }

    // ===== 有机生命膜（§12/§13：不只是 stroke）=====

    private fun drawMembrane(canvas: Canvas, mem: MembraneSpec, cx: Float, cy: Float, minDim: Float) {
        val rPx = mem.radiusFraction * minDim
        val path = membranePathOf(mem, cx, cy, rPx)

        // a. inner body fill（中心透明 → 边缘 body 色：壳层厚度感）
        bgPaint.shader = RadialGradient(
            cx, cy, rPx,
            intArrayOf(
                withAlpha(mem.fillColor, 0f),
                withAlpha(mem.fillColor, mem.fillAlpha * 0.45f),
                withAlpha(mem.fillColor, mem.fillAlpha),
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, bgPaint)

        // b. outer haze（最宽最淡——膜向空气散射）
        memPaint.color = mem.edgeColor
        memPaint.strokeWidth = mem.edgeWidthFraction * minDim * 2.4f
        memPaint.alpha = (mem.edgeAlpha.coerceIn(0f, 1f) * 0.32f * 255f).toInt()
        canvas.drawPath(path, memPaint)

        // c. edge scattering band（宽而柔的边界散射）
        memPaint.strokeWidth = mem.edgeWidthFraction * minDim
        memPaint.alpha = (mem.edgeAlpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawPath(path, memPaint)

        // d. rim glow（cyan 局部亮缘——生命高光）
        memPaint.color = mem.rimColor
        memPaint.strokeWidth = mem.rimWidthFraction * minDim
        memPaint.alpha = (mem.rimAlpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawPath(path, memPaint)
    }

    /**
     * Breakthrough §12：有机膜轮廓——
     * radius(θ) = R·(1 + Σ amp·sin(order·θ + phase)·deformScale + localWave)。
     * identity 谐波恒定；localWave 是慢呼吸非对称漂移（确定性，来自 clock 相位）。
     */
    private fun membranePathOf(mem: MembraneSpec, cx: Float, cy: Float, radiusPx: Float): Path {
        membranePath.rewind()
        val steps = 72
        val harmonics = mem.harmonics
        for (i in 0..steps) {
            val theta = i.toFloat() / steps * 2f * Math.PI.toFloat()
            var r = 1f
            harmonics.forEach { h ->
                r += h.amplitude * sin(h.order * theta + h.phase) * mem.deformScale
            }
            r += mem.localWaveAmplitude * sin(2f * theta + mem.localWavePhase)
            val x = cx + cos(theta) * radiusPx * r
            val y = cy + sin(theta) * radiusPx * r
            if (i == 0) membranePath.moveTo(x, y) else membranePath.lineTo(x, y)
        }
        membranePath.close()
        return membranePath
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
     * Breakthrough §16 丝的三层材质：wide soft glow → medium chromatic body → thin bright core。
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
        if (glow && stroke.glow > 0f) {
            drawStrokePass(canvas, pts, w, paint, widthScale = 5.2f) { a -> a * stroke.glow * 0.15f }
            drawStrokePass(canvas, pts, w, paint, widthScale = 2.4f) { a -> a * stroke.glow * 0.42f }
        }
        drawStrokePass(canvas, pts, w, paint) { a -> a }
    }

    private inline fun drawStrokePass(
        canvas: Canvas,
        pts: List<com.yunjue.echo.mind.visual.render.StrokePoint>,
        baseWidth: Float,
        paint: Paint,
        widthScale: Float = 1f,
        alphaScale: (Float) -> Float,
    ) {
        // 宽辉光 pass 用放大线宽（§16 三层结构）
        val width = baseWidth * widthScale
        paint.strokeWidth = width
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val alpha = alphaScale(((a.alpha + b.alpha) * 0.5f).coerceIn(0f, 1f))
            if (alpha <= 0.004f) continue
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawLine(
                a.x * canvas.width, a.y * canvas.height, b.x * canvas.width, b.y * canvas.height, paint,
            )
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return color and 0x00FFFFFF or (a shl 24)
    }
}
