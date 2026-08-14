package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EchoContextCompiler
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 5：EchoContextCompiler 回归。
 * 剔除禁止数据 + Privacy Budget 截断 + 最小上下文（不是 Maximum context）。
 */
class EchoContextCompilerTest {

    private fun item(category: DataSourceCategory, label: String, text: String) =
        EvidenceItem(category, label, text)

    @Test
    fun prohibitedDataIsExcludedAndReported() {
        val compiled = EchoContextCompiler.compile(
            ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            listOf(
                item(DataSourceCategory.TODAY_AGGREGATE, "今天", "屏幕互动比平常多"),
                item(DataSourceCategory.RAW_AUDIO, "原始音频", "禁止内容"),
                item(DataSourceCategory.BASELINE, "基线", "28 天基线"),
            )
        )
        assertFalse("禁止数据不得进入上下文", compiled.userContent.contains("禁止内容"))
        assertTrue(DataSourceCategory.RAW_AUDIO in compiled.excludedSources)
        assertTrue(DataSourceCategory.TODAY_AGGREGATE in compiled.usedSources)
    }

    @Test
    fun evidenceIsCappedToPrivacyBudget() {
        val many = (1..50).map { item(DataSourceCategory.PORTRAIT_HISTORY, "历史", "第 $it 天") }
        val compiled = EchoContextCompiler.compile(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, many)
        // maxEvidenceItems=40：第 41+ 条被截断
        assertFalse(compiled.userContent.contains("第 50 天"))
        assertTrue(compiled.userContent.contains("第 40 天"))
    }

    @Test
    fun questionIsIncludedForPersonalQuestion() {
        val compiled = EchoContextCompiler.compile(
            ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            listOf(item(DataSourceCategory.BASELINE, "基线", "数据")),
            question = "我最近是不是越来越晚？",
        )
        assertTrue(compiled.userContent.contains("我最近是不是越来越晚？"))
    }

    @Test
    fun structuredTaskIncludesSchemaInstruction() {
        val compiled = EchoContextCompiler.compile(
            ReasoningTaskId.GENERATE_NOW_INTERPRETATION,
            listOf(item(DataSourceCategory.TODAY_AGGREGATE, "今天", "数据")),
        )
        assertTrue(compiled.userContent.contains("JSON"))
    }

    @Test
    fun usedSourcesAreDeduplicated() {
        val compiled = EchoContextCompiler.compile(
            ReasoningTaskId.GENERATE_NOW_INTERPRETATION,
            listOf(
                item(DataSourceCategory.TODAY_AGGREGATE, "a", "1"),
                item(DataSourceCategory.TODAY_AGGREGATE, "b", "2"),
            ),
        )
        assertEquals(listOf(DataSourceCategory.TODAY_AGGREGATE), compiled.usedSources)
    }

    @Test
    fun emptyEvidenceIsHonest() {
        val compiled = EchoContextCompiler.compile(ReasoningTaskId.GENERATE_NOW_INTERPRETATION, emptyList())
        assertTrue(compiled.userContent.contains("没有可用的节律数据"))
    }
}
