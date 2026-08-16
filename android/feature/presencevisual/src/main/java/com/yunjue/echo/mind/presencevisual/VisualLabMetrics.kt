package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VisualLabMetrics — V3 §34/§82 自动视觉指标（从真实渲染位图计算）。
 *
 * 指标定义（Reference KNOWN Day28 门，§82）：
 * - nearBlackRatio      近黑像素占比（luma < 0.045）≥ 58%
 * - highlightRatio      高亮像素占比（luma > 0.80）≤ 4%
 * - warmRatio           暖色像素占比（hue 10°..50° 且饱和）≤ 15%
 * - negativeSpaceRatio  外围负空间占比（1.35R 外近黑）≥ 40%
 * - visualMassInside    视觉质量（非近黑能量）在 .9R 内占比 ≥ 82%
 * - centerLuminance / outerLuminance  中心/外围亮度（core cavity 必须清楚：中心不得是高亮白球）
 *
 * 只做 composition / density / tone / visual weight / negative space 比较，不 pixel-perfect。
 */
object VisualLabMetrics {

    data class Metrics(
        val nearBlackRatio: Float,
        val highlightRatio: Float,
        val warmRatio: Float,
        val negativeSpaceRatio: Float,
        val visualMassInside: Float,
        val centerLuminance: Float,
        val outerLuminance: Float,
    )

    /** §82 Reference 自动门结果（每项独立判定）。 */
    data class GateResult(
        val metrics: Metrics,
        val nearBlackPass: Boolean,
        val highlightPass: Boolean,
        val warmPass: Boolean,
        val negativeSpacePass: Boolean,
        val visualMassPass: Boolean,
        val cavityPass: Boolean,
    ) {
        val allPass: Boolean
            get() = nearBlackPass && highlightPass && warmPass && negativeSpacePass &&
                visualMassPass && cavityPass
    }

    fun evaluate(m: Metrics): GateResult = GateResult(
        metrics = m,
        nearBlackPass = m.nearBlackRatio >= 0.58f,
        highlightPass = m.highlightRatio <= 0.04f,
        warmPass = m.warmRatio <= 0.15f,
        negativeSpacePass = m.negativeSpaceRatio >= 0.40f,
        visualMassPass = m.visualMassInside >= 0.82f,
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
        var highlight = 0
        var warm = 0
        var outerTotal = 0
        var outerNearBlack = 0
        var massInside = 0f
        var massTotal = 0f
        var centerSum = 0f
        var centerCount = 0
        var outerSum = 0f
        val step = 2 // 2px 采样：指标精度足够，耗时减半
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
                if (luma > 0.80f) highlight++
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
                }
                x += step
            }
            y += step
        }
        return Metrics(
            nearBlackRatio = nearBlack / total.toFloat(),
            highlightRatio = highlight / total.toFloat(),
            warmRatio = warm / total.toFloat(),
            negativeSpaceRatio = if (outerTotal > 0) outerNearBlack / outerTotal.toFloat() else 0f,
            visualMassInside = if (massTotal > 0f) massInside / massTotal else 0f,
            centerLuminance = if (centerCount > 0) centerSum / centerCount else 0f,
            outerLuminance = if (outerTotal > 0) outerSum / outerTotal else 0f,
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
