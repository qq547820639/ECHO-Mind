package com.yunjue.echo.mind.ui

/** T12.3：日记录入已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val JOURNAL_DEPRECATION_NOTICE = "日记录入已停用，历史记录只读查看；新增内容请通过被动感知自动生成。"

/** T12.6：量表录入已停用提示文案（QuestionnaireScreen 已删除，常量保留供单测锚点）。 */
internal const val QUESTIONNAIRE_DEPRECATION_NOTICE = "量表录入已停用，筛查提示改由能力卡片驱动。"

/** T12.3：练习打卡已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val PRACTICE_DEPRECATION_NOTICE = "练习打卡已停用，练习改由「能力」标签下发的 Skill 卡片驱动。"
