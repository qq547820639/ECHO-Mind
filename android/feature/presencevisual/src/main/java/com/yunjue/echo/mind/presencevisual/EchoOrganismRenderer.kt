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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import com.yunjue.echo.mind.visual.surface.motionScaleFor
import com.yunjue.echo.mind.visual.surface.reducedMotionFor
import androidx.compose.ui.geometry.Size
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

/** DrawScope 绘制 V3 分层 organism（public：Journey 等其他 Surface 复用）。 */
fun DrawScope.drawOrganism(frame: OrganismFrame) {
    val minDim = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)

    // 1. Ambient field（近黑径向衰减；半径随身体 ≈1.6R）
    drawRect(
        brush = Brush.radialGradient(
            0f to Color(frame.ambientField.centerColor),
            0.42f to Color(frame.ambientField.midColor),
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

    // 2. Halo（远层）
    frame.halos.forEach { halo ->
        drawCircle(
            color = Color(frame.frontMembrane.color).copy(alpha = halo.alpha.coerceIn(0f, 1f)),
            radius = halo.radiusFraction * minDim,
            center = center,
            style = Stroke(width = halo.widthFraction * minDim),
        )
    }

    // 3. 结构环（identity skeleton）
    frame.structuralRings.forEach { drawStrokePath(it, minDim) }
    // 4. 长丝（含 behind-core 遮挡 alpha）
    frame.longFilaments.forEach { drawStrokePath(it, minDim, glowPass = true) }
    // 5. 局部碎片（§7：含辉光——可见的生命纹理）
    frame.localFragments.forEach { drawStrokePath(it, minDim, glowPass = true) }

    // 6. 空心核：暗腔 + 内部大气（禁止实心白球，§8；有机形变边缘）
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
        color = Color(frame.coreCavity.darkColor),
    )

    // 7. 核心细缕 + 稳定结
    frame.coreStrands.forEach { drawStrokePath(it, minDim) }
    frame.coreKnots.forEach { k ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(k.color).copy(alpha = k.alpha.coerceIn(0f, 1f)),
                    Color(k.color).copy(alpha = 0f),
                ),
                center = Offset(k.x * size.width, k.y * size.height),
                radius = k.radiusFraction * minDim * 1.4f,
            ),
            radius = k.radiusFraction * minDim * 1.4f,
            center = Offset(k.x * size.width, k.y * size.height),
        )
    }

    // 8. 粒子（Fibonacci 投影）
    frame.particles.forEach { p ->
        drawCircle(
            color = Color(p.color).copy(alpha = p.alpha.coerceIn(0f, 1f)),
            radius = p.radiusFraction * minDim,
            center = Offset(p.x * size.width, p.y * size.height),
        )
    }

    // 9. 前膜（前半球壳层微光）
    drawCircle(
        color = Color(frame.frontMembrane.color).copy(alpha = frame.frontMembrane.alpha.coerceIn(0f, 1f)),
        radius = frame.frontMembrane.radiusFraction * minDim,
        center = center,
        style = Stroke(width = minDim * 0.0016f),
    )

    // 10. 涟漪
    frame.ripples.forEach { r ->
        drawCircle(
            color = Color(frame.frontMembrane.color).copy(alpha = r.alpha.coerceIn(0f, 1f)),
            radius = r.radiusFraction * minDim,
            center = Offset(r.x * size.width, r.y * size.height),
            style = Stroke(width = minDim * 0.002f),
        )
    }

    // 11. 暖金高光（极少量；§12 面积上限）
    frame.warmAccents.forEach { w ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(ColorSpace.WARM_GOLD).copy(alpha = w.alpha), Color(0x00000000)),
                center = Offset(w.x * size.width, w.y * size.height),
                radius = w.radiusFraction * minDim * 2f,
            ),
            radius = w.radiusFraction * minDim * 2f,
            center = Offset(w.x * size.width, w.y * size.height),
        )
    }
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
 * §Y：每段先画宽而淡的 GLOW，再画细而实的 core（crisp core 压在辉光之上，
 * 与 OrganismCanvasRenderer 同序）。
 */
private fun DrawScope.drawStrokePath(stroke: FilamentStroke, minDim: Float, glowPass: Boolean = false) {
    val pts = stroke.points
    if (pts.size < 2) return
    val w = stroke.widthFraction * minDim
    val color = Color(stroke.color)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        val alpha = ((a.alpha + b.alpha) * 0.5f).coerceIn(0f, 1f)
        if (alpha <= 0.004f) continue
        if (glowPass && stroke.glow > 0f) {
            drawLine(
                color = color.copy(alpha = alpha * stroke.glow * 0.4f),
                start = Offset(a.x * size.width, a.y * size.height),
                end = Offset(b.x * size.width, b.y * size.height),
                strokeWidth = w * 3.2f,
            )
        }
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(a.x * size.width, a.y * size.height),
            end = Offset(b.x * size.width, b.y * size.height),
            strokeWidth = w,
        )
    }
}
