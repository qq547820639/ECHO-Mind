package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.EchoLifeField

/**
 * v3 §9 — EchoVisualSurface：ECHO Scene 的视觉主体（生命场）。
 * 只渲染 [EchoPresenceState]；不接触 Repository / Provider / DB。
 */
@Composable
fun EchoVisualSurface(
    presence: EchoPresenceState?,
    preferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    val motionLevel = when (preferences.presenceMotionLevel) {
        "QUIET" -> PresenceMotionLevel.QUIET
        "LIVELY" -> PresenceMotionLevel.LIVELY
        else -> PresenceMotionLevel.DEFAULT
    }
    val surface = if (preferences.presenceReduceMotion) SurfaceMode.REDUCED_MOTION else SurfaceMode.APP
    EchoLifeField(
        presence = presence,
        modifier = modifier.fillMaxWidth().height(260.dp),
        surface = surface,
        motionLevel = motionLevel,
        nightMode = preferences.presenceNightMode,
    )
}
