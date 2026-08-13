package com.yunjue.echo.mind.localportrait

/**
 * 端侧画像稳健统计纯函数（离线演示模式 / 离线回退共用）。
 *
 * 与后端 `app/services/baseline/metrics.py` + `circular.py` **逐语义镜像**：
 * - 线性 median/MAD/percentile：percentile 固定线性插值（numpy 风格，与后端一致）；
 * - 圆周 median/MAD（Phase 5, C3）：把 0..1439 分钟视为圆周，
 *   23:55 与 00:05 在圆周视角下相差 10 分钟而非 1430 分钟；
 * - circular=True 时 p10..p90 恒为 null（圆周分位数无线性意义）。
 *
 * 纯 Kotlin 无 Android 依赖，可被 JVM 单测直接覆盖。
 */
internal object LocalPortraitMath {

    const val MINUTES_PER_DAY = 1440

    /** 线性中位数（偶数长度取中间两值平均，与 Python statistics.median 一致）。 */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Median Absolute Deviation（围绕中位数）。 */
    fun mad(values: List<Double>): Double? {
        val med = median(values) ?: return null
        return median(values.map { kotlin.math.abs(it - med) })
    }

    /** 线性插值分位数（0 < p < 100，numpy 风格；与后端 metrics.percentile 一致）。 */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val ordered = values.sorted()
        val k = (ordered.size - 1) * (p / 100.0)
        val lower = k.toInt()
        val upper = lower + 1
        if (upper > ordered.size - 1) return ordered.last()
        return ordered[lower] + (ordered[upper] - ordered[lower]) * (k - lower)
    }

    /** 把分钟值折回 [0, 1440)。 */
    fun wrapMinute(value: Double): Double = value % MINUTES_PER_DAY

    /** 两个分钟值之间的最短圆周距离（0..720 分钟）。 */
    fun circularDistance(a: Double, b: Double): Double {
        val raw = kotlin.math.abs(wrapMinute(a) - wrapMinute(b))
        return minOf(raw, MINUTES_PER_DAY - raw)
    }

    /**
     * 圆周中位数：使到其余样本圆周距离之和最小的输入样本值（确定性，返回输入值之一）。
     * 空输入返回 null。与后端 circular_median 一致。
     */
    fun circularMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val samples = values.map { wrapMinute(it) }
        return samples.minByOrNull { m -> samples.sumOf { v -> circularDistance(m, v) } }
    }

    /** 圆周 MAD：样本到圆周中位数的圆周距离的中位数；空输入返回 null。 */
    fun circularMad(values: List<Double>): Double? {
        val med = circularMedian(values) ?: return null
        val distances = values.map { circularDistance(med, it) }
        return median(distances)
    }
}

/** 单指标 robust 统计量（镜像后端 metrics.compute_stats 输出结构）。 */
internal data class LocalMetricStats(
    val median: Double?,
    val mad: Double?,
    val p10: Double?,
    val p25: Double?,
    val p75: Double?,
    val p90: Double?,
    val validDays: Int
)

/** 指标 robust 统计（circular=True 时 p10..p90 恒为 null）。 */
internal fun computeLocalStats(values: List<Double>, circular: Boolean = false): LocalMetricStats {
    if (values.isEmpty()) {
        return LocalMetricStats(null, null, null, null, null, null, 0)
    }
    return if (circular) {
        LocalMetricStats(
            median = LocalPortraitMath.circularMedian(values),
            mad = LocalPortraitMath.circularMad(values),
            p10 = null, p25 = null, p75 = null, p90 = null,
            validDays = values.size
        )
    } else {
        LocalMetricStats(
            median = LocalPortraitMath.median(values),
            mad = LocalPortraitMath.mad(values),
            p10 = LocalPortraitMath.percentile(values, 10.0),
            p25 = LocalPortraitMath.percentile(values, 25.0),
            p75 = LocalPortraitMath.percentile(values, 75.0),
            p90 = LocalPortraitMath.percentile(values, 90.0),
            validDays = values.size
        )
    }
}
