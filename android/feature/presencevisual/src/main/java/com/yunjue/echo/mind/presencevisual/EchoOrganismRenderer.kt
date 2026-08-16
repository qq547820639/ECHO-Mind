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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.visual.model.GenomeDeriver
import com.yunjue.echo.mind.visual.motion.MotionEngine
import com.yunjue.echo.mind.visual.motion.MotionPolicy
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import java.time.LocalTime
import kotlin.math.min

/**
 * EchoOrganismRenderer — Compose 渲染器（APP Scene）。
 *
 * 渲染管线：EchoPresenceState → GenomeDeriver → SurfacePolicy.crop → OrganismFrameComputer → 绘制。
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
    motionPolicy: MotionPolicy = MotionPolicy.FULL,
    reducedMotion: Boolean = false,
    aggregateDescription: String = DEFAULT_DESCRIPTION,
) {
    var clockSeconds by remember { mutableFloatStateOf(0f) }
    // 帧钟：REDUCED_MOTION 下仍推进（呼吸/亮度漂移保留），但 renderer 内部已降动效
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> clockSeconds = (now - start) / 1_000_000_000f }
        }
    }

    val policy = if (reducedMotion) MotionPolicy.REDUCED_MOTION else motionPolicy
    val hourOfDay = remember(presence?.updatedAt) {
        LocalTime.now().let { it.hour + it.minute / 60f }
    }
    val genome = remember(presence, hourOfDay) {
        presence?.let { GenomeDeriver.derive(it, hourOfDay) }
    }
    val phase = MotionEngine.phaseAt(
        clockSeconds = clockSeconds,
        pulseRate = genome?.pulseRate ?: 5f,
        driftRate = genome?.driftRate ?: 0.1f,
        policy = policy,
    )

    Canvas(
        modifier = modifier.semantics { contentDescription = aggregateDescription },
    ) {
        val frame = if (genome != null) {
            // 亮度漂移作为 luminance 的极慢微调（不改变 genome identity）
            val spec = SurfacePolicy.crop(genome, surface, clockSeconds)
            val adjusted = spec.copy(
                genome = spec.genome.copy(
                    luminance = (spec.genome.luminance + phase.luminanceDrift * 0.05f).coerceIn(0f, 1f),
                ),
            )
            OrganismFrameComputer.compute(adjusted, size.width, size.height)
        } else {
            OrganismFrameComputer.compute(
                SurfacePolicy.crop(neutralGenome(), surface, clockSeconds),
                size.width, size.height,
            )
        }
        drawOrganism(frame)
    }
}

/** 中性占位 genome（无 presence 时：静止、弥散、低亮度，不编造）。 */
private fun neutralGenome() = GenomeDeriver.derive(EchoPresenceState(), hourOfDay = 12f)

/** DrawScope 绘制 9 层 organism（public：Journey 等其他 Surface 复用）。 */
fun DrawScope.drawOrganism(frame: OrganismFrame) {
    val minDim = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)

    // 1. Ambient field（径向渐变 + 噪声颗粒）
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(frame.ambientField.centerColor), Color(frame.ambientField.edgeColor)),
            center = center,
            radius = minDim * 1.15f,
        ),
    )

    // 7. Halo（在 membrane 之下，营造纵深）
    frame.halos.forEach { halo ->
        drawCircle(
            color = Color(frame.membrane.strokeColor).copy(alpha = halo.alpha),
            radius = halo.radiusFraction * minDim,
            center = center,
            style = Stroke(width = halo.widthFraction * minDim),
        )
    }

    // 4. Orbital 轨迹（椭圆）
    frame.orbitals.forEach { orb ->
        drawOval(
            color = Color(frame.membrane.strokeColor).copy(alpha = orb.alpha),
            topLeft = Offset(
                center.x - orb.radiusFraction * minDim,
                center.y - orb.radiusFraction * minDim * (1f - orb.eccentricity),
            ),
            size = androidx.compose.ui.geometry.Size(
                orb.radiusFraction * minDim * 2f,
                orb.radiusFraction * minDim * (1f - orb.eccentricity) * 2f,
            ),
            style = Stroke(width = orb.widthFraction * minDim),
        )
    }

    // 3. Filament 网络
    frame.filaments.forEach { f ->
        drawLine(
            color = Color(frame.membrane.strokeColor).copy(alpha = f.alpha),
            start = Offset(f.x1 * size.width, f.y1 * size.height),
            end = Offset(f.x2 * size.width, f.y2 * size.height),
            strokeWidth = f.widthFraction * minDim,
        )
    }

    // 2. 主膜轮廓（闭合 path）
    if (frame.membrane.outline.size >= 3) {
        val path = Path()
        val first = frame.membrane.outline[0]
        path.moveTo(first.x * size.width, first.y * size.height)
        for (i in 1 until frame.membrane.outline.size) {
            val p = frame.membrane.outline[i]
            path.lineTo(p.x * size.width, p.y * size.height)
        }
        path.close()
        drawPath(
            path = path,
            color = Color(frame.membrane.strokeColor).copy(alpha = frame.membrane.strokeAlpha),
            style = Stroke(width = frame.membrane.strokeWidthFraction * minDim),
        )
    }

    // 5. 粒子（流线或圆点）
    frame.particles.forEach { p ->
        if (p.streakLength > 0f) {
            val len = p.streakLength * minDim
            val px = p.x * size.width
            val py = p.y * size.height
            drawLine(
                color = Color(frame.membrane.strokeColor).copy(alpha = p.alpha),
                start = Offset(px - p.streakDirX * len / 2f, py - p.streakDirY * len / 2f),
                end = Offset(px + p.streakDirX * len / 2f, py + p.streakDirY * len / 2f),
                strokeWidth = p.radiusFraction * minDim,
            )
        } else {
            drawCircle(
                color = Color(frame.membrane.strokeColor).copy(alpha = p.alpha),
                radius = p.radiusFraction * minDim,
                center = Offset(p.x * size.width, p.y * size.height),
            )
        }
    }

    // 8. 涟漪
    frame.ripples.forEach { r ->
        drawCircle(
            color = Color(frame.membrane.strokeColor).copy(alpha = r.alpha),
            radius = r.radiusFraction * minDim,
            center = center,
            style = Stroke(width = minDim * 0.002f),
        )
    }

    // 6. 核心光斑（最顶层）
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(frame.coreGlow.color).copy(alpha = frame.coreGlow.intensity),
                Color(frame.coreGlow.color).copy(alpha = 0f),
            ),
            center = center,
            radius = frame.coreGlow.radiusFraction * minDim * 2.4f,
        ),
        radius = frame.coreGlow.radiusFraction * minDim * 2.4f,
        center = center,
    )

    // 9. 暖金高光（极少量）
    frame.warmAccents.forEach { w ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(com.yunjue.echo.mind.visual.render.ColorSpace.WARM_GOLD).copy(alpha = w.alpha),
                    Color(com.yunjue.echo.mind.visual.render.ColorSpace.WARM_GOLD).copy(alpha = 0f),
                ),
                center = Offset(w.x * size.width, w.y * size.height),
                radius = w.radiusFraction * minDim * 2f,
            ),
            radius = w.radiusFraction * minDim * 2f,
            center = Offset(w.x * size.width, w.y * size.height),
        )
    }
}
