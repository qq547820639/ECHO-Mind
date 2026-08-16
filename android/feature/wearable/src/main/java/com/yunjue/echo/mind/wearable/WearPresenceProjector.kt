package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoPresenceState

/**
 * WearPresenceProjector —— 同一个 ECHO 的腕上投影（SAME ECHO）。
 *
 * 输入：当前 production [EchoPresenceState]（手机单一事实源）。
 * 输出：低维 [WearIdentityProjection] + [WearMomentProjection] + [WearSurfaceParams]。
 *
 * 手环 renderer 不复制完整 Phone renderer，但必须共享：
 * topology family / symmetry / orbit / motion personality / texture family /
 * color family / maturity。surface-specific difference 只包括：
 * geometry simplification / animation budget / privacy / screen shape / power budget。
 *
 * 断连/离线时手环必须继续显示缓存 Identity（本投影是确定性的 Identity 保留依据）。
 */
object WearPresenceProjector {

    /** 腕上运动档位：由运动人格 + 表面约束推导（纯函数，双端一致）。 */
    fun wearMotionLevel(
        motionPersonality: Float,
        lowPower: Boolean,
        reducedMotion: Boolean,
    ): String = when {
        reducedMotion || lowPower || motionPersonality < 0.33f -> MOTION_QUIET
        motionPersonality > 0.67f -> MOTION_LIVELY
        else -> MOTION_DEFAULT
    }

    fun projectIdentity(identity: EchoIdentityGenome): WearIdentityProjection = WearIdentityProjection(
        topology = identity.coreTopology.coerceIn(0f, 1f),
        symmetry = identity.symmetryTendency.coerceIn(0f, 1f),
        orbit = identity.orbitGeometry.coerceIn(0f, 1f),
        motion = identity.motionPersonality.coerceIn(0f, 1f),
        texture = identity.textureFamily.coerceIn(0, 3),
        colorFamily = identity.colorFamily.coerceIn(0, 4),
        accent = identity.accentHue.coerceIn(0f, 1f),
    )

    fun projectMoment(composition: EchoDailyComposition): WearMomentProjection = WearMomentProjection(
        flow = composition.flowSpeed.coerceIn(0f, 1f),
        coherence = composition.coherence.coerceIn(0f, 1f),
        density = composition.particleDensity.coerceIn(0f, 1f),
        turbulence = composition.turbulence.coerceIn(0f, 1f),
        brightness = composition.brightness.coerceIn(0f, 1f),
    )

    fun projectSurface(
        identity: EchoIdentityGenome,
        lowPower: Boolean,
        reducedMotion: Boolean,
        motionSummaryEnabled: Boolean = false,
        hapticsEnabled: Boolean = false,
    ): WearSurfaceParams = WearSurfaceParams(
        motionLevel = wearMotionLevel(identity.motionPersonality, lowPower, reducedMotion),
        lowPower = lowPower,
        reducedMotion = reducedMotion,
        motionSummaryEnabled = motionSummaryEnabled,
        hapticsEnabled = hapticsEnabled,
    )

    /**
     * 完整投影（不含 headline —— headline 由 [WearablePrivacyProjector] 单独克制生成）。
     */
    fun project(
        state: EchoPresenceState,
        lowPower: Boolean = false,
        reducedMotion: Boolean = false,
        motionSummaryEnabled: Boolean = false,
        hapticsEnabled: Boolean = false,
    ): WearProjection = WearProjection(
        maturity = state.maturity.name,
        identity = projectIdentity(state.identityGenome),
        moment = projectMoment(state.dailyComposition),
        surface = projectSurface(state.identityGenome, lowPower, reducedMotion, motionSummaryEnabled, hapticsEnabled),
    )

    const val MOTION_QUIET: String = "QUIET"
    const val MOTION_DEFAULT: String = "DEFAULT"
    const val MOTION_LIVELY: String = "LIVELY"
}

/** 投影结果（与 envelope 分离：envelope 装配由 WearableRuntime + PrivacyProjector 完成）。 */
data class WearProjection(
    val maturity: String,
    val identity: WearIdentityProjection,
    val moment: WearMomentProjection,
    val surface: WearSurfaceParams,
)
