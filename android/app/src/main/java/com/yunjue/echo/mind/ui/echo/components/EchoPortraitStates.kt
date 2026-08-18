package com.yunjue.echo.mind.ui.echo.components

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

/**
 * v3 §9 — EchoPortraitStates：画像九态的子组件（解锁仪式）。
 * ERA 31 R28：BaselineProgress（X/7 进度条）与 CoverageRow（今日已学习 NN%）
 * 已从 Scene 移除（§9/§13：第一视觉是 ECHO 不是仪表盘，禁止进度条）；
 * SeedPortraitBlock / PortraitSummaryOnly 亦为生产死代码（Scene 九态收敛后无调用），随删。
 * 只渲染 [PortraitUiState]；状态文案一律来自 model 层锚点。
 */

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

