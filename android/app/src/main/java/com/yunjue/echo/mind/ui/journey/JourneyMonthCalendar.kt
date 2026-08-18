package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyDay
import java.time.LocalDate
import java.time.YearMonth

/** §AN Month：真实月历——YearMonth.lengthOfMonth()（28/29/30/31）；上/下月导航 + 年月标题。 */
@Composable
internal fun MonthCalendar(
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
