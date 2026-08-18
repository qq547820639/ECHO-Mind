package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VisualLabMetrics — V3 §34/§82 自动视觉指标（从真实渲染位图计算）。
 *
 * 指标定义（Reference KNOWN Day28 门，§82 + Organism Quality Pass §23）：
 * - nearBlackRatio      近黑像素占比（luma < 0.045）≥ 58%
 * - highLuminanceRatio  高亮像素占比（luma > 0.80）≤ 4%
 * - extremeGlintRatio   极亮 glint 占比（luma > 0.92）≤ 2.5%
 * - warmRatio           暖色像素占比（hue 10°..50° 且饱和）：target ≤ 10%，hard ≤ 15%
 * - organismBoundingBox 发光像素包围盒（luma > 0.06）：宽目标 72%..82% viewport
 * - negativeSpaceRatio  外围负空间占比（1.35R 外近黑）≥ 40%
 * - visualMassInside    视觉质量（非近黑能量）在 .9R 内占比 ≥ 82%
 * - edgeDensity         1.35R 内明暗跳变密度（结构纹理密度；星空壁纸观感=低 edge 高亮点）
 * - centerLuminance / outerLuminance  中心/外围亮度（core cavity 必须清楚：中心不得是高亮白球）
 *
 * 只做 composition / density / tone / visual weight / negative space 比较，不 pixel-perfect。
 */
object VisualLabMetrics {

    /** 发光像素包围盒（viewport 归一化 0..1）。 */
    data class BoundingBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val widthFraction: Float,
        val heightFraction: Float,
    )

    data class Metrics(
        val nearBlackRatio: Float,
        val highLuminanceRatio: Float,
        val extremeGlintRatio: Float,
        val warmRatio: Float,
        val negativeSpaceRatio: Float,
        val visualMassInside: Float,
        val centerLuminance: Float,
        val outerLuminance: Float,
        val edgeDensity: Float,
        val organismWidthFraction: Float,
        val organismHeightFraction: Float,
    ) {
        @Deprecated(" renamed to highLuminanceRatio", ReplaceWith("highLuminanceRatio"))
        val highlightRatio: Float get() = highLuminanceRatio
    }

    /** §82 Reference 自动门结果（每项独立判定）。 */
    data class GateResult(
        val metrics: Metrics,
        val nearBlackPass: Boolean,
        val highLuminancePass: Boolean,
        val extremeGlintPass: Boolean,
        val warmPass: Boolean,
        val warmTargetPass: Boolean,
        val negativeSpacePass: Boolean,
        val visualMassPass: Boolean,
        val organismWidthPass: Boolean,
        val cavityPass: Boolean,
    ) {
        val allPass: Boolean
            get() = nearBlackPass && highLuminancePass && extremeGlintPass && warmPass &&
                negativeSpacePass && visualMassPass && organismWidthPass && cavityPass
    }

    fun evaluate(m: Metrics): GateResult = GateResult(
        metrics = m,
        nearBlackPass = m.nearBlackRatio >= 0.58f,
        highLuminancePass = m.highLuminanceRatio <= 0.04f,
        extremeGlintPass = m.extremeGlintRatio <= 0.025f,
        warmPass = m.warmRatio <= 0.15f,
        warmTargetPass = m.warmRatio <= 0.10f,
        negativeSpacePass = m.negativeSpaceRatio >= 0.40f,
        visualMassPass = m.visualMassInside >= 0.82f,
        organismWidthPass = m.organismWidthFraction in 0.72f..0.82f,
        // core cavity 清楚：中心区域平均亮度必须低于全图高亮阈值（暗腔存在）
        cavityPass = m.centerLuminance < 0.45f,
    )

    /**
     * 计算位图指标。
     * @param cx/cy organism 中心（像素）；@param radiusPx 视觉半径 R（.9R/1.35R 以此度量）。
     */
    fun compute(bitmap: Bitmap, cx: Float, cy: Float, radiusPx: Float): Metrics {
        val w = bitmap.width
        val h = bitmap.height
        var total = 0
        var nearBlack = 0
        var highLuminance = 0
        var extremeGlint = 0
        var warm = 0
        var outerTotal = 0
        var outerNearBlack = 0
        var massInside = 0f
        var massTotal = 0f
        var centerSum = 0f
        var centerCount = 0
        var outerSum = 0f
        var edges = 0
        var edgeSamples = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        val step = 2 // 2px 采样：指标精度足够，耗时减半
        val luminousThreshold = 0.06f
        // 边缘检测窗口：比较 (x-4px) 与 (x+4px) 的 luma 跳变（分辨率无关性由 step*2 保证近似）
        val edgeStride = step * 2
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16 and 0xFF) / 255f
                val g = (c shr 8 and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val luma = 0.2126f * r + 0.587f * g + 0.0722f * b
                total++
                if (luma < 0.045f) nearBlack++
                if (luma > 0.80f) highLuminance++
                if (luma > 0.92f) extremeGlint++
                if (isWarm(r, g, b)) warm++
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                // 视觉质量 = 高出近黑地板的能量（背景近黑不计入质量）
                val mass = (luma - 0.045f).coerceAtLeast(0f)
                massTotal += mass
                if (dist <= radiusPx * 0.9f) massInside += mass
                if (dist <= radiusPx * 0.45f) {
                    centerSum += luma
                    centerCount++
                }
                if (dist > radiusPx * 1.35f) {
                    outerTotal++
                    if (luma < 0.045f) outerNearBlack++
                    outerSum += luma
                } else if (luma > luminousThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                // edge density：1.35R 内水平明暗跳变（纹理丰富度代理）
                if (dist <= radiusPx * 1.35f && x - edgeStride >= 0 && x + edgeStride < w) {
                    val cl = bitmap.getPixel(x - edgeStride, y)
                    val cr = bitmap.getPixel(x + edgeStride, y)
                    val ll = 0.2126f * ((cl shr 16 and 0xFF) / 255f) +
                        0.587f * ((cl shr 8 and 0xFF) / 255f) + 0.0722f * ((cl and 0xFF) / 255f)
                    val lr = 0.2126f * ((cr shr 16 and 0xFF) / 255f) +
                        0.587f * ((cr shr 8 and 0xFF) / 255f) + 0.0722f * ((cr and 0xFF) / 255f)
                    edgeSamples++
                    if (kotlin.math.abs(ll - luma) > 0.05f || kotlin.math.abs(lr - luma) > 0.05f) edges++
                }
                x += step
            }
            y += step
        }
        val bbox = if (maxX >= minX && maxY >= minY) {
            BoundingBox(
                left = minX / w.toFloat(),
                top = minY / h.toFloat(),
                right = maxX / w.toFloat(),
                bottom = maxY / h.toFloat(),
                widthFraction = (maxX - minX) / w.toFloat(),
                heightFraction = (maxY - minY) / h.toFloat(),
            )
        } else {
            null
        }
        return Metrics(
            nearBlackRatio = nearBlack / total.toFloat(),
            highLuminanceRatio = highLuminance / total.toFloat(),
            extremeGlintRatio = extremeGlint / total.toFloat(),
            warmRatio = warm / total.toFloat(),
            negativeSpaceRatio = if (outerTotal > 0) outerNearBlack / outerTotal.toFloat() else 0f,
            visualMassInside = if (massTotal > 0f) massInside / massTotal else 0f,
            centerLuminance = if (centerCount > 0) centerSum / centerCount else 0f,
            outerLuminance = if (outerTotal > 0) outerSum / outerTotal else 0f,
            edgeDensity = if (edgeSamples > 0) edges.toFloat() / edgeSamples else 0f,
            organismWidthFraction = bbox?.widthFraction ?: 0f,
            organismHeightFraction = bbox?.heightFraction ?: 0f,
        )
    }

    /** 暖色判定：R 明显大于 B 且有一定饱和度（hue 约 10°..50° 族）。 */
    private fun isWarm(r: Float, g: Float, b: Float): Boolean {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx < 0.10f) return false // 近黑不算暖
        val sat = (mx - mn) / mx
        return r > b * 1.35f && r >= g && sat > 0.25f
    }
}
