package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.intelligence.GroundingValidator
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 22 §29 — Grounding claim-evidence compatibility eval：
 * 证据只是行为观察（如「屏幕使用晚 40 分钟」）时，
 * 模型不得生成「你最近压力很大」类的状态断言。
 *
 * 同时锚定兼容的正面路径：用户自己说过「压力」→ 允许引用。
 */
class QaGroundingCompatEvalTest {

    private fun observation(text: String) = EvidenceItem(
        category = DataSourceCategory.TODAY_AGGREGATE,
        label = "行为观察",
        text = text,
        id = "obs_1",
        type = "observation",
    )

    private fun userStatement(text: String, type: String = "correction") = EvidenceItem(
        category = if (type == "correction") DataSourceCategory.USER_CORRECTIONS else DataSourceCategory.CONTEXT_EXCEPTIONS,
        label = "你说过",
        text = text,
        id = "mem_1",
        type = type,
    )

    /** §29 例子：证据只是「屏幕使用晚 40 分钟」→ 不得生成「你最近压力很大」。 */
    @Test
    fun screenEvidenceCannotGroundStressClaim() {
        val report = GroundingValidator.validate(
            text = "你最近压力很大。",
            evidence = listOf(observation("屏幕使用比平常晚 40 分钟")),
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertFalse("行为证据不得支撑状态断言", report.passed)
        assertTrue(
            "必须给出 claim-evidence compatibility 问题",
            report.problems.any { it.contains("claim-evidence compatibility") },
        )
    }

    @Test
    fun userStatementGroundsClaim() {
        val report = GroundingValidator.validate(
            text = "你提到最近压力很大，我会把这段时间看得更轻一些。",
            evidence = listOf(userStatement("我最近压力很大，所以节奏乱了。")),
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertTrue("用户自述可支撑同一断言（兼容路径）", report.passed)
    }

    @Test
    fun neutralBehavioralClaimsPassWithoutUserStatement() {
        // 行为语言（晚一些/零散/更少）不需要用户自述支撑
        for (text in listOf(
            "最近你开始活跃的时间比平时晚一些。",
            "今天的行为比较零散。",
            "这个月屏幕时间比上个月多。",
        )) {
            val report = GroundingValidator.validate(
                text = text,
                evidence = listOf(observation("活跃起点 10:14，通常 09:21")),
                fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
            )
            assertTrue("中性行为句必须通过：$text", report.passed)
        }
    }

    @Test
    fun claimWordCoverageMatrix() {
        // 每个断言词都有负例（行为证据）+ 正例（用户自述）
        val claims = listOf("压力", "疲惫", "沮丧", "失眠", "心烦", "崩溃", "心情不好")
        for (word in claims) {
            val negative = GroundingValidator.validate(
                text = "你最近$word。",
                evidence = listOf(observation("屏幕使用比平常晚 40 分钟")),
                fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
            )
            assertFalse("断言「$word」无自述必须失败", negative.passed)
            val positive = GroundingValidator.validate(
                text = "你说最近$word，我会注意把这段时间当作特殊时期。",
                evidence = listOf(userStatement("我最近$word，别把这段时间当常态。")),
                fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
            )
            assertTrue("用户自述「$word」必须通过", positive.passed)
        }
    }
}
