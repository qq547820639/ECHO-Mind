package com.yunjue.echo.mind.visual.render

/**
 * Organism 帧模型 — V3 拓扑有机体的纯数据表达（ECHO_VISUAL_CONSTITUTION §二 + V3 §14–§18）。
 *
 * 渲染器只负责把这些层画出来（Compose Canvas / android.graphics.Canvas 两个 adapter 共用；
 * AGSL 后端消费同一帧的 vector mask 语义，V3 §19）。
 * 全部为归一化坐标（0..1，相对视口；半径相对 minDim）与 ARGB Int；不含任何业务/心理语义。
 *
 * 分层（自底向上）：
 *  1  ambientField      环境背景场（近黑 radial 衰减，半径跟随 organism R）
 *  2  atmosphere        体积大气（subtle volume haze + rim scattering；Organism Quality §13）
 *  3  halos             环境光晕（远层）
 *  4  structuralRings   结构环（identity skeleton，约 15–20%，非闭合轨道圆）
 *  5  longFilaments     长丝（跨半球 3D 弧，约 35–45%；含 behind-core 遮挡 alpha）
 *  6  localFragments    局部碎片（短弧生命纹理，约 35–45%；不大量穿过中心）
 *  7  coreStrands       核心内部细缕
 *  8  coreCavity        空心核暗腔（dark cavity + internal atmosphere + 有机非机械边缘）
 *  9  coreKnots         核心稳定结（2–4；暖色小结=核心解剖，随 identity 恒定）
 *  10 particles         Fibonacci 球粒子（AMBIENT/BRIGHT/GLINT）
 *  11 frontMembrane     前膜（前半球壳层微光）
 *  12 ripples           涟漪（交互/瞬时响应，不改底层状态）
 *  13 warmAccents       可选暖金大高光（面积极小；仅 allowWarmAccent surface）
 */

/** 颜色（ARGB）。 */
typealias Argb = Int

/** 环境背景场：中心/边缘色 + 噪声颗粒强度（径向半径跟随 organism R）。 */
data class AmbientField(
    val centerColor: Argb,
    /** 中间过渡色（约 42% 半径处；近黑——§24 大量 black/near-black 的执行点）。 */
    val midColor: Argb,
    val edgeColor: Argb,
    /** 环境颗粒强度 0..1（背景星尘）。 */
    val grainIntensity: Float,
    /** 径向半径（相对 minDim；≈1.6R——大气包裹身体而非整屏）。 */
    val radiusFraction: Float = 1.15f,
)

/** 体积大气（Organism Quality §13：空间感 ≠ 整屏 blur；alpha 全部克制）。 */
data class Atmosphere(
    /** volume haze 半径（相对 minDim；≈1.55R）。 */
    val hazeRadiusFraction: Float,
    /** haze 峰值 alpha（中心 0 → 近边缘峰值 → 0 的环形分布由渲染器表达）。 */
    val hazeAlpha: Float,
    val hazeColor: Argb,
    /** rim（膜散射）半径（≈0.97R）与宽度。 */
    val rimRadiusFraction: Float,
    val rimAlpha: Float,
    val rimWidthFraction: Float,
    val rimColor: Argb,
)

/** 描边采样点（屏幕归一化坐标 + alpha + 归一化深度 -1..1——AGSL mask B 通道）。 */
data class StrokePoint(val x: Float, val y: Float, val alpha: Float, val depth: Float = 0f)

/** 一条丝/环/碎片路径（polyline；widthFraction 相对 minDim）。 */
data class FilamentStroke(
    val points: List<StrokePoint>,
    val color: Argb,
    val widthFraction: Float,
    /** 附加辉光强度 0..1（材质后端用；Canvas fallback 用轻微二次描边表达）。 */
    val glow: Float = 0f,
)

/** 环境光晕（同心环）。 */
data class Halo(val radiusFraction: Float, val alpha: Float, val widthFraction: Float)

/** 腔缘有机形变谐波（identity 恒定；§8 subtle asymmetric deformation，非机械完美圆）。 */
data class CavityHarmonic(val order: Int, val amplitude: Float, val phase: Float)

/** 空心核暗腔（§8：dark cavity + internal atmosphere + internal strands 见 coreStrands）。 */
data class CoreCavity(
    val radiusFraction: Float,
    /** 暗腔色（近黑，比背景更暗）。 */
    val darkColor: Argb,
    /** 内部大气色（低亮度 identity 色）。 */
    val atmosphereColor: Argb,
    /** 有机边缘形变（2–3 阶谐波；identity 恒定）。 */
    val harmonics: List<CavityHarmonic> = emptyList(),
)

/** 核心结。 */
data class CoreKnotV(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val color: Argb,
    val alpha: Float,
)

/** 前膜（front membrane：前半球壳层微光）。 */
data class FrontMembrane(val radiusFraction: Float, val color: Argb, val alpha: Float)

/** 粒子（Fibonacci 球投影；kind 决定亮度上限）。 */
data class SceneParticleV3(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val alpha: Float,
    val kind: ParticleKind,
    val color: Argb,
)

/** 涟漪（触摸/校正响应；振幅随时间衰减，不改底层状态）。 */
data class Ripple(val x: Float, val y: Float, val radiusFraction: Float, val alpha: Float)

/** 暖金高光（极少量生命性高光；面积受 §12 ≤15% 上限约束）。 */
data class WarmAccent(val x: Float, val y: Float, val radiusFraction: Float, val alpha: Float)

/** 一帧完整 organism（确定性：同 spec + 视口 + 时间 → 逐值相同）。 */
data class OrganismFrame(
    val ambientField: AmbientField,
    val atmosphere: Atmosphere,
    val halos: List<Halo>,
    val structuralRings: List<FilamentStroke>,
    val longFilaments: List<FilamentStroke>,
    val localFragments: List<FilamentStroke>,
    val coreStrands: List<FilamentStroke>,
    val coreCavity: CoreCavity,
    val coreKnots: List<CoreKnotV>,
    val particles: List<SceneParticleV3>,
    val frontMembrane: FrontMembrane,
    val ripples: List<Ripple>,
    val warmAccents: List<WarmAccent>,
)
