package com.yunjue.echo.mind.ui

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoSceneFrame
import com.yunjue.echo.mind.presence.EchoVisualParameters
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.computeEchoSceneFrame
import com.yunjue.echo.mind.presence.computeVisualParameters
import java.time.LocalTime
import kotlin.math.min

/**
 * ERA 2 — ECHO 生命场（Compose 渲染器）。
 *
 * 渲染器不依赖业务数据库：输入只有视觉参数 + 时间 + 视口 + surface。
 * 帧模型来自纯函数 [computeEchoSceneFrame]（确定性：同一 identity/day/state/time 可复现）。
 * 状态更新是分钟级，渲染是帧级——两个时间尺度（Master Prompt PART 61）。
 */

/** 中性占位参数（无 presence 状态时：静止、弥散、低亮度，不编造状态）。 */
val NEUTRAL_VISUAL_PARAMS: EchoVisualParameters = EchoVisualParameters(
    flowSpeed = 0.1f,
    coherence = 0.05f,
    turbulence = 0f,
    particleDensity = 0.1f,
    coreOpenness = 0.15f,
    dispersion = 0.5f,
    pulsePeriodSeconds = 5.6f,
    depth = 0.3f,
    brightness = 0.35f,
    contrast = 0.4f,
    accentIntensity = 0.3f,
    structureComplexity = 0.15f,
)

/**
 * ECHO 生命场（首页主视觉 + 锁屏/壁纸/Dream 共用同一参数引擎）。
 *
 * @param presence 当前 EchoPresenceState；null → 中性占位（不编造）
 */
@Composable
fun EchoLifeField(
    presence: EchoPresenceState?,
    modifier: Modifier = Modifier,
    surface: SurfaceMode = SurfaceMode.APP,
    motionLevel: PresenceMotionLevel = PresenceMotionLevel.DEFAULT,
    nightMode: Boolean = false,
) {
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeSeconds = (now - start) / 1_000_000_000f }
        }
    }

    // 小时粒度重算参数（分钟级状态，小时级昼夜）
    val hourOfDay = remember(presence?.updatedAt) {
        LocalTime.now().let { it.hour + it.minute / 60f }
    }
    val params = if (presence != null) {
        computeVisualParameters(presence, hourOfDay, surface, motionLevel, nightMode)
    } else {
        NEUTRAL_VISUAL_PARAMS
    }
    val seed = presence?.identityGenome?.seed ?: 0L

    Canvas(modifier = modifier) {
        val frame = computeEchoSceneFrame(params, seed, timeSeconds, size.width, size.height)
        drawEchoFrame(frame)
    }
}

/** DrawScope 渲染帧（Compose 表面：APP Scene）。 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEchoFrame(frame: EchoSceneFrame) {
    val minDim = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = minDim * 1.1f

    // 背景：中心→边缘径向渐变（夜间更暗、活跃更亮由 frame 颜色表达）
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(frame.backgroundCenterColor), Color(frame.backgroundEdgeColor)),
            center = center,
            radius = radius,
        )
    )

    // 同心波纹（coherence 决定清晰度）
    drawCircle(
        color = Color(frame.accentColor).copy(alpha = frame.ringAlpha * 0.5f),
        radius = frame.ringRadiusFraction * minDim,
        center = center,
        style = Stroke(width = minDim * 0.003f, cap = StrokeCap.Round),
    )
    drawCircle(
        color = Color(frame.accentColor).copy(alpha = frame.ringAlpha * 0.25f),
        radius = frame.ringRadiusFraction * minDim * 1.25f,
        center = center,
        style = Stroke(width = minDim * 0.0015f),
    )

    // 粒子（density 决定数量，coherence 决定可见度）
    frame.particles.forEach { p ->
        drawCircle(
            color = Color(frame.accentColor).copy(alpha = p.alpha),
            radius = p.radiusFraction * minDim,
            center = Offset(p.x * size.width, p.y * size.height),
        )
    }

    // 核心光斑（呼吸）
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(frame.accentColor).copy(alpha = 0.9f),
                Color(frame.accentColor).copy(alpha = 0f),
            ),
            center = center,
            radius = frame.coreRadiusFraction * minDim * 2.4f,
        ),
        radius = frame.coreRadiusFraction * minDim * 2.4f,
        center = center,
    )
}

// ===== Android Canvas 渲染适配（Wallpaper / Dream 共用；ERA 3） =====

/** android.graphics.Canvas 渲染帧（WallpaperService / DreamService 表面）。 */
fun renderEchoFrameToCanvas(
    canvas: android.graphics.Canvas,
    frame: EchoSceneFrame,
    widthPx: Float,
    heightPx: Float,
) {
    val minDim = min(widthPx, heightPx)
    val cx = widthPx / 2f
    val cy = heightPx / 2f

    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        shader = android.graphics.RadialGradient(
            cx, cy, minDim * 1.1f,
            intArrayOf(frame.backgroundCenterColor, frame.backgroundEdgeColor),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, widthPx, heightPx, bgPaint)

    val ringPaint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = minDim * 0.003f
        isAntiAlias = true
    }
    ringPaint.color = frame.accentColor.withAlpha(0x99)
    ringPaint.alpha = (frame.ringAlpha * 0x99).toInt()
    canvas.drawCircle(cx, cy, frame.ringRadiusFraction * minDim, ringPaint)

    val particlePaint = android.graphics.Paint().apply { isAntiAlias = true }
    particlePaint.color = frame.accentColor
    frame.particles.forEach { p ->
        particlePaint.alpha = (p.alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(p.x * widthPx, p.y * heightPx, p.radiusFraction * minDim, particlePaint)
    }

    val corePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        shader = android.graphics.RadialGradient(
            cx, cy, frame.coreRadiusFraction * minDim * 2.4f,
            intArrayOf(frame.accentColor, frame.accentColor.withAlpha(0)),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(cx, cy, frame.coreRadiusFraction * minDim * 2.4f, corePaint)
}

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)
