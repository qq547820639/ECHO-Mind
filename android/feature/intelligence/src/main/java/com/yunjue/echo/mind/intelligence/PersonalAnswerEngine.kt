package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionValueText
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ERA 31 BATCH 2 §19/§24 — Personal Answer Engine（确定性个人回答引擎）。
 *
 * 回答「只有我的 ECHO 才可能回答」的问题，**不需要任何 AI Provider**：
 * 数据 → 计算 → 证据先行 → 克制表述。无 Provider/离线时 Ask ECHO 依然有用
 * （免费核心承诺：Basic Reasoning 不依赖付费 AI）。
 *
 * 铁律：
 * - Evidence before interpretation：答案只来自 [PersonalAnswerInputs] 的数据；
 * - 数据不足明确说不足，禁止编造；
 * - 中性词表（禁心理/医学推断）；
 * - 不认识的问题 → 返回 null（诚实交回 AI/「暂时回答不了」路径，不硬凑）。
 *
 * 与 QA oracle（QaAskEcho）同源：QaAskEcho 现为本引擎的薄适配器——
 * QA 测的引擎就是产品用的引擎（不再存在 QA 重写产品）。
 */
data class PersonalDayFacts(
    val date: LocalDate,
    val activeStartMinute: Int?,
    val activeEndMinute: Int?,
    val screenOnMinutes: Double,
    val lateScreenMinutes: Double,
    val activeHourSpread: Double?,
    val movementIndex: Double?,
    val dimensions: Map<String, PortraitDimensionDto>,
    val hasPortrait: Boolean,
)

/** 用户自述上下文窗口（出差等）；[fromDay]/[toDay] 为 days 列表下标（含端点）。 */
data class PersonalContextWindow(
    val fromDay: Int,
    val toDay: Int,
    val label: String = "",
)

data class PersonalAnswerInputs(
    /** 认识天数（days.size - 1；仅用于「认识多久」类回答）。 */
    val dayIndex: Int,
    /** 逐日事实（date 升序；缺失数据 → null 字段）。 */
    val days: List<PersonalDayFacts>,
    /** 用户自述上下文窗口（最近在前）。 */
    val contextWindows: List<PersonalContextWindow>,
    /** 人生季节漂移 0..1（稳定性回答的补充提示；无 presence 状态时 0f）。 */
    val seasonDrift: Float,
    /** 用户纠正过的内容摘要（「我纠正过你什么？」回答用）。 */
    val userCorrections: List<String> = emptyList(),
    /** 用户确认过的事实（USER_CONFIRMED 记忆；q042/q043 回答用）。 */
    val userConfirmed: List<String> = emptyList(),
    /** 外部基准活跃起点（QA fixture 提供 profile 真值；生产传 null → 用窗口前中位数）。 */
    val baselineWakeMinute: Double? = null,
)

data class PersonalAnswer(
    val text: String,
    val evidence: String,
    /**
     * ERA 31 R24：这条回答真实用到的数据源（依据双清单诚实化——Ask ECHO 的「依据」
     * 不再一律说「历史画像」，纠正/上下文/确认类回答如实标注自己用了什么）。
     * 节律/画像族默认 PORTRAIT_HISTORY；上下文/纠正/确认族覆盖为真实来源。
     */
    val usedSources: List<DataSourceCategory> = listOf(DataSourceCategory.PORTRAIT_HISTORY),
)

object PersonalAnswerEngine {

    /** 问题 → 回答族（严格表；未收录 → null，诚实交回 AI 路径）。 */
    private enum class Family {
        DRIFTING_LATER, END_DRIFT, WEEKEND, SIMILAR_DAYS, STABILITY, MONTH_VS_MONTH,
        SCREEN_MONTH_DELTA, WEEK_SUMMARY, WHY_TODAY, WHY_TODAY_FRAGMENTED, TRAVEL_CONTEXT,
        HALF_YEAR, CORRECTIONS_RECALL, CONFIRMED_RECALL, CONFIRMED_WEEKEND_CHECK,
    }

    /** 确定性覆盖表：canonical 问法 + Core Set 变体（同一族不同窗口）。 */
    private val RAW_FAMILY_TABLE: Map<String, Pair<Family, Int>> = mapOf(
        // 节律漂移（window = 比较窗口天数）
        "最近我是不是越来越晚？" to (Family.DRIFTING_LATER to 90),
        "最近一个月我明显变晚了吗？" to (Family.DRIFTING_LATER to 60),
        "我最近是不是开始得更早了？" to (Family.DRIFTING_LATER to 90),
        "我的起床时间在慢慢后移吗？" to (Family.DRIFTING_LATER to 90),
        // 晚上结束时间趋势（独立族：证据必须说结束时间，不能说起点）
        "这段时间我的晚上结束时间有什么趋势？" to (Family.END_DRIFT to 90),
        // 周末 vs 工作日
        "周末和平时有什么变化？" to (Family.WEEKEND to 56),
        "工作日和周末我的节奏差多少？" to (Family.WEEKEND to 56),
        "我的周末和工作日像两个人吗？" to (Family.WEEKEND to 56),
        "周末我的屏幕时间和工作日差多少？" to (Family.WEEKEND to 56),
        // 相似日
        "最近哪几天最像今天？" to (Family.SIMILAR_DAYS to 28),
        "今天最像最近什么时候的我？" to (Family.SIMILAR_DAYS to 28),
        "有没有哪几天和今天节奏很像？" to (Family.SIMILAR_DAYS to 28),
        // 稳定性
        "我最近稳定了吗？" to (Family.STABILITY to 14),
        "我最近是不是波动很大？" to (Family.STABILITY to 14),
        "和上个月比，我这个月更规律了吗？" to (Family.STABILITY to 28),
        // 月对比
        "这个月和上个月最大的区别是什么？" to (Family.MONTH_VS_MONTH to 60),
        "最近两个月我最大的改变是什么？" to (Family.MONTH_VS_MONTH to 60),
        "这个月我比上个月更晚睡了吗？" to (Family.MONTH_VS_MONTH to 60),
        // 月间屏幕差（独立族：无论差多少都给出具体数字）
        "上个月和这个月我的屏幕时间差多少？" to (Family.SCREEN_MONTH_DELTA to 60),
        // 周总结 / 周环比
        "为什么这个星期特别碎？" to (Family.WEEK_SUMMARY to 14),
        "这个星期为什么看起来这么零散？" to (Family.WEEK_SUMMARY to 14),
        "这一周和上一周有什么变化？" to (Family.WEEK_SUMMARY to 14),
        // 今天为什么不一样
        "为什么你觉得今天不一样？" to (Family.WHY_TODAY to 1),
        "今天的状态和平时有什么不同？" to (Family.WHY_TODAY to 1),
        // ERA 31 R17：Ask ECHO 界面建议的视觉问题——今天 ECHO 看起来不一样 = 今天最强的
        // 节律差异维度（Why 层 headline 的解释对象同源），离线确定性即可回答，不再掉进「连接 AI」文案。
        "为什么今天 ECHO 看起来不一样？" to (Family.WHY_TODAY to 1),
        // 今天为什么碎（独立族：只回答碎片化维度，不拿别的维度顶替）
        "今天为什么这么碎？" to (Family.WHY_TODAY_FRAGMENTED to 1),
        // 上下文 / 纠正 / 确认召回
        "我说过最近在出差，这有没有影响？" to (Family.TRAVEL_CONTEXT to 28),
        "我之前跟你说过我在出差，还记得吗？" to (Family.TRAVEL_CONTEXT to 28),
        "我纠正过你的那次，后来你改了吗？" to (Family.CORRECTIONS_RECALL to 1),
        "你还记得我确认过的那些事情吗？" to (Family.CONFIRMED_RECALL to 1),
        "我确认过周末会晚起，你的观察一致吗？" to (Family.CONFIRMED_WEEKEND_CHECK to 56),
        // 半年变化（Day 180 验收）
        "这半年我有什么变化？" to (Family.HALF_YEAR to 180),
        "我的活跃起点和半年前一样吗？" to (Family.HALF_YEAR to 180),
    )

    private val SENTENCE_TRAILERS =
        setOf('吗', '呢', '啊', '呀', '吧', '嘛', '了', '？', '?', '！', '!', '。', '.', '，', ',', '~', '～')

    /** 去首尾空白 + 反复去掉句末语气词/标点（与表键同侧归一）。 */
    private fun normalizeQuestion(question: String): String {
        var normalized = question.trim()
        while (normalized.isNotEmpty() && normalized.last() in SENTENCE_TRAILERS) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    /**
     * ERA 31 R17：句末语气词/标点归一化后建索引——用户微调措辞（加「了」、半角问号、
     * 感叹号、不带标点）也能命中同一族，不再因逐字匹配掉进「换一种问法」兜底。
     * 归一化对键与输入同侧进行；撞车（两个问题归一化后相同）在类初始化时直接失败。
     */
    private val FAMILY_TABLE: Map<String, Pair<Family, Int>> = buildMap {
        for ((question, entry) in RAW_FAMILY_TABLE) {
            check(put(normalizeQuestion(question), entry) == null) { "问题归一化后撞车：$question" }
        }
    }

    fun answer(question: String, inputs: PersonalAnswerInputs): PersonalAnswer? {
        if (inputs.days.isEmpty()) return null
        val (family, window) = FAMILY_TABLE[normalizeQuestion(question)] ?: return null
        val dayIndex = inputs.dayIndex.coerceIn(0, inputs.days.size - 1)
        val safe = inputs.copy(dayIndex = dayIndex)
        return when (family) {
            Family.DRIFTING_LATER -> driftingLater(safe, window)
            Family.END_DRIFT -> endDrift(safe, window)
            Family.WEEKEND -> weekendVsWeekday(safe)
            Family.SIMILAR_DAYS -> mostSimilarDays(safe)
            Family.STABILITY -> stability(safe, window)
            Family.MONTH_VS_MONTH -> monthVsMonth(safe)
            Family.SCREEN_MONTH_DELTA -> screenMonthDelta(safe)
            Family.WEEK_SUMMARY -> weekSummary(safe)
            Family.WHY_TODAY -> whyTodayDifferent(safe, preferFragment = false)
            Family.WHY_TODAY_FRAGMENTED -> whyTodayDifferent(safe, preferFragment = true)
            Family.TRAVEL_CONTEXT -> travelContext(safe)
            Family.HALF_YEAR -> halfYearChange(safe)
            Family.CORRECTIONS_RECALL -> correctionsRecall(safe)
            Family.CONFIRMED_RECALL -> confirmedRecall(safe)
            Family.CONFIRMED_WEEKEND_CHECK -> confirmedWeekendCheck(safe)
        }
    }

    // ===== 数据工具 =====

    private fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private fun medianOf(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun minuteText(minute: Double): String {
        val total = minute.roundToInt().mod(1440)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun wakeSeries(days: List<PersonalDayFacts>, range: IntRange): List<Double> =
        range.mapNotNull { days.getOrNull(it)?.activeStartMinute?.toDouble() }

    // ===== 回答族 =====

    /** 最近是不是越来越晚（两半窗口中位数对比 + 绝对分钟阈值）。 */
    private fun driftingLater(inputs: PersonalAnswerInputs, windowDays: Int): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        val from = (dayIndex - windowDays + 1).coerceAtLeast(0)
        val series = wakeSeries(days, from..dayIndex)
        if (series.size < 30) return PersonalAnswer(
            "现在还没攒够能回答这个问题的节奏。",
            "有起点的日子 ${series.size} 天（需要至少一个月）",
        )
        val half = series.size / 2
        val first = medianOf(series.take(half)) ?: return PersonalAnswer("现在还没攒够能回答这个问题的节奏。", "有起点的日子 ${series.size} 天")
        val second = medianOf(series.drop(half)) ?: return PersonalAnswer("现在还没攒够能回答这个问题的节奏。", "有起点的日子 ${series.size} 天")
        val delta = second - first
        return when {
            delta >= 12 -> PersonalAnswer(
                "是的，最近这一个月你明显开始得比前一个月晚。",
                "活跃起点中位数 ${minuteText(first)} → ${minuteText(second)}（后移 ${delta.toInt()} 分钟）",
            )
            delta <= -12 -> PersonalAnswer(
                "没有，反而是更早了。",
                "活跃起点中位数 ${minuteText(first)} → ${minuteText(second)}（提前 ${(-delta).toInt()} 分钟）",
            )
            else -> PersonalAnswer(
                "整体节奏和之前差不多，没有明显的后移。",
                "活跃起点中位数 ${minuteText(first)} → ${minuteText(second)}（差 ${delta.toInt()} 分钟）",
            )
        }
    }

    /** 晚上结束时间趋势（独立族：证据必须说结束时间，不能拿活跃起点顶替）。 */
    private fun endDrift(inputs: PersonalAnswerInputs, windowDays: Int): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        val from = (dayIndex - windowDays + 1).coerceAtLeast(0)
        val series = (from..dayIndex).mapNotNull { days.getOrNull(it)?.activeEndMinute?.toDouble() }
        if (series.size < 30) return PersonalAnswer(
            "现在还没攒够能回答这个问题的节奏。",
            "有结束时间记录的日子 ${series.size} 天（需要至少一个月）",
        )
        val half = series.size / 2
        val first = medianOf(series.take(half)) ?: return PersonalAnswer("现在还没攒够能回答这个问题的节奏。", "有结束时间记录的日子 ${series.size} 天")
        val second = medianOf(series.drop(half)) ?: return PersonalAnswer("现在还没攒够能回答这个问题的节奏。", "有结束时间记录的日子 ${series.size} 天")
        val delta = second - first
        return when {
            delta >= 12 -> PersonalAnswer(
                "你的晚上结束时间在慢慢变晚。",
                "结束时间中位数 ${minuteText(first)} → ${minuteText(second)}（后移 ${delta.toInt()} 分钟）",
            )
            delta <= -12 -> PersonalAnswer(
                "你的晚上结束时间在慢慢变早。",
                "结束时间中位数 ${minuteText(first)} → ${minuteText(second)}（提前 ${(-delta).toInt()} 分钟）",
            )
            else -> PersonalAnswer(
                "晚上结束时间和之前差不多，没有明显趋势。",
                "结束时间中位数 ${minuteText(first)} → ${minuteText(second)}（差 ${delta.toInt()} 分钟）",
            )
        }
    }

    /** 周末 vs 工作日（起床 + 屏幕两条线；样本不足诚实说）。 */
    private fun weekendVsWeekday(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val from = (inputs.dayIndex - 55).coerceAtLeast(0)
        val window = days.subList(from, inputs.dayIndex + 1)
        val valid = window.filter { it.activeStartMinute != null }
        val weekday = valid.filter { !isWeekend(it.date) }.mapNotNull { it.activeStartMinute?.toDouble() }
        val weekend = valid.filter { isWeekend(it.date) }.mapNotNull { it.activeStartMinute?.toDouble() }
        if (weekday.size < 5 || weekend.size < 3) return PersonalAnswer(
            "目前积累的周末数据还不多，我先把工作日的样子看清楚。",
            "工作日 ${weekday.size} 天 / 周末 ${weekend.size} 天",
        )
        val delta = medianOf(weekend)!! - medianOf(weekday)!!
        val screenWeekday = medianOf(valid.filter { !isWeekend(it.date) }.map { it.screenOnMinutes })!!
        val screenWeekend = medianOf(valid.filter { isWeekend(it.date) }.map { it.screenOnMinutes })!!
        val wakeLine = if (abs(delta) >= 15) {
            val dir = if (delta > 0) "晚起 ${delta.toInt()} 分钟" else "早起 ${(-delta).toInt()} 分钟"
            "周末比工作日$dir"
        } else {
            "周末和工作日的起床时间很接近"
        }
        val screenLine = if (abs(screenWeekend - screenWeekday) >= 30) {
            val dir = if (screenWeekend > screenWeekday) "多 ${(screenWeekend - screenWeekday).toInt()} 分钟"
            else "少 ${(screenWeekday - screenWeekend).toInt()} 分钟"
            "周末屏幕时间$dir"
        } else {
            null
        }
        return PersonalAnswer(
            listOfNotNull(wakeLine, screenLine).joinToString("；") + "。",
            "工作日起床 ${minuteText(medianOf(weekday)!!)} · 周末 ${minuteText(medianOf(weekend)!!)}" +
                "；工作日屏幕 ${screenWeekday.toInt()} 分钟 · 周末 ${screenWeekend.toInt()} 分钟",
        )
    }

    /** 最近哪几天最像今天（画像维度 z 距离最小的 3 天）。 */
    private fun mostSimilarDays(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val today = days.getOrNull(inputs.dayIndex) ?: return PersonalAnswer("今天的有效数据还不多，先不比较。", "无有效画像")
        if (!today.hasPortrait || today.dimensions.none { it.key != "STABILITY" }) {
            return PersonalAnswer("今天的有效数据还不多，先不比较。", "无有效画像")
        }
        val from = (inputs.dayIndex - 28).coerceAtLeast(0)
        val scored = (from until inputs.dayIndex).mapNotNull { d ->
            val other = days.getOrNull(d) ?: return@mapNotNull null
            if (!other.hasPortrait) return@mapNotNull null
            val zSum = today.dimensions.entries.filter { it.key != "STABILITY" }.sumOf { (k, v) ->
                val z1 = v.z ?: 0.0
                val z2 = other.dimensions[k]?.z ?: 0.0
                abs(z1 - z2)
            }
            if (zSum == 0.0 && other.dimensions.none { it.key != "STABILITY" }) null else d to zSum
        }.sortedBy { it.second }.take(3)
        if (scored.isEmpty()) return PersonalAnswer("最近没有足够相似的日子可比。", "无候选")
        return PersonalAnswer(
            "最像的是 ${scored.joinToString("、") { days[it.first].date.toString().takeLast(5) }}。",
            scored.joinToString(" · ") { "${days[it.first].date.toString().takeLast(5)}（z 距离 ${"%.2f".format(it.second)}）" },
        )
    }

    /** 最近稳定了吗（近 window 天 STABILITY 占比 + 季节漂移补充）。 */
    private fun stability(inputs: PersonalAnswerInputs, windowDays: Int): PersonalAnswer {
        val days = inputs.days
        val from = (inputs.dayIndex - windowDays + 1).coerceAtLeast(0)
        val statuses = (from..inputs.dayIndex).mapNotNull { days.getOrNull(it)?.dimensions?.get("STABILITY")?.value }
        if (statuses.size < 7) return PersonalAnswer("最近有效天数还不足一周，再攒几天才能说。", "有效画像 ${statuses.size} 天")
        val similar = statuses.count { it == "VERY_SIMILAR" || it == "SLIGHTLY_DIFFERENT" }
        val ratio = similar.toDouble() / statuses.size
        val driftLine = if (inputs.seasonDrift > 0.3f) "不过这段时间的整体节奏有些漂移，我在慢慢观察。" else ""
        // ERA 31 R25：证据不泄露无意义的原始漂移浮点（0.42 对用户不可解读），说人话
        val driftEvidence = if (inputs.seasonDrift > 0.3f) "整体节律有轻微漂移" else "整体节律稳定"
        return PersonalAnswer(
            if (ratio >= 0.7) "最近两周里大多数日子都和你的通常状态接近，算稳定的。$driftLine".trimEnd()
            else "最近两周变化的日子偏多，还在波动中。$driftLine".trimEnd(),
            "近 $windowDays 天：接近通常 ${similar}/${statuses.size} 天 · $driftEvidence",
        )
    }

    /** 月 vs 月（活跃起点 + 屏幕时间两线；变化不足 → 诚实说接近）。 */
    private fun monthVsMonth(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        if (dayIndex < 30) return PersonalAnswer("现在还不满两个月，先继续积累。", "第 $dayIndex 天")
        val lastMonth = dayIndex - 29..dayIndex
        val prevMonth = dayIndex - 59..dayIndex - 30
        val wakeLast = medianOf(wakeSeries(days, lastMonth))
        val wakePrev = medianOf(wakeSeries(days, prevMonth))
        val screenLast = medianOf(lastMonth.mapNotNull { days.getOrNull(it)?.screenOnMinutes })
        val screenPrev = medianOf(prevMonth.mapNotNull { days.getOrNull(it)?.screenOnMinutes })
        val diffs = mutableListOf<String>()
        val ev = mutableListOf<String>()
        wakeLast?.let { a -> wakePrev?.let { b ->
            val delta = a - b
            if (abs(delta) >= 12) {
                diffs += if (delta > 0) "这个月开始得比上个月晚一些" else "这个月开始得比上个月更早"
                ev += "活跃起点 ${minuteText(b)} → ${minuteText(a)}"
            }
        } }
        val sDelta = (screenLast ?: 0.0) - (screenPrev ?: 0.0)
        if (abs(sDelta) >= 30) {
            diffs += if (sDelta > 0) "屏幕时间比上个月多" else "屏幕时间比上个月少"
            ev += "屏幕 ${screenPrev?.toInt()} → ${screenLast?.toInt()} 分钟"
        }
        if (diffs.isEmpty()) return PersonalAnswer(
            "这两个月的节奏很接近，没有明显差别。",
            "活跃起点/屏幕时间的月间差都在日常波动范围内",
        )
        return PersonalAnswer(diffs.joinToString("；") + "。", ev.joinToString(" · "))
    }

    /** 月间屏幕差（独立族：问题要数字，回答必须给数字——无论差多少）。 */
    private fun screenMonthDelta(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        if (dayIndex < 30) return PersonalAnswer("现在还不满两个月，先继续积累。", "第 $dayIndex 天")
        val lastMonth = dayIndex - 29..dayIndex
        val prevMonth = dayIndex - 59..dayIndex - 30
        val screenLast = medianOf(lastMonth.mapNotNull { days.getOrNull(it)?.screenOnMinutes })
        val screenPrev = medianOf(prevMonth.mapNotNull { days.getOrNull(it)?.screenOnMinutes })
        if (screenLast == null || screenPrev == null) {
            return PersonalAnswer("这两个月的屏幕数据还不够完整，先继续积累。", "上月 ${screenPrev?.toInt() ?: "—"} 分钟 · 本月 ${screenLast?.toInt() ?: "—"} 分钟")
        }
        val delta = screenLast - screenPrev
        val statement = when {
            delta >= 30 -> "这个月屏幕时间比上个月多 ${delta.toInt()} 分钟。"
            delta <= -30 -> "这个月屏幕时间比上个月少 ${(-delta).toInt()} 分钟。"
            delta.toInt() == 0 -> "这个月屏幕时间和上个月几乎一样。"
            delta > 0 -> "这个月屏幕时间和上个月差不多（多 ${delta.toInt()} 分钟）。"
            else -> "这个月屏幕时间和上个月差不多（少 ${(-delta).toInt()} 分钟）。"
        }
        return PersonalAnswer(
            statement,
            "上月每天约 ${screenPrev.toInt()} 分钟 · 本月 ${screenLast.toInt()} 分钟",
        )
    }

    /** 周总结 / 周环比（本周 vs 上周：起点/屏幕/碎片度三线，样本不足诚实）。 */
    private fun weekSummary(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        if (dayIndex < 13) return PersonalAnswer("我们认识还不到两周，先继续积累。", "第 $dayIndex 天")
        val thisWeek = dayIndex - 6..dayIndex
        val lastWeek = dayIndex - 13..dayIndex - 7
        val wakeNow = medianOf(thisWeek.mapNotNull { days.getOrNull(it)?.activeStartMinute?.toDouble() })
        val wakePrev = medianOf(lastWeek.mapNotNull { days.getOrNull(it)?.activeStartMinute?.toDouble() })
        val screenNow = medianOf(thisWeek.mapNotNull { days.getOrNull(it)?.screenOnMinutes })
        val screenPrev = medianOf(lastWeek.mapNotNull { days.getOrNull(it)?.screenOnMinutes })
        val fragNow = thisWeek.count { i ->
            days.getOrNull(i)?.dimensions?.get("DAY_STRUCTURE")?.value == "MORE_FRAGMENTED"
        }
        val fragPrev = lastWeek.count { i ->
            days.getOrNull(i)?.dimensions?.get("DAY_STRUCTURE")?.value == "MORE_FRAGMENTED"
        }
        val lines = mutableListOf<String>()
        val ev = mutableListOf<String>()
        wakeNow?.let { a -> wakePrev?.let { b ->
            val delta = a - b
            if (abs(delta) >= 12) {
                lines += if (delta > 0) "这周开始得比上周晚一些" else "这周开始得比上周更早"
                ev += "活跃起点 ${minuteText(b)} → ${minuteText(a)}"
            }
        } }
        val sDelta = (screenNow ?: 0.0) - (screenPrev ?: 0.0)
        if (abs(sDelta) >= 30) {
            lines += if (sDelta > 0) "屏幕时间比上周多" else "屏幕时间比上周少"
            ev += "屏幕 ${screenPrev?.toInt()} → ${screenNow?.toInt()} 分钟"
        }
        if (fragNow > fragPrev) {
            lines += "零散的日子比上周多"
            ev += "零散日 ${fragPrev} → $fragNow 天"
        }
        if (lines.isEmpty()) return PersonalAnswer(
            "这一周和上一周的节奏很接近，没有明显变化。",
            "起点/屏幕/零散日三项周间差都在日常波动内",
        )
        return PersonalAnswer(lines.joinToString("；") + "。", ev.joinToString(" · "))
    }

    /** 为什么今天不一样（今日画像最强差异维度；preferFragment = 问题问「碎」时只回答碎片化维度）。 */
    private fun whyTodayDifferent(inputs: PersonalAnswerInputs, preferFragment: Boolean): PersonalAnswer {
        val today = inputs.days.getOrNull(inputs.dayIndex) ?: return PersonalAnswer(
            "今天的有效数据还不多，我没有说今天不一样。",
            "无有效画像",
        )
        if (!today.hasPortrait) return PersonalAnswer("今天的有效数据还不多，我没有说今天不一样。", "无有效画像")
        val nonSim = today.dimensions.filter { it.value.value != "SIMILAR" && it.key != "STABILITY" }
        val diff = if (preferFragment) {
            // 问「碎」→ 只允许 DAY_STRUCTURE 维度作答；今天不碎就诚实说不碎
            val fragment = today.dimensions["DAY_STRUCTURE"]
            if (fragment != null && fragment.value == "MORE_FRAGMENTED") {
                fragment.let { "DAY_STRUCTURE" to it }
            } else {
                null
            }
        } else {
            nonSim.maxByOrNull { abs(it.value.z ?: 0.0) }?.let { it.key to it.value }
        }
        if (diff == null) {
            return if (preferFragment) {
                PersonalAnswer("其实今天不算特别零散，和平时接近。", "整体节律：与平时接近")
            } else {
                PersonalAnswer("其实今天和你的通常节奏很接近，我没觉得特别不一样。", "整体节律：与平时接近")
            }
        }
        val (dim, value) = diff
        val reason = when (dim to value.value) {
            "RHYTHM" to "LATER" -> "主要是开始活跃的时间比平时晚"
            "RHYTHM" to "EARLIER" -> "主要是开始活跃的时间比平时早"
            "MOVEMENT" to "MORE" -> "主要是活动量比平时大"
            "MOVEMENT" to "LESS" -> "主要是活动量比平时小"
            "SCREEN_AMOUNT" to "MORE" -> "主要是屏幕时间比平时长"
            "SCREEN_TIMING" to "LATER" -> "主要是晚间屏幕比平时更晚"
            "DAY_STRUCTURE" to "MORE_FRAGMENTED" -> "主要是一天被切得比较碎"
            else -> "差别来自今天的整体节奏"
        }
        // ERA 31 R25：证据说人话（维度中文名 + 取值中文），不再泄露
        // 「维度 RHYTHM = LATER（z=1.2）」类工程键与 z 分数。
        return PersonalAnswer(
            "$reason。",
            "今天差异最大的维度：${dimensionDisplayName(dim)}（${dimensionValueText(dim, value.value)}）",
        )
    }

    /** 出差上下文：窗口内当前节奏 vs 平时基准（用户自述优先于被动观察）。 */
    private fun travelContext(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        // 只考虑已经开始（fromDay <= 今天）的窗口；未来窗口不参与（快照时点语义）
        val recent = inputs.contextWindows.filter { it.fromDay <= dayIndex }.maxByOrNull { it.fromDay }
        if (recent == null) return PersonalAnswer(
            "我这里没有找到出差相关的上下文，所以暂时没有把它算进去。",
            "无出差窗口记录",
            usedSources = emptyList(),
        )
        val active = dayIndex in recent.fromDay..recent.toDay
        val woke = medianOf(wakeSeries(days, recent.fromDay..minOf(recent.toDay, dayIndex)))
        val base = inputs.baselineWakeMinute
            ?: medianOf(wakeSeries(days, 0..(recent.fromDay - 1).coerceAtLeast(0)))
            ?: return PersonalAnswer(
                "出差窗口前后的节奏数据还不够，先不下结论。",
                "窗口内有效起点 ${woke != null}",
                usedSources = listOf(DataSourceCategory.CONTEXT_EXCEPTIONS),
            )
        val delta = (woke ?: base) - base
        val label = recent.label.ifBlank { "出差" }
        val contextSources = listOf(DataSourceCategory.CONTEXT_EXCEPTIONS, DataSourceCategory.PORTRAIT_HISTORY)
        return when {
            active && abs(delta) >= 45 -> PersonalAnswer(
                "有影响。${label}的这几天，你明显开始了不同的节奏。",
                "$label 窗口内活跃起点约 ${minuteText(woke ?: base)}，平时 ${minuteText(base)}（差 ${delta.toInt()} 分钟）",
                usedSources = contextSources,
            )
            active -> PersonalAnswer(
                "这几天在${label}的窗口里，但我先把它当成你的当前状态，不急着下结论。",
                "${label}窗口 ${days[recent.fromDay].date.toString().takeLast(5)}~${days[recent.toDay.coerceAtMost(days.size - 1)].date.toString().takeLast(5)}",
                usedSources = contextSources,
            )
            else -> PersonalAnswer(
                "最近一次的${label}已经结束了，你的节奏看起来正在回到平时。",
                "上次$label ${days[recent.fromDay].date.toString().takeLast(5)}~${days[recent.toDay.coerceAtMost(days.size - 1)].date.toString().takeLast(5)}",
                usedSources = contextSources,
            )
        }
    }

    /** 这半年我有什么变化（Day 180 验收：工作日中位数半年对比）。 */
    private fun halfYearChange(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val dayIndex = inputs.dayIndex
        if (dayIndex < 60) return PersonalAnswer("我们认识还不到两个月，半年之后再一起看。", "第 $dayIndex 天")
        val early = 0..60
        val late = dayIndex - 59..dayIndex
        val wakeEarly = medianOf(early.filter { !isWeekend(days[it].date) }
            .mapNotNull { days[it].activeStartMinute?.toDouble() })
        val wakeLate = medianOf(late.filter { !isWeekend(days[it].date) }
            .mapNotNull { days[it].activeStartMinute?.toDouble() })
        val screenEarly = medianOf(early.map { days[it].screenOnMinutes })
        val screenLate = medianOf(late.map { days[it].screenOnMinutes })
        val lines = mutableListOf<String>()
        val ev = mutableListOf<String>()
        wakeEarly?.let { a -> wakeLate?.let { b ->
            val delta = b - a
            if (abs(delta) >= 12) {
                lines += if (delta > 0) "你开始活跃的时间比半年前晚了约 ${delta.toInt()} 分钟"
                else "你开始活跃的时间比半年前早了约 ${(-delta).toInt()} 分钟"
                ev += "活跃起点 ${minuteText(a)} → ${minuteText(b)}"
            }
        } }
        val sDelta = (screenLate ?: 0.0) - (screenEarly ?: 0.0)
        if (abs(sDelta) >= 30) {
            lines += if (sDelta > 0) "每天屏幕时间比半年前多了约 ${sDelta.toInt()} 分钟"
            else "每天屏幕时间比半年前少了约 ${(-sDelta).toInt()} 分钟"
            ev += "屏幕 ${screenEarly?.toInt()} → ${screenLate?.toInt()} 分钟"
        }
        if (lines.isEmpty()) return PersonalAnswer(
            "半年前后你的核心节奏非常接近——这是属于你的稳定，不一定是没变化。",
            "活跃起点/屏幕时间的半年差都在日常波动内",
        )
        return PersonalAnswer(lines.joinToString("；") + "。", ev.joinToString(" · "))
    }

    /** 我纠正过你什么（用户纠正内容优先回放；没有 → 诚实）。 */
    private fun correctionsRecall(inputs: PersonalAnswerInputs): PersonalAnswer {
        val corrections = inputs.userCorrections.filter { it.isNotBlank() }
        if (corrections.isEmpty()) return PersonalAnswer(
            "你还没有纠正过我。等你纠正时我会记住，并且以后优先考虑你的说法。",
            "纠正记录 0 条",
            usedSources = emptyList(),
        )
        val recent = corrections.takeLast(3)
        return PersonalAnswer(
            "你纠正过我：${recent.joinToString("；")}。这些我以后都会优先考虑。",
            "纠正记录 ${corrections.size} 条（最近 ${recent.size} 条）",
            usedSources = listOf(DataSourceCategory.USER_CORRECTIONS),
        )
    }

    /** 你还记得我确认过的事情吗（USER_CONFIRMED 回放；没有 → 诚实）。 */
    private fun confirmedRecall(inputs: PersonalAnswerInputs): PersonalAnswer {
        val confirmed = inputs.userConfirmed.filter { it.isNotBlank() }
        if (confirmed.isEmpty()) return PersonalAnswer(
            "你还没有确认过什么。你确认过的事情我会一直记得，并且优先相信你的说法。",
            "确认记录 0 条",
            usedSources = emptyList(),
        )
        val recent = confirmed.takeLast(3)
        return PersonalAnswer(
            "记得。你确认过：${recent.joinToString("；")}。",
            "确认记录 ${confirmed.size} 条（最近 ${recent.size} 条）",
            usedSources = listOf(DataSourceCategory.PREFERENCES),
        )
    }

    /** 我确认过周末会晚起，你的观察一致吗（用户自述 vs 观察对拍；User truth 优先）。 */
    private fun confirmedWeekendCheck(inputs: PersonalAnswerInputs): PersonalAnswer {
        val days = inputs.days
        val from = (inputs.dayIndex - 55).coerceAtLeast(0)
        val window = days.subList(from, inputs.dayIndex + 1)
        val valid = window.filter { it.activeStartMinute != null }
        val weekday = valid.filter { !isWeekend(it.date) }.mapNotNull { it.activeStartMinute?.toDouble() }
        val weekend = valid.filter { isWeekend(it.date) }.mapNotNull { it.activeStartMinute?.toDouble() }
        val bothSources = listOf(DataSourceCategory.PREFERENCES, DataSourceCategory.PORTRAIT_HISTORY)
        if (weekday.size < 5 || weekend.size < 3) {
            return PersonalAnswer(
                "你确认过周末会晚起。目前周末的观察样本还不多，我先把工作日的样子看清楚再对。",
                "工作日 ${weekday.size} 天 / 周末 ${weekend.size} 天",
                usedSources = bothSources,
            )
        }
        val delta = medianOf(weekend)!! - medianOf(weekday)!!
        return if (delta >= 15) {
            PersonalAnswer(
                "一致。你的观察也对：周末确实比工作日晚起约 ${delta.toInt()} 分钟。",
                "工作日起床 ${minuteText(medianOf(weekday)!!)} · 周末 ${minuteText(medianOf(weekend)!!)}",
                usedSources = bothSources,
            )
        } else {
            PersonalAnswer(
                "你确认过周末会晚起。不过最近的观察里，周末和工作日起床时间差得不多（${delta.toInt()} 分钟），我按你的说法继续看。",
                "工作日起床 ${minuteText(medianOf(weekday)!!)} · 周末 ${minuteText(medianOf(weekend)!!)}",
                usedSources = bothSources,
            )
        }
    }
}
