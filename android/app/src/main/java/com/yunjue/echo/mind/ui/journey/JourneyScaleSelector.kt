package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyScale

/** V3 §55：时间尺度选择器（日/周/月/季/年）——quiet text tab，不做 FilterChip container。 */
@Composable
internal fun JourneyScaleSelector(
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
                        role = Role.Tab,
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
                            else Color.Transparent,
                        ),
                )
            }
        }
    }
}
