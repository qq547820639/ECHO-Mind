package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.EchoLifeField
import com.yunjue.echo.mind.presence.resolveSurfaceConfig

/**
 * ERA 38 — EchoVisualSurface 偏好输入纯函数化：
 * 渲染参数映射（偏好字符串/开关 → 视觉配置）提取为 [echoVisualSurfaceConfig] 纯函数
 * （JVM 可测）；组件只消费 [EchoVisualSurfaceConfig]，不再持有 AppPreferences。
 */
data class EchoVisualSurfaceConfig(
    val motionLevel: PresenceMotionLevel,
    val surface: SurfaceMode,
    val nightMode: Boolean,
)

/** 偏好输入 → 视觉配置纯函数（未知动效等级回退 DEFAULT；减少动画 → REDUCED_MOTION）。 */
fun echoVisualSurfaceConfig(
    motionLevelPref: String,
    reduceMotion: Boolean,
    nightMode: Boolean,
): EchoVisualSurfaceConfig {
    // ERA 75：与 Wallpaper/Dream 共用唯一映射真值（resolveSurfaceConfig），
    // APP 基底表面 = APP；语义保持既有契约（未知等级 fail-closed DEFAULT）。
    val config = resolveSurfaceConfig(SurfaceMode.APP, reduceMotion, motionLevelPref, nightMode)
    return EchoVisualSurfaceConfig(
        motionLevel = config.motionLevel,
        surface = config.surface,
        nightMode = config.nightMode,
    )
}

/**
 * v3 §9 — EchoVisualSurface：ECHO Scene 的视觉主体（生命场）。
 * 只渲染 [EchoPresenceState]；不接触 Repository / Provider / DB / Preferences。
 * 注：EchoLifeField 含无限帧动画，由构造隔离（Robolectric 不适配，设备/CI 覆盖）。
 */
@Composable
fun EchoVisualSurface(
    presence: EchoPresenceState?,
    config: EchoVisualSurfaceConfig,
    modifier: Modifier = Modifier,
) {
    EchoLifeField(
        presence = presence,
        modifier = modifier.fillMaxWidth().height(260.dp),
        surface = config.surface,
        motionLevel = config.motionLevel,
        nightMode = config.nightMode,
    )
}
