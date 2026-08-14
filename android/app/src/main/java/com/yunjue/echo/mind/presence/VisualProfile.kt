package com.yunjue.echo.mind.presence

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
internal fun maturityOpenness(maturity: EchoMaturity): Float = when (maturity) {
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
    val h = ((hourOfDay % 24f) + 24f) % 24f
    return when {
        h < 6f -> 0.35f + (h / 6f) * 0.05f            // 0..6 深夜→微亮
        h < 10f -> 0.4f + ((h - 6f) / 4f) * 0.45f     // 晨：0.4→0.85
        h < 17f -> 0.85f                              // 昼
        h < 22f -> 0.85f - ((h - 17f) / 5f) * 0.4f    // 暮：0.85→0.45
        else -> 0.45f - ((h - 22f) / 2f) * 0.1f       // 22-24：0.45→0.35
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

    val flow = (v.activityLevel * 0.6f + b.density * 0.4f) * surfaceFlow * motionFactor * nightFactor
    val coherence = state.confidence.coerceIn(0f, 1f)
    val brightness = (dayFactor * (0.55f + v.activityLevel * 0.45f) * nightFactor).coerceIn(0.15f, 1f)

    return EchoVisualParameters(
        flowSpeed = flow.coerceIn(0f, 1f),
        coherence = coherence,
        turbulence = b.deviation.coerceIn(0f, 1f),
        particleDensity = (b.density * 0.9f + 0.1f).coerceIn(0f, 1f),
        coreOpenness = maturityOpenness(maturity),
        dispersion = ((1f - coherence) * 0.5f + 0.2f).coerceIn(0.15f, 0.8f),
        pulsePeriodSeconds = 5.6f - v.activityLevel * 1.8f, // 3.8s（活跃）~ 5.6s（平静）
        depth = (0.3f + v.regularity * 0.7f).coerceIn(0.3f, 1f),
        brightness = brightness,
        contrast = (0.4f + b.deviation * 0.6f).coerceIn(0f, 1f),
        accentIntensity = (0.3f + coherence * 0.7f).coerceIn(0f, 1f),
        structureComplexity = maturityOpenness(maturity),
    )
}

// ===== EchoSceneModel：共享帧模型（确定性；Compose / Wallpaper / Dream 三个渲染器共用） =====

/** 粒子（帧内坐标，0..1 归一化）。 */
data class SceneParticle(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val alpha: Float,
)

/** 一帧的完整视觉状态（纯数据；渲染器只负责把帧画出来）。 */
data class EchoSceneFrame(
    val backgroundCenterColor: Int,
    val backgroundEdgeColor: Int,
    val coreRadiusFraction: Float,
    val ringRadiusFraction: Float,
    val ringAlpha: Float,
    val accentColor: Int,
    val particles: List<SceneParticle>,
)

/** 确定性伪随机（LCG，seed + 序号 → 0..1；保证同一 identity/day/state 画面可复现）。 */
internal fun sceneRandom(seed: Long, index: Int): Float {
    var x = (seed xor (index.toLong() shl 32)) and 0x7FFFFFFF
    if (x == 0L) x = 1L
    x = (x * 48271L) % 2147483647L
    return (x and 0xFFFFFF).toFloat() / 16777215f
}

/** hue(0..1)/sat/value → ARGB Int（视觉主色由 Identity Genome 决定，非状态决定）。 */
internal fun hsvToArgb(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Int {
    val h = ((hue % 1f) + 1f) % 1f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h * 6f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 1f / 6f -> Triple(c, x, 0f)
        h < 2f / 6f -> Triple(x, c, 0f)
        h < 3f / 6f -> Triple(0f, c, x)
        h < 4f / 6f -> Triple(0f, x, c)
        h < 5f / 6f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val rr = ((r + m) * 255f).toInt().coerceIn(0, 255)
    val gg = ((g + m) * 255f).toInt().coerceIn(0, 255)
    val bb = ((b + m) * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (rr shl 16) or (gg shl 8) or bb
}

/**
 * 计算一帧（确定性：给定 params/seed/时间/尺寸 → 完全相同的帧）。
 *
 * @param timeSeconds 墙钟秒（驱动呼吸相位与轨道相位）
 * @param width/height 视口尺寸（仅影响半径比例计算，粒子坐标归一化）
 */
fun computeEchoSceneFrame(
    params: EchoVisualParameters,
    seed: Long,
    timeSeconds: Float,
    width: Float,
    height: Float,
): EchoSceneFrame {
    val minDim = min(width, height)
    // Identity 色相：Knuth 乘法散列把 seed 均匀展开到 [0.45, 0.75]（青蓝→紫区间）。
    // 色相属于 Identity Genome（数月恒定），不随状态变化——因此不构成情绪色彩联想。
    val golden = (seed * 2654435761L) and 0x7FFFFFFF
    val frac = (golden and 0xFFFFFF).toFloat() / 16777215f
    val hue = 0.45f + frac * 0.3f

    val bgCenter = hsvToArgb(hue, 0.25f, 0.10f + params.brightness * 0.10f)
    val bgEdge = hsvToArgb(hue, 0.5f, 0.04f + params.brightness * 0.05f)
    val accent = hsvToArgb(hue, 0.7f, 0.75f, 0.25f + params.accentIntensity * 0.6f)

    // 呼吸相位：pulsePeriod 驱动核心缩放
    val period = params.pulsePeriodSeconds.coerceAtLeast(1f)
    val breathe = sin((timeSeconds % period) / period * 2f * PI.toFloat())
    val coreRadius = 0.10f + params.coreOpenness * 0.06f + breathe * 0.02f * (1f - params.turbulence * 0.5f)
    val ringRadius = coreRadius * 1.9f + params.dispersion * 0.35f +
        sin(timeSeconds * 0.3f) * params.turbulence * 0.04f
    val ringAlpha = (0.25f + params.coherence * 0.4f).coerceIn(0f, 0.8f)

    // 粒子：数量/半径/α 由 density/coherence 决定；轨道由 flowSpeed 驱动
    val count = (14 + params.particleDensity * 46).toInt()
    val particles = ArrayList<SceneParticle>(count)
    for (i in 0 until count) {
        val r1 = sceneRandom(seed, i * 3 + 1)
        val r2 = sceneRandom(seed, i * 3 + 2)
        val r3 = sceneRandom(seed, i * 3 + 3)
        val orbitSpeed = params.flowSpeed * (0.15f + r2 * 0.7f)
        val angle = r1 * 2f * PI.toFloat() + timeSeconds * orbitSpeed * 0.35f
        val orbit = coreRadius * (1.4f + r2 * params.dispersion * 3.2f)
        val px = 0.5f + cos(angle) * orbit * (width / minDim) * 0.5f
        val py = 0.5f + sin(angle) * orbit * (height / minDim) * 0.5f
        particles.add(
            SceneParticle(
                x = px.coerceIn(-0.1f, 1.1f),
                y = py.coerceIn(-0.1f, 1.1f),
                radiusFraction = (0.003f + r3 * 0.008f) * (0.6f + params.depth * 0.8f),
                alpha = (0.15f + params.coherence * 0.55f) * (0.5f + r1 * 0.5f),
            )
        )
    }

    return EchoSceneFrame(
        backgroundCenterColor = bgCenter,
        backgroundEdgeColor = bgEdge,
        coreRadiusFraction = coreRadius.coerceIn(0.05f, 0.4f),
        ringRadiusFraction = ringRadius.coerceIn(0.1f, 0.9f),
        ringAlpha = ringAlpha,
        accentColor = accent,
        particles = particles,
    )
}
