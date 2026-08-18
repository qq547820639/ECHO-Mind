package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState


/**
 * ERA 2 — Generative Visual Profile（纯 Kotlin，无 Android 依赖，JVM 可测）。
 *
 * V3 §H/§I：本文件是 Presence → Visual 的**唯一语义解释层**：
 *   EchoPresenceState → EchoVisualMapper → EchoVisualParameters（canonical 语义值）
 *     → VisualGenomeCompiler（core:visual 机械编译）→ EchoVisualGenome → Renderer
 *
 * 算法与视觉解耦（Master Prompt PART 47/50）：禁止「焦虑=红/开心=黄」式映射；
 * 低置信度 = 更弥散、更少语义结构。maturityOpenness / dayBrightnessCurve 只在此定义。
 */

/** canonical 视觉语义参数唯一定义在 core:visual；presence 以别名保持源兼容。 */
typealias EchoVisualParameters = com.yunjue.echo.mind.visual.model.EchoVisualParameters

/** 用户动态程度设置（Me → Presence）。 */
enum class PresenceMotionLevel { QUIET, DEFAULT, LIVELY }

/** 成熟度 → 核心开放度（SEED 闭合的芽 → MATURE 完整开放；唯一定义）。 */
fun maturityOpenness(maturity: EchoMaturity): Float = when (maturity) {
    EchoMaturity.SEED -> 0.15f
    EchoMaturity.DISCOVERING -> 0.3f
    EchoMaturity.EMERGING -> 0.5f
    EchoMaturity.KNOWN -> 0.75f
    EchoMaturity.MATURE -> 0.9f
}

/**
 * 昼夜亮度曲线（小时 → 0..1）：深夜最低、白天最高，分段线性（唯一定义）。
 * 22-6 夜 / 6-10 晨 / 10-17 昼 / 17-22 暮。
 */
fun dayBrightnessCurve(hourOfDay: Float): Float {
    val h = (hourOfDay % 24f + 24f) % 24f
    return when {
        h < 6f -> 0.35f + h / 6f * 0.05f            // 0..6 深夜→微亮
        h < 10f -> 0.4f + (h - 6f) / 4f * 0.45f     // 晨：0.4→0.85
        h < 17f -> 0.85f                              // 昼
        h < 22f -> 0.85f - (h - 17f) / 5f * 0.4f    // 暮：0.85→0.45
        else -> 0.45f - (h - 22f) / 2f * 0.1f       // 22-24：0.45→0.35
    }
}

private fun frac(v: Float): Float = v - kotlin.math.floor(v)

/**
 * 由 EchoPresenceState + 时间 + 用户设置 → canonical 视觉参数（确定性纯函数）。
 *
 * 映射关系（唯一事实源，改动需同步单测）：
 * - flowSpeed ← activation/density（越活跃流越快；含用户动态程度调制）；
 * - coherence ← confidence（置信度 = 视觉确定程度）；
 * - turbulence ← deviation（与基线偏差越大越湍动）；
 * - particleDensity ← density（事件密集度）；
 * - coreOpenness / structureComplexity ← maturity（成长视觉）；
 * - pulsePeriod ← activation（呼吸快慢，非心率模拟）；
 * - brightness ← 昼夜曲线 × 活跃度（夜间自动更暗更慢）；
 * - dataClarity ← coverage（低数据更轻更模糊）；
 * - haloIntensity / filamentDensity ← coherence × 纹理族；
 * - seasonPhase / dayComposition ← identity/season/daily 稳定拓扑指纹。
 *
 * V3 §M：Surface/MotionPolicy/RenderQuality 不进入本映射（表现强度由渲染层正交承载）；
 * reduceMotion（无障碍）例外——它是语义级硬契约：flowSpeed 归零（仅保留编译期呼吸）。
 */
fun computeVisualParameters(
    state: EchoPresenceState,
    hourOfDay: Float,
    motionLevel: PresenceMotionLevel = PresenceMotionLevel.DEFAULT,
    nightMode: Boolean = false,
    reduceMotion: Boolean = false,
): EchoVisualParameters {
    val v = state.rhythmState
    val b = state.behaviorState
    val maturity = state.maturity
    val id = state.identityGenome
    val daily = state.dailyComposition
    val moment = state.momentState
    val season = state.lifeSeason

    val dayFactor = dayBrightnessCurve(hourOfDay)
    val nightFactor = if (nightMode) 0.6f else 1f

    val motionFactor = when (motionLevel) {
        PresenceMotionLevel.QUIET -> 0.6f
        PresenceMotionLevel.DEFAULT -> 1f
        PresenceMotionLevel.LIVELY -> 1.3f
    }

    // Daily Composition 未填充（v1 旧快照/未接日构图的状态）→ 回退旧推导（向后兼容）。
    // T3-P2-3：判定改显式标志，不再以 flowSpeed/coherence 哨兵值推断
    //（真实日构图两值恰为 0 时不再误判回退旧路径）。
    val hasDaily = state.dailyCompositionFilled

    val baseFlow = if (hasDaily) daily.flowSpeed else v.activityLevel * 0.6f + b.density * 0.4f
    val flow = if (reduceMotion) 0f else baseFlow * motionFactor * nightFactor *
        (0.85f + 0.3f * id.motionPersonality) // 运动人格长效调制（§53）
    val baseCoherence = if (hasDaily) daily.coherence else state.confidence.coerceIn(0f, 1f)
    val coherence = (baseCoherence * (0.6f + 0.4f * id.symmetryTendency)).coerceIn(0f, 1f)
    // 人生阶段漂移 → 慢湍流下限（§56：节律漂移进入视觉，但不突变）
    val baseTurbulence = if (hasDaily) daily.turbulence else b.deviation
    val turbulence = (baseTurbulence + season.drift * 0.25f).coerceIn(0f, 1f)
    val baseDensity = if (hasDaily) daily.particleDensity else b.density * 0.9f + 0.1f
    val textureFactor = 0.9f + id.textureFamily * 0.05f
    val brightness = (dayFactor * (0.55f + v.activityLevel * 0.45f) * nightFactor).coerceIn(0.15f, 1f)
    val dataClarity = v.coverage.coerceIn(0f, 1f)
        .let { if (it > 0f) it else (0.3f + state.confidence * 0.7f).coerceIn(0f, 1f) }

    return EchoVisualParameters(
        flowSpeed = flow.coerceIn(0f, 1f),
        coherence = coherence,
        turbulence = turbulence,
        particleDensity = (baseDensity * textureFactor).coerceIn(0f, 1f),
        coreOpenness = if (hasDaily) daily.coreOpenness else maturityOpenness(maturity),
        dispersion = if (hasDaily) daily.dispersion.coerceIn(0f, 1f)
        else ((1f - coherence) * 0.5f + 0.2f).coerceIn(0.15f, 0.8f),
        // 分钟级调制：呼吸周期优先（§59）；日级 pulse 次之；旧推导兜底
        pulsePeriodSeconds = moment.breathingPeriod.takeIf { it > 0f }
            ?: daily.pulsePeriod.takeIf { it > 0f }
            ?: (5.6f - v.activityLevel * 1.8f).coerceIn(3.6f, 6f),
        depth = if (hasDaily) daily.depth else (0.3f + v.regularity * 0.7f).coerceIn(0.3f, 1f),
        brightness = brightness,
        contrast = if (hasDaily) daily.contrast else (0.4f + b.deviation * 0.6f).coerceIn(0f, 1f),
        accentIntensity = if (hasDaily) daily.accentIntensity else (0.3f + coherence * 0.7f).coerceIn(0f, 1f),
        structureComplexity = if (hasDaily) daily.structureComplexity else maturityOpenness(maturity),
        dataClarity = dataClarity,
        haloIntensity = (0.3f + coherence * 0.6f).coerceIn(0f, 1f),
        momentIntensity = moment.noiseScale.coerceIn(0f, 1f),
        filamentDensity = (0.3f + id.textureFamily * 0.15f + coherence * 0.3f).coerceIn(0f, 1f),
        seasonPhase = frac(season.phaseIndex * 0.25f + season.drift * 0.5f),
        dayComposition = if (hasDaily) frac(
            daily.flowSpeed * 0.3f + daily.coherence * 0.3f + daily.turbulence * 0.4f,
        ) else 0.5f,
    )
}

/**
 * ERA 14 §62 / V3 §H — EchoVisualMapper（正式冻结的映射链唯一入口）：
 *
 *   EchoPresenceState → EchoVisualMapper.map → EchoVisualParameters
 *     → VisualGenomeCompiler.compile → EchoVisualGenome → Renderer
 *
 * 所有向视觉语义的解释只在此发生；渲染器不得自行推导身份/季节/构图/调制参数。
 */
object EchoVisualMapper {
    fun map(
        state: EchoPresenceState,
        hourOfDay: Float,
        motionLevel: PresenceMotionLevel = PresenceMotionLevel.DEFAULT,
        nightMode: Boolean = false,
        reduceMotion: Boolean = false,
    ): EchoVisualParameters = computeVisualParameters(state, hourOfDay, motionLevel, nightMode, reduceMotion)
}
