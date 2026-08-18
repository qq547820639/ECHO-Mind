package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.memory.contextExceptionKindLabel
import com.yunjue.echo.mind.presence.EchoVisualParameters

/**
 * ERA 24（Batch 4）— Journey 情感价值层：§39 期间故事 / §40 Significant Change /
 * §41 Memory Landmarks / §42 年故事 / §43 情感克制。
 *
 * 原则：Journey 不是数据浏览器，也不是周报机器——
 * 每周/月最多突出 1-3 个真正重要的变化；变化必须同时满足
 * 视觉距离 + 持续时间 + 置信度；上下文时期标注为特殊阶段而非「异常变化」；
 * 行为变化只描述行为（禁「这是艰难的一年」式自动情绪判断）。
 */

/** §40 显著变化（综合视觉距离 / 基线偏差 / 持续时间 / 上下文 / 置信度）。 */
data class JourneySignificantChange(
    val date: String,
    val beforeDate: String?,
    val before: EchoVisualParameters?,
    val after: EchoVisualParameters?,
    val distance: Float,
    /** 变化持续的天数（窗口长度）。 */
    val durationDays: Int,
    val changedAspects: List<String>,
    /** 命中的上下文时期 kind（特殊时期内的变化标注为上下文，而非节奏异常）。 */
    val contextKind: String? = null,
    /** 0..1 置信度（窗口内有效画像天数占比）。 */
    val confidence: Float,
)

data class SignificantChangeConfig(
    val windowDays: Int = 14,
    val threshold: Float = RIVER_TRANSITION_DISTANCE,
    /** 有效画像天数低于该值不产生变化判断（置信不足）。 */
    val minValidDaysPerWindow: Int = 5,
)

/**
 * §40 — 显著变化检测（确定性）。
 *
 * 滑动窗口（[config.windowDays] 天）相邻视觉距离超过阈值 → 候选；
 * 候选要求窗口内有效画像 ≥ minValidDaysPerWindow（置信度）；
 * 命中上下文时期 → 标注 contextKind（特殊阶段，不是节奏异常）。
 */
fun detectSignificantChanges(
    days: List<JourneyDay>,
    contextPeriods: List<JourneyContextPeriod> = emptyList(),
    config: SignificantChangeConfig = SignificantChangeConfig(),
): List<JourneySignificantChange> {
    val sorted = days.sortedBy { it.date }
    if (sorted.size < config.windowDays * 2) return emptyList()
    val changes = mutableListOf<JourneySignificantChange>()
    val step = (config.windowDays / 2).coerceAtLeast(1)
    var i = 0
    while (i + config.windowDays * 2 <= sorted.size) {
        val beforeWindow = sorted.subList(i, i + config.windowDays)
        val afterWindow = sorted.subList(i + config.windowDays, i + config.windowDays * 2)
        val before = journeyAggregateOfDays(beforeWindow)
        val after = journeyAggregateOfDays(afterWindow)
        if (before != null && after != null) {
            // §40：视觉距离 + 基线偏差取强者。偏差 = 方向一致性：
            // 各 (维度, 非 SIMILAR 取值) 在两窗口的占比差最大值——
            // 「SCREEN_AMOUNT=MORE 从 0.1 → 0.9」这类方向性变化；
            // 噪声日产生的无序 LATER/EARLIER 标签两窗口占比接近 → 差 ≈ 0。
            val visual = visualDistance(before, after)
            val behaviorDelta = directionalDeviation(beforeWindow, afterWindow)
            val distance = maxOf(visual, behaviorDelta)
            if (distance >= config.threshold) {
                val validBefore = beforeWindow.count { it.visualParams != null }
                val validAfter = afterWindow.count { it.visualParams != null }
                val confidence = (
                    (validBefore + validAfter).toFloat() / (config.windowDays * 2)
                    ).coerceIn(0f, 1f)
                if (validBefore >= config.minValidDaysPerWindow && validAfter >= config.minValidDaysPerWindow) {
                    val date = afterWindow.first().date
                    val contextKind = contextPeriods.firstOrNull { date in it.startDate..it.endDate }?.kind
                    val aspects = buildList {
                        addAll(changedVisualAspects(before, after).take(2))
                        addAll(shiftedBehaviorAspects(beforeWindow, afterWindow).take(2))
                    }.distinct()
                    changes.add(
                        JourneySignificantChange(
                            date = date,
                            beforeDate = beforeWindow.last().date,
                            before = before,
                            after = after,
                            distance = distance,
                            durationDays = config.windowDays,
                            changedAspects = aspects,
                            contextKind = contextKind,
                            confidence = confidence,
                        ),
                    )
                }
            }
        }
        i += step
    }
    // 相邻窗口重叠产生的重复候选：按日期去重（同日保留距离最大者）
    val deduped = changes.groupBy { it.date }
        .map { (_, group) -> group.maxByOrNull { it.distance }!! }
        .sortedBy { it.date }
    // 同一变化在滑动窗口下产生多个日期 → 合并（间隔 ≤ 窗口天数且变化面重叠
    // 或上下文可解释 → 同一变化；边界窗口可能先于上下文时期 1-3 天命中）
    val merged = mutableListOf<JourneySignificantChange>()
    for (change in deduped) {
        val last = merged.lastOrNull()
        val aspectsOverlap = last != null && last.changedAspects.any { it in change.changedAspects }
        val contextCompatible = last == null || last.contextKind == change.contextKind ||
            last.contextKind == null || change.contextKind == null
        val withinWindow = last != null &&
            daysBetween(last.date, change.date) <= config.windowDays * 2 &&
            aspectsOverlap && contextCompatible
        if (withinWindow) {
            val kept = if (change.distance > last!!.distance) change.copy(date = last.date) else last
            merged[merged.size - 1] = kept.copy(contextKind = last.contextKind ?: change.contextKind)
        } else {
            merged.add(change)
        }
    }
    return merged
}

private fun daysBetween(a: String, b: String): Long = runCatching {
    java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b))
}.getOrDefault(Long.MAX_VALUE)

/**
 * 方向性偏差：各 (维度, 非 SIMILAR 取值) 占比差的最大值（0..1）。
 * 约束：该取值必须在前/后窗口至少一侧成为主导（占比 ≥ 0.5）——
 * 早期基线噪声产生的无序 LATER/EARLIER 标签（两侧占比都低）不构成变化。
 */
internal fun directionalDeviation(before: List<JourneyDay>, after: List<JourneyDay>): Float {
    val dims = (before + after).flatMap { it.dimensionValues.keys }.toSet().filter { it != "STABILITY" }
    var maxDelta = 0f
    for (dim in dims) {
        val values = (before + after).mapNotNull { it.dimensionValues[dim] }
            .filter { it !in setOf("SIMILAR", "VERY_SIMILAR", "") }.toSet()
        for (value in values) {
            fun frac(days: List<JourneyDay>): Float {
                val counted = days.count { it.dimensionValues[dim] == value }
                return counted.toFloat() / days.size.coerceAtLeast(1)
            }
            val beforeFrac = frac(before)
            val afterFrac = frac(after)
            if (maxOf(beforeFrac, afterFrac) < 0.5f) continue
            val delta = kotlin.math.abs(afterFrac - beforeFrac)
            // 至少 0.4 的占比翻转（0.2→0.6 级）才算方向性变化；
            // 早期基线噪声的 0.5→0.2 抖动（delta≈0.3）不算
            if (delta < 0.4f) continue
            if (delta > maxDelta) maxDelta = delta
        }
    }
    return maxDelta
}

/** 行为维度变化方向（两窗口占比差最大的维度 → 中性标签）。 */
private fun shiftedBehaviorAspects(before: List<JourneyDay>, after: List<JourneyDay>): List<String> {
    val dims = before.flatMap { it.dimensionValues.keys }.toSet().filter { it != "STABILITY" }
    return dims.mapNotNull { dim ->
        fun shiftedFraction(days: List<JourneyDay>): Float {
            val counted = days.count { day ->
                day.dimensionValues[dim]?.let { it !in setOf("SIMILAR", "VERY_SIMILAR", "") } == true
            }
            return counted.toFloat() / days.size.coerceAtLeast(1)
        }
        val delta = shiftedFraction(after) - shiftedFraction(before)
        if (kotlin.math.abs(delta) < 0.3f) return@mapNotNull null
        val direction = if (delta > 0) dominantValue(dim, after) else dominantValue(dim, before)
        if (direction.isBlank()) return@mapNotNull null
        dimensionShiftLabel(dim, direction).takeUnless { it == dim } // 未知组合不得泄漏工程键
    }.sortedByDescending { kotlin.math.abs(it.length) }
}

private fun dominantValue(dim: String, days: List<JourneyDay>): String =
    days.mapNotNull { it.dimensionValues[dim] }
        .filter { it !in setOf("SIMILAR", "VERY_SIMILAR", "") }
        .groupBy { it }.maxByOrNull { it.value.size }?.key ?: ""

/** 维度 → 中性变化标签（行为语言）。 */
private fun dimensionShiftLabel(dim: String, value: String): String = when (dim to value) {
    "RHYTHM" to "LATER" -> "活跃起点后移"
    "RHYTHM" to "EARLIER" -> "活跃起点前移"
    "SCREEN_AMOUNT" to "MORE" -> "屏幕时间增加"
    "SCREEN_AMOUNT" to "LESS" -> "屏幕时间减少"
    "SCREEN_TIMING" to "LATER" -> "晚间屏幕更晚"
    "SCREEN_TIMING" to "EARLIER" -> "晚间屏幕更早"
    "MOVEMENT" to "MORE" -> "活动量增加"
    "MOVEMENT" to "LESS" -> "活动量减少"
    "DAY_STRUCTURE" to "MORE_FRAGMENTED" -> "一天更零散"
    "DAY_STRUCTURE" to "MORE_CONCENTRATED" -> "一天更集中"
    else -> dim
}

/**
 * §39 — 期间故事：最多 1-3 个真正重要的变化（不是流水账）。
 * 无显著变化 → 一句平稳描述；全部行为语言（§43 无情绪判断）。
 */
fun buildPeriodStory(
    days: List<JourneyDay>,
    contextPeriods: List<JourneyContextPeriod> = emptyList(),
    maxChanges: Int = 3,
    config: SignificantChangeConfig = SignificantChangeConfig(),
): String {
    // ERA 32 R06：不足两个检测窗口（<28 天）不妄断「平稳」——数据不足就是数据不足。
    if (days.size < config.windowDays * 2) return ""
    val changes = detectSignificantChanges(days, contextPeriods, config)
    if (changes.isEmpty()) {
        return "这段时间的节奏很平稳，没有特别大的变化。"
    }
    val top = changes.sortedByDescending { it.distance }.take(maxChanges)
    val sentences = top.map { c ->
        val aspects = c.changedAspects.take(3).joinToString("、")
        val context = c.contextKind?.let { "（你提到过的${contextExceptionKindLabel(it)}期间）" } ?: ""
        "${c.date} 前后，${aspects}方面有明显变化$context。"
    }
    return sentences.joinToString(" ")
}

/**
 * §41 90 天测试：现在（近 7 天）与一个月前（30~23 天前）对比。
 * 复用 §40 的同一批组合原语（视觉聚合 + 行为方向性变化）；
 * 无变化 → 诚实的「很接近」；不足 38 天 → 空（不硬凑）。
 */
fun compareNowWithMonthAgo(days: List<JourneyDay>): List<String> {
    val sorted = days.sortedBy { it.date }
    if (sorted.size < 38) return emptyList()
    val now = sorted.takeLast(7)
    val monthAgo = sorted.subList(sorted.size - 38, sorted.size - 31)
    val before = journeyAggregateOfDays(monthAgo) ?: return emptyList()
    val after = journeyAggregateOfDays(now) ?: return emptyList()
    val lines = buildList {
        addAll(explainPeriodChange(before, after, monthAgo.lastOrNull()?.date, now.lastOrNull()?.date))
        addAll(shiftedBehaviorAspects(monthAgo, now))
    }.distinct()
    return if (lines.isEmpty()) listOf("和一个月前相比，整体节奏很接近。") else lines
}

/** §41 — Memory Landmarks（时间锚点：用户愿意回看的时间）。 */
enum class JourneyLandmarkKind { BASELINE_MATURE, MAJOR_SHIFT, CONTEXT_PERIOD, USER_CONFIRMED_PHASE }

data class JourneyLandmark(
    val date: String,
    val kind: JourneyLandmarkKind,
    val text: String,
)

fun landmarkKindLabel(kind: JourneyLandmarkKind): String = when (kind) {
    JourneyLandmarkKind.BASELINE_MATURE -> "第一次基线成熟"
    JourneyLandmarkKind.MAJOR_SHIFT -> "节奏明显变化"
    JourneyLandmarkKind.CONTEXT_PERIOD -> "特殊时期"
    JourneyLandmarkKind.USER_CONFIRMED_PHASE -> "你确认过的阶段"
}

/**
 * §41 — 构建时间地标：
 * - 第一次 baseline 成熟（baselineDays ≥ 7 的首日）；
 * - 节奏明显变化（§40 显著变化）；
 * - 持续特殊上下文（上下文时期）；
 * - 用户确认的重要阶段（confirmedDates: date → label）。
 */
fun buildLandmarks(
    days: List<JourneyDay>,
    contextPeriods: List<JourneyContextPeriod> = emptyList(),
    confirmedPhases: Map<String, String> = emptyMap(),
    config: SignificantChangeConfig = SignificantChangeConfig(),
): List<JourneyLandmark> {
    val sorted = days.sortedBy { it.date }
    val landmarks = mutableListOf<JourneyLandmark>()

    sorted.firstOrNull { it.baselineDays >= 7 }?.let { firstMature ->
        landmarks += JourneyLandmark(
            date = firstMature.date,
            kind = JourneyLandmarkKind.BASELINE_MATURE,
            text = "基线在这一天成熟，我开始拿「通常的你」做对照。",
        )
    }

    detectSignificantChanges(sorted, contextPeriods, config).forEach { c ->
        landmarks += JourneyLandmark(
            date = c.date,
            kind = JourneyLandmarkKind.MAJOR_SHIFT,
            text = "节奏在这里出现了明显变化（${c.changedAspects.take(2).joinToString("、")}）。",
        )
    }

    contextPeriods.forEach { p ->
        landmarks += JourneyLandmark(
            date = p.startDate,
            kind = JourneyLandmarkKind.CONTEXT_PERIOD,
            text = "一段${contextExceptionKindLabel(p.kind)}（${p.startDate}~${p.endDate}）。",
        )
    }

    confirmedPhases.entries.sortedBy { it.key }.forEach { (date, label) ->
        landmarks += JourneyLandmark(
            date = date,
            kind = JourneyLandmarkKind.USER_CONFIRMED_PHASE,
            text = label,
        )
    }

    return landmarks.sortedBy { it.date }
}


/**
 * §43 — 情感克制守卫：故事文案不得自动输出情绪判断词。
 * 返回命中的禁词（空列表 = 通过）。行为变化只描述行为。
 */
fun storyRestraintCheck(story: String): List<String> {
    val banned = listOf(
        "艰难", "痛苦", "糟糕", "难受", "可怜", "辛苦", "不容易", "命苦", "好惨",
        "熬过", "撑过", "低谷", "黑暗", "崩溃的一年", "糟糕的一年", "这是艰难",
    )
    return banned.filter { story.contains(it) }
}
