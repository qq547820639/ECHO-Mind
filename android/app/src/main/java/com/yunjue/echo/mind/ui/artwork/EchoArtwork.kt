/**
 * 程序化视觉资产库（设计稿氛围合成层）。
 *
 * 全部资产为确定性程序化绘制（固定种子 Canvas/Path），不依赖位图、不引入外部生图服务、
 * 不承载任何测量语义（纯装饰）。对应设计稿：19 山湖暮色地平线、1/5 生命体涟漪环与星空、
 * 5 细线状态图标、19 卡片细线图标。
 */
package com.yunjue.echo.mind.ui.artwork

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/** 星空底纹（固定种子确定性散点；纯装饰）。[maxYFraction] 限制星星分布的纵向范围。 */
@Composable
fun StarfieldCanvas(
    modifier: Modifier = Modifier,
    seed: Int = 20260906,
    starCount: Int = 46,
    maxYFraction: Float = 1f,
) {
    Canvas(modifier = modifier) {
        val rnd = kotlin.random.Random(seed)
        repeat(starCount) {
            val x = rnd.nextFloat() * size.width
            val y = rnd.nextFloat() * size.height * maxYFraction
            val radius = 1f + rnd.nextFloat() * 1.6f
            drawCircle(
                color = Color.White.copy(alpha = 0.05f + rnd.nextFloat() * 0.14f),
                radius = radius,
                center = Offset(x, y),
            )
        }
    }
}

/**
 * 山湖暮色地平线（设计稿 19 底部）：暖色地平辉光 + 两层山脊剪影 + 湖面倒影微光。
 * 确定性（固定种子）；纯装饰层，置于页面底部、内容之下。
 */
@Composable
fun DuskHorizon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val horizonY = h * 0.52f

        // 地平辉光（暖橙，径向）
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x0005070F),
                    Color(0x14241A4E),
                    Color(0x33E8A46B),
                ),
                startY = h * 0.08f,
                endY = horizonY,
            ),
            topLeft = Offset(0f, 0f),
            size = Size(w, horizonY),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x40E8A46B), Color.Transparent),
                center = Offset(w * 0.5f, horizonY),
                radius = w * 0.55f,
            ),
            radius = w * 0.55f,
            center = Offset(w * 0.5f, horizonY),
        )

        // 远山层（淡靛蓝剪影）
        val farRnd = kotlin.random.Random(1919)
        val farPath = Path()
        farPath.moveTo(0f, horizonY)
        val farSegments = 9
        for (i in 1..farSegments) {
            val x = w * i / farSegments.toFloat()
            val peak = horizonY - h * (0.10f + farRnd.nextFloat() * 0.14f)
            farPath.lineTo(x - w / farSegments.toFloat() / 2f, peak)
            farPath.lineTo(x, horizonY - h * 0.04f)
        }
        farPath.lineTo(w, horizonY)
        farPath.lineTo(w, h)
        farPath.lineTo(0f, h)
        farPath.close()
        drawPath(farPath, color = Color(0xCC1A2142))

        // 近山层（更暗剪影）
        val nearRnd = kotlin.random.Random(1949)
        val nearPath = Path()
        nearPath.moveTo(0f, horizonY + h * 0.03f)
        val nearSegments = 7
        for (i in 1..nearSegments) {
            val x = w * i / nearSegments.toFloat()
            val peak = horizonY + h * 0.03f - h * (0.05f + nearRnd.nextFloat() * 0.09f)
            nearPath.lineTo(x - w / nearSegments.toFloat() / 2f, peak)
            nearPath.lineTo(x, horizonY + h * 0.01f)
        }
        nearPath.lineTo(w, horizonY + h * 0.03f)
        nearPath.lineTo(w, h)
        nearPath.lineTo(0f, h)
        nearPath.close()
        drawPath(nearPath, color = Color(0xFF10162E))

        // 湖面（暖色倒影渐变 + 水平微光线）
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF2A2440), Color(0xFF0B1122)),
                startY = horizonY,
                endY = h,
            ),
            topLeft = Offset(0f, horizonY),
            size = Size(w, h - horizonY),
        )
        val shimmerRnd = kotlin.random.Random(1996)
        for (i in 0 until 12) {
            val y = horizonY + (h - horizonY) * (0.06f + shimmerRnd.nextFloat() * 0.9f)
            val lineW = w * (0.10f + shimmerRnd.nextFloat() * 0.28f)
            val x0 = shimmerRnd.nextFloat() * (w - lineW)
            val alpha = (0.16f - (y - horizonY) / (h - horizonY) * 0.14f).coerceAtLeast(0.02f)
            drawLine(
                color = Color(0xFFE8A46B).copy(alpha = alpha),
                start = Offset(x0, y),
                end = Offset(x0 + lineW, y),
                strokeWidth = 1.2f,
            )
        }
        // 水平线高光
        drawLine(
            color = Color(0x55E8A46B),
            start = Offset(w * 0.06f, horizonY),
            end = Offset(w * 0.94f, horizonY),
            strokeWidth = 1.4f,
        )
    }
}

/**
 * 涟漪环（设计稿 1/5 生命体底部）：同心椭圆环组，向下展开、透明度渐隐。纯装饰。
 */
@Composable
fun RippleRings(
    modifier: Modifier = Modifier,
    ringCount: Int = 5,
    tint: Color = Color(0xFF38BDF8),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        for (i in 0 until ringCount) {
            val f = (i + 1) / ringCount.toFloat()
            val ringW = w * (0.30f + 0.62f * f)
            val ringH = h * (0.16f + 0.66f * f)
            drawOval(
                color = if (i % 2 == 0) tint else Color(0xFF8F7CF0),
                alpha = (0.30f * (1f - f)).coerceAtLeast(0.03f),
                topLeft = Offset(cx - ringW / 2f, cy - ringH / 2f),
                size = Size(ringW, ringH),
                style = Stroke(width = 1.6f),
            )
        }
    }
}

/** 状态卡细线图标类型（设计稿 5：青色波浪 / 绿色闪电 / 紫色同心圆）。 */
enum class StatusLineIconType { WAVE, BOLT, RINGS }

/**
 * 细线状态图标（设计稿 5 圆环内 1.8dp 细线风格）。
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStatusLineIcon(
    type: StatusLineIconType,
    color: Color,
) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (type) {
        StatusLineIconType.WAVE -> {
            val path = Path()
            path.moveTo(w * 0.16f, h * 0.5f)
            path.cubicTo(w * 0.30f, h * 0.22f, w * 0.40f, h * 0.22f, w * 0.50f, h * 0.5f)
            path.cubicTo(w * 0.60f, h * 0.78f, w * 0.70f, h * 0.78f, w * 0.84f, h * 0.5f)
            drawPath(path, color = color, style = stroke)
        }
        StatusLineIconType.BOLT -> {
            val path = Path()
            path.moveTo(w * 0.56f, h * 0.14f)
            path.lineTo(w * 0.30f, h * 0.55f)
            path.lineTo(w * 0.48f, h * 0.55f)
            path.lineTo(w * 0.42f, h * 0.86f)
            path.lineTo(w * 0.72f, h * 0.42f)
            path.lineTo(w * 0.53f, h * 0.42f)
            path.close()
            drawPath(path, color = color, style = stroke)
        }
        StatusLineIconType.RINGS -> {
            drawCircle(color = color, radius = w * 0.30f, style = stroke)
            drawCircle(color = color, radius = w * 0.16f, style = stroke)
            drawCircle(color = color, radius = w * 0.045f)
        }
    }
}

/** 成长卡细线图标类型（设计稿 19：书签 / 日历 / 波形 / 心形）。 */
enum class GrowthLineIconType { BOOKMARK, CALENDAR, WAVEFORM, HEART }

/**
 * 细线成长卡图标（设计稿 19 卡片 1.8dp 线性风格）。
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrowthLineIcon(
    type: GrowthLineIconType,
    color: Color,
) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (type) {
        GrowthLineIconType.BOOKMARK -> {
            val path = Path()
            path.moveTo(w * 0.30f, h * 0.18f)
            path.lineTo(w * 0.70f, h * 0.18f)
            path.lineTo(w * 0.70f, h * 0.82f)
            path.lineTo(w * 0.50f, h * 0.63f)
            path.lineTo(w * 0.30f, h * 0.82f)
            path.close()
            drawPath(path, color = color, style = stroke)
        }
        GrowthLineIconType.CALENDAR -> {
            val radius = w * 0.06f
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.22f, h * 0.26f),
                size = Size(w * 0.56f, h * 0.54f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = stroke,
            )
            drawLine(color, Offset(w * 0.22f, h * 0.42f), Offset(w * 0.78f, h * 0.42f), strokeWidth = stroke.width)
            drawLine(color, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.38f, h * 0.30f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.62f, h * 0.30f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        GrowthLineIconType.WAVEFORM -> {
            val bars = listOf(
                0.20f to 0.22f, 0.35f to 0.44f, 0.50f to 0.72f, 0.65f to 0.40f, 0.80f to 0.20f,
            )
            bars.forEach { (x, heightFraction) ->
                val barX = w * x
                val half = h * heightFraction / 2f
                drawLine(
                    color = color,
                    start = Offset(barX, h * 0.5f - half),
                    end = Offset(barX, h * 0.5f + half),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
        GrowthLineIconType.HEART -> {
            val path = Path()
            path.moveTo(w * 0.5f, h * 0.80f)
            path.cubicTo(w * 0.16f, h * 0.58f, w * 0.16f, h * 0.26f, w * 0.38f, h * 0.24f)
            path.cubicTo(w * 0.46f, h * 0.23f, w * 0.5f, h * 0.32f, w * 0.5f, h * 0.36f)
            path.cubicTo(w * 0.5f, h * 0.32f, w * 0.54f, h * 0.23f, w * 0.62f, h * 0.24f)
            path.cubicTo(w * 0.84f, h * 0.26f, w * 0.84f, h * 0.58f, w * 0.5f, h * 0.80f)
            path.close()
            drawPath(path, color = color, style = stroke)
        }
    }
}

/** 隐私承诺卡细线图标类型（设计稿 2：本地优先=家 / 最小化=锁 / 完全掌控=人 / 可撤回=历史）。 */
enum class PrivacyLineIconType { HOME, LOCK, PERSON, HISTORY }

/**
 * 隐私承诺卡细线图标（设计稿 2 圆形徽章内线性风格）。
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPrivacyLineIcon(
    type: PrivacyLineIconType,
    color: Color,
) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (type) {
        PrivacyLineIconType.HOME -> {
            val path = Path()
            path.moveTo(w * 0.16f, h * 0.5f)
            path.lineTo(w * 0.5f, h * 0.2f)
            path.lineTo(w * 0.84f, h * 0.5f)
            path.moveTo(w * 0.28f, h * 0.44f)
            path.lineTo(w * 0.28f, h * 0.8f)
            path.lineTo(w * 0.72f, h * 0.8f)
            path.lineTo(w * 0.72f, h * 0.44f)
            drawPath(path, color = color, style = stroke)
        }
        PrivacyLineIconType.LOCK -> {
            val radius = w * 0.06f
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.24f, h * 0.44f),
                size = Size(w * 0.52f, h * 0.36f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = stroke,
            )
            val arcPath = Path()
            arcPath.moveTo(w * 0.34f, h * 0.46f)
            arcPath.lineTo(w * 0.34f, h * 0.32f)
            arcPath.cubicTo(w * 0.34f, h * 0.14f, w * 0.66f, h * 0.14f, w * 0.66f, h * 0.32f)
            arcPath.lineTo(w * 0.66f, h * 0.46f)
            drawPath(arcPath, color = color, style = stroke)
            drawCircle(color = color, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.61f))
        }
        PrivacyLineIconType.PERSON -> {
            drawCircle(color = color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.32f), style = stroke)
            val path = Path()
            path.moveTo(w * 0.18f, h * 0.82f)
            path.cubicTo(w * 0.2f, h * 0.56f, w * 0.8f, h * 0.56f, w * 0.82f, h * 0.82f)
            drawPath(path, color = color, style = stroke)
        }
        PrivacyLineIconType.HISTORY -> {
            drawCircle(
                color = color,
                radius = w * 0.32f,
                center = Offset(w * 0.5f, h * 0.54f),
                style = stroke,
            )
            drawLine(
                color = color,
                start = Offset(w * 0.5f, h * 0.36f),
                end = Offset(w * 0.5f, h * 0.56f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(w * 0.5f, h * 0.56f),
                end = Offset(w * 0.64f, h * 0.64f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            // 逆时针箭头小翼（撤回语义）
            drawLine(
                color = color,
                start = Offset(w * 0.14f, h * 0.40f),
                end = Offset(w * 0.24f, h * 0.34f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 智能地图节点细线图标类型（设计稿 10：眼睛 / 火花 / 书本 / 齿轮）。 */
enum class SmartMapIconType { EYE, SPARK, BOOK, GEAR }

/**
 * 智能地图节点图标（小尺寸细线；[iconBox] 为图标方形边长，供大画布内定位绘制）。
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSmartMapIcon(
    type: SmartMapIconType,
    color: Color,
    iconBox: Size,
) {
    val w = iconBox.width
    val h = iconBox.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (type) {
        SmartMapIconType.EYE -> {
            val path = Path()
            path.moveTo(w * 0.14f, h * 0.5f)
            path.cubicTo(w * 0.32f, h * 0.20f, w * 0.68f, h * 0.20f, w * 0.86f, h * 0.5f)
            path.cubicTo(w * 0.68f, h * 0.80f, w * 0.32f, h * 0.80f, w * 0.14f, h * 0.5f)
            path.close()
            drawPath(path, color = color, style = stroke)
            drawCircle(color = color, radius = w * 0.12f)
        }
        SmartMapIconType.SPARK -> {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val outer = w * 0.36f
            val inner = w * 0.12f
            val path = Path()
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0 - 90.0)
                val r = if (i % 2 == 0) outer else inner
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = color, style = stroke)
        }
        SmartMapIconType.BOOK -> {
            val path = Path()
            path.moveTo(w * 0.5f, h * 0.24f)
            path.cubicTo(w * 0.36f, h * 0.16f, w * 0.18f, h * 0.18f, w * 0.14f, h * 0.22f)
            path.lineTo(w * 0.14f, h * 0.74f)
            path.cubicTo(w * 0.18f, h * 0.70f, w * 0.36f, h * 0.68f, w * 0.5f, h * 0.76f)
            path.cubicTo(w * 0.64f, h * 0.68f, w * 0.82f, h * 0.70f, w * 0.86f, h * 0.74f)
            path.lineTo(w * 0.86f, h * 0.22f)
            path.cubicTo(w * 0.82f, h * 0.18f, w * 0.64f, h * 0.16f, w * 0.5f, h * 0.24f)
            path.close()
            drawPath(path, color = color, style = stroke)
            drawLine(color, Offset(w * 0.5f, h * 0.24f), Offset(w * 0.5f, h * 0.76f), strokeWidth = stroke.width)
        }
        SmartMapIconType.GEAR -> {
            drawCircle(color = color, radius = w * 0.20f, style = stroke)
            drawCircle(color = color, radius = w * 0.08f, style = stroke)
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val r0 = w * 0.30f
                val r1 = w * 0.40f
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f + r0 * cos(angle).toFloat(), h * 0.5f + r0 * sin(angle).toFloat()),
                    end = Offset(w * 0.5f + r1 * cos(angle).toFloat(), h * 0.5f + r1 * sin(angle).toFloat()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
