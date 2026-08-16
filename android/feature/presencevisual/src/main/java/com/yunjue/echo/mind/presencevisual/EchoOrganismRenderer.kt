package com.yunjue.echo.mind.presencevisual

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.visual.model.GenomeDeriver
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import java.time.LocalTime
import kotlin.math.min

/**
 * EchoOrganismRenderer — Compose 渲染器（APP Scene / Me / 其他 Compose Surface）。
 *
 * 渲染管线：EchoPresenceState → GenomeDeriver → SurfacePolicy.crop
 *   → OrganismFrameComputer（V3：EchoSceneCompiler packet + 缓存拓扑 + MotionEvaluator）→ 绘制。
 * - 渲染器不依赖业务数据库，不重新推理用户状态；帧钟与 presence 更新是两个时间尺度。
 * - TalkBack：organism 作为装饰/状态视觉给**聚合语义描述**（[aggregateDescription]），不朗读粒子。
 * - presence 为 null → 中性占位（不编造状态）。
 */

/** 聚合语义描述（无障碍；不逐粒子朗读）。 */
private const val DEFAULT_DESCRIPTION = "ECHO 生命体，反映你今天的节奏"

@Composable
fun EchoOrganism(
    presence: EchoPresenceState?,
    modifier: Modifier = Modifier,
    surface: EchoSurface = EchoSurface.APP_PRIVATE,
    reducedMotion: Boolean = false,
    aggregateDescription: String = DEFAULT_DESCRIPTION,
    options: OrganismFrameComputer.EchoRenderOptions = OrganismFrameComputer.EchoRenderOptions(),
) {
    var clockSeconds by remember { mutableFloatStateOf(0f) }
    // 帧钟：REDUCED_MOTION 下仍推进（低频呼吸/亮度漂移保留），运动系数在编译期已降级
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> clockSeconds = (now - start) / 1_000_000_000f }
        }
    }

    val hourOfDay = remember(presence?.updatedAt) {
        LocalTime.now().let { it.hour + it.minute / 60f }
    }
    val genome = remember(presence, hourOfDay) {
        presence?.let { GenomeDeriver.derive(it, hourOfDay) }
    }
    val effectiveOptions = remember(options, reducedMotion, presence?.maturity) {
        options.copy(
            reducedMotion = reducedMotion || options.reducedMotion,
            maturityName = presence?.maturity?.name ?: options.maturityName,
        )
    }

    // AGSL 后端（§20）：STANDARD/ADVANCED tier 且 RuntimeShader 可用时走材质后端；
    // 否则 Canvas fallback（§22 同一 organism，更简单材质）。
    val agslUsable = remember { AgslEchoBackend.isAvailable() }
    val sessionHolder = remember { AgslSessionHolder() }

    Canvas(
        modifier = modifier.semantics { contentDescription = aggregateDescription },
    ) {
        val base = genome ?: GenomeDeriver.derive(EchoPresenceState(), hourOfDay)
        val spec = SurfacePolicy.crop(base, surface, clockSeconds)
        val frame = OrganismFrameComputer.compute(spec, size.width, size.height, effectiveOptions)
        val useAgsl = agslUsable && android.os.Build.VERSION.SDK_INT >= 33 &&
            effectiveOptions.tier != com.yunjue.echo.mind.visual.render.EchoRenderTier.LEGACY
        if (useAgsl) {
            val palette = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(base.identitySeed).palette
            val session = sessionHolder.sessionFor(size.width.toInt(), size.height.toInt())
            if (session == null) {
                drawOrganism(frame)
                return@Canvas
            }
            drawIntoCanvas { composeCanvas ->
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
    fun sessionFor(width: Int, height: Int): AgslEchoBackend.AgslSession? {
        if (android.os.Build.VERSION.SDK_INT < 33) return null
        val s = session
        return if (s != null && s.width == width && s.height == height) {
            s
        } else {
            AgslEchoBackend.AgslSession(width, height).also { session = it }
        }
    }
}

/** DrawScope 绘制 V3 分层 organism（public：Journey 等其他 Surface 复用）。 */
fun DrawScope.drawOrganism(frame: OrganismFrame) {
    val minDim = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)

    // 1. Ambient field（近黑径向衰减）
    drawRect(
        brush = Brush.radialGradient(
            0f to Color(frame.ambientField.centerColor),
            0.42f to Color(frame.ambientField.midColor),
            1f to Color(frame.ambientField.edgeColor),
            center = center,
            radius = minDim * 1.15f,
        ),
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
    // 5. 局部碎片
    frame.localFragments.forEach { drawStrokePath(it, minDim) }

    // 6. 空心核：暗腔 + 内部大气（禁止实心白球，§18）
    val cavityR = frame.coreCavity.radiusFraction * minDim
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(frame.coreCavity.atmosphereColor), Color(frame.coreCavity.darkColor)),
            center = center,
            radius = cavityR * 1.35f,
        ),
        radius = cavityR * 1.35f,
        center = center,
    )
    drawCircle(color = Color(frame.coreCavity.darkColor), radius = cavityR, center = center)

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

/** 逐段描边（per-point alpha 已烘焙 3D 深度/遮挡）；glowPass 追加一次更宽更淡的辉光。 */
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
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(a.x * size.width, a.y * size.height),
            end = Offset(b.x * size.width, b.y * size.height),
            strokeWidth = w,
        )
        if (glowPass && stroke.glow > 0f) {
            drawLine(
                color = color.copy(alpha = alpha * stroke.glow * 0.4f),
                start = Offset(a.x * size.width, a.y * size.height),
                end = Offset(b.x * size.width, b.y * size.height),
                strokeWidth = w * 3.2f,
            )
        }
    }
}
