package com.yunjue.echo.mind.visual.surface

/**
 * Surface — 渲染表面（ECHO_SURFACE_POLICY.md 的唯一事实源）。
 *
 * 每个 surface 决定：允许文字 / 允许证据 / 动效复杂度 / 亮度 / 隐私边界。
 * Renderer 必须拿到**已被 SurfacePolicy 裁剪**的 [EchoVisualSpec]，不得自行判断隐私。
 */
enum class EchoSurface {
    /** 应用内私有（全部文字/证据/context 允许）。 */
    APP_PRIVATE,

    /** 应用内证据页（完整 + 溯源）。 */
    APP_EVIDENCE,

    /** 桌面壁纸（无文字、低功耗、视觉 only）。 */
    WALLPAPER_VISUAL_ONLY,

    /** 锁屏（仅 PUBLIC_SAFE、低动效、无文字）。 */
    LOCK_PUBLIC_SAFE,

    /** Dream/屏保（极少文字、慢呼吸、低暖光）。 */
    DREAM_AMBIENT,

    /** 腕上（PUBLIC_SAFE、极少形状、无 Memory/Journey/Provider）。 */
    WRIST_PUBLIC_SAFE,
}

/** Surface 能力裁剪结果（Renderer 唯一可见的能力集）。 */
data class SurfaceCapabilities(
    /** 是否允许渲染任何文字。 */
    val allowText: Boolean,
    /** 是否允许渲染证据/来源标注。 */
    val allowEvidence: Boolean,
    /** 动效复杂度 0..1（驱动粒子数/filament 数/轨道数上限）。 */
    val motionComplexity: Float,
    /** 亮度上限 0..1。 */
    val maxLuminance: Float,
    /** 是否允许暖金高光（仅 Dream 充电态等极少数场景）。 */
    val allowWarmAccent: Boolean,
)

/** Surface → 能力裁剪（纯函数）。 */
fun capabilitiesFor(surface: EchoSurface): SurfaceCapabilities = when (surface) {
    EchoSurface.APP_PRIVATE -> SurfaceCapabilities(
        allowText = true, allowEvidence = true,
        motionComplexity = 1f, maxLuminance = 1f, allowWarmAccent = false,
    )
    EchoSurface.APP_EVIDENCE -> SurfaceCapabilities(
        allowText = true, allowEvidence = true,
        motionComplexity = 1f, maxLuminance = 1f, allowWarmAccent = false,
    )
    EchoSurface.WALLPAPER_VISUAL_ONLY -> SurfaceCapabilities(
        allowText = false, allowEvidence = false,
        motionComplexity = 0.6f, maxLuminance = 0.7f, allowWarmAccent = false,
    )
    EchoSurface.LOCK_PUBLIC_SAFE -> SurfaceCapabilities(
        allowText = false, allowEvidence = false,
        motionComplexity = 0.35f, maxLuminance = 0.5f, allowWarmAccent = false,
    )
    EchoSurface.DREAM_AMBIENT -> SurfaceCapabilities(
        allowText = true, allowEvidence = false,
        motionComplexity = 0.4f, maxLuminance = 0.55f, allowWarmAccent = true,
    )
    EchoSurface.WRIST_PUBLIC_SAFE -> SurfaceCapabilities(
        allowText = false, allowEvidence = false,
        motionComplexity = 0.25f, maxLuminance = 0.5f, allowWarmAccent = false,
    )
}
