package com.yunjue.echo.mind.localportrait

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PORTRAIT_DIMENSIONS
import com.yunjue.echo.mind.model.PortraitFactDto
import java.time.LocalDate

/**
 * 端侧画像生成引擎（离线演示模式 / 离线回退共用）。
 *
 * 与后端 `app/services/portrait/{engine,dimensions,narrative,explain}.py` 与
 * `app/services/baseline/confidence.py` **逐语义镜像**（确定性、可复现）：
 *
 * 状态机：
 * - WARMING_UP（基线有效日 0-2）：固定文案，无维度比较；
 * - EARLY_BASELINE（3-6）：当天事实句，不输出"比平常"；
 * - BASELINE_READY（>=7）：confidence_for → LOW → LOW_CONFIDENCE 固定文案；
 *   否则 READY；today_coverage < 0.4 时 status=PARTIAL_DATA（narrative 加前缀）。
 *
 * 维度语义（Phase 5 C4）：
 * - missing != irregular：active_start 缺失 → RHYTHM 维度省略；STABILITY diff_count
 *   只统计实际输出维度的非 SIMILAR 个数；
 * - minimum meaningful absolute delta：near-zero baseline 不产生巨大 z；
 * - SCREEN_AMOUNT 与 SCREEN_TIMING 按不同指标分别分类（screen_on_minutes /
 *   late_screen_minutes）；注意 SCREEN_TIMING 存在量-时名实混义（见维度处注释）。
 *
 * 产品契约：维度取值禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL；
 * 所有句子不含被禁止词（焦虑/抑郁/孤独/压力过大/心理异常/风险/精神疾病/社交退缩）。
 *
 * 纯 Kotlin 无 Android 依赖。
 */
object LocalPortraitEngine {

    const val PORTRAIT_SCHEMA_VERSION = "portrait-v1"
    const val PARTIAL_COVERAGE_THRESHOLD = 0.4

    /** 每个指标的最小有意义绝对差（低于该差视为 SIMILAR；镜像 dimensions.MIN_ABS_DELTA）。 */
    val MIN_ABS_DELTA: Map<String, Double> = mapOf(
        "active_start_minute" to 10.0,
        "active_end_minute" to 10.0,
        "movement_index" to 0.02,
        "screen_on_minutes" to 5.0,
        "late_screen_minutes" to 5.0,
        "active_hour_spread" to 0.05
    )

    /** 相对基线比例下限（med 较大时按比例放大阈值）。 */
    const val MIN_REL_DELTA = 0.05

    /** 分类 z 阈值（|z| <= 该值 → SIMILAR）。 */
    const val Z_SIMILAR = 0.7

    // ===== 固定文案（镜像 narrative.py） =====
    const val WARMING_UP_TEXT =
        "ECHO 正在慢慢了解你的日常节奏。再积累几天，就能开始比较「今天」和「平常的你」。"
    const val LOW_CONFIDENCE_TEXT =
        "今天的数据还不够完整，暂时看不出和你平时相比有什么可靠变化。"
    const val PARTIAL_DATA_PREFIX =
        "今天的数据还不完整，以下画像仅反映已经采集到的部分。 "

    val RHYTHM_SENTENCES: Map<String, String> = mapOf(
        "EARLIER" to "今天开始活跃的时间比你最近的习惯早",
        "LATER" to "今天开始活跃的时间比你最近的习惯稍晚",
        "SIMILAR" to "今天的作息时间和你最近的习惯比较接近"
    )
    val MOVEMENT_SENTENCES: Map<String, String> = mapOf(
        "LESS" to "白天整体移动也少了一些",
        "MORE" to "白天整体移动比平常多一些",
        "SIMILAR" to "白天的移动情况和你的平常比较接近"
    )
    val SCREEN_AMOUNT_SENTENCES: Map<String, String> = mapOf(
        "LESS" to "屏幕互动比平常少一些",
        "MORE" to "屏幕互动比平常多一些",
        "SIMILAR" to "屏幕互动时长和你的平常比较接近"
    )
    val SCREEN_TIMING_SENTENCES: Map<String, String> = mapOf(
        "EARLIER" to "晚间屏幕互动比通常更早结束",
        "SIMILAR" to "晚间屏幕使用时间和你的平常比较接近",
        "LATER" to "晚间屏幕互动比通常集中"
    )
    val DAY_STRUCTURE_SENTENCES: Map<String, String> = mapOf(
        "MORE_CONCENTRATED" to "今天的行为比较集中",
        "SIMILAR" to "今天的行为分布和平时差不多",
        "MORE_FRAGMENTED" to "今天的行为比较零散"
    )
    val STABILITY_SENTENCES: Map<String, String> = mapOf(
        "VERY_SIMILAR" to "整体来看，今天和通常的你非常接近",
        "SLIGHTLY_DIFFERENT" to "整体来看，今天和通常的你有一些小变化",
        "CLEARLY_DIFFERENT" to "整体来看，今天和通常的你相比有明显变化"
    )

    val HEADLINE_MAP: Map<Pair<String, String>, String> = mapOf(
        "RHYTHM" to "LATER" to "偏晚",
        "RHYTHM" to "EARLIER" to "偏早",
        // Phase 6.2（Psychology Review）：行为观察措辞
        "MOVEMENT" to "LESS" to "移动较少",
        "MOVEMENT" to "MORE" to "移动较多",
        "SCREEN_AMOUNT" to "MORE" to "多屏",
        "SCREEN_AMOUNT" to "LESS" to "少屏",
        "SCREEN_TIMING" to "LATER" to "晚屏",
        "STABILITY" to "VERY_SIMILAR" to "接近",
        "STABILITY" to "SLIGHTLY_DIFFERENT" to "小变化",
        "STABILITY" to "CLEARLY_DIFFERENT" to "变化明显"
    )



    // ===== explain.py 粗粒度阈值 =====
    val COARSE_BASELINE_THRESHOLDS: Map<String, Double> = mapOf(
        "movement_index" to 0.05,
        "screen_on_minutes" to 5.0,
        "late_screen_minutes" to 5.0,
        "active_hour_spread" to 0.05,
        "active_start_minute" to 10.0,
        "active_end_minute" to 10.0
    )
    const val PERCENTAGE_CAP = 300

    /**
     * 生成当日画像（镜像 engine.generate_portrait 的纯计算部分）。
     *
     * @param todayAggregate 今日聚合（可为 null = 无今日数据，走轻量状态）
     * @param pastAggregates 该用户全部历史聚合（函数内部取 [today-28, today-1] 有效日）
     */
    fun generate(
        localDate: LocalDate,
        zoneId: java.time.ZoneId,
        todayAggregate: LocalDayAggregate?,
        pastAggregates: List<LocalDayAggregate>
    ): DailyPortraitDto {
        val tzName = zoneId.id
        val today = todayAggregate
        val coverageScore = today?.coverageScore ?: 0.0
        val coverage: Map<String, Any> = if (today != null) {
            mapOf(
                "coverage_score" to today.coverageScore,
                "valid_window_count" to today.validWindowCount,
                "expected_window_count" to today.expectedWindowCount,
                "sources_present" to today.sourcesPresent,
                "missing_sources" to today.missingSources
            )
        } else {
            mapOf(
                "coverage_score" to 0.0,
                "valid_window_count" to 0,
                "expected_window_count" to expectedWindowCountForDay(zoneId, localDate)
            )
        }

        val snapshot = buildLocalBaseline(localDate, pastAggregates)
        val state = LocalBaselineCalculator.baselineState(snapshot.validDays)

        // 基线未成型 / 早期：固定文案或事实句（镜像 engine 状态机）
        if (state == "WARMING_UP") {
            // v0.7.4 UX（冷启动获得感）：第 1 天就给出当天事实句——
            // 让用户立刻确认「它在记录、它看得见我的节奏」，而非只有一句等待文案。
            // 仅本地模式（端侧引擎）生效；服务端 WARMING_UP 文案保持镜像稳定。
            val facts = factSentences(today)
            val warmingSummary = if (facts.isNotBlank()) "$WARMING_UP_TEXT\n\n$facts" else WARMING_UP_TEXT
            return DailyPortraitDto(
                date = localDate.toString(),
                status = "WARMING_UP",
                confidence = "LOW",
                baselineDays = snapshot.validDays,
                baselineVersion = snapshot.version,
                summary = warmingSummary,
                coverage = coverage,
                timezoneUsed = tzName
            )
        }

        if (state == "EARLY_BASELINE") {
            return DailyPortraitDto(
                date = localDate.toString(),
                status = "EARLY_BASELINE",
                confidence = "LOW",
                baselineDays = snapshot.validDays,
                baselineVersion = snapshot.version,
                summary = factSentences(today),
                coverage = coverage,
                timezoneUsed = tzName
            )
        }

        // BASELINE_READY
        val confidence = confidenceFor(coverageScore, snapshot.validDays, today?.missingSources.orEmpty())
        if (confidence == "LOW") {
            return DailyPortraitDto(
                date = localDate.toString(),
                status = "LOW_CONFIDENCE",
                confidence = "LOW",
                baselineDays = snapshot.validDays,
                baselineVersion = snapshot.version,
                summary = LOW_CONFIDENCE_TEXT,
                coverage = coverage,
                timezoneUsed = tzName
            )
        }

        val status = if (coverageScore < PARTIAL_COVERAGE_THRESHOLD) "PARTIAL_DATA" else "READY"
        val dimensions = computeDimensions(today!!, snapshot.metrics)
        val (summary, highlights) = buildNarrative(dimensions)
        val facts = buildFacts(today, snapshot.metrics)
        val finalSummary = if (status == "PARTIAL_DATA" && summary.isNotBlank()) PARTIAL_DATA_PREFIX + summary else summary

        return DailyPortraitDto(
            date = localDate.toString(),
            status = status,
            confidence = confidence,
            baselineDays = snapshot.validDays,
            baselineVersion = snapshot.version,
            headline = highlights,
            summary = finalSummary,
            dimensions = dimensions,
            coverage = coverage,
            facts = facts,
            timezoneUsed = tzName
        )
    }

    /** 镜像 confidence.confidence_for。 */
    fun confidenceFor(todayCoverage: Double, baselineValidDays: Int, missingSources: List<String>): String {
        val missing = missingSources.toSet()
        if (todayCoverage >= 0.7 && baselineValidDays >= 7 && missing.isEmpty()) return "HIGH"
        if (todayCoverage >= 0.3 && baselineValidDays >= 3) return "MEDIUM"
        return "LOW"
    }

    /**
     * z 的 scale（镜像 dimensions._scale）。
     * ERA 21 修复：scale 下限 = 该指标的最小有意义差（MIN_ABS_DELTA），
     * 而不是 1e-6——早期基线（2-3 天近重复样本 → mad/IQR≈0）曾产生数千万量级的
     * 荒谬 z，污染 Why 证据与 Journey 变化排名。
     */
    fun scaleOf(stats: LocalMetricStats, metric: String? = null): Double {
        val madV = stats.mad ?: 0.0
        val p25 = stats.p25 ?: 0.0
        val p75 = stats.p75 ?: 0.0
        val floor = metric?.let { MIN_ABS_DELTA[it] } ?: 1e-6
        return maxOf(madV * 1.4826, (p75 - p25) / 2.0, floor)
    }

    /** 最小有意义绝对差判定（镜像 dimensions._below_min_delta）。 */
    fun belowMinDelta(value: Double, stats: LocalMetricStats, metric: String): Boolean {
        val med = stats.median ?: return false
        val absDelta = kotlin.math.abs(value - med)
        val threshold = maxOf(MIN_ABS_DELTA[metric] ?: 0.0, kotlin.math.abs(med) * MIN_REL_DELTA)
        return absDelta < threshold
    }

    /** 标准化 z（镜像 dimensions._z；最小差内返回 0.0）。 */
    fun zOf(value: Double, stats: LocalMetricStats, metric: String? = null): Double? {
        val med = stats.median ?: return null
        if (metric != null && belowMinDelta(value, stats, metric)) return 0.0
        return (value - med) / scaleOf(stats, metric)
    }

    /** 方向分类（镜像 dimensions._classify）。 */
    fun classify(value: Double, stats: LocalMetricStats, lower: String, higher: String, metric: String): String {
        val z = zOf(value, stats, metric) ?: return "SIMILAR"
        if (kotlin.math.abs(z) <= Z_SIMILAR) return "SIMILAR"
        return if (z > 0) higher else lower
    }

    /** 写入一个维度（z 四舍五入 4 位；镜像 dimensions._emit）。 */
    private fun emit(dims: LinkedHashMap<String, PortraitDimensionDto>, name: String, value: String, metric: String?, z: Double?) {
        dims[name] = PortraitDimensionDto(
            value = value,
            metric = metric,
            z = z?.let { round4(it) }
        )
    }

    /** 维度计算（镜像 dimensions.compute_dimensions）。 */
    fun computeDimensions(
        today: LocalDayAggregate,
        baselineMetrics: Map<String, LocalMetricStats>
    ): Map<String, PortraitDimensionDto> {
        val dims = LinkedHashMap<String, PortraitDimensionDto>()
        var diffCount = 0

        // RHYTHM：active_start_minute；数据缺失 → 维度省略（missing != irregular）
        val rhythmValue = today.activeStartMinute
        val rhythmStats = baselineMetrics["active_start_minute"]
        if (rhythmValue != null && rhythmStats?.median != null) {
            val z = zOf(rhythmValue.toDouble(), rhythmStats, "active_start_minute")
            when {
                z == null -> Unit // 基线缺失 → 省略
                kotlin.math.abs(z) <= Z_SIMILAR ->
                    emit(dims, "RHYTHM", "SIMILAR", "active_start_minute", z)
                z < 0 -> {
                    emit(dims, "RHYTHM", "EARLIER", "active_start_minute", z); diffCount++
                }
                else -> {
                    emit(dims, "RHYTHM", "LATER", "active_start_minute", z); diffCount++
                }
            }
        }

        // MOVEMENT：movement_index（None 则维度不输出）
        val movementValue = today.movementIndex
        val movementStats = baselineMetrics["movement_index"]
        if (movementValue != null && movementStats?.median != null) {
            val value = classify(movementValue, movementStats, "LESS", "MORE", "movement_index")
            emit(dims, "MOVEMENT", value, "movement_index", zOf(movementValue, movementStats, "movement_index"))
            if (value != "SIMILAR") diffCount++
        }

        // SCREEN_AMOUNT：screen_on_minutes
        val screenValue = today.screenOnMinutes
        val screenStats = baselineMetrics["screen_on_minutes"]
        if (screenStats?.median != null) {
            val value = classify(screenValue, screenStats, "LESS", "MORE", "screen_on_minutes")
            emit(dims, "SCREEN_AMOUNT", value, "screen_on_minutes", zOf(screenValue, screenStats, "screen_on_minutes"))
            if (value != "SIMILAR") diffCount++
        }

        // SCREEN_TIMING：late_screen_minutes
        // 已知量-时名实混义（LEDGER T3-P2-4，FOLLOW_UP 待产品裁定）：驱动指标是晚间屏幕
        // 分钟数（量），取值标签却为时间方向词 EARLIER/LATER（晚间屏幕量少 → EARLIER
        // 「更早结束」句式）。换时间点指标（如 last_screen_end_minute）或改量词标签
        // （MORE/LESS）须与后端 dimensions.py 同步裁定；端侧逐语义镜像后端，此处不改标签值。
        val lateValue = today.lateScreenMinutes
        val lateStats = baselineMetrics["late_screen_minutes"]
        if (lateStats?.median != null) {
            val value = classify(lateValue, lateStats, "EARLIER", "LATER", "late_screen_minutes")
            emit(dims, "SCREEN_TIMING", value, "late_screen_minutes", zOf(lateValue, lateStats, "late_screen_minutes"))
            if (value != "SIMILAR") diffCount++
        }

        // DAY_STRUCTURE：active_hour_spread
        val spreadValue = today.activeHourSpread
        val spreadStats = baselineMetrics["active_hour_spread"]
        if (spreadValue != null && spreadStats?.median != null) {
            val z = zOf(spreadValue, spreadStats, "active_hour_spread")
            when {
                z == null -> Unit
                kotlin.math.abs(z) <= Z_SIMILAR ->
                    emit(dims, "DAY_STRUCTURE", "SIMILAR", "active_hour_spread", z)
                z < 0 -> {
                    emit(dims, "DAY_STRUCTURE", "MORE_CONCENTRATED", "active_hour_spread", z); diffCount++
                }
                else -> {
                    emit(dims, "DAY_STRUCTURE", "MORE_FRAGMENTED", "active_hour_spread", z); diffCount++
                }
            }
        }

        // STABILITY：实际输出维度中非 SIMILAR 个数（缺失/UNKNOWN 自然不计入）
        val stability = when {
            diffCount == 0 -> "VERY_SIMILAR"
            diffCount <= 2 -> "SLIGHTLY_DIFFERENT"
            else -> "CLEARLY_DIFFERENT"
        }
        dims["STABILITY"] = PortraitDimensionDto(value = stability, metric = null, z = null)

        return dims
    }

    private fun sentenceFor(dimension: String, value: String): String? = when (dimension) {
        "RHYTHM" -> RHYTHM_SENTENCES[value]
        "MOVEMENT" -> MOVEMENT_SENTENCES[value]
        "SCREEN_AMOUNT" -> SCREEN_AMOUNT_SENTENCES[value]
        "SCREEN_TIMING" -> SCREEN_TIMING_SENTENCES[value]
        "DAY_STRUCTURE" -> DAY_STRUCTURE_SENTENCES[value]
        "STABILITY" -> STABILITY_SENTENCES[value]
        else -> null
    }

    /** 叙事 + headline（镜像 narrative.build_narrative；headline 最多 3 个）。 */
    fun buildNarrative(dimensions: Map<String, PortraitDimensionDto>): Pair<String, List<String>> {
        val sentences = mutableListOf<String>()
        val headline = mutableListOf<String>()
        for (dim in PORTRAIT_DIMENSIONS) {
            val entry = dimensions[dim] ?: continue
            val value = entry.value
            sentenceFor(dim, value)?.let { sentences.add(it) }
            val tag = HEADLINE_MAP[dim to value]
            if (tag != null && tag !in headline) headline.add(tag)
        }
        val summary = if (sentences.isEmpty()) "" else sentences.joinToString("。") + "。"
        return summary to headline.take(3)
    }

    /** EARLY_BASELINE 事实句（镜像 narrative.fact_sentences；today 为 null 返回空串）。 */
    fun factSentences(today: LocalDayAggregate?): String {
        if (today == null) return ""
        val parts = mutableListOf<String>()
        val screen = today.screenOnMinutes
        if (screen > 0) parts.add("今天累计屏幕互动 ${kotlin.math.round(screen).toInt()} 分钟")
        val notifications = today.notificationCount
        if (notifications > 0) parts.add("今天收到 $notifications 条通知")
        val switches = today.appSwitchCount
        if (switches > 0) parts.add("今天切换应用 $switches 次")
        return if (parts.isEmpty()) "" else parts.joinToString("。") + "。"
    }

    /** 时间格式化为 HH:MM（镜像 explain._hhmm）。 */
    fun hhmm(minute: Double): String {
        val m = kotlin.math.round(minute).toInt()
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 粗粒度措辞（镜像 explain._coarse_delta）。 */
    fun coarseDelta(z: Double, direction: String): String =
        if (kotlin.math.abs(z) > 2 * Z_SIMILAR) "比近期明显更$direction" else "比近期略$direction"

    /** delta 文案（镜像 explain._delta_text）。 */
    fun deltaText(value: Double, stats: LocalMetricStats, metric: String): String {
        val med = stats.median ?: return "暂无基线"
        val absDelta = kotlin.math.abs(value - med)
        val threshold = maxOf(MIN_ABS_DELTA[metric] ?: 0.0, kotlin.math.abs(med) * MIN_REL_DELTA)
        if (absDelta < threshold) return "和近期水平接近"
        val z = (value - med) / scaleOf(stats, metric)
        val direction = if (value < med) "少" else "多"
        if (kotlin.math.abs(med) < COARSE_BASELINE_THRESHOLDS[metric] ?: 0.0) {
            // 近零基线：百分比无意义（如 +900%），用粗粒度措辞
            return coarseDelta(z, direction)
        }
        val pct = kotlin.math.round(absDelta / maxOf(med, 1e-9) * 100).toInt()
        if (pct >= PERCENTAGE_CAP) return coarseDelta(z, direction)
        return "比近期中位水平${direction}约 $pct%"
    }

    /** 事实清单（镜像 explain.build_facts）。 */
    fun buildFacts(today: LocalDayAggregate, baselineMetrics: Map<String, LocalMetricStats>): List<PortraitFactDto> {
        val facts = mutableListOf<PortraitFactDto>()

        val start = today.activeStartMinute
        val startStats = baselineMetrics["active_start_minute"]
        if (start != null) {
            val baselineText = if (startStats?.median != null) "约 ${hhmm(startStats.median)}" else "暂无基线"
            // ERA 31 R23：开始活跃的「变化」说成人话（§11 示例「10:14 通常 09:21 +53 min」）——
            // 此前 delta 留空，用户要自己心算 10:14 与 09:21 的差；现在直接「晚 53 分钟」。
            val startDelta = startStats?.median?.let { median ->
                val diff = start.toDouble() - median
                val threshold = maxOf(
                    MIN_ABS_DELTA["active_start_minute"] ?: 0.0,
                    kotlin.math.abs(median) * MIN_REL_DELTA,
                )
                when {
                    kotlin.math.abs(diff) < threshold -> "和近期接近"
                    diff > 0 -> "晚 ${kotlin.math.round(kotlin.math.abs(diff)).toInt()} 分钟"
                    else -> "早 ${kotlin.math.round(kotlin.math.abs(diff)).toInt()} 分钟"
                }
            } ?: ""
            facts.add(
                PortraitFactDto(
                    label = "开始活跃",
                    todayText = hhmm(start.toDouble()),
                    baselineText = baselineText,
                    deltaText = startDelta
                )
            )
        }

        val movement = today.movementIndex
        val moveStats = baselineMetrics["movement_index"]
        if (movement != null) {
            val todayText: String
            if (moveStats?.median != null) {
                val med = moveStats.median
                todayText = if (kotlin.math.abs(movement - med) <
                    maxOf(MIN_ABS_DELTA["movement_index"] ?: 0.0, kotlin.math.abs(med) * MIN_REL_DELTA)
                ) {
                    "和近期典型水平接近"
                } else if (movement < med) {
                    "比近期典型水平少一些"
                } else {
                    "比近期典型水平多一些"
                }
            } else {
                todayText = "暂无基线"
            }
            facts.add(
                PortraitFactDto(
                    label = "日间移动",
                    todayText = todayText,
                    baselineText = "",
                    deltaText = deltaText(movement, moveStats ?: LocalMetricStats(null, null, null, null, null, null, 0), "movement_index")
                )
            )
        }

        val late = today.lateScreenMinutes
        val lateStats = baselineMetrics["late_screen_minutes"]
        if (late > 0) {
            facts.add(
                PortraitFactDto(
                    label = "晚间屏幕",
                    todayText = "${kotlin.math.round(late).toInt()} 分钟",
                    baselineText = "",
                    deltaText = deltaText(late, lateStats ?: LocalMetricStats(null, null, null, null, null, null, 0), "late_screen_minutes")
                )
            )
        }

        return facts
    }
}
