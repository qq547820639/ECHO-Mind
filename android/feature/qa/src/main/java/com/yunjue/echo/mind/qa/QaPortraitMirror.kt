package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.localportrait.LocalBaselineCalculator
import com.yunjue.echo.mind.localportrait.LocalBaselineSnapshot
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.localportrait.LocalMetricStats
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitFactDto
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Product Quality Era — 画像维度镜像（与后端 app/services/portrait/dimensions.py 逐语义一致）：
 *
 * - scale = max(mad*1.4826, (p75-p25)/2, 1e-6)；|z| <= 0.7 → SIMILAR；
 * - minimum meaningful delta（MIN_ABS_DELTA / MIN_REL_DELTA）→ SIMILAR（z=0）；
 * - missing != irregular：active_start 缺失 → RHYTHM 省略；
 * - STABILITY = 实际输出维度中非 SIMILAR 个数；
 * - 维度词表只允许中性方向词（禁 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL）。
 *
 * 输出 [DailyPortraitDto]：与 GET /v1/portraits 响应元素同构，
 * 供 computeLifeSeason / Journey / Why / Ask ECHO 全链路消费。
 */
object QaPortraitMirror {

    const val Z_SIMILAR = 0.7

    private val MIN_ABS_DELTA = mapOf(
        "active_start_minute" to 10.0,
        "active_end_minute" to 10.0,
        "movement_index" to 0.02,
        "screen_on_minutes" to 5.0,
        "late_screen_minutes" to 5.0,
        "active_hour_spread" to 0.05,
    )

    private const val MIN_REL_DELTA = 0.05

    fun portrait(
        date: String,
        today: LocalDayAggregate,
        baseline: LocalBaselineSnapshot?,
        timezone: String,
    ): DailyPortraitDto {
        val dims = computeDimensions(today, baseline)
        val status = portraitStatus(today.coverageScore, baseline?.validDays ?: 0)
        val confidence = confidenceLabel(today.coverageScore, baseline?.validDays ?: 0)
        val headlines = headlinesFor(dims)
        val facts = factsFor(dims, today, baseline)
        return DailyPortraitDto(
            date = date,
            status = status,
            confidence = confidence,
            baselineDays = baseline?.validDays ?: 0,
            headline = headlines,
            summary = if (headlines.isEmpty()) "今天和通常差不多。" else headlines.joinToString(" · ") + "。",
            dimensions = dims,
            facts = facts,
            timezoneUsed = timezone,
        )
    }

    /** 镜像 backend compute_dimensions（无基线 → 维度缺省，不伪造）。 */
    fun computeDimensions(
        today: LocalDayAggregate,
        baseline: LocalBaselineSnapshot?,
    ): Map<String, PortraitDimensionDto> {
        val dims = LinkedHashMap<String, PortraitDimensionDto>()
        var diffCount = 0

        fun emit(name: String, value: String, metric: String?, z: Double?) {
            dims[name] = PortraitDimensionDto(value = value, metric = metric, z = z)
        }

        // RHYTHM：active_start_minute；缺失/无基线 → 省略（missing != irregular，镜像后端）
        val start = today.activeStartMinute?.toDouble()
        val startStats = baseline?.metrics?.get("active_start_minute")
        if (start != null && startStats?.median != null) {
            val z = zOf(start, startStats, "active_start_minute")
            if (z != null) {
                when {
                    abs(z) <= Z_SIMILAR -> emit("RHYTHM", "SIMILAR", "active_start_minute", z)
                    z < 0 -> { emit("RHYTHM", "EARLIER", "active_start_minute", z); diffCount++ }
                    else -> { emit("RHYTHM", "LATER", "active_start_minute", z); diffCount++ }
                }
            }
        }

        // MOVEMENT：movement_index（无基线 → 维度省略）
        today.movementIndex?.let { v ->
            val stats = baseline?.metrics?.get("movement_index")
            if (stats?.median != null) {
                val value = classify(v, stats, "LESS", "MORE", "movement_index")
                emit("MOVEMENT", value, "movement_index", zOf(v, stats, "movement_index"))
                if (value != "SIMILAR") diffCount++
            }
        }

        // SCREEN_AMOUNT：screen_on_minutes
        val screenStats = baseline?.metrics?.get("screen_on_minutes")
        if (screenStats?.median != null) {
            val value = classify(today.screenOnMinutes, screenStats, "LESS", "MORE", "screen_on_minutes")
            emit("SCREEN_AMOUNT", value, "screen_on_minutes", zOf(today.screenOnMinutes, screenStats, "screen_on_minutes"))
            if (value != "SIMILAR") diffCount++
        }

        // SCREEN_TIMING：late_screen_minutes
        val lateStats = baseline?.metrics?.get("late_screen_minutes")
        if (lateStats?.median != null) {
            val value = classify(today.lateScreenMinutes, lateStats, "EARLIER", "LATER", "late_screen_minutes")
            emit("SCREEN_TIMING", value, "late_screen_minutes", zOf(today.lateScreenMinutes, lateStats, "late_screen_minutes"))
            if (value != "SIMILAR") diffCount++
        }

        // DAY_STRUCTURE：active_hour_spread
        today.activeHourSpread?.let { v ->
            val stats = baseline?.metrics?.get("active_hour_spread")
            if (stats?.median != null) {
                val z = zOf(v, stats, "active_hour_spread")
                if (z != null) {
                    when {
                        abs(z) <= Z_SIMILAR -> emit("DAY_STRUCTURE", "SIMILAR", "active_hour_spread", z)
                        z < 0 -> { emit("DAY_STRUCTURE", "MORE_CONCENTRATED", "active_hour_spread", z); diffCount++ }
                        else -> { emit("DAY_STRUCTURE", "MORE_FRAGMENTED", "active_hour_spread", z); diffCount++ }
                    }
                }
            }
        }

        // STABILITY：实际输出维度的非 SIMILAR 个数
        val stability = when {
            diffCount == 0 -> "VERY_SIMILAR"
            diffCount <= 2 -> "SLIGHTLY_DIFFERENT"
            else -> "CLEARLY_DIFFERENT"
        }
        emit("STABILITY", stability, null, null)
        return dims
    }

    /** 镜像 backend dimensions._z（minimum meaningful delta → z=0；scale 下限 = 最小有意义差）。 */
    fun zOf(value: Double, stats: LocalMetricStats, metric: String? = null): Double? {
        val med = stats.median ?: return null
        if (metric != null && belowMinDelta(value, stats, metric)) return 0.0
        val mad = stats.mad ?: 0.0
        val p25 = stats.p25 ?: 0.0
        val p75 = stats.p75 ?: 0.0
        // ERA 21：floor = MIN_ABS_DELTA（早期基线近重复样本不产生千万级 z）
        val floor = metric?.let { MIN_ABS_DELTA[it] } ?: 1e-6
        val scale = maxOf(mad * 1.4826, (p75 - p25) / 2.0, floor)
        return (value - med) / scale
    }

    private fun belowMinDelta(value: Double, stats: LocalMetricStats, metric: String): Boolean {
        val med = stats.median ?: return false
        val threshold = maxOf(MIN_ABS_DELTA[metric] ?: 0.0, abs(med) * MIN_REL_DELTA)
        return abs(value - med) < threshold
    }

    private fun classify(value: Double, stats: LocalMetricStats, lower: String, higher: String, metric: String): String {
        val z = zOf(value, stats, metric) ?: return "SIMILAR"
        if (abs(z) <= Z_SIMILAR) return "SIMILAR"
        return if (z > 0) higher else lower
    }

    fun portraitStatus(coverage: Double, validDays: Int): String = when {
        coverage < 0.25 -> "PARTIAL_DATA"
        validDays <= 0 -> "WARMING_UP"
        else -> LocalBaselineCalculator.baselineState(validDays)
    }

    fun confidenceLabel(coverage: Double, validDays: Int): String = when {
        validDays >= 7 && coverage >= 0.6 -> "HIGH"
        validDays < 3 || coverage < 0.4 -> "LOW"
        else -> "MEDIUM"
    }

    /** 中性 headline 短语（与 backend narrative 词表一致，无心理推断）。 */
    fun headlinesFor(dims: Map<String, PortraitDimensionDto>): List<String> {
        val out = mutableListOf<String>()
        when (dims["RHYTHM"]?.value) {
            "EARLIER" -> out += "活跃开始比通常早"
            "LATER" -> out += "活跃开始比通常晚"
        }
        when (dims["MOVEMENT"]?.value) {
            "MORE" -> out += "活动比通常多"
            "LESS" -> out += "活动比通常少"
        }
        when (dims["SCREEN_AMOUNT"]?.value) {
            "MORE" -> out += "屏幕时间比通常多"
            "LESS" -> out += "屏幕时间比通常少"
        }
        when (dims["SCREEN_TIMING"]?.value) {
            "LATER" -> out += "晚间屏幕比通常更晚"
            "EARLIER" -> out += "晚间屏幕比通常更早结束"
        }
        when (dims["DAY_STRUCTURE"]?.value) {
            "MORE_FRAGMENTED" -> out += "今天比较零散"
            "MORE_CONCENTRATED" -> out += "今天比较集中"
        }
        return out
    }

    /** 证据对照行（Why 层：today vs baseline 具体数字，10 秒可读）。 */
    fun factsFor(
        dims: Map<String, PortraitDimensionDto>,
        today: LocalDayAggregate,
        baseline: LocalBaselineSnapshot?,
    ): List<PortraitFactDto> {
        val facts = mutableListOf<PortraitFactDto>()
        fun fact(label: String, todayText: String, baselineText: String, deltaText: String) {
            facts += PortraitFactDto(label = label, todayText = todayText, baselineText = baselineText, deltaText = deltaText)
        }
        dims["RHYTHM"]?.let { d ->
            if (d.value != "SIMILAR") {
                val t = today.activeStartMinute?.toDouble()?.let(::minuteText) ?: "—"
                val b = baseline?.metrics?.get("active_start_minute")?.median?.let(::minuteText) ?: "—"
                fact("活跃起点", t, b, deltaTextOf(d.z))
            }
        }
        dims["MOVEMENT"]?.let { d ->
            if (d.value != "SIMILAR") {
                val t = today.movementIndex?.let { "%.2f".format(it) } ?: "—"
                val b = baseline?.metrics?.get("movement_index")?.median?.let { "%.2f".format(it) } ?: "—"
                fact("活动量", t, b, deltaTextOf(d.z))
            }
        }
        dims["SCREEN_AMOUNT"]?.let { d ->
            if (d.value != "SIMILAR") {
                val b = baseline?.metrics?.get("screen_on_minutes")?.median?.roundToInt()?.toString() ?: "—"
                fact("屏幕时间", "${today.screenOnMinutes.roundToInt()} 分钟", "$b 分钟", deltaTextOf(d.z))
            }
        }
        dims["SCREEN_TIMING"]?.let { d ->
            if (d.value != "SIMILAR") {
                val b = baseline?.metrics?.get("late_screen_minutes")?.median?.roundToInt()?.toString() ?: "—"
                fact("晚间屏幕", "${today.lateScreenMinutes.roundToInt()} 分钟", "$b 分钟", deltaTextOf(d.z))
            }
        }
        dims["DAY_STRUCTURE"]?.let { d ->
            if (d.value != "SIMILAR") {
                val t = today.activeHourSpread?.let { "${(it * 100).roundToInt()}%" } ?: "—"
                val b = baseline?.metrics?.get("active_hour_spread")?.median
                    ?.let { "${(it * 100).roundToInt()}%" } ?: "—"
                fact("活跃时段占比", t, b, deltaTextOf(d.z))
            }
        }
        return facts
    }

    fun minuteText(minute: Double): String {
        val total = minute.roundToInt().mod(1440)
        val h = total / 60
        val m = total % 60
        return "%02d:%02d".format(h, m)
    }

    private fun deltaTextOf(z: Double?): String = when {
        z == null -> ""
        abs(z) <= Z_SIMILAR -> "接近"
        z > 0 -> "偏高"
        else -> "偏低"
    }
}
