package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.presencevisual.EchoRenderSession
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.visual.model.EchoVisualGenome

/**
 * §AP：Journey mini 肖像经 EchoRendererFacade 低预算 session（journeyThumbnailRequest：
 * tier LEGACY / quality MINIMAL / JOURNEY_PRIVATE）；静态确定性（canonical 时钟，无 ticker）；
 * session remembered per（genome, 尺寸）避免逐帧重算。无 genome = quiet ring（不编造）。
 */
@Composable
internal fun JourneyMiniOrganism(
    genome: EchoVisualGenome?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }.coerceAtLeast(1)
    val session = remember(genome, sizePx) {
        genome?.let { EchoRendererFacade.createSession(EchoRenderSession.journeyThumbnailRequest(it), sizePx, sizePx) }
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
            drawIntoCanvas { c -> s.draw(c.nativeCanvas, JOURNEY_CANONICAL_NANOS) }
        }
    }
}

/** 单日确定性肖像（production facade；无 genome = quiet ring 占位，不编造）。 */
@Composable
internal fun JourneyPortrait(
    day: JourneyDay?,
    size: Dp,
    alpha: Float,
) {
    JourneyMiniOrganism(genome = day?.genome, size = size, modifier = Modifier.alpha(alpha))
}
