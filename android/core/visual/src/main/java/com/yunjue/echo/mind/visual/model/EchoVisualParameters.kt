package com.yunjue.echo.mind.visual.model

/**
 * EchoVisualParameters — Presence → Visual 的 canonical 语义参数（V3 §H/§I）。
 *
 * 唯一事实链：
 *   EchoPresenceState → EchoVisualMapper（presence：唯一语义解释层）→ **EchoVisualParameters**
 *     → VisualGenomeCompiler（core:visual：机械编译，不重新解释 Presence）→ EchoVisualGenome
 *     → EchoSceneCompiler → Renderer
 *
 * 约束：
 * - 本类型承载**全部**业务语义值；core:visual 不得从 rhythm/behavior/maturity 重新推导任何字段；
 * - maturityOpenness / dayBrightness 曲线只存在于 presence 映射层（单一定义）；
 * - 全部字段 0..1 或明确量纲（秒）。
 */
data class EchoVisualParameters(
    /** 漂移速率 0..1（越活跃流越快；含用户动态程度调制）。 */
    val flowSpeed: Float,
    /** 结构相干性 0..1（置信度 = 视觉确定程度）。 */
    val coherence: Float,
    /** 湍流 0..1（与基线偏差越大越湍动）。 */
    val turbulence: Float,
    /** 粒子密度 0..1（事件密集度）。 */
    val particleDensity: Float,
    /** 核心开放度 0..1（maturity 成长视觉；= maturityOpenness(maturity)）。 */
    val coreOpenness: Float,
    /** 径向展开 0..1（daily composition 的 dispersion）。 */
    val dispersion: Float,
    /** 呼吸周期（秒；非心率模拟）。输入周期域 3.6–6.0s → 编译到 8.2–10.2s 呈现窗口（EchoSceneCompiler §27）。 */
    val pulsePeriodSeconds: Float,
    /** 空间深度 0..1（regularity 语义）。 */
    val depth: Float,
    /** 环境亮度 0..1（昼夜曲线 × 活跃度）。 */
    val brightness: Float,
    /** 对比 0..1。 */
    val contrast: Float,
    /** 强调强度 0..1。 */
    val accentIntensity: Float,
    /** 结构复杂度 0..1（maturity）。 */
    val structureComplexity: Float,
    /** 数据清晰度 0..1（coverage；低数据更轻更模糊）。 */
    val dataClarity: Float = 0f,
    /** 光晕强度 0..1（coherence 调制）。 */
    val haloIntensity: Float = 0f,
    /** 当下强度 0..1（moment 调制；不改变 identity）。 */
    val momentIntensity: Float = 0f,
    /** filament 密度 0..1（结构丰富度 + 纹理族）。 */
    val filamentDensity: Float = 0f,
    /** 人生阶段相位 0..1（season 慢漂移；编译 stable topology 用）。 */
    val seasonPhase: Float = 0f,
    /** 当日构图指纹 0..1（一天级；编译 stable topology 用）。 */
    val dayComposition: Float = 0f,
)
