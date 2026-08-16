package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.resolveSurfaceConfig
import com.yunjue.echo.mind.presencevisual.EchoOrganism
import com.yunjue.echo.mind.presencevisual.EchoRenderEnvironment
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
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

/** 旧 SurfaceMode → 渲染选项降级（V3：运动系数在 EchoSceneCompiler 编译期展开）。 */
private fun SurfaceMode.toRenderOptions(motionLevel: PresenceMotionLevel): OrganismFrameComputer.EchoRenderOptions =
    when (this) {
        SurfaceMode.REDUCED_MOTION -> OrganismFrameComputer.EchoRenderOptions(reducedMotion = true)
        SurfaceMode.LOW_POWER -> OrganismFrameComputer.EchoRenderOptions(lowPower = true)
        else -> when (motionLevel) {
            PresenceMotionLevel.QUIET -> OrganismFrameComputer.EchoRenderOptions(lowPower = true)
            else -> OrganismFrameComputer.EchoRenderOptions()
        }
    }

/**
 * v3 §9 — EchoVisualSurface：ECHO Scene 的视觉主体（V3 分层拓扑 Organism）。
 *
 * visual-runtime R2 起：内部渲染切换到 core/visual organism；
 * V3 R4 起为 3D 拓扑（结构环/长丝/碎片 + Fibonacci 粒子 + 空心核）；
 * V3 R6 起 tier/quality 由 EchoRenderEnvironment 按真实设备解析（AGSL/Canvas 自动后端）。
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val envOptions = remember(context) {
        OrganismFrameComputer.EchoRenderOptions(
            tier = EchoRenderEnvironment.resolveTier(),
            quality = EchoRenderEnvironment.currentQuality(context),
        )
    }
    val base = config.surface.toRenderOptions(config.motionLevel)
    EchoOrganism(
        presence = presence,
        modifier = modifier.fillMaxWidth().height(380.dp),
        surface = config.surface.toEchoSurface(),
        reducedMotion = config.surface == SurfaceMode.REDUCED_MOTION,
        options = base.copy(tier = envOptions.tier, quality = envOptions.quality),
    )
}
