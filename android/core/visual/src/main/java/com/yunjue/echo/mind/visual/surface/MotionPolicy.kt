package com.yunjue.echo.mind.visual.surface

import com.yunjue.echo.mind.visual.render.EchoRenderQuality

/**
 * MotionPolicy — 动效策略（V3 §L/§M；与 Surface/RenderQuality 正交）。
 *
 * - Surface 决定 privacy/allowText/allowEvidence/亮度上限；
 * - **MotionPolicy 决定 velocity/amplitude/parallax**；
 * - RenderQuality 决定 particle/filament 数量与光晕质量。
 * 三者不得混入单一 motionComplexity。
 */
enum class MotionPolicy {
    /** 正常动效。 */
    NORMAL,

    /** 减少动态（无障碍 Reduced Motion：保留轻微呼吸，降低旋转与粒子运动）。 */
    REDUCED,

    /** 安静（低动效偏好；幅度按 motionScaleFor 缩放）。 */
    QUIET,
}

/** MotionPolicy → 运动幅度系数（velocity/amplitude/parallax 联合缩放）。 */
fun motionScaleFor(policy: MotionPolicy): Float = when (policy) {
    MotionPolicy.NORMAL -> 1f
    MotionPolicy.QUIET -> 0.7f
    MotionPolicy.REDUCED -> 0.5f
}

/** MotionPolicy → 编译期 reducedMotion 分支（EchoSceneCompiler 的低动效语义）。 */
fun reducedMotionFor(policy: MotionPolicy): Boolean = policy == MotionPolicy.REDUCED

/** Surface 默认渲染质量（功耗预算属 surface 约束；数量/光晕由 RenderQuality 承载）。 */
fun defaultQualityFor(surface: EchoSurface): EchoRenderQuality = when (surface) {
    EchoSurface.APP_PRIVATE, EchoSurface.APP_EVIDENCE, EchoSurface.JOURNEY_PRIVATE -> EchoRenderQuality.NORMAL
    EchoSurface.WALLPAPER_VISUAL_ONLY, EchoSurface.LOCK_PUBLIC_SAFE, EchoSurface.DREAM_AMBIENT -> EchoRenderQuality.CONSERVE
    EchoSurface.WRIST_PUBLIC_SAFE -> EchoRenderQuality.MINIMAL
}
