package com.yunjue.echo.mind.visual.model

/**
 * ECHO Visual Genome — 视觉基因（纯数据、可序列化、可测试）。
 *
 * 渲染管线的确定性数据心脏：
 *   EchoPresenceState → EchoVisualMapper（presence 语义层）→ EchoVisualParameters
 *     → VisualGenomeCompiler（机械编译）→ **EchoVisualGenome** → SurfacePolicy
 *     → EchoVisualSpec → Renderer
 *
 * 设计约束（ECHO_VISUAL_SEMANTICS.md / ECHO_VISUAL_CONSTITUTION.md）：
 * - **确定性**：同一 fixture（seed + presence + clock + surface + viewport）重复生成完全相同结果。
 * - **稳定 identity**：`identitySeed/identityTopology/identityPhase` 数月恒定，moment 不得改变 identity。
 * - **无心理语义**：禁止 emotion / depression / anxiety / health score 等字段；全部为中性连续量。
 * - 所有字段为 Float/Int/Long（无量纲歧义处注明 0..1），可直接序列化进 EchoPortraitSnapshot。
 */
data class EchoVisualGenome(
    /** 视觉种子（installation random seed；identity 的唯一随机源）。 */
    val identitySeed: Long,
    /** 核心拓扑 0..1（1 = 高度对称；identity 层，恒定）。 */
    val identityTopology: Float,
    /** 身份相位 0..1（filament/orbital 的固定相位偏移；identity 层，恒定）。 */
    val identityPhase: Float,
    /** 人生阶段相位 0..1（数周/月慢漂移映射到视觉相位；season 层）。 */
    val seasonPhase: Float,
    /** 当日构图指纹 0..1（一天级；一天内不漂移）。 */
    val dayComposition: Float,
    /** 结构相干性 0..1（temporal concentration；filament 网络紧密度）。 */
    val coherence: Float,
    /** 径向展开 0..1（主环/膜的展开程度）。 */
    val radialSpread: Float,
    /** 轨道离心率 0..1（baseline-relative magnitude；0=圆轨道，越大越偏离）。 */
    val orbitalEccentricity: Float,
    /** 粒子密度 0..1（activity density）。 */
    val particleDensity: Float,
    /** filament 密度 0..1（结构丰富度 + 纹理族）。 */
    val filamentDensity: Float,
    /** 漂移速率 0..1（轨道/粒子的缓慢漂移；非抖动）。 */
    val driftRate: Float,
    /** 脉动速率（呼吸周期输入秒数；域 3.6–6.0s → 编译到 8.2–10.2s 呈现窗口；非心率模拟）。 */
    val pulseRate: Float,
    /** 湍流 0..1（与基线偏差的非评价性表达；局部轨迹扰动）。 */
    val turbulence: Float,
    /** 环境亮度 0..1（time of day × coverage；昼夜曲线驱动）。 */
    val luminance: Float,
    /** 光谱偏置 0..1（在主光谱蓝→紫区间内的偏移；暖金仅在 <5% 高光出现）。 */
    val spectralBias: Float,
    /** 核心强度 0..1（maturity 核心开放度；SEED 闭合 → MATURE 开放）。 */
    val coreIntensity: Float,
    /** 光晕强度 0..1（ambient halo；confidence/coherence 调制）。 */
    val haloIntensity: Float,
    /** 数据清晰度 0..1（data coverage；低数据更轻更模糊）。 */
    val dataClarity: Float,
    /** 空间深度 0..1（regularity 语义；<0 = 未设置，编译期由 coherence 派生）。 */
    val depth: Float = -1f,
    /** 当下强度 0..1（moment 调制；不改变 identity）。 */
    val momentIntensity: Float,
    /** genome 结构修订号（schema 演进用；序列化兼容锚点）。 */
    val revision: Int = CURRENT_REVISION,
) {
    init {
        require(revision >= 1) { "revision must be >= 1" }
    }

    companion object {
        const val CURRENT_REVISION: Int = 2
    }
}
