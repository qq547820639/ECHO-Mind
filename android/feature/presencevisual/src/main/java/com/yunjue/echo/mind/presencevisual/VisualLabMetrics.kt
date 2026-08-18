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

    /**
     * V3 §12 契约：暖色视觉面积硬上限（15%）。**QA 门执行点单点常量**——
     * 渲染链不执行该阈值（由拓扑 4% warm 分类保证下界），EchoMaterialSpec 不再携带副本。
     */
    const val WARM_AREA_HARD_CAP = 0.15f

    /** V3 §12 契约：暖色面积目标上限（10%，soft target）。 */
    const val WARM_AREA_TARGET_CAP = 0.10f

    /**
     * V3 §24 契约：高亮像素（luma > 0.80）占比上限（4%）。**QA 门执行点单点常量**——
     * 渲染链 soft-knee 不执行该阈值，EchoMaterialSpec 不再携带副本。
     */
    const val HIGHLIGHT_CAP = 0.04f

    /** V3 §10 契约：极亮 glint（luma > 0.92）占比上限（2.5%）。 */
    const val EXTREME_GLINT_CAP = 0.025f

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
        // ---- Organism Visual Breakthrough §30–§33 新艺术指标（防 wireframe 回归）----
        /** hero（1.0R 盘）平均亮度（目标 0.10–0.20；§31）。 */
        val heroMeanLuminance: Float = 0f,
        /** hero 盘内 bright（luma>0.25）占比（目标 ≥0.10；§31）。 */
        val heroBrightRatio: Float = 0f,
        /** hero 盘内强彩色发光占比（sat>0.25 且 luma>0.10；目标 ≥0.30；§31）。 */
        val chromaticLuminousRatio: Float = 0f,
        /** 强彩色发光像素平均饱和度（目标 ≥0.55；§31）。 */
        val meanChromaticSaturation: Float = 0f,
        /** 发光 bbox 中央 60%×60% 内彩色发光占比（反「空心线框」；§33）。 */
        val centralVolumeCoverage: Float = 0f,
        /** 发光像素中细线像素占比（5×5 网格腐蚀后消失比例；§32 反 wireframe 主导）。 */
        val wireframeDominance: Float = 0f,
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
        // ---- Breakthrough §31 新艺术门（wireframe→organism 防回归）----
        val heroLuminancePass: Boolean = true,
        val heroBrightPass: Boolean = true,
        val chromaticLuminousPass: Boolean = true,
        val chromaticSaturationPass: Boolean = true,
        val centralVolumePass: Boolean = true,
        val wireframePass: Boolean = true,
    ) {
        val allPass: Boolean
            get() = nearBlackPass && highLuminancePass && extremeGlintPass && warmPass &&
                negativeSpacePass && visualMassPass && organismWidthPass && cavityPass &&
                heroLuminancePass && heroBrightPass && chromaticLuminousPass &&
                chromaticSaturationPass && centralVolumePass && wireframePass
    }

    fun evaluate(m: Metrics): GateResult = GateResult(
        metrics = m,
        nearBlackPass = m.nearBlackRatio >= 0.58f,
        highLuminancePass = m.highLuminanceRatio <= HIGHLIGHT_CAP,
        extremeGlintPass = m.extremeGlintRatio <= EXTREME_GLINT_CAP,
        warmPass = m.warmRatio <= WARM_AREA_HARD_CAP,
        warmTargetPass = m.warmRatio <= WARM_AREA_TARGET_CAP,
        negativeSpacePass = m.negativeSpaceRatio >= 0.40f,
        visualMassPass = m.visualMassInside >= 0.82f,
        organismWidthPass = m.organismWidthFraction in 0.72f..0.82f,
        // core cavity 清楚：中心区域平均亮度必须低于全图高亮阈值（暗腔存在）
        cavityPass = m.centerLuminance < 0.45f,
        // ---- Breakthrough §31 艺术门（防退回 dark wireframe atom；区间取自设计代理目标，
        // 非 pixel-perfect 教条——§64）----
        heroLuminancePass = m.heroMeanLuminance in 0.10f..0.20f,
        heroBrightPass = m.heroBrightRatio >= 0.10f,
        chromaticLuminousPass = m.chromaticLuminousRatio >= 0.30f,
        chromaticSaturationPass = m.meanChromaticSaturation >= 0.55f,
        centralVolumePass = m.centralVolumeCoverage >= 0.30f,
        wireframePass = m.wireframeDominance <= 0.30f,
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
        // Breakthrough §30–§33 新指标累加器
        var heroCount = 0
        var heroLumaSum = 0f
        var heroBright = 0
        var chromaticCount = 0
        var chromaticSatSum = 0f
        val step = 2 // 2px 采样：指标精度足够，耗时减半
        val luminousThreshold = 0.06f
        // Breakthrough §32：wireframe dominance 网格（step 分辨率；5×5 网格腐蚀 ≈ 10px 结构宽）
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        val lumGrid = BooleanArray(gw * gh)
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
                // Breakthrough：hero 盘（≤1.0R）亮度/彩色发光统计
                if (dist <= radiusPx) {
                    heroCount++
                    heroLumaSum += luma
                    if (luma > 0.25f) heroBright++
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val sat = if (mx > 1e-6f) (mx - mn) / mx else 0f
                    if (sat > 0.25f && luma > 0.10f) {
                        chromaticCount++
                        chromaticSatSum += sat
                    }
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
                    lumGrid[(y / step) * gw + (x / step)] = true
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
        // Breakthrough §33：中央体积——发光 bbox 中央 60%×60% 内彩色发光占比
        var centralVolume = 0f
        if (bbox != null && maxX > minX && maxY > minY) {
            val bw = (maxX - minX) * 0.6f
            val bh = (maxY - minY) * 0.6f
            val cx0 = ((maxX + minX - bw) / 2f).toInt().coerceIn(0, w - 1)
            val cx1 = ((maxX + minX + bw) / 2f).toInt().coerceIn(0, w - 1)
            val cy0 = ((maxY + minY - bh) / 2f).toInt().coerceIn(0, h - 1)
            val cy1 = ((maxY + minY + bh) / 2f).toInt().coerceIn(0, h - 1)
            var n = 0
            var chrom = 0
            var yy = cy0
            while (yy <= cy1) {
                var xx = cx0
                while (xx <= cx1) {
                    val c = bitmap.getPixel(xx, yy)
                    val r = (c shr 16 and 0xFF) / 255f
                    val g = (c shr 8 and 0xFF) / 255f
                    val b = (c and 0xFF) / 255f
                    val luma = 0.2126f * r + 0.587f * g + 0.0722f * b
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val sat = if (mx > 1e-6f) (mx - mn) / mx else 0f
                    n++
                    if (sat > 0.25f && luma > 0.10f) chrom++
                    xx += step
                }
                yy += step
            }
            if (n > 0) centralVolume = chrom.toFloat() / n
        }

        // Breakthrough §32：wireframe dominance——5×5 网格腐蚀（≈10px）后仍存活的发光像素
        // 占比的补：细线（宽 <10px）全部消失 → dominance≈1；实心体积内部存活 → 低。
        var wireframeDominance = 1f
        if (bbox != null && maxX > minX && maxY > minY) {
            var lumCells = 0
            var survived = 0
            val gy0 = (minY / step).coerceAtLeast(2)
            val gy1 = (maxY / step).coerceAtMost(gh - 3)
            val gx0 = (minX / step).coerceAtLeast(2)
            val gx1 = (maxX / step).coerceAtMost(gw - 3)
            var gy = gy0
            while (gy <= gy1) {
                var gx = gx0
                while (gx <= gx1) {
                    if (lumGrid[gy * gw + gx]) {
                        lumCells++
                        var all = true
                        var dy2 = -2
                        while (dy2 <= 2 && all) {
                            var dx2 = -2
                            while (dx2 <= 2 && all) {
                                if (!lumGrid[(gy + dy2) * gw + (gx + dx2)]) all = false
                                dx2++
                            }
                            dy2++
                        }
                        if (all) survived++
                    }
                    gx++
                }
                gy++
            }
            if (lumCells > 0) wireframeDominance = 1f - survived.toFloat() / lumCells
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
            heroMeanLuminance = if (heroCount > 0) heroLumaSum / heroCount else 0f,
            heroBrightRatio = if (heroCount > 0) heroBright.toFloat() / heroCount else 0f,
            chromaticLuminousRatio = if (heroCount > 0) chromaticCount.toFloat() / heroCount else 0f,
            meanChromaticSaturation = if (chromaticCount > 0) chromaticSatSum / chromaticCount else 0f,
            centralVolumeCoverage = centralVolume,
            wireframeDominance = wireframeDominance,
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
