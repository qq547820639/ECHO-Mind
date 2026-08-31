package com.yunjue.echo.mind.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GroundingValidator 误伤回归：
 * - 「emo」须用单词边界匹配，避免误伤 automatic/system/emotion 等含子串的词汇。
 */
class GroundingValidatorEmoTest {

    private val emptyEvidence = emptyList<EvidenceItem>()

    private fun validate(text: String) =
        GroundingValidator.validate(text, emptyEvidence, NarrativeFallbackLevel.AI_NARRATIVE)

    @Test
    fun emoAsStandaloneWordIsRejected() {
        val report = validate("今天有点 emo，不想动。")
        assertTrue("standalone emo 应被拒绝", report.problems.isNotEmpty())
    }

    @Test
    fun emoInsideAutomaticIsNotTriggered() {
        // "automatic" 含子串 "emo" 但不应触发禁词
        val report = validate("系统自动执行了自动校正。")
        assertFalse("automatic 不应触发 emo 禁词", report.problems.any { it.contains("emo") })
    }

    @Test
    fun emoInsideSystemIsNotTriggered() {
        val report = validate("系统状态正常。")
        assertFalse("system 不应触发 emo 禁词", report.problems.any { it.contains("emo") })
    }

    @Test
    fun emoInsideEmotionIsNotTriggered() {
        val report = validate("这是对情绪的客观描述。")
        assertFalse("emotion 不应触发 emo 禁词", report.problems.any { it.contains("emo") })
    }

    @Test
    fun chineseBannedWordsStillWork() {
        val report = validate("用户说最近很焦虑。")
        assertTrue("焦虑 应被拒绝", report.problems.isNotEmpty())
    }
}
