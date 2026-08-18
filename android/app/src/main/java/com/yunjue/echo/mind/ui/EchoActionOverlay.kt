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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.presencevisual.EchoVisualClock
import com.yunjue.echo.mind.ui.echo.components.EchoVisualSurfaceConfig
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
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
    config: EchoVisualSurfaceConfig,
    onDone: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(ACTION_DURATION_SECONDS) }
    var frameTick by remember { mutableIntStateOf(0) }

    // §N：ticker 只请求帧（frameTick 驱动重绘），视觉时间来自 boot-global
    // EchoVisualClock（行动层呼吸覆盖与主 Scene 同一时间基准，不因 overlay 重组重启相位）
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTick++ }
        }
    }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000L)
            remaining--
        }
    }

    val hourOfDay = LocalTime.now().let { it.hour + it.minute / 60f }
    // V3 §H / UX-B5：行动层统一经 EchoRendererFacade.createSession（crop→compute→
    // dispatch 全在会话内；LEGACY 预算），直连 OrganismFrameComputer/drawOrganism
    // 路径已删除。genome 经唯一语义链计算（EchoVisualMapper → VisualGenomeCompiler），
    // 视觉偏好经既有 config 保持；呼吸引导语义保持（BREATHING 更慢更开 / PAUSE 更静），
    // 只调表现参数，不动 identity。T4-P2-3：remember 键（presence/hourOfDay/config/mode）。
    val presenceState = presence ?: EchoPresenceState()
    val request = remember(presenceState, hourOfDay, config, mode) {
        val genome = VisualGenomeCompiler.compile(
            EchoVisualMapper.map(
                presenceState, hourOfDay,
                config.motionLevel, config.nightMode, config.reduceMotion,
            ),
            presenceState.identityGenome,
        ).let { g ->
            when (mode) {
                EchoActionMode.BREATHING -> g.copy(
                    driftRate = (g.driftRate * 0.5f).coerceIn(0f, 1f),
                    coreIntensity = (g.coreIntensity * 1.15f).coerceIn(0f, 1f),
                    // 固定 8s 呼吸引导周期（显式吸/呼）
                    pulseRate = BREATHING_CYCLE_SECONDS,
                )
                EchoActionMode.PAUSE -> g.copy(
                    driftRate = (g.driftRate * 0.3f).coerceIn(0f, 1f),
                )
            }
        }
        EchoRenderRequest(
            genome = genome,
            surface = EchoSurface.APP_PRIVATE,
            motion = if (config.reduceMotion) MotionPolicy.REDUCED else MotionPolicy.NORMAL,
            maturityName = presenceState.maturity.name,
            requestedTier = EchoRenderTier.LEGACY,
        )
    }

    // 吸/呼相位：elapsed 在 8s 周期前半 = 吸气（核心扩大），后半 = 呼气
    val elapsed = (ACTION_DURATION_SECONDS - remaining).toFloat()
    val inhale = elapsed % BREATHING_CYCLE_SECONDS < BREATHING_CYCLE_SECONDS / 2f

    // 尺寸经 onSizeChanged 回传（DrawScope 非组合上下文，remember 须在 @Composable 层）
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val session = remember(request, canvasSize) {
        if (canvasSize.width < 1 || canvasSize.height < 1) null
        else EchoRendererFacade.createSession(request, canvasSize.width, canvasSize.height)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
                frameTick // 读取以建立逐帧重绘依赖（视觉时间由会话内 EchoVisualClock 承载）
                drawIntoCanvas { c ->
                    session?.draw(c.nativeCanvas, EchoVisualClock.nowNanos())
                }
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
