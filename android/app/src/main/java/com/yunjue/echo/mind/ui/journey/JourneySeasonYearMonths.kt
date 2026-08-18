package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.journeyRepresentativeDay

/** §AO Season/Year：按自然月分组的列表行（月标签 + 聚合肖像 + 一行摘要）。 */
@Composable
internal fun SeasonYearMonths(
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
