package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.TrendNoDataReason
import com.yunjue.echo.mind.journey.TrendUiState
import com.yunjue.echo.mind.journey.journeyNaturalSummary
import com.yunjue.echo.mind.journey.journeyRepresentativeDay
import com.yunjue.echo.mind.journey.trendNoDataReasonText
import com.yunjue.echo.mind.presencevisual.EchoRenderSession
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.batteryOptimizationSettingsIntent
import com.yunjue.echo.mind.ui.formatTimestamp
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * V3 §AK–§AO — Journey 熟悉时间导航重构：
 * - 根布局不再使用共享 Page 容器（其内部是整页纵向滚动 Column）包裹内部列表——
 *   每个尺度恰好一个主纵向滚动容器（§AK）；
 * - DAY = 时间线列表（最新在前，行高 80dp：日期 + 40dp 肖像 + 一行事实摘要；§AL）；
 * - WEEK = 7 天水平条（星期 + 日期 + mini 肖像；§AM）；
 * - MONTH = 真实月历（YearMonth.lengthOfMonth；月前/月后空位惰性不可点；§AN）；
 * - SEASON/YEAR = 按自然月分组列表（§AO）；
 * - 日期锚点一律来自 state（选中日 / 最新数据日）或调用方传入的 today 参数，
 *   组合内部不做 LocalDate.now() 窗口计算（§AI/§AQ）。
 *
 * ERA 32：JourneyScreen 只 collect + 路由 ViewModel；纯渲染在 JourneyScreenContent
 * （state-in / event-out），Compose smoke test 直接注入状态。
 */
@Composable
fun JourneyScreen(
    viewModel: JourneyViewModel,
    onGoToSupport: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    JourneyScreenContent(
        state = state,
        onEvent = viewModel::onEvent,
        feedback = viewModel::feedback,
        onGoToSupport = onGoToSupport,
        today = LocalDate.now(),
    )
}

/**
 * ERA 32 — JourneyScreen 纯状态内容（state-in / event-out）。
 * 只消费 [JourneyUiState]，交互以 [JourneyEvent] 与回调输出；
 * 不持有 ViewModel / Repository / Context 业务编排（Context 仅用于系统设置深链按钮）。
 * [today] 由调用方传入（§AI：组合内部不取 LocalDate.now()）。
 */
@Composable
fun JourneyScreenContent(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
    onGoToSupport: () -> Unit = {},
    today: LocalDate,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "旅程 · 我的时间",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        JourneyScaleSelector(
            selectedScale = state.selectedScale,
            onSelect = { scale -> onEvent(JourneyEvent.SelectScale(scale)) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.trendState) {
                TrendUiState.LOADING -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // §40：quiet loading（无 spinner 主视觉）
                    Text(
                        "正在整理你的时间…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    )
                }
                TrendUiState.PERMISSION_DISABLED -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("被动感知已关闭或权限被撤，无法获取新的旅程数据。")
                    // ERA 32 R26：恢复入口是支持页（数据与感知开关），不是系统设置。
                    OutlinedButton(onClick = onGoToSupport) { Text("前往支持页重新开启") }
                }
                TrendUiState.ERROR -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("旅程加载失败")
                    Button(onClick = { onEvent(JourneyEvent.Refresh) }) { Text("重试") }
                }
                TrendUiState.NO_DATA -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NoDataContent(state = state, onGoToSupport = onGoToSupport)
                }
                TrendUiState.OFFLINE_CACHED -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "当前离线，以下为缓存的旅程。",
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        JourneyScaleContent(state = state, onEvent = onEvent, feedback = feedback, today = today)
                    }
                }
                TrendUiState.FRESH, TrendUiState.PARTIAL ->
                    JourneyScaleContent(state = state, onEvent = onEvent, feedback = feedback, today = today)
            }
        }
        // 契约点 2 固定免责文案（单测锚点）——固定页脚，安静呈现。
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Text(
            TREND_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun NoDataContent(state: JourneyUiState, onGoToSupport: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text(trendNoDataReasonText(state.noDataReason))
    Text("最近成功采集：${formatTimestamp(state.syncStatus.lastCollectedAt)}")
    Text("最近成功同步：${formatTimestamp(state.syncStatus.lastSyncedAt)}")
    when (state.noDataReason) {
        TrendNoDataReason.CLOSED -> OutlinedButton(onClick = onGoToSupport) {
            Text("前往支持页重新开启")
        }
        TrendNoDataReason.SYSTEM_BACKGROUND -> OutlinedButton(onClick = {
            runCatching { context.startActivity(batteryOptimizationSettingsIntent(context)) }
        }) {
            Text("前往系统设置")
        }
        else -> Unit
    }
}

/** V3 §55：时间尺度选择器（日/周/月/季/年）——quiet text tab，不做 FilterChip container。 */
@Composable
private fun JourneyScaleSelector(
    selectedScale: JourneyScale,
    onSelect: (JourneyScale) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("journey_scale_selector")
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        JourneyScale.entries.forEach { scale ->
            val selected = selectedScale == scale
            Column(
                Modifier
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(scale) },
                        role = androidx.compose.ui.semantics.Role.Tab,
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    scaleLabel(scale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.52f),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent,
                        ),
                )
            }
        }
    }
}

/**
 * §AK：每个尺度恰好一个主纵向滚动容器（此处统一 LazyColumn；无 verticalScroll 嵌套）。
 */
@Composable
private fun JourneyScaleContent(
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
            detail = { JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate) },
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
            detail = { JourneyDetailSections(state = state, onEvent = onEvent, feedback = feedback, anchorDate = anchorDate) },
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

/** §AL Day：按时间顺序的时间线列表（最新在前——「找昨天」一屏内）；行高 80dp。 */
@Composable
private fun DayTimeline(
    days: List<JourneyDay>,
    today: LocalDate,
    selectedDate: String?,
    feedback: (String) -> Boolean?,
    onSelectDay: (String) -> Unit,
    detail: @Composable () -> Unit,
) {
    val sorted = remember(days) { days.sortedByDescending { it.date } }
    val todayKey = today.toString()
    // ERA 31 R19：锚定旅程实际最新一天（感知滞后时不渲染空占位的「今天」）
    val currentDate = sorted.firstOrNull { it.date == todayKey }?.date ?: sorted.firstOrNull()?.date
    LazyColumn(
        Modifier.fillMaxSize().testTag("journey_memory_visual"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(sorted.size) { index ->
            val day = sorted[index]
            DayTimelineRow(
                day = day,
                isCurrent = day.date == currentDate,
                isSelected = day.date == selectedDate,
                feedbackMark = feedback(day.date),
                onSelect = onSelectDay,
            )
        }
        item { detail() }
    }
}

@Composable
private fun DayTimelineRow(
    day: JourneyDay,
    isCurrent: Boolean,
    isSelected: Boolean,
    feedbackMark: Boolean?,
    onSelect: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val highlight = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(highlight, shape)
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable { onSelect(day.date) }
            .testTag("journey_day_item")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JourneyPortrait(day = day, size = 40.dp, alpha = 1f)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                journeyDateLabel(day.date),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            )
            val line = day.headline.ifBlank { day.summary }
            if (line.isNotBlank()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (feedbackMark != null) {
                Text(
                    if (feedbackMark) "你觉得像" else "你觉得不太像",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                )
            }
        }
    }
}

/** §AM Week：7 天水平条（星期 + 日期 + mini 肖像；选中 accent ring），叙事在下方。 */
@Composable
private fun WeekScaleContent(
    days: List<JourneyDay>,
    anchorDate: LocalDate,
    today: LocalDate,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
    detail: @Composable () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WeekStrip(
                days = days,
                anchor = anchorDate,
                today = today,
                selectedDate = selectedDate,
                onSelectDay = onSelectDay,
            )
        }
        item { detail() }
    }
}

@Composable
private fun WeekStrip(
    days: List<JourneyDay>,
    anchor: LocalDate,
    today: LocalDate,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
) {
    val weekDays = remember(anchor) { (0 until 7).map { anchor.minusDays((6 - it).toLong()) } }
    val byDate = remember(days) { days.associateBy { it.date } }
    Row(
        Modifier.fillMaxWidth().testTag("journey_week_strip"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weekDays.forEach { day ->
            val journeyDay = byDate[day.toString()]
            val selected = day.toString() == selectedDate
            val isToday = day == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (journeyDay != null) {
                            Modifier.clickable { onSelectDay(day.toString()) }
                        } else {
                            Modifier
                        },
                    )
                    .testTag("journey_week_day")
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    weekDayLabel(day.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (selected || isToday) 1f else 0.52f,
                    ),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .then(
                            if (selected) {
                                Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    JourneyPortrait(day = journeyDay, size = 32.dp, alpha = if (selected) 1f else 0.82f)
                }
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (journeyDay != null) 0.87f else 0.38f,
                    ),
                )
            }
        }
    }
}

/** §AN Month：真实月历——YearMonth.lengthOfMonth()（28/29/30/31）；上/下月导航 + 年月标题。 */
@Composable
private fun MonthCalendar(
    days: List<JourneyDay>,
    anchorDate: LocalDate,
    today: LocalDate,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
    detail: @Composable () -> Unit,
) {
    val anchorYm = remember(anchorDate) { YearMonth.from(anchorDate) }
    val earliestYm = remember(days, anchorYm) {
        days.firstOrNull()?.date
            ?.let { runCatching { YearMonth.from(LocalDate.parse(it)) }.getOrNull() }
            ?: anchorYm
    }
    var monthOffset by remember(anchorYm) { mutableIntStateOf(0) }
    val ym = anchorYm.plusMonths(monthOffset.toLong())
    val canPrevious = ym > earliestYm
    val canNext = ym < anchorYm
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { monthOffset-- },
                    enabled = canPrevious,
                    modifier = Modifier.testTag("journey_month_previous"),
                ) { Text("上个月", style = MaterialTheme.typography.labelMedium) }
                Text(
                    "${ym.year}年${ym.monthValue}月",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("journey_month_title"),
                )
                TextButton(
                    onClick = { monthOffset++ },
                    enabled = canNext,
                    modifier = Modifier.testTag("journey_month_next"),
                ) { Text("下个月", style = MaterialTheme.typography.labelMedium) }
            }
        }
        item {
            MonthGrid(
                days = days,
                yearMonth = ym,
                today = today,
                selectedDate = selectedDate,
                onSelectDay = onSelectDay,
            )
        }
        item { detail() }
    }
}

@Composable
private fun MonthGrid(
    days: List<JourneyDay>,
    yearMonth: YearMonth,
    today: LocalDate,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
) {
    val cells = remember(yearMonth) { journeyMonthGrid(yearMonth) }
    val byDate = remember(days) { days.associateBy { it.date } }
    Column(Modifier.fillMaxWidth().testTag("journey_month_grid")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            MONTH_WEEKDAY_HEADERS.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    val date = cell.date
                    val journeyDay = date?.let { byDate[it.toString()] }
                    val selected = date?.toString() == selectedDate
                    val isToday = date == today
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .then(
                                if (journeyDay != null && date != null) {
                                    Modifier.clickable { onSelectDay(date.toString()) }
                                } else {
                                    Modifier
                                },
                            )
                            .testTag(if (cell.inMonth) "journey_month_day" else "journey_month_blank")
                            .padding(vertical = 2.dp),
                    ) {
                        if (cell.inMonth && date != null) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isToday || selected) FontWeight.SemiBold else null,
                                color = when {
                                    selected || isToday -> MaterialTheme.colorScheme.primary
                                    journeyDay != null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                            Spacer(Modifier.height(2.dp))
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .then(
                                        if (selected) {
                                            Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                JourneyPortrait(day = journeyDay, size = 28.dp, alpha = if (selected) 1f else 0.85f)
                            }
                        }
                        // 月前/月后空位：可见的惰性占位（空、无肖像、不可点）
                    }
                }
            }
        }
    }
}

/** §AO Season/Year：按自然月分组的列表行（月标签 + 聚合肖像 + 一行摘要）。 */
@Composable
private fun SeasonYearMonths(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    monthClickable: Boolean,
    detail: @Composable () -> Unit,
) {
    val months = remember(state.visualDays) { journeyMonthGroups(state.visualDays) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("journey_memory_visual"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(months.size) { index ->
            val group = months[index]
            SeasonMonthRow(
                group = group,
                onClick = if (monthClickable) {
                    {
                        // Year：点月份 → 月尺度并锚定该月（先切尺度再选日，VM 顺序处理）
                        group.days.maxOfOrNull { it.date }?.let { date ->
                            onEvent(JourneyEvent.SelectScale(JourneyScale.MONTH))
                            onEvent(JourneyEvent.SelectDay(date))
                        }
                    }
                } else {
                    null
                },
            )
        }
        item { detail() }
    }
}

@Composable
private fun SeasonMonthRow(group: JourneyMonthGroup, onClick: (() -> Unit)?) {
    val representative = remember(group) { journeyRepresentativeDay(group.days) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                },
            )
            .testTag("journey_month_item")
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JourneyPortrait(day = representative, size = 44.dp, alpha = 1f)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("${group.year}年${group.month}月", style = MaterialTheme.typography.titleSmall)
            val line = representative?.let { it.headline.ifBlank { it.summary } }.orEmpty()
            Text(
                if (line.isBlank()) "${group.days.size} 天记录" else "${group.days.size} 天 · $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 各尺度共用详情层（在各自滚动容器末尾）：长期叙事 → 期间故事 → 季解释/年视图/历史重建 →
 * 「查看依据」Evidence Layer（定量证据只在展开后出现，非第一视觉）。
 */
@Composable
private fun JourneyDetailSections(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
    anchorDate: LocalDate,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val narrative = state.narrative
        if (narrative != null) {
            val portraits = state.timeline.portraits
            Text(
                if (narrative.result.text.isBlank()) journeyNaturalSummary(portraits)
                else narrative.result.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (narrative.result.usedSources.isNotEmpty()) {
                Text(
                    "依据：${narrative.result.usedSources.joinToString("、") { dataSourceLabelForJourney(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (narrative.contextExceptions.isNotEmpty()) {
                Text(
                    "你告诉我的特殊日期：${narrative.contextExceptions.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.periodStory.isNotBlank()) {
            Text(
                state.periodStory,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.monthAgoLines.forEach { line ->
            Text(
                "现在和一个月前：$line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SeasonExplanationSection(lines = state.seasonExplanation)
        YearViewSection(state = state, seed = state.journeySeed)
        HistoricalReconstructionSection(
            day = state.selectedDay,
            canonical = state.selectedCanonical,
            fallbackSeed = state.journeySeed,
            explanation = state.selectedDayExplanation,
        )
        HorizontalDivider()
        TextButton(onClick = { onEvent(JourneyEvent.ToggleEvidence) }) {
            Text(if (state.showEvidence) "收起依据" else "查看依据")
        }
        if (state.showEvidence) {
            JourneyEvidenceView(
                timeline = state.timeline,
                lastCollectionTs = state.syncStatus.lastCollectedAt,
                lastSyncTs = state.syncStatus.lastSyncedAt,
                feedback = feedback,
                anchor = anchorDate,
            )
        }
    }
}

/** 依据数据源 → 用户可读标签（Journey 版；与 Today 的 dataSourceLabel 语义一致）。 */
private fun dataSourceLabelForJourney(category: DataSourceCategory): String = when (category) {
    DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    DataSourceCategory.BASELINE -> "个人基线"
    DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    else -> "其他"
}

private fun scaleLabel(scale: JourneyScale): String = when (scale) {
    JourneyScale.DAY -> "天"
    JourneyScale.WEEK -> "周"
    JourneyScale.MONTH -> "月"
    JourneyScale.SEASON -> "季"
    JourneyScale.YEAR -> "年"
}

// ===== 纯函数（可单测；组合内不做 LocalDate.now() 窗口计算） =====

/** 月历单元：date=null 且 inMonth=false 为月前/月后惰性空位。 */
internal data class JourneyMonthCell(
    val date: LocalDate?,
    val inMonth: Boolean,
)

/**
 * §AN 真实月历网格（周一为首列，表头 一二三四五六日）：
 * leadingBlanks = 该月 1 号前的空位数；天数 = [YearMonth.lengthOfMonth]（28/29/30/31）；
 * 补尾空位使每行恰好 7 列。
 */
internal fun journeyMonthGrid(yearMonth: YearMonth): List<JourneyMonthCell> {
    val leadingBlanks = (yearMonth.atDay(1).dayOfWeek.value + 6) % 7
    val daysInMonth = yearMonth.lengthOfMonth()
    val cells = mutableListOf<JourneyMonthCell>()
    repeat(leadingBlanks) { cells += JourneyMonthCell(date = null, inMonth = false) }
    for (day in 1..daysInMonth) {
        cells += JourneyMonthCell(date = yearMonth.atDay(day), inMonth = true)
    }
    val trailingBlanks = (7 - cells.size % 7) % 7
    repeat(trailingBlanks) { cells += JourneyMonthCell(date = null, inMonth = false) }
    return cells
}

/** §AO 季/年尺度：按自然月分组（旧 → 新）。 */
internal data class JourneyMonthGroup(
    val year: Int,
    val month: Int,
    val days: List<JourneyDay>,
)

internal fun journeyMonthGroups(days: List<JourneyDay>): List<JourneyMonthGroup> =
    days.groupBy { it.date.take(7) }
        .toSortedMap()
        .mapNotNull { (monthKey, monthDays) ->
            runCatching {
                JourneyMonthGroup(
                    year = monthKey.substring(0, 4).toInt(),
                    month = monthKey.substring(5, 7).toInt(),
                    days = monthDays,
                )
            }.getOrNull()
        }

/** §AQ 锚点日：选中日 → 最新数据日 → 调用方传入的 today（组合内不取 now()）。 */
internal fun journeyAnchorDate(state: JourneyUiState, today: LocalDate): LocalDate {
    state.selectedDay?.date?.let { selected ->
        runCatching { LocalDate.parse(selected) }.getOrNull()?.let { return it }
    }
    state.visualDays.lastOrNull()?.date?.let { latest ->
        runCatching { LocalDate.parse(latest) }.getOrNull()?.let { return it }
    }
    return today
}

/** 星期短标签（周一…周日）。 */
internal fun weekDayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** "2026-08-14" → "8月14日 · 周五"（解析失败原样返回）。 */
internal fun journeyDateLabel(date: String): String {
    val localDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return "${localDate.monthValue}月${localDate.dayOfMonth}日 · ${weekDayLabel(localDate.dayOfWeek)}"
}

/** 月历表头（周一为首列）。 */
internal val MONTH_WEEKDAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")

/** Journey canonical 确定性时钟（纳秒；JOURNEY_CANONICAL_TIME_SECONDS 固定相位）。 */
internal val JOURNEY_CANONICAL_NANOS: Long = (JOURNEY_CANONICAL_TIME_SECONDS * 1_000_000_000f).toLong()

/**
 * §AP：Journey mini 肖像经 EchoRendererFacade 低预算 session（journeyThumbnailRequest：
 * tier LEGACY / quality MINIMAL / JOURNEY_PRIVATE）；静态确定性（canonical 时钟，无 ticker）；
 * session remembered per（genome, 尺寸）避免逐帧重算。无 genome = quiet ring（不编造）。
 */
@Composable
internal fun JourneyMiniOrganism(
    genome: EchoVisualGenome?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }.coerceAtLeast(1)
    val session = remember(genome, sizePx) {
        genome?.let { EchoRendererFacade.createSession(EchoRenderSession.journeyThumbnailRequest(it), sizePx, sizePx) }
    }
    val quietRing = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Canvas(modifier.size(size)) {
        val s = session
        if (s == null) {
            // 无数据 = quiet ring（不 X / 不 warning）
            drawCircle(
                color = quietRing,
                radius = this.size.minDimension * 0.30f,
                style = Stroke(width = this.size.minDimension * 0.03f),
            )
        } else {
            drawIntoCanvas { c -> s.draw(c.nativeCanvas, JOURNEY_CANONICAL_NANOS) }
        }
    }
}

/** 单日确定性肖像（production facade；无 genome = quiet ring 占位，不编造）。 */
@Composable
private fun JourneyPortrait(
    day: JourneyDay?,
    size: androidx.compose.ui.unit.Dp,
    alpha: Float,
) {
    JourneyMiniOrganism(genome = day?.genome, size = size, modifier = Modifier.alpha(alpha))
}
