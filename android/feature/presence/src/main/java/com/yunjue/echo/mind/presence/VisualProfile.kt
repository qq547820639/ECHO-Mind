package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState


/**
 * ERA 2 — Generative Visual Profile（纯 Kotlin，无 Android 依赖，JVM 可测）。
 *
 * 算法与视觉解耦（Master Prompt PART 47/50）：
 * AmbientEngine 输出中性状态向量 → 本文件映射连续视觉参数 → 渲染器只画参数。
 * 禁止「焦虑=红/开心=黄」式映射；低置信度 = 更弥散、更少语义结构。
 */

/** Surface 模式（Master Prompt PART 55）：同一视觉身份，不同表现强度。 */
enum class SurfaceMode { APP, HOME_WALLPAPER, LOCK_SAFE, DREAM, REDUCED_MOTION, LOW_POWER }

/** 用户动态程度设置（Me → Presence）。 */
enum class PresenceMotionLevel { QUIET, DEFAULT, LIVELY }

/** 视觉参数（渲染器唯一输入；全部 0..1 或明确量纲）。 */
data class EchoVisualParameters(
    val flowSpeed: Float,
    val coherence: Float,
    val turbulence: Float,
    val particleDensity: Float,
    val coreOpenness: Float,
    val dispersion: Float,
    val pulsePeriodSeconds: Float,
    val depth: Float,
    val brightness: Float,
    val contrast: Float,
    val accentIntensity: Float,
    val structureComplexity: Float,
)

/** 成熟度 → 核心开放度（SEED 闭合的芽 → MATURE 完整开放）。 */
fun maturityOpenness(maturity: EchoMaturity): Float = when (maturity) {
    EchoMaturity.SEED -> 0.15f
    EchoMaturity.DISCOVERING -> 0.3f
    EchoMaturity.EMERGING -> 0.5f
    EchoMaturity.KNOWN -> 0.75f
    EchoMaturity.MATURE -> 0.9f
}

/**
 * 昼夜亮度曲线（小时 → 0..1）：深夜最低、白天最高，分段线性。
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

/**
 * 由 EchoPresenceState + 时间 + surface + 用户设置 → 视觉参数（确定性纯函数）。
 *
 * 映射关系（唯一事实源，改动需同步单测）：
 * - flowSpeed ← activation/density（越活跃流越快）；
 * - coherence ← confidence（置信度 = 视觉确定程度）；
 * - turbulence ← deviation（与基线偏差越大越湍动）；
 * - particleDensity ← density（事件密集度）；
 * - coreOpenness / structureComplexity ← maturity（成长视觉）；
 * - pulsePeriod ← activation（呼吸快慢，非心率模拟）；
 * - brightness ← 昼夜曲线 × 活跃度（夜间自动更暗更慢）。
 */
fun computeVisualParameters(
    state: EchoPresenceState,
    hourOfDay: Float,
    surface: SurfaceMode,
    motionLevel: PresenceMotionLevel = PresenceMotionLevel.DEFAULT,
    nightMode: Boolean = false,
): EchoVisualParameters {
    val v = state.rhythmState
    val b = state.behaviorState
    val maturity = state.maturity

    val dayFactor = dayBrightnessCurve(hourOfDay)
    val nightFactor = if (nightMode) 0.6f else 1f

    // Surface 强度系数：锁屏/低功耗更静，Dream 最沉浸
    val surfaceFlow = when (surface) {
        SurfaceMode.APP -> 1f
        SurfaceMode.HOME_WALLPAPER -> 0.9f
        SurfaceMode.LOCK_SAFE -> 0.55f
        SurfaceMode.DREAM -> 0.7f
        SurfaceMode.LOW_POWER -> 0.35f
        SurfaceMode.REDUCED_MOTION -> 0f
    }
    val motionFactor = when (motionLevel) {
        PresenceMotionLevel.QUIET -> 0.6f
        PresenceMotionLevel.DEFAULT -> 1f
        PresenceMotionLevel.LIVELY -> 1.3f
    }

    // ERA 14 §62：Identity/Season/Daily/Moment 四层进入映射（渲染器不自行推导身份）。
    // Daily Composition 未填充（旧快照/Journey 状态）→ 回退旧推导（向后兼容）。
    val daily = state.dailyComposition
    val hasDaily = daily.flowSpeed > 0f || daily.coherence > 0f
    val identityMotion = state.identityGenome.motionPersonality
    val identitySymmetry = state.identityGenome.symmetryTendency

    val baseFlow = if (hasDaily) daily.flowSpeed else v.activityLevel * 0.6f + b.density * 0.4f
    val flow = baseFlow * surfaceFlow * motionFactor * nightFactor *
        (0.85f + 0.3f * identityMotion) // 运动人格长效调制（§53）
    val baseCoherence = if (hasDaily) daily.coherence else state.confidence.coerceIn(0f, 1f)
    val coherence = (baseCoherence * (0.6f + 0.4f * identitySymmetry)).coerceIn(0f, 1f)
    // 人生阶段漂移 → 慢湍流下限（§56：节律漂移进入视觉，但不突变）
    val baseTurbulence = if (hasDaily) daily.turbulence else b.deviation
    val turbulence = (baseTurbulence + state.lifeSeason.drift * 0.25f).coerceIn(0f, 1f)
    val baseDensity = if (hasDaily) daily.particleDensity else b.density * 0.9f + 0.1f
    val textureFactor = 0.9f + state.identityGenome.textureFamily * 0.05f
    val brightness = (dayFactor * (0.55f + v.activityLevel * 0.45f) * nightFactor).coerceIn(0.15f, 1f)

    return EchoVisualParameters(
        flowSpeed = flow.coerceIn(0f, 1f),
        coherence = coherence,
        turbulence = turbulence,
        particleDensity = (baseDensity * textureFactor).coerceIn(0f, 1f),
        coreOpenness = if (hasDaily) daily.coreOpenness else maturityOpenness(maturity),
        dispersion = ((1f - coherence) * 0.5f + 0.2f).coerceIn(0.15f, 0.8f),
        // 分钟级调制：呼吸周期优先（§59）；日级 pulse 次之；旧推导兜底
        pulsePeriodSeconds = state.momentState.breathingPeriod.takeIf { it > 0f }
            ?: daily.pulsePeriod.takeIf { it > 0f }
            ?: 5.6f - v.activityLevel * 1.8f,
        depth = if (hasDaily) daily.depth else (0.3f + v.regularity * 0.7f).coerceIn(0.3f, 1f),
        brightness = brightness,
        contrast = if (hasDaily) daily.contrast else (0.4f + b.deviation * 0.6f).coerceIn(0f, 1f),
        accentIntensity = if (hasDaily) daily.accentIntensity else (0.3f + coherence * 0.7f).coerceIn(0f, 1f),
        structureComplexity = if (hasDaily) daily.structureComplexity else maturityOpenness(maturity),
    )
}

/**
 * ERA 14 §62 — EchoVisualMapper（正式冻结的映射链唯一入口）：
 *
 *   EchoPresenceState → EchoVisualMapper.map → EchoVisualParameters → EchoSceneRenderer
 *
 * 所有 Surface（APP/HOME_WALLPAPER/LOCK_SAFE/DREAM/LOW_POWER/REDUCED_MOTION）必须经此映射；
 * 渲染器不得自行推导身份/季节/构图/调制参数。
 */
object EchoVisualMapper {
    fun map(
        state: EchoPresenceState,
        hourOfDay: Float,
        surface: SurfaceMode,
        motionLevel: PresenceMotionLevel = PresenceMotionLevel.DEFAULT,
        nightMode: Boolean = false,
    ): EchoVisualParameters = computeVisualParameters(state, hourOfDay, surface, motionLevel, nightMode)
}
