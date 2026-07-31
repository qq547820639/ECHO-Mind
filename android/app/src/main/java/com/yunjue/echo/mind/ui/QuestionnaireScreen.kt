package com.yunjue.echo.mind.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.yunjue.echo.mind.data.LocalRepository

/** T12.3：量表录入已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val QUESTIONNAIRE_DEPRECATION_NOTICE = "量表录入已停用，筛查提示改由能力卡片驱动。"

/**
 * T12.3：量表录入 UI 已移除（EchoMindApp 不再导航到此页面）。
 *
 * 原PHQ-9/GAD-9 题目作答与 saveQuestionnaire 调用已删除；筛查提示改由后端下发的
 * Skill 卡片（GET /v1/skills）驱动。保留空壳与原签名以便旧引用可解析。
 */
@Composable
fun QuestionnaireScreen(code: String, repository: LocalRepository, onBack: () -> Unit) {
    Page(if (code == "phq9") "PHQ-9 状态筛查" else "GAD-7 状态筛查") {
        Text(QUESTIONNAIRE_DEPRECATION_NOTICE, style = MaterialTheme.typography.bodyMedium)
        Text("请在「能力」标签查看下发的 Skill 卡片。")
    }
}
