package com.yunjue.echo.mind.visual.render

/**
 * Organism 帧模型 — V3 拓扑有机体的纯数据表达（ECHO_VISUAL_CONSTITUTION §二 + V3 §14–§18）。
 *
 * 渲染器只负责把这些层画出来（Compose Canvas / android.graphics.Canvas 两个 adapter 共用；
 * AGSL 后端消费同一帧的 vector mask 语义，V3 §19）。
 * 全部为归一化坐标（0..1，相对视口；半径相对 minDim）与 ARGB Int；不含任何业务/心理语义。
 *
 * 分层（自底向上）：
 *  1  ambientField      环境背景场（近黑 radial 衰减）
 *  2  halos             环境光晕（远层）
 *  3  structuralRings   结构环（identity skeleton，约 20%）
 *  4  longFilaments     长丝（跨半球 3D 弧，约 45%；含 behind-core 遮挡 alpha）
 *  5  localFragments    局部碎片（短弧生命纹理，约 35%）
 *  6  coreStrands       核心内部细缕
 *  7  coreCavity        空心核暗腔（禁止实心白球，§18）
 *  8  coreKnots         核心稳定结（2–4；一个暖色小结）
 *  9  particles         Fibonacci 球粒子（AMBIENT/BRIGHT/GLINT）
 *  10 frontMembrane     前膜（前半球壳层微光）
 *  11 ripples           涟漪（交互/瞬时响应，不改底层状态）
 *  12 warmAccents       可选暖金高光（面积极小）
 */

/** 颜色（ARGB）。 */
typealias Argb = Int

/** 环境背景场：中心/边缘色 + 噪声颗粒强度。 */
data class AmbientField(
    val centerColor: Argb,
    /** 中间过渡色（约 42% 半径处；近黑——§24 大量 black/near-black 的执行点）。 */
    val midColor: Argb,
    val edgeColor: Argb,
    /** 环境颗粒强度 0..1（背景星尘）。 */
    val grainIntensity: Float,
)

/** 描边采样点（屏幕归一化坐标 + 该点 alpha——3D 深度与遮挡已在 CPU 侧烘焙）。 */
data class StrokePoint(val x: Float, val y: Float, val alpha: Float)

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

/** 空心核暗腔（§18：dark cavity + internal atmosphere）。 */
data class CoreCavity(
    val radiusFraction: Float,
    /** 暗腔色（近黑，比背景更暗）。 */
    val darkColor: Argb,
    /** 内部大气色（低亮度 identity 色）。 */
    val atmosphereColor: Argb,
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
