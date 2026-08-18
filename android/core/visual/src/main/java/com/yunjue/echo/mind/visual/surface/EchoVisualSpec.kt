package com.yunjue.echo.mind.visual.surface

import com.yunjue.echo.mind.visual.model.EchoVisualGenome

/**
 * EchoVisualSpec — 已按 SurfacePolicy 安全裁剪的渲染输入。
 *
 * Renderer 只消费本对象 + 时间 + 视口；**隐私裁剪已在 SurfacePolicy 完成**，
 * renderer 不再接触任何原始 presence/observation 字段，从架构上杜绝隐私泄露散落。
 */
data class EchoVisualSpec(
    /** genome（identity 字段永不被裁剪）。 */
    val genome: EchoVisualGenome,
    /** surface 能力（renderer 据此决定是否画文字/证据/暖高光）。 */
    val capabilities: SurfaceCapabilities,
    /** 渲染时的确定性时间基准（秒；测试注入固定值，运行时注入墙钟——仅限 canonical 小时间锚）。 */
    val clockSeconds: Float,
    /** 目标 surface（V3 SceneCompiler 需要真实 surface，禁止从 capabilities 反推）。 */
    val surface: EchoSurface = EchoSurface.APP_PRIVATE,
    /**
     * §N Long-nanos 时间基准（boot-global；生产运动求值唯一时间源——
     * Float 秒在大 uptime 下 ulp 超过帧间隔，相位精度只能靠 Long 保持）。
     * 缺省由 clockSeconds 派生（canonical/Journey 固定小时间锚无损）。
     */
    val clockNanos: Long = (clockSeconds * 1_000_000_000f).toLong(),
)

/**
 * SurfacePolicy — 把 genome 按 surface 能力裁剪成 spec（纯函数）。
 *
 * V3 §M：Surface 只裁剪 privacy 约束（亮度上限等表现约束）；动效幅度由 MotionPolicy
 * （motionScale/reducedMotion）承载，粒子/filament 数量由 RenderQuality 承载。
 * 铁律：identity 相关字段（identitySeed/identityTopology/identityPhase/spectralBias）
 * **永不缩放**，保证 SAME ECHO。
 */
object SurfacePolicy {
    fun crop(genome: EchoVisualGenome, surface: EchoSurface, clockSeconds: Float): EchoVisualSpec {
        val cap = capabilitiesFor(surface)
        val scaled = genome.copy(
            luminance = (genome.luminance * cap.maxLuminance).coerceIn(0f, 1f),
        )
        return EchoVisualSpec(genome = scaled, capabilities = cap, clockSeconds = clockSeconds, surface = surface)
    }

    /** §N Long-nanos 裁剪入口（生产路径：boot-global 时钟全程保持 Long 精度）。 */
    fun cropNanos(genome: EchoVisualGenome, surface: EchoSurface, clockNanos: Long): EchoVisualSpec {
        val cap = capabilitiesFor(surface)
        val scaled = genome.copy(
            luminance = (genome.luminance * cap.maxLuminance).coerceIn(0f, 1f),
        )
        return EchoVisualSpec(
            genome = scaled,
            capabilities = cap,
            clockSeconds = clockNanos / 1_000_000_000f,
            surface = surface,
            clockNanos = clockNanos,
        )
    }
}
