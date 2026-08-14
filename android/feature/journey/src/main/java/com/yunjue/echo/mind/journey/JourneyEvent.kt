package com.yunjue.echo.mind.journey

/**
 * ERA 13 §25 — JourneyEvent：Screen → ViewModel 的唯一交互面。
 *
 * SelectDay / SelectPeriod 属 ERA 16（周期选择交互），当前 UI 无对应入口，不预置死事件。
 */
sealed interface JourneyEvent {
    /** 切换时间尺度（Day/Week/Month/Season/Year）。 */
    data class SelectScale(val scale: JourneyScale) : JourneyEvent

    /** 全量刷新（画像窗口 + 运行时快照 + 叙事）。 */
    data object Refresh : JourneyEvent

    /** 展开/收起「查看依据」Evidence Layer。 */
    data object ToggleEvidence : JourneyEvent

    /** 就该周期提问（重新生成长期叙事）。 */
    data object AskAboutPeriod : JourneyEvent

    /** AI 叙事失败后重试。 */
    data object RetryNarrative : JourneyEvent
}
