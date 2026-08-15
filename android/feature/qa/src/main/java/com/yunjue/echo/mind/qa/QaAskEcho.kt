package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.localportrait.isWeekendLocal
import com.yunjue.echo.mind.qa.QaPortraitMirror.minuteText
import kotlin.math.abs

/**
 * ERA 22 §24/§25（fixture 层先导）— Ask ECHO 确定性回答样本。
 *
 * 每条回答 = 证据（时间窗/基线/数字）+ 中性结论；只描述行为，不做心理推断。
 * 目标：让开发者看到「只有这个人的 ECHO 才能回答」的样子；
 * 也是 Batch 2 Personal Question Eval 的 Expected Evidence 种子。
 */
object QaAskEcho {

    data class QaAnswer(
        val question: String,
        val answer: String,
        val evidence: String,
    )

    /** 八条典型个人问题（ERA 22 §24 例子 + Day-180 验收问题）。 */
    val CANONICAL_QUESTIONS: List<String> = listOf(
        "最近我是不是越来越晚？",
        "周末和平时有什么变化？",
        "最近哪几天最像今天？",
        "我最近稳定了吗？",
        "这个月和上个月最大的区别是什么？",
        "为什么你觉得今天不一样？",
        "我说过最近在出差，这有没有影响？",
        "这半年我有什么变化？",
    )

    fun answer(timeline: QaTimeline, dayIndex: Int, question: String): QaAnswer {
        val d = dayIndex
        return when (question) {
            "最近我是不是越来越晚？" -> driftingLater(timeline, d)
            "周末和平时有什么变化？" -> weekendVsWeekday(timeline, d)
            "最近哪几天最像今天？" -> mostSimilarDays(timeline, d)
            "我最近稳定了吗？" -> stabilityQuestion(timeline, d)
            "这个月和上个月最大的区别是什么？" -> monthVsMonth(timeline, d)
            "为什么你觉得今天不一样？" -> whyTodayDifferent(timeline, d)
            "我说过最近在出差，这有没有影响？" -> travelContext(timeline, d)
            "这半年我有什么变化？" -> halfYearChange(timeline, d)
            else -> QaAnswer(question, "这个问题我还需要更多你的上下文才能回答。", "无相关证据")
        }
    }

    private fun medianOf(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun wakeSeries(timeline: QaTimeline, range: IntRange): List<Double> =
        range.mapNotNull { timeline.aggregate(it).activeStartMinute?.toDouble() }

    /**
     * 时间窗：最近 90 天两半比较（含绝对分钟漂移）。
     * 数据守卫：≥30 个有起点的日子才回答「最近是不是越来越晚」——
     * 4-7 天的两半中位数会把周末混入判成漂移（false positive，Restraint 红线）。
     */
    private fun driftingLater(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        val from = (dayIndex - 89).coerceAtLeast(0)
        val series = wakeSeries(timeline, from..dayIndex)
        if (series.size < 30) return QaAnswer(
            "最近我是不是越来越晚？",
            "现在还没攒够能回答这个问题的节奏。",
            "有起点的日子 ${series.size} 天（需要至少一个月）",
        )
        val half = series.size / 2
        val first = medianOf(series.take(half))!!
        val second = medianOf(series.drop(half))!!
        val delta = second - first
        return when {
            delta >= 12 -> QaAnswer(
                "最近我是不是越来越晚？",
                "是的，最近这一个月你明显开始得比前一个月晚。",
                "活跃起点中位数 ${minuteText(first)} → ${minuteText(second)}（后移 ${delta.toInt()} 分钟）",
            )
            delta <= -12 -> QaAnswer(
                "最近我是不是越来越晚？",
                "没有，反而是更早了。",
                "活跃起点中位数 ${minuteText(first)} → ${minuteText(second)}（提前 ${(-delta).toInt()} 分钟）",
            )
            else -> QaAnswer(
                "最近我是不是越来越晚？",
                "整体节奏和之前差不多，没有明显的后移。",
                "活跃起点中位数 ${minuteText(first)} → ${minuteText(second)}（差 ${delta.toInt()} 分钟）",
            )
        }
    }

    private fun weekendVsWeekday(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        val from = (dayIndex - 55).coerceAtLeast(0)
        val days = (from..dayIndex).filter { timeline.aggregate(it).activeStartMinute != null }
        val weekday = days.filter { !isWeekendLocal(timeline.dateOf(it)) }.mapNotNull { timeline.aggregate(it).activeStartMinute?.toDouble() }
        val weekend = days.filter { isWeekendLocal(timeline.dateOf(it)) }.mapNotNull { timeline.aggregate(it).activeStartMinute?.toDouble() }
        if (weekday.size < 5 || weekend.size < 3) return QaAnswer(
            "周末和平时有什么变化？",
            "目前积累的周末数据还不多，我先把工作日的样子看清楚。",
            "工作日 ${weekday.size} 天 / 周末 ${weekend.size} 天",
        )
        val delta = medianOf(weekend)!! - medianOf(weekday)!!
        val screenWeekday = medianOf(days.filter { !isWeekendLocal(timeline.dateOf(it)) }.map { timeline.aggregate(it).screenOnMinutes })!!
        val screenWeekend = medianOf(days.filter { isWeekendLocal(timeline.dateOf(it)) }.map { timeline.aggregate(it).screenOnMinutes })!!
        val wakeLine = if (abs(delta) >= 15) {
            val dir = if (delta > 0) "晚起 ${delta.toInt()} 分钟" else "早起 ${(-delta).toInt()} 分钟"
            "周末比工作日$dir"
        } else "周末和工作日的起床时间很接近"
        val screenLine = if (abs(screenWeekend - screenWeekday) >= 30) {
            val dir = if (screenWeekend > screenWeekday) "多 ${(screenWeekend - screenWeekday).toInt()} 分钟" else "少 ${(screenWeekday - screenWeekend).toInt()} 分钟"
            "屏幕时间$dir"
        } else null
        return QaAnswer(
            "周末和平时有什么变化？",
            listOfNotNull(wakeLine, screenLine?.let { "周末$it" }).joinToString("；") + "。",
            "工作日起床 ${minuteText(medianOf(weekday)!!)} · 周末 ${minuteText(medianOf(weekend)!!)}" +
                "；工作日屏幕 ${screenWeekday.toInt()} 分钟 · 周末 ${screenWeekend.toInt()} 分钟",
        )
    }

    private fun mostSimilarDays(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        val p = timeline.portraitFor(dayIndex)
        if (p == null || p.dimensions.none { it.key != "STABILITY" }) {
            return QaAnswer("最近哪几天最像今天？", "今天的有效数据还不多，先不比较。", "无有效画像")
        }
        val from = (dayIndex - 28).coerceAtLeast(0)
        val scored = (from until dayIndex).mapNotNull { d ->
            val other = timeline.portraitFor(d) ?: return@mapNotNull null
            val zSum = p.dimensions.entries.filter { it.key != "STABILITY" }.sumOf { (k, v) ->
                val z1 = v.z ?: 0.0
                val z2 = other.dimensions[k]?.z ?: 0.0
                abs(z1 - z2)
            }
            if (zSum == 0.0 && other.dimensions.none { it.key != "STABILITY" }) null else d to zSum
        }.sortedBy { it.second }.take(3)
        if (scored.isEmpty()) return QaAnswer("最近哪几天最像今天？", "最近没有足够相似的日子可比。", "无候选")
        return QaAnswer(
            "最近哪几天最像今天？",
            "最像的是 ${scored.joinToString("、") { timeline.dateOf(it.first).toString().takeLast(5) }}。",
            scored.joinToString(" · ") { "${timeline.dateOf(it.first).toString().takeLast(5)}（z 距离 ${"%.2f".format(it.second)}）" },
        )
    }

    private fun stabilityQuestion(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        val from = (dayIndex - 13).coerceAtLeast(0)
        val statuses = (from..dayIndex).mapNotNull { timeline.portraitFor(it)?.dimensionValue("STABILITY") }
        if (statuses.size < 7) return QaAnswer("我最近稳定了吗？", "最近有效天数还不足一周，再攒几天才能说。", "有效画像 ${statuses.size} 天")
        val similar = statuses.count { it == "VERY_SIMILAR" || it == "SLIGHTLY_DIFFERENT" }
        val ratio = similar.toDouble() / statuses.size
        val season = timeline.snapshotAt(dayIndex).season
        // 漂移提示只在真实显著时出现（>0.3），避免「算稳定的」与「有些漂移」自相矛盾
        val driftLine = if (season.drift > 0.3f) "不过这段时间的整体节奏有些漂移，我在慢慢观察。" else ""
        return QaAnswer(
            "我最近稳定了吗？",
            if (ratio >= 0.7) "最近两周里大多数日子都和你的通常状态接近，算稳定的。$driftLine".trimEnd()
            else "最近两周变化的日子偏多，还在波动中。$driftLine".trimEnd(),
            "近 14 天：接近通常 ${similar}/${statuses.size} 天 · 节律漂移 ${"%.2f".format(season.drift)}",
        )
    }

    private fun monthVsMonth(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        if (dayIndex < 30) return QaAnswer("这个月和上个月最大的区别是什么？", "现在还不满两个月，先继续积累。", "第 $dayIndex 天")
        val lastMonth = dayIndex - 29..dayIndex
        val prevMonth = dayIndex - 59..dayIndex - 30
        val wakeLast = medianOf(wakeSeries(timeline, lastMonth))
        val wakePrev = medianOf(wakeSeries(timeline, prevMonth))
        val screenLast = medianOf(lastMonth.map { timeline.aggregate(it).screenOnMinutes })
        val screenPrev = medianOf(prevMonth.map { timeline.aggregate(it).screenOnMinutes })
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
        if (diffs.isEmpty()) return QaAnswer(
            "这个月和上个月最大的区别是什么？",
            "这两个月的节奏很接近，没有明显差别。",
            "活跃起点/屏幕时间的月间差都在日常波动范围内",
        )
        return QaAnswer(
            "这个月和上个月最大的区别是什么？",
            diffs.joinToString("；") + "。",
            ev.joinToString(" · "),
        )
    }

    private fun whyTodayDifferent(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        val p = timeline.portraitFor(dayIndex) ?: return QaAnswer(
            "为什么你觉得今天不一样？",
            "今天的有效数据还不多，我没有说今天不一样。",
            "无有效画像",
        )
        val diff = p.dimensions.filter { it.value.value != "SIMILAR" && it.key != "STABILITY" }
            .maxByOrNull { abs(it.value.z ?: 0.0) }
        if (diff == null) return QaAnswer(
            "为什么你觉得今天不一样？",
            "其实今天和你的通常节奏很接近，我没觉得特别不一样。",
            "STABILITY = ${p.dimensionValue("STABILITY")}",
        )
        val dim = diff.key
        val value = diff.value.value
        val reason = when (dim to value) {
            "RHYTHM" to "LATER" -> "主要是开始活跃的时间比平时晚"
            "RHYTHM" to "EARLIER" -> "主要是开始活跃的时间比平时早"
            "MOVEMENT" to "MORE" -> "主要是活动量比平时大"
            "MOVEMENT" to "LESS" -> "主要是活动量比平时小"
            "SCREEN_AMOUNT" to "MORE" -> "主要是屏幕时间比平时长"
            "SCREEN_TIMING" to "LATER" -> "主要是晚间屏幕比平时更晚"
            "DAY_STRUCTURE" to "MORE_FRAGMENTED" -> "主要是一天被切得比较碎"
            else -> "差别来自今天的整体节奏"
        }
        return QaAnswer("为什么你觉得今天不一样？", "$reason。", "维度 $dim = $value（z=${"%.1f".format(diff.value.z ?: 0.0)}）")
    }

    private fun travelContext(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        val windows = timeline.profile.specialWindows.filter { it.toDay <= dayIndex }
        if (windows.isEmpty()) return QaAnswer(
            "我说过最近在出差，这有没有影响？",
            "我这里没有找到出差相关的上下文，所以暂时没有把它算进去。",
            "无出差窗口记录",
        )
        val recent = windows.last()
        val inWindow = timeline.aggregate(dayIndex).let { agg ->
            agg.activeStartMinute?.let { start ->
                val baseStart = timeline.profile.wakeMinute.toDouble()
                abs(start - baseStart) >= 45
            } == true
        }
        val active = dayIndex in recent.fromDay..recent.toDay
        val woke = medianOf(wakeSeries(timeline, recent.fromDay..minOf(recent.toDay, dayIndex)))
        val base = timeline.profile.wakeMinute.toDouble()
        val delta = (woke ?: base) - base
        return when {
            active && inWindow -> QaAnswer(
                "我说过最近在出差，这有没有影响？",
                "有影响。出差这几天你明显开始得更早，和平时不是一个节奏。",
                "出差窗口（${timeline.dateOf(recent.fromDay).toString().takeLast(5)} 起）：活跃起点约 ${minuteText(woke ?: base)}，比平时早 ${(-delta).toInt()} 分钟",
            )
            active -> QaAnswer(
                "我说过最近在出差，这有没有影响？",
                "这几天在出差窗口里，但我先把它当成你的当前状态，不急着下结论。",
                "出差窗口 ${timeline.dateOf(recent.fromDay).toString().takeLast(5)}~${timeline.dateOf(recent.toDay).toString().takeLast(5)}",
            )
            else -> QaAnswer(
                "我说过最近在出差，这有没有影响？",
                "最近一次出差已经结束了，你的节奏看起来正在回到平时。",
                "上次出差 ${timeline.dateOf(recent.fromDay).toString().takeLast(5)}~${timeline.dateOf(recent.toDay).toString().takeLast(5)}",
            )
        }
    }

    private fun halfYearChange(timeline: QaTimeline, dayIndex: Int): QaAnswer {
        if (dayIndex < 60) return QaAnswer("这半年我有什么变化？", "我们认识还不到两个月，半年之后再一起看。", "第 $dayIndex 天")
        // 60 天窗口 + 仅工作日（排除周末平移噪声）→ 中位数稳健估计半年漂移
        val early = 0..60
        val late = dayIndex - 59..dayIndex
        val wakeEarly = medianOf(early.filter { !isWeekendLocal(timeline.dateOf(it)) }
            .mapNotNull { timeline.aggregate(it).activeStartMinute?.toDouble() })
        val wakeLate = medianOf(late.filter { !isWeekendLocal(timeline.dateOf(it)) }
            .mapNotNull { timeline.aggregate(it).activeStartMinute?.toDouble() })
        val screenEarly = medianOf(early.map { timeline.aggregate(it).screenOnMinutes })
        val screenLate = medianOf(late.map { timeline.aggregate(it).screenOnMinutes })
        val lines = mutableListOf<String>()
        val ev = mutableListOf<String>()
        wakeEarly?.let { a -> wakeLate?.let { b ->
            val delta = b - a
            if (abs(delta) >= 12) {
                lines += if (delta > 0) "你开始活跃的时间比半年前晚了约 ${delta.toInt()} 分钟" else "你开始活跃的时间比半年前早了约 ${(-delta).toInt()} 分钟"
                ev += "活跃起点 ${minuteText(a)} → ${minuteText(b)}"
            }
        } }
        val sDelta = (screenLate ?: 0.0) - (screenEarly ?: 0.0)
        if (abs(sDelta) >= 30) {
            lines += if (sDelta > 0) "每天屏幕时间比半年前多了约 ${sDelta.toInt()} 分钟" else "每天屏幕时间比半年前少了约 ${(-sDelta).toInt()} 分钟"
            ev += "屏幕 ${screenEarly?.toInt()} → ${screenLate?.toInt()} 分钟"
        }
        if (lines.isEmpty()) return QaAnswer(
            "这半年我有什么变化？",
            "半年前后你的核心节奏非常接近——这是属于你的稳定，不一定是没变化。",
            "活跃起点/屏幕时间的半年差都在日常波动内",
        )
        return QaAnswer(
            "这半年我有什么变化？",
            lines.joinToString("；") + "。",
            ev.joinToString(" · "),
        )
    }

}
