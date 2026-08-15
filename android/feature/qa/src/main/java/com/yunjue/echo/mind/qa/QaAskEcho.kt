package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.PersonalAnswerEngine
import com.yunjue.echo.mind.intelligence.PersonalAnswerInputs
import com.yunjue.echo.mind.intelligence.PersonalContextWindow
import com.yunjue.echo.mind.intelligence.PersonalDayFacts

/**
 * ERA 22 §24/§25（fixture 层先导）+ ERA 31 R3 —— Ask ECHO 确定性回答样本。
 *
 * **ERA 31 R3 起本对象只是 [PersonalAnswerEngine] 的薄适配器**：
 * QA 审阅的回答 = 产品在无 Provider/离线时真实产出的回答（QA 不再重写产品）。
 * 构建输入：QaTimeline 逐日事实 → PersonalAnswerInputs → 引擎 → QaAnswer。
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
        val inputs = inputsFor(timeline, dayIndex)
        val result = PersonalAnswerEngine.answer(question, inputs)
        return result?.let { QaAnswer(question, it.text, it.evidence) }
            ?: QaAnswer(question, "这个问题我还需要更多你的上下文才能回答。", "无相关证据")
    }

    /** QaTimeline → 引擎输入（逐日事实；QA fixture 提供 profile 真值作为基准起点）。 */
    private fun inputsFor(timeline: QaTimeline, dayIndex: Int): PersonalAnswerInputs {
        val days = (0..dayIndex).map { d ->
            val date = timeline.dateOf(d)
            val agg = timeline.aggregate(d)
            val portrait = timeline.portraitFor(d)
            PersonalDayFacts(
                date = date,
                activeStartMinute = agg.activeStartMinute,
                activeEndMinute = agg.activeEndMinute,
                screenOnMinutes = agg.screenOnMinutes,
                lateScreenMinutes = agg.lateScreenMinutes,
                activeHourSpread = agg.activeHourSpread,
                movementIndex = agg.movementIndex,
                dimensions = portrait?.dimensions ?: emptyMap(),
                hasPortrait = portrait != null,
            )
        }
        val windows = timeline.profile.specialWindows.map {
            PersonalContextWindow(fromDay = it.fromDay, toDay = it.toDay, label = it.label)
        }
        return PersonalAnswerInputs(
            dayIndex = dayIndex,
            days = days,
            contextWindows = windows,
            seasonDrift = timeline.snapshotAt(dayIndex).season.drift,
            userCorrections = emptyList(),
            baselineWakeMinute = timeline.profile.wakeMinute.toDouble(),
        )
    }
}
