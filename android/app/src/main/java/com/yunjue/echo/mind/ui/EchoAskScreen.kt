package com.yunjue.echo.mind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.ui.echo.conversation.EchoConversationLayer
import com.yunjue.echo.mind.ui.echo.components.EchoVisualSurfaceConfig
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface
import java.time.LocalTime

/**
 * V3 §AF — Ask 标准全屏目的地（ECHO tab 内 overlay；替代旧 Ask sheet + 第二个
 * 220dp live ECHO）。标准 Android 范式：TopAppBar + back（BackHandler）；
 * 顶部唯一 48–72dp 低成本 mini ECHO（thumbnail 质量 session）；会话/输入/
 * 依据链接复用 [EchoConversationLayer]。
 *
 * 唯一高成本会话：本屏组合时 home organism 不参与组合（EchoSceneContent if/else）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoAskScreen(
    state: EchoSceneContentState,
    coreActions: EchoSceneCoreActions,
    onBack: () -> Unit,
    miniEcho: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问 ECHO") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize().testTag("echo_ask_screen"),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 唯一 mini ECHO（48–72dp；LEGACY/MINIMAL 低预算 session）
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .heightIn(min = 48.dp, max = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                miniEcho()
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                // §46：small identity glyph 用 identity palette primary（SAME ECHO）
                val identityColor = state.uiState.presence?.identityGenome?.seed?.let { seed ->
                    val p = EchoIdentitySpec.derive(seed).palette.primary
                    Color(ColorSpace.lch(p.l, p.c, p.h))
                } ?: MaterialTheme.colorScheme.primary
                EchoConversationLayer(
                    turns = state.turns,
                    phase = state.phase,
                    onAsk = coreActions.onAsk,
                    onFeedback = coreActions.onConversationFeedback,
                    identityColor = identityColor,
                )
            }
        }
    }
}

/** §AF：mini ECHO 静态确定性时钟（无 ticker；thumbnail 单帧）。 */
private const val ASK_MINI_STATIC_NANOS = 0L

/**
 * §AF — Ask 顶部 mini ECHO：经 [EchoRendererFacade] 低预算 session
 * （journeyThumbnailRequest 同型：tier LEGACY / quality MINIMAL，surface APP_PRIVATE）；
 * 静态确定性渲染（canonical 固定相位，无 ticker）；无 genome = quiet ring（不编造）。
 */
@Composable
fun EchoAskMiniOrganism(
    presence: EchoPresenceState?,
    config: EchoVisualSurfaceConfig,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }.coerceAtLeast(1)
    val hourOfDay = remember(presence?.updatedAt) {
        LocalTime.now().let { it.hour + it.minute / 60f }
    }
    // §H：genome 经唯一语义链计算（EchoVisualMapper → VisualGenomeCompiler）
    val genome = remember(presence, hourOfDay, config) {
        presence?.let {
            VisualGenomeCompiler.compile(
                EchoVisualMapper.map(it, hourOfDay, config.motionLevel, config.nightMode, config.reduceMotion),
                it.identityGenome,
            )
        }
    }
    val session = remember(genome, sizePx) {
        genome?.let {
            EchoRendererFacade.createSession(
                EchoRenderRequest(
                    genome = it,
                    surface = EchoSurface.APP_PRIVATE,
                    maturityName = presence?.maturity?.name ?: "SEED",
                    requestedTier = EchoRenderTier.LEGACY,
                    quality = EchoRenderQuality.MINIMAL,
                ),
                sizePx,
                sizePx,
            )
        }
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
            drawIntoCanvas { c -> s.draw(c.nativeCanvas, ASK_MINI_STATIC_NANOS) }
        }
    }
}
