package com.yunjue.echo.mind.ui.journey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyUiState
import java.time.LocalDate

/**
 * §AK：每个尺度恰好一个主纵向滚动容器（统一 LazyColumn；无 verticalScroll 嵌套）。
 * 尺度路由（root 只负责 state/navigation，各 period 文件负责 presentation）。
 */
@Composable
internal fun JourneyScaleContent(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
    today: LocalDate,
) {
    val anchorDate = remember(state.visualDays, state.selectedDay, today) {
        journeyAnchorDate(state, today)
    }
    val selectedDate = state.selectedDay?.date
    val onSelectDay: (String) -> Unit = { date -> onEvent(JourneyEvent.SelectDay(date)) }
    when (state.selectedScale) {
        JourneyScale.DAY -> DayTimeline(
            days = state.visualDays,
            today = today,
            selectedDate = selectedDate,
            feedback = feedback,
            onSelectDay = onSelectDay,
            selectedCanonical = state.selectedCanonical,
            selectedDayExplanation = state.selectedDayExplanation,
            detail = { JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate, showDayReconstruction = false) },
        )
        JourneyScale.WEEK -> WeekScaleContent(
            days = state.visualDays,
            anchorDate = anchorDate,
            today = today,
            selectedDate = selectedDate,
            onSelectDay = onSelectDay,
            detail = { JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate) },
        )
        JourneyScale.MONTH -> MonthCalendar(
            days = state.visualDays,
            anchorDate = anchorDate,
            today = today,
            selectedDate = selectedDate,
            onSelectDay = onSelectDay,
            detail = {
                // 设计稿 9「本月画像」卡：真实叙事 + 四维行为趋势（挂载在月历 detail 槽位）
                MonthPortraitCard(state = state)
                JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate)
            },
        )
        // §AO：SEASON/YEAR = 按自然月分组列表（river/constellation 不再是导航模型）
        JourneyScale.SEASON -> SeasonYearMonths(
            state = state,
            onEvent = onEvent,
            monthClickable = false,
            detail = { JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate) },
        )
        JourneyScale.YEAR -> SeasonYearMonths(
            state = state,
            onEvent = onEvent,
            monthClickable = true,
            detail = { JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate) },
        )
    }
}

/**
 * 设计稿 9「本月画像」卡：真实叙事（state.narrative）+ 四维行为趋势
 * （buildMonthTrendSeries，全缺数据时组件内弃权渲染）。
 */
@Composable
private fun MonthPortraitCard(state: JourneyUiState) {
    val series = remember(state.timeline.portraits) { buildMonthTrendSeries(state.timeline.portraits) }
    val narrativeLines = remember(state.narrative) {
        listOfNotNull(state.narrative?.result?.text?.takeIf { it.isNotBlank() })
    }
    JourneyMonthTrendChart(
        dimensions = series.dimensions,
        isEmptyData = series.isEmptyData,
        narrativeLines = narrativeLines,
        xLabels = series.xLabels,
    )
}
