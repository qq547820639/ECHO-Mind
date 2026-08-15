package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.PersonalAnswerEngine
import com.yunjue.echo.mind.intelligence.PersonalAnswerInputs
import com.yunjue.echo.mind.intelligence.PersonalDayFacts
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

/**
 * ERA 31 R32 — 跨表面契约：Ask ECHO 界面建议的问题必须离线可答。
 *
 * `EchoConversationLayer` 的建议文案（「适合问：…」）与 `PersonalAnswerEngine` 的
 * 覆盖表是两个文件两套字符串——若有人改建议文案而不入表（R17 前的真实缺陷：
 * 「为什么今天 ECHO 看起来不一样？」建议即诱饵），免费用户照建议问会掉进兜底文案。
 * 本测试锁死同步契约：UI 建议的每个问法都必须能被引擎确定性回答（含问法归一）。
 * 改 UI 文案时必须同步改本测试（或入表）。
 */
class AskEchoSuggestionContractTest {

    /** EchoConversationLayer 建议文案（一字不差；修改 UI 时同步这里）。 */
    private val UI_SUGGESTED_QUESTIONS = listOf(
        "最近我是不是越来越晚？",
        "为什么今天 ECHO 看起来不一样？",
    )

    private fun minimalInputs(): PersonalAnswerInputs {
        val epoch = LocalDate.parse("2026-01-05")
        val days = (0 until 20).map { i ->
            PersonalDayFacts(
                date = epoch.plusDays(i.toLong()),
                activeStartMinute = if (i % 7 == 0) null else 540,
                activeEndMinute = null,
                screenOnMinutes = 120.0,
                lateScreenMinutes = 20.0,
                activeHourSpread = 0.5,
                movementIndex = 1.0,
                dimensions = emptyMap(),
                hasPortrait = false,
            )
        }
        return PersonalAnswerInputs(
            dayIndex = days.size - 1,
            days = days,
            contextWindows = emptyList(),
            seasonDrift = 0f,
        )
    }

    @Test
    fun uiSuggestedQuestionsAreAnswerableOffline() {
        val inputs = minimalInputs()
        for (question in UI_SUGGESTED_QUESTIONS) {
            assertNotNull(
                "UI 建议的问法必须离线确定性可答（thin-data 诚实回答也算答）：$question",
                PersonalAnswerEngine.answer(question, inputs),
            )
        }
    }
}
