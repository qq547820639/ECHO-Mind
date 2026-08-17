package com.yunjue.echo.mind.ui.echo.components


import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.resolveRenderPolicy
import com.yunjue.echo.mind.presencevisual.EchoOrganism
import com.yunjue.echo.mind.presencevisual.EchoRenderEnvironment
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.defaultQualityFor
import com.yunjue.echo.mind.visual.surface.organismDescriptionFor
import java.time.LocalTime

/**
 * ERA 38 — EchoVisualSurface 偏好输入纯函数化：
 * 渲染参数映射（偏好字符串/开关 → 视觉配置）提取为 [echoVisualSurfaceConfig] 纯函数
 * （JVM 可测）；组件只消费 [EchoVisualSurfaceConfig]，不再持有 AppPreferences。
 *
 * V3 §L/§M：SurfaceMode 已删除——reduceMotion/motionLevel/nightMode 直接进入
 * [resolveRenderPolicy]（PresenceRenderPolicy）与 [EchoVisualMapper]；渲染质量由
 * Surface 默认预算（defaultQualityFor）与环境质量取更差者。
 */
data class EchoVisualSurfaceConfig(
    val motionLevel: PresenceMotionLevel,
    val reduceMotion: Boolean,
    val nightMode: Boolean,
)

/** 偏好输入 → 视觉配置纯函数（未知动效等级回退 DEFAULT；减少动画 → reduceMotion）。 */
fun echoVisualSurfaceConfig(
    motionLevelPref: String,
    reduceMotion: Boolean,
    nightMode: Boolean,
): EchoVisualSurfaceConfig {
    // ERA 75：与 Wallpaper/Dream 共用唯一映射真值（resolveRenderPolicy），
    // APP 基底表面 = APP_PRIVATE；语义保持既有契约（未知等级 fail-closed DEFAULT）。
    val policy = resolveRenderPolicy(EchoSurface.APP_PRIVATE, reduceMotion, motionLevelPref, nightMode)
    return EchoVisualSurfaceConfig(
        motionLevel = policy.motionLevel,
        reduceMotion = policy.reduceMotion,
        nightMode = policy.nightMode,
    )
}

/**
 * v3 §9 — EchoVisualSurface：ECHO Scene 的视觉主体（V3 分层拓扑 Organism）。
 *
 * V3 §H：genome 在此经唯一语义链计算（EchoVisualMapper.map → VisualGenomeCompiler.compile），
 * 传入 EchoOrganism（presencevisual 不再解释 Presence）。
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
    correctionPulseTrigger: Int = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val envOptions = remember(context) {
        val q = EchoRenderEnvironment.currentQuality(context)
        OrganismFrameComputer.EchoRenderOptions(
            tier = EchoRenderEnvironment.resolveTier(),
            quality = q,
            // §23：HDR 仅 API34+ 且显示链路真实支持且非降级态；pipeline 绝不依赖 HDR
            hdrEligible = EchoRenderEnvironment.isHdrEligible(context, q),
        )
    }
    val policy = remember(config) {
        resolveRenderPolicy(
            baseSurface = EchoSurface.APP_PRIVATE,
            reduceMotion = config.reduceMotion,
            motionLevelName = config.motionLevel.name,
            nightMode = config.nightMode,
        )
    }
    val hourOfDay = remember(presence?.updatedAt) {
        LocalTime.now().let { it.hour + it.minute / 60f }
    }
    val genome = remember(presence, hourOfDay, config.motionLevel, config.nightMode, config.reduceMotion) {
        presence?.let {
            VisualGenomeCompiler.compile(
                EchoVisualMapper.map(it, hourOfDay, config.motionLevel, config.nightMode, config.reduceMotion),
                it.identityGenome,
            )
        }
    }
    // §42：Sensing Disabled（USER_PAUSED / NOT_AUTHORIZED）→ motion ×.30 / detail ×.55；
    // identity 保留，不是 error screen（V3：从渲染器上移到调用方）。
    val baseOptions = remember(config, presence?.sensingStatus) {
        val sensingOff = presence?.sensingStatus == SensingRuntimeStatus.USER_PAUSED ||
            presence?.sensingStatus == SensingRuntimeStatus.NOT_AUTHORIZED
        OrganismFrameComputer.EchoRenderOptions(
            motionScale = if (sensingOff) 0.30f else 1f,
            detailScale = if (sensingOff) 0.55f else 1f,
        )
    }
    EchoOrganism(
        genome = genome,
        modifier = modifier.fillMaxSize(),
        surface = EchoSurface.APP_PRIVATE,
        motion = policy.motion,
        maturityName = presence?.maturity?.name ?: "SEED",
        aggregateDescription = organismDescriptionFor(presence),
        options = baseOptions.copy(
            tier = envOptions.tier,
            quality = EchoRenderEnvironment.worseOf(
                defaultQualityFor(EchoSurface.APP_PRIVATE), envOptions.quality,
            ),
            hdrEligible = envOptions.hdrEligible,
        ),
        correctionPulseTrigger = correctionPulseTrigger,
    )
}
