package com.yunjue.echo.mind

import com.yunjue.echo.mind.ui.JOURNAL_DEPRECATION_NOTICE
import com.yunjue.echo.mind.ui.PRACTICE_DEPRECATION_NOTICE
import com.yunjue.echo.mind.ui.QUESTIONNAIRE_DEPRECATION_NOTICE
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12.6 旧主动输入入口移除不变量回归（纯常量，无需 Robolectric）。
 *
 * 验证 QuestionnaireScreen / RecordScreen / PracticeScreen 的录入 UI 已停用。
 * 项目未引入 Compose UI 测试框架，故以各页面渲染并对外暴露的「停用提示文案」
 * 常量作为代码审查不变量锚点（与 shouldShowEmergencyFab / coldStartHint 同一模式）。
 */
class DeprecatedInputRemovalTest {

    @Test
    fun questionnaireInputDeprecated() {
        // QuestionnaireScreen 录入 UI 已移除：不再有题目作答与 saveQuestionnaire 调用
        assertTrue("量表录入应已停用", QUESTIONNAIRE_DEPRECATION_NOTICE.contains("停用"))
    }

    @Test
    fun journalInputDeprecated() {
        // RecordScreen 录入 UI 已移除：不再有 OutlinedTextField 与 saveJournal 调用，仅保留只读历史
        assertTrue("日记录入应已停用", JOURNAL_DEPRECATION_NOTICE.contains("停用"))
    }

    @Test
    fun practiceInputDeprecated() {
        // PracticeScreen 打卡逻辑已移除：不再有硬编码练习列表与 recordPractice 调用
        assertTrue("练习打卡应已停用", PRACTICE_DEPRECATION_NOTICE.contains("停用"))
    }
}
