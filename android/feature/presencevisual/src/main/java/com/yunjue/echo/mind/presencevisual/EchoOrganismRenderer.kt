package com.yunjue.echo.mind.presencevisual

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.MembraneSpec
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.render.ParticleKind
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import com.yunjue.echo.mind.visual.surface.motionScaleFor
import com.yunjue.echo.mind.visual.surface.reducedMotionFor
import kotlin.math.PI
import kotlin.math.min

/**
 * EchoOrganismRenderer — Compose 渲染器（APP Scene / Me / 其他 Compose Surface）。
 *
 * V3 §H/§M：genome 由调用方（:app）经唯一语义链
 * `EchoVisualMapper.map → VisualGenomeCompiler.compile` 计算后传入——
 * 本模块（core:visual + core:model 边界）不再解释 Presence，只消费 genome。
 * 渲染管线：EchoVisualGenome → SurfacePolicy.crop
 *   → OrganismFrameComputer（EchoSceneCompiler packet + 缓存拓扑 + MotionEvaluator）→ 绘制。
 * - MotionPolicy（NORMAL/REDUCED/QUIET）在编译期展开为 reducedMotion/motionScale；
 * - TalkBack：organism 作为装饰/状态视觉给**聚合语义描述**（[aggregateDescription]），不朗读粒子；
 * - genome 为 null → 静默空画布（不编造状态）。
 */

/** §79：聚合语义默认中性描述（真实 state 派生描述由调用方经 aggregateDescription 传入）。 */
private val DEFAULT_DESCRIPTION: String? = null

@Composable
fun EchoOrganism(
    genome: EchoVisualGenome?,
    modifier: Modifier = Modifier,
    surface: EchoSurface = EchoSurface.APP_PRIVATE,
    motion: MotionPolicy = MotionPolicy.NORMAL,
    maturityName: String = "KNOWN",
    aggregateDescription: String? = DEFAULT_DESCRIPTION,
    options: OrganismFrameComputer.EchoRenderOptions = OrganismFrameComputer.EchoRenderOptions(),
    /** §45 Correction 脉冲触发（递增计数；只触发 transient 视觉反馈，不改任何状态层）。 */
    correctionPulseTrigger: Int = 0,
) {
    var clockNanos by remember { mutableLongStateOf(0L) }
    // 帧钟（§N）：ticker 只负责请求帧，视觉时间来自 boot-global EchoVisualClock——
    // 同一 ECHO 不因 recompose/navigation/visibility 重启 phase 0；REDUCED 下仍推进
    // （低频呼吸/亮度漂移保留），运动系数在编译期已降级。
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { clockNanos = EchoVisualClock.nowNanos() }
        }
    }

    // §45：触发沿捕获当前帧钟；每帧年龄 = clockNanos - pulseStart（Long nanos 差值——
    // Float 大数相减 catastrophic cancellation 会在大 uptime 下把 0.9s 脉冲量化到不可用）
    var pulseStartNanos by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(correctionPulseTrigger) {
        if (correctionPulseTrigger > 0) pulseStartNanos = clockNanos
    }
    val effectiveOptions = remember(options, motion, maturityName) {
        options.copy(
            reducedMotion = reducedMotionFor(motion) || options.reducedMotion,
            maturityName = maturityName,
            motionScale = motionScaleFor(motion) * options.motionScale,
        )
    }

    // AGSL 后端（§20）：STANDARD/ADVANCED tier 且 RuntimeShader 可用时走材质后端；
    // 否则 Canvas fallback（§22 同一 organism，更简单材质）。
    // §X：能力探测（进程级缓存）每次 composition 只解析一次，绝不逐帧。
    val agslUsable = remember { AgslEchoBackend.isAvailable() }
    val agslAdvanced = remember { AgslEchoBackend.isAdvancedAvailable() }
    val sessionHolder = remember { AgslSessionHolder() }

    val semanticsText = aggregateDescription ?: "ECHO 生命体"
    Canvas(
        modifier = modifier.semantics { contentDescription = semanticsText },
    ) {
        val base = genome ?: return@Canvas // null genome → 静默空画布（不编造状态）
        val spec = SurfacePolicy.cropNanos(base, surface, clockNanos)
        val pulseAgeNanos = if (pulseStartNanos < 0L) null else clockNanos - pulseStartNanos
        val frame = OrganismFrameComputer.compute(
            spec, size.width, size.height,
            effectiveOptions.copy(correctionPulseAgeNanos = pulseAgeNanos),
        )
        val useAgsl = agslUsable && android.os.Build.VERSION.SDK_INT >= 33 &&
            effectiveOptions.tier != com.yunjue.echo.mind.visual.render.EchoRenderTier.LEGACY
        if (useAgsl) {
            val palette = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(base.identitySeed).palette
            val advanced = effectiveOptions.tier == com.yunjue.echo.mind.visual.render.EchoRenderTier.ADVANCED &&
                agslAdvanced
            val session = sessionHolder.sessionFor(size.width.toInt(), size.height.toInt(), advanced)
            if (session == null) {
                drawOrganism(frame)
                return@Canvas
            }
            drawIntoCanvas { composeCanvas ->
                try {
                    session.draw(
                        canvas = composeCanvas.nativeCanvas,
                        frame = frame,
                        widthPx = size.width,
                        heightPx = size.height,
                        exposure = spec.genome.luminance,
                        halo = spec.genome.haloIntensity,
                        primaryColor = ColorSpace.lch(palette.primary.l, palette.primary.c, palette.primary.h),
                        secondaryColor = ColorSpace.lch(palette.secondary.l, palette.secondary.c, palette.secondary.h),
                        warmColor = ColorSpace.lch(palette.warm.l, palette.warm.c, palette.warm.h),
                        // Breakthrough §18/§20：cyan 高光 + FBM 云场相位与帧计算机同源
                        cyanColor = OrganismFrameComputer.cyanAccentFor(base.identitySeed),
                        noisePhase = AgslEchoBackend.noisePhaseFor(
                            identityPhase = base.identityPhase,
                            dayComposition = base.dayComposition,
                            clockSeconds = clockNanos / 1_000_000_000f,
                        ),
                    )
                } catch (_: IllegalArgumentException) {
                    // 软件 canvas（Robolectric/Compose preview/个别低层 fallback）无法执行
                    // RuntimeShader——按 §22 设计降级为 Canvas 后端（同一 organism，更简单材质）。
                    drawOrganism(frame)
                }
            }
        } else {
            drawOrganism(frame)
        }
    }
}

/** AGSL 会话持有器（按尺寸复用 RuntimeShader + mask bitmap；§32 hot path 零位图分配）。 */
internal class AgslSessionHolder {
    private var session: AgslEchoBackend.AgslSession? = null
    /** API < 33 → null（调用侧回退 Canvas 后端）。 */
    fun sessionFor(width: Int, height: Int, advanced: Boolean): AgslEchoBackend.AgslSession? {
        if (android.os.Build.VERSION.SDK_INT < 33) return null
        val s = session
        return if (s != null && s.width == width && s.height == height && s.advanced == advanced) {
            s
        } else {
            AgslEchoBackend.AgslSession(width, height, advanced).also { session = it }
        }
    }
}

/** DrawScope 绘制 V3 分层 organism（public：Journey 等其他 Surface 复用）。
 *
 * Organism Visual Breakthrough 分层（与 OrganismCanvasRenderer 同序/同帧模型）：
 * ambient → atmosphere → halos → ground rings → back lobes → core glow →
 * 三层丝（3-pass）→ mid lobes → cavity → front lobes → strands/knots →
 * particles（带辉光晕）→ organic membrane → ripples → warm accents。
 */
fun DrawScope.drawOrganism(frame: OrganismFrame) {
    val minDim = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)

    // 1. Ambient field（近黑径向衰减；半径随身体 ≈1.6R）
    drawRect(
        brush = Brush.radialGradient(
            0f to Color(frame.ambientField.centerColor),
            0.58f to Color(frame.ambientField.midColor),
            1f to Color(frame.ambientField.edgeColor),
            center = center,
            radius = (frame.ambientField.radiusFraction * minDim).coerceAtLeast(minDim * 0.4f),
        ),
    )

    // 1b. Atmosphere（§13：volume haze 环形分布 + rim 膜散射；克制——禁整屏 bloom）
    val atm = frame.atmosphere
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(atm.hazeColor).copy(alpha = 0f),
            0.42f to Color(atm.hazeColor).copy(alpha = atm.hazeAlpha * 0.45f),
            0.72f to Color(atm.hazeColor).copy(alpha = atm.hazeAlpha),
            1f to Color(atm.hazeColor).copy(alpha = 0f),
            center = center,
            radius = atm.hazeRadiusFraction * minDim,
        ),
        radius = atm.hazeRadiusFraction * minDim,
        center = center,
    )
    drawCircle(
        color = Color(atm.rimColor).copy(alpha = atm.rimAlpha.coerceIn(0f, 1f)),
        radius = atm.rimRadiusFraction * minDim,
        center = center,
        style = Stroke(width = atm.rimWidthFraction * minDim),
    )

    // 2. Halo（远层；自带颜色——不再借 frontMembrane.color）
    frame.halos.forEach { halo ->
        drawCircle(
            color = Color(halo.color).copy(alpha = halo.alpha.coerceIn(0f, 1f)),
            radius = halo.radiusFraction * minDim,
            center = center,
            style = Stroke(width = halo.widthFraction * minDim),
        )
    }

    // 2b. 下方空间能量环 + 反射辉光（Breakthrough §26）
    drawGroundRings(frame, minDim)

    // 3. 后层体积叶（nebula 云底）
    drawVolumeLobes(frame, minDim, maxDepth = 0.45f)

    // 3b. 核心辉光（心脏光）
    frame.coreGlow?.let { glow ->
        drawNebulaImage(
            nebulaImage(glow.color), center.x, center.y,
            glow.radiusFraction * minDim, glow.radiusFraction * minDim,
            0f, glow.alpha,
        )
    }

    // 4-6. 三层丝（3-pass 丝材质：宽辉光 + 中间体 + 细亮芯）
    frame.structuralRings.forEach { drawStrokePath(it, minDim) }
    frame.longFilaments.forEach { drawStrokePath(it, minDim, glowPass = true) }
    frame.localFragments.forEach { drawStrokePath(it, minDim, glowPass = true) }

    // 6b. 中层体积叶（主云体）
    drawVolumeLobes(frame, minDim, minDepth = 0.45f, maxDepth = 0.82f)

    // 7. 空心核：暗腔 + 内部大气（§14 重平衡：小而柔，嵌入云组织）
    val cavityR = frame.coreCavity.radiusFraction * minDim
    drawPath(
        path = cavityPath(frame, center, cavityR * 1.35f),
        brush = Brush.radialGradient(
            colors = listOf(Color(frame.coreCavity.atmosphereColor), Color(frame.coreCavity.darkColor)),
            center = center,
            radius = cavityR * 1.35f,
        ),
    )
    drawPath(
        path = cavityPath(frame, center, cavityR),
        color = Color(frame.coreCavity.darkColor).copy(alpha = 0.92f),
    )

    // 7b. 前层体积叶（前景云——部分遮暗腔，制造深度）
    drawVolumeLobes(frame, minDim, minDepth = 0.82f)

    // 8. 核心细缕 + 稳定结（bright nodes；平坦中心剖面——Canvas 渲染器同式）
    frame.coreStrands.forEach { drawStrokePath(it, minDim) }
    frame.coreKnots.forEach { k ->
        val kr = k.radiusFraction * minDim * 1.4f
        val kCenter = Offset(k.x * size.width, k.y * size.height)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(k.color).copy(alpha = k.alpha.coerceIn(0f, 1f)),
                0.30f to Color(k.color).copy(alpha = k.alpha.coerceIn(0f, 1f) * 0.88f),
                1f to Color(k.color).copy(alpha = 0f),
                center = kCenter,
                radius = kr,
            ),
            radius = kr,
            center = kCenter,
        )
    }

    // 9. 粒子（BRIGHT/GLINT 带辉光晕——嵌在 volume 中的光尘）
    frame.particles.forEach { p ->
        val px = p.x * size.width
        val py = p.y * size.height
        if (p.kind != ParticleKind.AMBIENT) {
            val haloScale = if (p.kind == ParticleKind.GLINT) 7.5f else 5.0f
            val haloAlpha = if (p.kind == ParticleKind.GLINT) 0.42f else 0.26f
            drawNebulaImage(
                nebulaImage(p.color), px, py,
                p.radiusFraction * minDim * haloScale,
                p.radiusFraction * minDim * haloScale,
                0f, p.alpha * haloAlpha,
            )
        }
        drawCircle(
            color = Color(p.color).copy(alpha = p.alpha.coerceIn(0f, 1f)),
            radius = p.radiusFraction * minDim,
            center = Offset(px, py),
        )
    }

    // 10. 有机生命膜（fill + edge scattering + rim glow + outer haze）
    val membrane = frame.membrane
    if (membrane != null) {
        drawMembrane(membrane, center, minDim)
    } else {
        drawCircle(
            color = Color(frame.frontMembrane.color).copy(alpha = frame.frontMembrane.alpha.coerceIn(0f, 1f)),
            radius = frame.frontMembrane.radiusFraction * minDim,
            center = center,
            style = Stroke(width = minDim * 0.0016f),
        )
    }

    // 11. 涟漪
    frame.ripples.forEach { r ->
        drawCircle(
            color = Color(frame.frontMembrane.color).copy(alpha = r.alpha.coerceIn(0f, 1f)),
            radius = r.radiusFraction * minDim,
            center = Offset(r.x * size.width, r.y * size.height),
            style = Stroke(width = minDim * 0.002f),
        )
    }

    // 12. 暖金高光（极少量；§12 面积上限；颜色 = identity palette.warm 单源——与 AGSL iWarm 同流）
    frame.warmAccents.forEach { w ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(w.color).copy(alpha = w.alpha), Color(0x00000000)),
                center = Offset(w.x * size.width, w.y * size.height),
                radius = w.radiusFraction * minDim * 2f,
            ),
            radius = w.radiusFraction * minDim * 2f,
            center = Offset(w.x * size.width, w.y * size.height),
        )
    }
}

// ===== Breakthrough 体积层（Compose adapter；与 Canvas 渲染器同帧同序）=====

/** Compose 星云纹理缓存（Bitmap→ImageBitmap 零拷贝包装；进程级复用）。 */
private val nebulaImageCache = HashMap<Int, ImageBitmap>()

private fun nebulaImage(color: Int): ImageBitmap =
    nebulaImageCache.getOrPut(color) { NebulaTextureCache.textureFor(color).asImageBitmap() }

private fun DrawScope.drawVolumeLobes(
    frame: OrganismFrame,
    minDim: Float,
    minDepth: Float = 0f,
    maxDepth: Float = 1f,
) {
    frame.volumeLobes.forEach { lobe ->
        if (lobe.depth < minDepth || lobe.depth >= maxDepth) return@forEach
        drawNebulaImage(
            nebulaImage(lobe.color),
            lobe.x * size.width, lobe.y * size.height,
            lobe.radiusX * minDim, lobe.radiusY * minDim,
            Math.toDegrees(lobe.rotation.toDouble()).toFloat(),
            lobe.alpha,
        )
    }
}

private fun DrawScope.drawNebulaImage(
    image: ImageBitmap,
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    rotationDeg: Float,
    alpha: Float,
) {
    if (rx <= 0f || ry <= 0f || alpha <= 0.003f) return
    val dstW = (rx * 2f).toInt().coerceAtLeast(1)
    val dstH = (ry * 2f).toInt().coerceAtLeast(1)
    val drawBlock: DrawScope.() -> Unit = {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((cx - rx).toInt(), (cy - ry).toInt()),
            dstSize = IntSize(dstW, dstH),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
    if (rotationDeg != 0f) {
        withTransform({ rotate(rotationDeg, pivot = Offset(cx, cy)) }) { drawBlock() }
    } else {
        drawBlock()
    }
}

private fun DrawScope.drawGroundRings(frame: OrganismFrame, minDim: Float) {
    val rings = frame.groundRings
    if (rings.isEmpty()) return
    val first = rings.first()
    drawNebulaImage(
        nebulaImage(first.color),
        size.width / 2f, (first.yCenter + 0.02f) * size.height,
        first.radiusXFraction * minDim * 0.9f,
        first.radiusYFraction * minDim * 2.6f,
        0f, first.alpha * 0.55f,
    )
    rings.forEach { ring ->
        val rcx = size.width / 2f
        val rcy = ring.yCenter * size.height
        drawOval(
            color = Color(ring.color).copy(alpha = ring.alpha.coerceIn(0f, 1f)),
            topLeft = Offset(rcx - ring.radiusXFraction * minDim, rcy - ring.radiusYFraction * minDim),
            size = Size(ring.radiusXFraction * minDim * 2f, ring.radiusYFraction * minDim * 2f),
            style = Stroke(width = ring.widthFraction * minDim),
        )
    }
}

private fun DrawScope.drawMembrane(mem: MembraneSpec, center: Offset, minDim: Float) {
    val rPx = mem.radiusFraction * minDim
    val path = membranePath(mem, center, rPx)
    // a. inner body fill
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            0f to Color(mem.fillColor).copy(alpha = 0f),
            0.55f to Color(mem.fillColor).copy(alpha = mem.fillAlpha * 0.45f),
            1f to Color(mem.fillColor).copy(alpha = mem.fillAlpha),
            center = center,
            radius = rPx,
        ),
    )
    // b. outer haze
    drawPath(
        path = path,
        color = Color(mem.edgeColor).copy(alpha = mem.edgeAlpha.coerceIn(0f, 1f) * 0.32f),
        style = Stroke(width = mem.edgeWidthFraction * minDim * 2.4f),
    )
    // c. edge scattering band
    drawPath(
        path = path,
        color = Color(mem.edgeColor).copy(alpha = mem.edgeAlpha.coerceIn(0f, 1f)),
        style = Stroke(width = mem.edgeWidthFraction * minDim),
    )
    // d. rim glow（cyan 局部亮缘）
    drawPath(
        path = path,
        color = Color(mem.rimColor).copy(alpha = mem.rimAlpha.coerceIn(0f, 1f)),
        style = Stroke(width = mem.rimWidthFraction * minDim),
    )
}

/** Breakthrough §12 有机膜轮廓（与 OrganismCanvasRenderer.membranePathOf 同式）。 */
private fun membranePath(
    mem: MembraneSpec,
    center: Offset,
    radiusPx: Float,
): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val steps = 72
    val harmonics = mem.harmonics
    for (i in 0..steps) {
        val theta = i.toFloat() / steps * 2f * PI.toFloat()
        var r = 1f
        harmonics.forEach { h -> r += h.amplitude * kotlin.math.sin(h.order * theta + h.phase) * mem.deformScale }
        r += mem.localWaveAmplitude * kotlin.math.sin(2f * theta + mem.localWavePhase)
        val x = center.x + kotlin.math.cos(theta) * radiusPx * r
        val y = center.y + kotlin.math.sin(theta) * radiusPx * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** §8 有机暗腔路径（identity 恒定谐波形变；与 OrganismCanvasRenderer.cavityPath 同式）。 */
private fun cavityPath(frame: OrganismFrame, center: Offset, radiusPx: Float): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val harmonics = frame.coreCavity.harmonics
    if (harmonics.isEmpty()) {
        path.addOval(
            androidx.compose.ui.geometry.Rect(center - Offset(radiusPx, radiusPx), Size(radiusPx * 2f, radiusPx * 2f)),
        )
        return path
    }
    val steps = 48
    for (i in 0..steps) {
        val theta = i.toFloat() / steps * 2f * PI.toFloat()
        var r = 1f
        harmonics.forEach { h -> r += h.amplitude * kotlin.math.cos(h.order * theta + h.phase) }
        val x = center.x + kotlin.math.cos(theta) * radiusPx * r
        val y = center.y + kotlin.math.sin(theta) * radiusPx * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * 逐段描边（per-point alpha 已烘焙 3D 深度/遮挡）。
 * Breakthrough §16 丝的三层材质：wide soft glow → medium chromatic body → thin bright core
 * （与 OrganismCanvasRenderer.drawStroke 同序同参）。
 */
private fun DrawScope.drawStrokePath(stroke: FilamentStroke, minDim: Float, glowPass: Boolean = false) {
    val pts = stroke.points
    if (pts.size < 2) return
    val w = stroke.widthFraction * minDim
    val color = Color(stroke.color)
    if (glowPass && stroke.glow > 0f) {
        drawStrokeSegments(pts, w * 5.2f) { a -> color.copy(alpha = a * stroke.glow * 0.15f) }
        drawStrokeSegments(pts, w * 2.4f) { a -> color.copy(alpha = a * stroke.glow * 0.42f) }
    }
    drawStrokeSegments(pts, w) { a -> color.copy(alpha = a) }
}

private inline fun DrawScope.drawStrokeSegments(
    pts: List<com.yunjue.echo.mind.visual.render.StrokePoint>,
    strokeWidth: Float,
    colorFor: (Float) -> Color,
) {
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        val alpha = ((a.alpha + b.alpha) * 0.5f).coerceIn(0f, 1f)
        if (alpha <= 0.004f) continue
        drawLine(
            color = colorFor(alpha),
            start = Offset(a.x * size.width, a.y * size.height),
            end = Offset(b.x * size.width, b.y * size.height),
            strokeWidth = strokeWidth,
        )
    }
}
