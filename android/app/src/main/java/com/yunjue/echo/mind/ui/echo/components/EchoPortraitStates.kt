package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.PORTRAIT_COPY_BASELINE_UNLOCKED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.baselineProgressText
import com.yunjue.echo.mind.model.todayCoveragePercent
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_BODY
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_TITLE
import com.yunjue.echo.mind.presence.presenceSeedRuntimeText

/**
 * v3 §9 — EchoPortraitStates：画像九态的子组件（SEED 画报/基线进度/覆盖度/解锁仪式）。
 * 只渲染 [PortraitUiState]；状态文案一律来自 model 层锚点。
 */

/** Day-0 SEED ECHO：存在与陪伴的表达，不伪造个性判断。 */
@Composable
fun SeedPortraitBlock(awakenedAtEpochMs: Long, state: PortraitUiState) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(PRESENCE_COPY_SEED_TITLE, style = MaterialTheme.typography.headlineMedium)
        Text(PRESENCE_COPY_SEED_BODY, style = MaterialTheme.typography.bodyLarge)
        val factsOnly = state.portrait?.summary
            ?.substringAfter("\n\n", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        if (factsOnly != null) {
            Text(factsOnly, style = MaterialTheme.typography.bodyMedium)
        }
        val observedMinutes = awakenedAtEpochMs
            .takeIf { it > 0L }
            ?.let { (System.currentTimeMillis() - it) / 60_000L }
        if (observedMinutes != null) {
            Text(
                presenceSeedRuntimeText(observedMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 基线积累进度（X/7 + 进度条）。 */
@Composable
fun BaselineProgress(baselineDays: Int) {
    val progress = baselineDays.coerceIn(0, 7) / 7f
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(baselineProgressText(baselineDays), style = MaterialTheme.typography.bodySmall)
    }
}

/** 今日数据覆盖率（无数据/非法 → 不渲染）。 */
@Composable
fun CoverageRow(coverage: Map<String, Any>?) {
    val pct = todayCoveragePercent(coverage) ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("今日已学习", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.weight(1f)
        )
        Text("$pct%", style = MaterialTheme.typography.bodySmall)
    }
}

/** 基线解锁仪式（首次 READY 只出现一次）。 */
@Composable
fun UnlockBanner(consumeUnlocked: () -> Boolean, state: PortraitUiState) {
    if ((state.portrait?.baselineDays ?: 0) < 7) return
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status == PortraitStatus.READY && consumeUnlocked()) {
            show = true
        }
    }
    if (show) {
        Card(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                PORTRAIT_COPY_BASELINE_UNLOCKED,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** EARLY_BASELINE / LOW_CONFIDENCE：仅渲染当天事实 summary。 */
@Composable
fun PortraitSummaryOnly(state: PortraitUiState) {
    val portrait = state.portrait ?: return
    Text(portrait.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp))
}
