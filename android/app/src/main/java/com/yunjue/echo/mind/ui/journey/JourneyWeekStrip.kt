package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyDay
import java.time.LocalDate

/** §AM Week：7 天水平条（星期 + 日期 + mini 肖像；选中 accent ring），叙事在下方。 */
@Composable
internal fun WeekScaleContent(
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
