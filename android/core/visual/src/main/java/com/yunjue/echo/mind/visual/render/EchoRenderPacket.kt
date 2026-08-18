package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfaceCapabilities

/**
 * EchoRenderPacket — renderer 编译产物（V3 §7）。
 *
 * 不是新业务 Contract：业务链仍是 Observation → Presence → EchoVisualParameters/Genome；
 * 本包只是 renderer compiled representation（EchoSceneCompiler 输出，Backend 消费）。
 *
 * CPU 侧职责（拓扑/几何/语义参数）在本包内完成编译；GPU/材质后端只负责物化
 * （atmosphere / glow / spectral mixing / tone mapping / compositing，V3 §8/§19）。
 */

/** 场规格（atmosphere / depth / halo 等 Daily+Moment 层语义参数；identity 不在此）。 */
data class EchoFieldSpec(
    /** 流动速度 0..1（Daily flowSpeed）。 */
    val flow: Float,
    /** 结构相干性 0..1。 */
    val coherence: Float,
    /** 湍流 0..1（中性偏差表达）。 */
    val turbulence: Float,
    /** 粒子密度 0..1。 */
    val particleDensity: Float,
    /** 空间深度 0..1。 */
    val depth: Float,
    /** 核心开放度 0..1（maturity 语义，经 §13 multiplier 缩放）。 */
    val coreOpenness: Float,
    /** 离散度 0..1。 */
    val dispersion: Float,
    /** 光晕强度 0..1。 */
    val halo: Float,
    /** 曝光 0..1（brightness 语义）。 */
    val exposure: Float,
    /** 数据清晰度 0..1（低数据视觉降级：更轻更模糊，非红色）。 */
    val dataClarity: Float,
    /** 成熟度乘数（§13：只影响 secondary complexity / filament / particle / orbital 丰富度）。 */
    val maturityMultiplier: Float,
)

/**
 * 材质规格（tone pipeline / HDR 门控；物化由后端执行）。
 *
 * toneKnee/toneCompression 是 soft-knee 曲线的**唯一事实源**：AGSL 后端经 uniform
 * iKnee/iComp 注入（AgslEchoBackend.AgslSession.draw），shader 内不再有硬编码副本。
 * 暖色面积 / 高亮像素上限（V3 §12 ≤15% / §24 ≤4%）的**QA 门执行点 = VisualLabMetrics**
 * （WARM_AREA_HARD_CAP / HIGHLIGHT_CAP 常量单点）；渲染侧由拓扑 4% warm 分类保证。
 */
data class EchoMaterialSpec(
    /** soft-knee 起点（V3 §24 默认 .58；AGSL uniform iKnee）。 */
    val toneKnee: Float = 0.58f,
    /** soft-knee 压缩率（默认 2.4；AGSL uniform iComp）。 */
    val toneCompression: Float = 2.4f,
    /**
     * 是否允许 HDR 高亮（API≥34 且显示链路支持且非省电且热态 < MODERATE 且非 Wallpaper）。
     * 当前消费方：EchoSceneCompiler 编译 + EchoSceneCompilerTest 断言（§23 语义锚点）；
     * 后端材质链暂不读取（§T HDR 诚实门恒 BLOCKED）。
     */
    val hdrAllowed: Boolean = false,
    /** HDR 高亮像素上限（≤2–3%；QA 门度量口径）。 */
    val hdrGlintCap: Float = 0.025f,
)

/** 运动语义参数（MotionEvaluator 的输入；帧插值在 evaluator 内，V3 §25–§28）。 */
data class EchoMotionSpec(
    /** 呼吸周期秒（输入周期域 3.6–6.0s → 编译到 8.2–10.2s 呈现窗口；Dream ×1.18 / Reduced ×1.45 在此展开）。 */
    val breathPeriodSeconds: Float,
    /** 呼吸幅度（App 2.4% / Wallpaper 1.6% / Dream 2.0% / Reduced 0.7%）。 */
    val breathAmplitude: Float,
    /** 亮度脉冲上限（≤±3%）。 */
    val brightnessPulse: Float,
    /** 主轨道完整旋转周期秒（实现区间约 30–55 分钟，随 driftRate 展开）。 */
    val orbitPeriodSeconds: Float,
    /** filament 内部相位周期秒（实现区间 35–55 秒，随 filamentDensity 展开）。 */
    val filamentPhaseSeconds: Float,
    /** 粒子速度乘数（Reduced ×.08 / Dream ×.55 / Wrist stale ×.10 等已展开）。 */
    val particleVelocity: Float,
    /** 轨道速度乘数。 */
    val orbitVelocity: Float,
    /** filament 相位乘数。 */
    val filamentPhaseScale: Float,
)

/** Surface 规格（已裁剪能力 + 渲染质量 + 后端 tier + 视口）。 */
data class EchoSurfaceSpec(
    val surface: EchoSurface,
    val capabilities: SurfaceCapabilities,
    val quality: EchoRenderQuality,
    val tier: EchoRenderTier,
    val viewportWidth: Float,
    val viewportHeight: Float,
)

/** 交互规格（transient renderer interaction；Touch 不改任何 Presence/Identity/Daily/Moment）。 */
data class EchoInteractionSpec(
    /** 是否有活跃触点。 */
    val active: Boolean = false,
    /** 触点归一化坐标（0..1 视口）。 */
    val touchX: Float = 0f,
    val touchY: Float = 0f,
    /** 交互包络 0..1（0–80ms capture → 80–180ms rise → 180–600ms peak/decay → 600–1150ms return）。 */
    val envelope: Float = 0f,
    /** 最大形变（R 比例；§29 上限 0.035R）。 */
    val maxDeformation: Float = 0.035f,
    /** 高斯 sigma（R 比例；0.18R；交互只消费 sigma/maxDeformation）。 */
    val sigma: Float = 0.18f,
)

/** 一帧的完整编译产物（Backend 唯一输入）。 */
data class EchoRenderPacket(
    val identity: EchoIdentitySpec,
    val field: EchoFieldSpec,
    val material: EchoMaterialSpec,
    val motion: EchoMotionSpec,
    val surface: EchoSurfaceSpec,
    val interaction: EchoInteractionSpec,
)

/** 渲染质量（V3 §31：NORMAL / CONSERVE / MINIMAL；Identity/Presence/privacy 永不降级）。 */
enum class EchoRenderQuality {
    NORMAL,
    /** Power Save 至少 CONSERVE；Thermal MODERATE → CONSERVE。 */
    CONSERVE,
    /** Thermal SEVERE+ → MINIMAL。 */
    MINIMAL,
}

/** 渲染层级（V3 §9 Capability Router；ULTRA 永不只因 API≥37 自动启用）。 */
enum class EchoRenderTier {
    /** API 26–32：Canvas fallback（multi-stroke / radial gradient / restrained halo / depth alpha）。 */
    LEGACY,

    /** API 33–35：RuntimeShader / AGSL。 */
    STANDARD,

    /** API 36+：AGSL + RuntimeColorFilter/RuntimeXfermode + 可门控 WCG/HDR。 */
    ADVANCED,

    /** API 37+ 且 AVP 2025 且内部 benchmark pass 且内部 flag 且功耗/热态合格。 */
    ULTRA,
}

/** 设备渲染能力（router 输入；由 Android adapter 层采集真实信号填充）。 */
data class DeviceRenderCapabilities(
    val api: Int,
    val runtimeShader: Boolean,
    val avp2025: Boolean = false,
    val ultraBenchmarkPassed: Boolean = false,
    val ultraFlag: Boolean = false,
)

/** V3 §9 层级选择（纯函数；ULTRA 需要全部硬门同时满足）。 */
fun selectTier(c: DeviceRenderCapabilities): EchoRenderTier = when {
    c.api >= 37 && c.avp2025 && c.ultraBenchmarkPassed && c.ultraFlag -> EchoRenderTier.ULTRA
    c.api >= 36 && c.runtimeShader -> EchoRenderTier.ADVANCED
    c.api >= 33 && c.runtimeShader -> EchoRenderTier.STANDARD
    else -> EchoRenderTier.LEGACY
}
