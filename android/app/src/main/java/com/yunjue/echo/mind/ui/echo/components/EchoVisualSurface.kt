package com.yunjue.echo.mind.ui.echo.components


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.resolveRenderPolicy
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.surface.EchoSurface
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
 * 经 EchoRendererFacade.Organism 渲染（§R 统一收口：tier/quality 由环境快照解析，
 * rememberEchoEnvironment 5s 轮询——quality/tier 变化对 UI 可观察，§U/§BG）。
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
    // §42：Sensing Disabled（USER_PAUSED / NOT_AUTHORIZED）→ genome 拷贝降级
    // （motion ×.30 → drift、detail ×.55 → particle/filament 密度）；identity 保留，
    // 不是 error screen（V3 §R：经 facade request 前应用到 genome 拷贝）。
    val renderGenome = remember(genome, presence?.sensingStatus) {
        val sensingOff = presence?.sensingStatus == SensingRuntimeStatus.USER_PAUSED ||
            presence?.sensingStatus == SensingRuntimeStatus.NOT_AUTHORIZED
        if (genome != null && sensingOff) sensingOffDegradedGenome(genome) else genome
    }
    val description = organismDescriptionFor(presence)
    val g = renderGenome
    if (g == null) {
        // null genome → 静默空画布（不编造状态）；语义描述保留（TalkBack 聚合描述）
        Canvas(modifier.fillMaxSize().semantics { contentDescription = description }) { }
    } else {
        EchoRendererFacade.Organism(
            request = EchoRenderRequest(
                genome = g,
                surface = EchoSurface.APP_PRIVATE,
                motion = policy.motion,
                maturityName = presence?.maturity?.name ?: "SEED",
                // Organism Quality §3/§16：App Home 请求 AGSL 材质（STANDARD tier）——
                // facade 按 capability 解析：API≥33 HW canvas → AGSL；API<33 → CANVAS+reason；
                // 软件 canvas（preview/Robolectric）→ EchoOrganism 内合法降级 Canvas 后端。
                // Wallpaper/Dream 仍钉 LEGACY（电池敏感面，216f3b3 决策不变）。
                requestedTier = com.yunjue.echo.mind.visual.render.EchoRenderTier.STANDARD,
            ),
            modifier = modifier.fillMaxSize(),
            aggregateDescription = description,
            correctionPulseTrigger = correctionPulseTrigger,
        )
    }
}

/** §42：Sensing Disabled 的 genome 级降级（motion ×.30 / detail ×.55；identity 不动）。 */
private fun sensingOffDegradedGenome(genome: EchoVisualGenome): EchoVisualGenome = genome.copy(
    driftRate = (genome.driftRate * 0.30f).coerceIn(0f, 1f),
    particleDensity = (genome.particleDensity * 0.55f).coerceIn(0f, 1f),
    filamentDensity = (genome.filamentDensity * 0.55f).coerceIn(0f, 1f),
)
