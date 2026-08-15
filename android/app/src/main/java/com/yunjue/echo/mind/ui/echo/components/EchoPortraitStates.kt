package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.yunjue.echo.mind.model.PRESENCE_COPY_SEED_BODY
import com.yunjue.echo.mind.model.PRESENCE_COPY_SEED_TITLE
import com.yunjue.echo.mind.model.presenceSeedRuntimeText

/**
 * v3 §9 — EchoPortraitStates：画像九态的子组件（SEED 画报 / 解锁仪式）。
 * ERA 31 R28：BaselineProgress（X/7 进度条）与 CoverageRow（今日已学习 NN%）
 * 已从 Scene 移除（§9/§13：第一视觉是 ECHO 不是仪表盘，禁止进度条）。
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


/**
 * 基线成型提示（首次 READY 只出现一次；ERA 20 §10：不是庆祝横幅，
 * 只是一句安静的过渡表达——ECHO 自然进入「认识通常的你」阶段）。
 */
@Composable
fun UnlockBanner(consumeUnlocked: () -> Boolean, state: PortraitUiState) {
    if (state.portrait?.baselineDays ?: 0 < 7) return
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status == PortraitStatus.READY && consumeUnlocked()) {
            show = true
        }
    }
    if (show) {
        Text(
            PORTRAIT_COPY_BASELINE_UNLOCKED,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** EARLY_BASELINE / LOW_CONFIDENCE：仅渲染当天事实 summary。 */
@Composable
fun PortraitSummaryOnly(state: PortraitUiState) {
    val portrait = state.portrait ?: return
    Text(portrait.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp))
}
