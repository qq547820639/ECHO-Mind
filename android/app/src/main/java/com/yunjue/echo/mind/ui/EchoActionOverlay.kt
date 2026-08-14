package com.yunjue.echo.mind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualParameters
import com.yunjue.echo.mind.presence.NEUTRAL_VISUAL_PARAMS
import com.yunjue.echo.mind.presence.drawEchoFrame
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.computeEchoSceneFrame
import com.yunjue.echo.mind.presence.computeVisualParameters
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * ERA 9 — Scene 内行动（Master Prompt PART 44）：
 * 行动不跳转到不同视觉风格的页面；ECHO Core 自己执行。
 *
 * BREATHING：ECHO 生命场变成呼吸引导（4 秒吸 / 4 秒呼，60 秒）；
 * PAUSE：离开屏幕一分钟（ECHO 安静陪着）；
 * 结束 → 返回 Ambient Scene。
 */
enum class EchoActionMode { BREATHING, PAUSE }

const val ACTION_DURATION_SECONDS = 60
const val BREATHING_CYCLE_SECONDS = 8f // 4s 吸 + 4s 呼

@Composable
fun EchoActionOverlay(
    presence: EchoPresenceState?,
    mode: EchoActionMode,
    onDone: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(ACTION_DURATION_SECONDS) }
    var timeSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeSeconds = (now - start) / 1_000_000_000f }
        }
    }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000L)
            remaining--
        }
    }

    val hourOfDay = LocalTime.now().let { it.hour + it.minute / 60f }
    val baseParams = if (presence != null) {
        computeVisualParameters(presence, hourOfDay, SurfaceMode.APP)
    } else {
        NEUTRAL_VISUAL_PARAMS
    }
    // 呼吸引导：固定 8 秒周期（显式吸/呼），其余视觉参数保持 ECHO 当前状态
    val actionParams: EchoVisualParameters = if (mode == EchoActionMode.BREATHING) {
        baseParams.copy(
            pulsePeriodSeconds = BREATHING_CYCLE_SECONDS,
            flowSpeed = baseParams.flowSpeed * 0.5f,
            coreOpenness = (baseParams.coreOpenness * 1.15f).coerceIn(0f, 1f),
        )
    } else {
        baseParams.copy(flowSpeed = baseParams.flowSpeed * 0.3f)
    }

    // 吸/呼相位：elapsed 在 8s 周期前半 = 吸气（核心扩大），后半 = 呼气
    val elapsed = (ACTION_DURATION_SECONDS - remaining).toFloat()
    val inhale = elapsed % BREATHING_CYCLE_SECONDS < BREATHING_CYCLE_SECONDS / 2f

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val frame = computeEchoSceneFrame(
                    params = actionParams,
                    seed = presence?.identityGenome?.seed ?: 0L,
                    timeSeconds = timeSeconds,
                    width = size.width,
                    height = size.height,
                )
                drawEchoFrame(frame)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
            ) {
                when (mode) {
                    EchoActionMode.BREATHING -> Text(
                        if (inhale) "吸气…" else "呼气…",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    EchoActionMode.PAUSE -> Text(
                        "把屏幕放下，停一分钟。",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                Text("${remaining}s", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onDone) { Text(if (remaining > 0) "结束" else "好了") }
            }
        }
    }
}
