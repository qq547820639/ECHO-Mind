package com.yunjue.echo.mind.visual.render

/**
 * Organism 帧模型 — 9 层有机体的纯数据表达（ECHO_VISUAL_CONSTITUTION §二）。
 *
 * 渲染器只负责把这些层画出来（Compose Canvas / android.graphics.Canvas 两个 adapter 共用）。
 * 全部为归一化坐标（0..1，相对视口）与 ARGB Int；不含任何业务/心理语义。
 *
 * 分层（自底向上）：
 *  1 ambientField   环境背景场（径向渐变 + 噪声颗粒）
 *  2 membrane       主膜（filament 网状闭合曲面）
 *  3 filaments      内部 filament 网络
 *  4 orbitals       轨道轨迹
 *  5 particles      粒子系统
 *  6 coreGlow       核心光斑（呼吸）
 *  7 halo           环境光晕
 *  8 ripples        反射/涟漪（交互响应，不改底层状态）
 *  9 warmAccent     可选暖金高光（仅 allowWarmAccent 的 surface）
 */

/** 颜色（ARGB）。 */
typealias Argb = Int

/** 环境背景场：中心/边缘色 + 噪声颗粒强度。 */
data class AmbientField(
    val centerColor: Argb,
    val edgeColor: Argb,
    /** 环境颗粒强度 0..1（背景星尘）。 */
    val grainIntensity: Float,
)

/** 膜上的一个采样点（极坐标展开为归一化坐标）。 */
data class MembranePoint(val x: Float, val y: Float)

/** 主膜：闭合曲面轮廓（filament 网状由 filaments 层叠加）。 */
data class Membrane(
    val outline: List<MembranePoint>,
    val strokeColor: Argb,
    val strokeAlpha: Float,
    val strokeWidthFraction: Float,
)

/** 单条 filament（膜内网线：两点 + 控制张力）。 */
data class Filament(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val alpha: Float,
    val widthFraction: Float,
)

/** 轨道轨迹（椭圆；eccentricity 决定偏离圆的程度）。 */
data class Orbital(
    val radiusFraction: Float,
    val eccentricity: Float,
    val rotationRadians: Float,
    val alpha: Float,
    val widthFraction: Float,
)

/** 粒子（流线或圆点）。 */
data class OrganismParticle(
    val x: Float, val y: Float,
    val radiusFraction: Float,
    val alpha: Float,
    val streakDirX: Float = 0f,
    val streakDirY: Float = 0f,
    val streakLength: Float = 0f,
)

/** 核心光斑。 */
data class CoreGlow(val radiusFraction: Float, val color: Argb, val intensity: Float)

/** 环境光晕（同心环）。 */
data class Halo(val radiusFraction: Float, val alpha: Float, val widthFraction: Float)

/** 涟漪（触摸/校正响应；振幅随时间衰减，不改底层状态）。 */
data class Ripple(val radiusFraction: Float, val alpha: Float)

/** 暖金高光（极少量生命性高光；<5% 面积）。 */
data class WarmAccent(val x: Float, val y: Float, val radiusFraction: Float, val alpha: Float)

/** 一帧完整 organism（确定性）。 */
data class OrganismFrame(
    val ambientField: AmbientField,
    val membrane: Membrane,
    val filaments: List<Filament>,
    val orbitals: List<Orbital>,
    val particles: List<OrganismParticle>,
    val coreGlow: CoreGlow,
    val halos: List<Halo>,
    val ripples: List<Ripple>,
    val warmAccents: List<WarmAccent>,
)
