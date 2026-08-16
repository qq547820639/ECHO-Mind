package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.resolveSurfaceConfig
import com.yunjue.echo.mind.presencevisual.EchoOrganism
import com.yunjue.echo.mind.visual.motion.MotionPolicy
import com.yunjue.echo.mind.visual.surface.EchoSurface

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

/** 旧 SurfaceMode → 新 EchoSurface（APP/EVIDENCE 私有；锁屏/壁纸/Dream 走各自 Service）。 */
private fun SurfaceMode.toEchoSurface(): EchoSurface = when (this) {
    SurfaceMode.APP -> EchoSurface.APP_PRIVATE
    SurfaceMode.HOME_WALLPAPER -> EchoSurface.WALLPAPER_VISUAL_ONLY
    SurfaceMode.LOCK_SAFE -> EchoSurface.LOCK_PUBLIC_SAFE
    SurfaceMode.DREAM -> EchoSurface.DREAM_AMBIENT
    SurfaceMode.LOW_POWER, SurfaceMode.REDUCED_MOTION -> EchoSurface.APP_PRIVATE
}

/** 旧 SurfaceMode → MotionPolicy（REDUCED_MOTION/LOW_POWER 降级；其余 FULL）。 */
private fun SurfaceMode.toMotionPolicy(motionLevel: PresenceMotionLevel): MotionPolicy = when (this) {
    SurfaceMode.REDUCED_MOTION -> MotionPolicy.REDUCED_MOTION
    SurfaceMode.LOW_POWER -> MotionPolicy.LOW_POWER
    else -> when (motionLevel) {
        PresenceMotionLevel.QUIET -> MotionPolicy.LOW_POWER
        else -> MotionPolicy.FULL
    }
}

/**
 * v3 §9 — EchoVisualSurface：ECHO Scene 的视觉主体（**新版分层 Organism**）。
 *
 * visual-runtime R2 起：内部渲染从旧 EchoLifeField（单环+圆点）切换为
 * core/visual 的 9 层 organism（ambient/membrane/filament/orbital/particle/core/halo/ripple/warm）。
 * 首屏 organism 占比提升（§8：第一眼是 ECHO，不是 Dashboard）。
 * 只渲染 [EchoPresenceState]；不接触 Repository / Provider / DB / Preferences。
 * 注：含无限帧动画，由构造隔离（Robolectric 不适配，设备/CI 覆盖）。
 */
@Composable
fun EchoVisualSurface(
    presence: EchoPresenceState?,
    config: EchoVisualSurfaceConfig,
    modifier: Modifier = Modifier,
) {
    EchoOrganism(
        presence = presence,
        modifier = modifier.fillMaxWidth().height(380.dp),
        surface = config.surface.toEchoSurface(),
        motionPolicy = config.surface.toMotionPolicy(config.motionLevel),
        reducedMotion = config.surface == SurfaceMode.REDUCED_MOTION,
    )
}
