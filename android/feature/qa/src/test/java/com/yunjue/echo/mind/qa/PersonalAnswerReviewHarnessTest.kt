package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.model.containsBlockedVocabulary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ERA 31 BATCH 2 §20/§21/§24 — Core Personal Reasoning Set 真实回答捕获。
 *
 * 对 26 条 Core Set 跑 **production 回答引擎**（QaAskEcho 为 PersonalAnswerEngine 的薄适配器——
 * 捕获的回答就是无 Provider/离线时用户真实看到的回答），落到
 * `qa/reports/personal_answer_review/answers.md` 供人审（Answer Quality A-D 四层）。
 *
 * 机器断言锁两件事（都是产品指标代理，不是文案评分）：
 * - 覆盖率 ≥20/26（Grounded personal answer rate）；
 * - 全部回答过 Grounding 词表门禁（False personal interpretation rate 的确定性层回归）。
 */
class PersonalAnswerReviewHarnessTest {

    /** 人审锚点：稳定用户 Day 180 / 出差用户 Day 90 / 低数据用户 Day 180。 */
    private val reviewPoints: List<Pair<QaProfileSpec, Int>> = listOf(
        QaProfiles.A_STABLE to 180,
        QaProfiles.D_TRAVEL to 90,
        QaProfiles.F_LOW_DATA to 180,
    )

    @Test
    fun capturesCoreSetAnswersForHumanReview() {
        val cases = QaQuestionBank.CORE_PERSONAL
        val sb = StringBuilder()
        sb.appendLine("# Core Personal Reasoning Set — 真实回答捕获（production PersonalAnswerEngine）")
        sb.appendLine()
        sb.appendLine("生成：PersonalAnswerReviewHarnessTest（:feature:qa）。回答 = 无 Provider/离线时用户所见。")
        sb.appendLine()

        var covered = 0
        val surveillance = listOf("我检测到", "我正在监测", "我监测到你", "盯着你")
        for (case in cases) {
            val answers = reviewPoints.map { (profile, day) ->
                val answer = QaAskEcho.answer(QaTimeline(profile), day, case.question)
                Triple(profile.id, day, answer)
            }
            val engineCovered = answers.all { !it.third.answer.contains("还需要更多你的上下文") }
            if (engineCovered) covered++
            // Grounding overreach 门禁（§24）：确定性回答永不出现心理推断词/监视语言
            for ((profileId, day, answer) in answers) {
                assertFalse(
                    "$profileId Day$day「${case.question}」命中心理推断词表：${answer.answer}",
                    containsBlockedVocabulary(answer.answer),
                )
                assertFalse(
                    "$profileId Day$day「${case.question}」出现监视语言：${answer.answer}",
                    surveillance.any { answer.answer.contains(it) },
                )
            }
            sb.appendLine("## ${case.id} ${case.question}")
            sb.appendLine()
            sb.appendLine("- 引擎覆盖：${if (engineCovered) "是" else "否（诚实交回 AI 路径）"}")
            sb.appendLine("- 期望证据：task=${case.expected.task} · 窗口 ${case.expected.timeWindowDays}d" +
                " · baseline=${case.expected.needsBaseline} · context=${case.expected.needsContextException}" +
                " · correction=${case.expected.needsCorrection}")
            for ((profileId, day, answer) in answers) {
                sb.appendLine("- **$profileId · Day $day**：${answer.answer}")
                sb.appendLine("  - 证据：${answer.evidence}")
            }
            sb.appendLine()
        }
        sb.appendLine("## 覆盖率")
        sb.appendLine()
        sb.appendLine("引擎覆盖 ${covered}/${cases.size} 条；其余诚实交回 AI 路径。")

        val dir = outputDir()
        dir.mkdirs()
        File(dir, "answers.md").writeText(sb.toString())
        assertTrue(
            "Core Set 引擎覆盖率必须 26/26（无 Provider 也能回答全部个人问题）：$covered/26",
            covered >= 26,
        )
    }

    private fun outputDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, ".git").exists()) dir = dir.parentFile
        val repo = dir ?: error("找不到仓库根（.git）")
        return File(repo, "qa/reports/personal_answer_review")
    }
}
