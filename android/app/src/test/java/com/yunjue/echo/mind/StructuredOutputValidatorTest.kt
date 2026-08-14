package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.StructuredOutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 5：Structured Output 校验/修复回归（PART 74：validation → repair → retry → fallback）。
 * 词表门禁对 AI 输出同样生效（心理推断词直接否决）。
 */
class StructuredOutputValidatorTest {

    @Test
    fun parsesCleanJson() {
        val parsed = StructuredOutputValidator.parseNowNarrative(
            """{"statement":"今天开始得比平常晚一些。","confidence":0.8,"evidence_count":3}"""
        )
        assertEquals("今天开始得比平常晚一些。", parsed?.statement)
        assertEquals(0.8f, parsed?.confidence ?: -1f, 1e-6f)
        assertEquals(3, parsed?.evidenceCount)
    }

    @Test
    fun repairsJsonWrappedInProse() {
        val parsed = StructuredOutputValidator.parseNowNarrative(
            "好的，根据你的数据，结果如下：{\"statement\":\"今天整体节奏和平时接近。\",\"confidence\":0.9,\"evidence_count\":4}"
        )
        assertEquals("今天整体节奏和平时接近。", parsed?.statement)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(StructuredOutputValidator.parseNowNarrative(null))
        assertNull(StructuredOutputValidator.parseNowNarrative("这不是 JSON"))
        assertNull(StructuredOutputValidator.parseNowNarrative("{\"statement\":\"\"}"))
    }

    @Test
    fun blockedVocabularyRejectsNarrative() {
        // PORTRAIT_CONTRACT_V1_OBSERVATION 门禁对 AI 输出同样生效
        assertFalse(StructuredOutputValidator.isSafeNarrative("你今天可能有些焦虑，节奏变得零散。"))
        assertFalse(StructuredOutputValidator.isSafeNarrative("这段可能压力过大。"))
    }

    @Test
    fun surveillanceLanguageRejectsNarrative() {
        assertFalse(StructuredOutputValidator.isSafeNarrative("我检测到你今天屏幕使用异常。"))
        assertFalse(StructuredOutputValidator.isSafeNarrative("我正在监测你的移动节律。"))
    }

    @Test
    fun overlongStatementRejected() {
        val long = "很" .repeat(300)
        assertFalse(StructuredOutputValidator.isSafeNarrative(long))
    }

    @Test
    fun safeObservationPasses() {
        assertTrue(StructuredOutputValidator.isSafeNarrative("今天开始活跃的时间比平常晚一些，屏幕互动也更零散。"))
    }
}
