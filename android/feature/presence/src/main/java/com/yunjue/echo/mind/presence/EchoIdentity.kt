package com.yunjue.echo.mind.presence

import com.yunjue.echo.mind.model.DailyPortraitDto
import kotlin.math.abs

/**
 * ERA 14 §53-§61 — ECHO Long-term Identity 纯函数层。
 *
 * - [deriveIdentityGenome]：installation random seed + 长期基线 + 视觉偏好 → 七维身份；
 * - [computeLifeSeason]：数周画像 → 中性人生阶段描述（§57 词表，禁医学/心理结论）；
 * - [buildDailyComposition] / [buildMomentState]：日级/分钟级视觉层；
 * - [smoothPresenceState]：所有变化平滑（§60 interpolation，不瞬切）。
 *
 * 全部确定性（同输入同输出）；渲染器只消费这些层，不得自行推导身份。
 */

/** 确定性伪随机（LCG；与 sceneRandom 同族但独立序列空间）。 */
private fun identityRandom(seed: Long, index: Int): Float {
    var x = seed xor (index.toLong() shl 32) and 0x7FFFFFFF
    if (x == 0L) x = 1L
    x = x * 48271L % 2147483647L
    return (x and 0xFFFFFF).toFloat() / 16777215f
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * §53/§54 — Identity Genome 派生。
 *
 * @param seed installation random seed（AppPreferences.identitySeed，一次性生成持久化）
 * @param baselineStability 长期基线稳定性 0..1（来自 AmbientVector.regularity：稳定模式缓慢塑形运动人格）
 * @param motionPreference 用户视觉偏好（QUIET/DEFAULT/LIVELY）
 */
fun deriveIdentityGenome(
    seed: Long,
    baselineStability: Float,
    motionPreference: PresenceMotionLevel,
): EchoIdentityGenome {
    val stability = baselineStability.coerceIn(0f, 1f)
    val derivedMotion = 0.25f + 0.75f * identityRandom(seed, 3)
    val preferenceFactor = when (motionPreference) {
        PresenceMotionLevel.QUIET -> 0.25f
        PresenceMotionLevel.DEFAULT -> 0.55f
        PresenceMotionLevel.LIVELY -> 0.85f
    }
    // 运动人格 = 安装随机 50% + 视觉偏好 35% + 长期节律稳定 15%（§55：稳定但允许长期缓慢塑形）
    val motionPersonality = (
        derivedMotion * 0.50f +
            preferenceFactor * 0.35f +
            stability * 0.15f
        ).coerceIn(0.05f, 0.95f)
    return EchoIdentityGenome(
        seed = seed,
        accentHue = (seed and 0xFFFF).toFloat() / 65535f,
        colorFamily = ((seed ushr 16) % 5).toInt().let { if (it < 0) it + 5 else it },
        textureFamily = ((seed ushr 24) % 4).toInt().let { if (it < 0) it + 4 else it },
        coreTopology = 0.4f + 0.6f * identityRandom(seed, 0),
        symmetryTendency = 0.3f + 0.7f * identityRandom(seed, 1),
        orbitGeometry = identityRandom(seed, 2),
        motionPersonality = motionPersonality,
    )
}

/** §56 — 两半窗口趋势（first half → second half；中性词表）。 */
private fun trendLabel(
    portraits: List<DailyPortraitDto>,
    extract: (DailyPortraitDto) -> Float?,
    upwardLabel: String,
    downwardLabel: String,
    threshold: Float,
): String {
    if (portraits.size < 4) return "stable"
    val half = portraits.size / 2
    val first = portraits.take(half).mapNotNull(extract).averageOrNull() ?: return "stable"
    val second = portraits.drop(half).mapNotNull(extract).averageOrNull() ?: return "stable"
    val delta = second - first
    return when {
        delta > threshold -> upwardLabel
        delta < -threshold -> downwardLabel
        else -> "stable"
    }
}

private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()

/**
 * §56/§57 — 人生阶段计算（数周/月画像窗口）。
 *
 * 输入为画像时间线（按日期升序）；输出全部中性描述。
 */
fun computeLifeSeason(portraits: List<DailyPortraitDto>): EchoLifeSeason {
    val sorted = portraits.sortedBy { it.date }
    if (sorted.isEmpty()) return EchoLifeSeason(phaseIndex = 0, drift = 0f)

    val baselineDays = sorted.maxOfOrNull { it.baselineDays } ?: 0
    val phaseIndex = when {
        baselineDays < 7 -> 0
        baselineDays < 30 -> 1
        baselineDays < 90 -> 2
        else -> 3
    }

    // RHYTHM 活跃起点：LATE/EARLY 比例漂移
    val rhythmValues = { d: DailyPortraitDto ->
        when (d.dimensionValue("RHYTHM")) {
            "LATER" -> 1f
            "EARLIER" -> 0f
            "SIMILAR", "VERY_SIMILAR" -> 0.5f
            else -> null
        }
    }
    val rhythmShift = trendLabel(sorted, rhythmValues, "later", "earlier", 0.25f)

    // SCREEN_AMOUNT 碎片化
    val screenValues = { d: DailyPortraitDto ->
        when (d.dimensionValue("SCREEN_AMOUNT")) {
            "MORE_FRAGMENTED" -> 1f
            "MORE_CONCENTRATED" -> 0f
            "SIMILAR", "VERY_SIMILAR" -> 0.5f
            else -> null
        }
    }
    val screenFragmentation = trendLabel(sorted, screenValues, "more_fragmented", "more_concentrated", 0.25f)

    // MOVEMENT 移动趋势
    val movementValues = { d: DailyPortraitDto ->
        when (d.dimensionValue("MOVEMENT")) {
            "MORE" -> 1f
            "LESS" -> 0f
            "SIMILAR", "VERY_SIMILAR" -> 0.5f
            else -> null
        }
    }
    val mobilityTrend = trendLabel(sorted, movementValues, "more_mobile", "less_mobile", 0.25f)

    // 活动变异性：z-score 标准差趋势（两半比较）
    val zValues = { d: DailyPortraitDto -> d.dimensions.values.mapNotNull { it.z }.map { it.toFloat() }.averageOrNull() }
    val activityVariability = when {
        sorted.size < 8 -> "stable"
        else -> {
            val half = sorted.size / 2
            val first = sorted.take(half).mapNotNull(zValues).averageOrNull() ?: return EchoLifeSeason(phaseIndex, 0f)
            val second = sorted.drop(half).mapNotNull(zValues).averageOrNull() ?: return EchoLifeSeason(phaseIndex, 0f)
            val delta = abs(second - first)
            when {
                delta > 0.3f -> "more_variable"
                delta < 0.1f -> "more_regular"
                else -> "stable"
            }
        }
    }

    // 规律性：SIMILAR 占比趋势
    val regularValues = { d: DailyPortraitDto ->
        when (d.dimensionValue("RHYTHM")) {
            "SIMILAR", "VERY_SIMILAR" -> 1f
            "IRREGULAR" -> 0f
            else -> 0.5f
        }
    }
    val regularityTrend = trendLabel(sorted, regularValues, "more_regular", "less_regular", 0.2f)

    // 跨日漂移幅度：两半 RHYTHM 均值差 + z 波动归一化
    val half = sorted.size / 2
    val firstRhythm = sorted.take(half).mapNotNull(rhythmValues).averageOrNull() ?: 0.5f
    val secondRhythm = sorted.drop(half).mapNotNull(rhythmValues).averageOrNull() ?: 0.5f
    val zSpread = sorted.mapNotNull { d -> d.dimensions.values.mapNotNull { it.z }.map { it.toFloat() }.averageOrNull() }
        .let { vals -> if (vals.size < 2) 0f else (vals.maxOrNull() ?: 0f) - (vals.minOrNull() ?: 0f) }
    val drift = (abs(secondRhythm - firstRhythm) * 0.7f + zSpread.coerceIn(0f, 2f) * 0.15f).coerceIn(0f, 1f)

    return EchoLifeSeason(
        phaseIndex = phaseIndex,
        drift = drift,
        rhythmShift = rhythmShift,
        screenFragmentation = screenFragmentation,
        activityVariability = activityVariability,
        mobilityTrend = mobilityTrend,
        regularityTrend = regularityTrend,
    )
}

/**
 * §58 — 当日稳定视觉构图（layout/core openness/flow/density/texture/daily seed
 * 全部由 Identity + 当日 Ambient 向量确定性合成；一天内不漂移）。
 */
fun buildDailyComposition(
    identity: EchoIdentityGenome,
    vector: AmbientVector,
): EchoDailyComposition {
    val activity = vector.activation.coerceIn(0f, 1f)
    val deviation = vector.deviation.coerceIn(0f, 1f)
    val regularity = vector.regularity.coerceIn(0f, 1f)
    val coherence = lerp(identity.symmetryTendency, vector.confidence.coerceIn(0f, 1f), 0.4f)
    return EchoDailyComposition(
        flowSpeed = lerp(identity.motionPersonality, activity, 0.5f).coerceIn(0f, 1f),
        coherence = coherence,
        turbulence = lerp(1f - identity.symmetryTendency, deviation, 0.5f).coerceIn(0f, 1f),
        particleDensity = (0.25f + identity.textureFamily * 0.10f + vector.density.coerceIn(0f, 1f) * 0.25f)
            .coerceIn(0.1f, 1f),
        coreOpenness = lerp(identity.coreTopology, maturityOpenness(echoMaturity(0)), 0.3f),
        dispersion = ((1f - coherence) * 0.5f + 0.2f).coerceIn(0.15f, 0.8f),
        pulsePeriod = 5.6f - activity * 1.8f,
        depth = (0.3f + regularity * 0.7f).coerceIn(0.3f, 1f),
        brightness = (0.55f + activity * 0.45f).coerceIn(0.15f, 1f),
        contrast = (0.4f + deviation * 0.6f).coerceIn(0f, 1f),
        accentIntensity = (0.3f + coherence * 0.7f).coerceIn(0f, 1f),
        structureComplexity = identity.orbitGeometry,
    )
}

/**
 * §59 — 当下调制（30-60 分钟尺度）：由当前 Ambient 向量 + 昼夜时刻调制；
 * breathingPeriod = 呼吸脉动周期，noiseScale = 湍流噪声调制。
 */
fun buildMomentState(vector: AmbientVector, hourOfDay: Float): EchoMomentState {
    val activity = vector.activation.coerceIn(0f, 1f)
    val deviation = vector.deviation.coerceIn(0f, 1f)
    val dayFactor = dayBrightnessCurve(hourOfDay).coerceIn(0.15f, 1f)
    return EchoMomentState(
        breathingPeriod = (5.4f - activity * 1.6f).coerceIn(3.8f, 5.6f),
        noiseScale = (0.15f + deviation * 0.6f) * dayFactor,
    )
}

/**
 * §60 — 视觉层平滑（interpolation；绝不状态瞬切）。
 *
 * 视觉四层（identity/season/daily/moment）与 confidence 按 alpha 向新状态插值；
 * 运行时字段（updatedAt/sensingStatus/affective/narrative）直接采用新值。
 */
fun smoothPresenceState(
    previous: EchoPresenceState?,
    next: EchoPresenceState,
    alpha: Float,
): EchoPresenceState {
    val prev = previous ?: return next
    val a = alpha.coerceIn(0f, 1f)
    fun f(p: Float, n: Float): Float = lerp(p, n, a)
    return next.copy(
        confidence = f(prev.confidence, next.confidence),
        identityGenome = next.identityGenome.copy(
            coreTopology = f(prev.identityGenome.coreTopology, next.identityGenome.coreTopology),
            symmetryTendency = f(prev.identityGenome.symmetryTendency, next.identityGenome.symmetryTendency),
            orbitGeometry = f(prev.identityGenome.orbitGeometry, next.identityGenome.orbitGeometry),
            motionPersonality = f(prev.identityGenome.motionPersonality, next.identityGenome.motionPersonality),
        ),
        lifeSeason = next.lifeSeason.copy(drift = f(prev.lifeSeason.drift, next.lifeSeason.drift)),
        dailyComposition = next.dailyComposition.copy(
            flowSpeed = f(prev.dailyComposition.flowSpeed, next.dailyComposition.flowSpeed),
            coherence = f(prev.dailyComposition.coherence, next.dailyComposition.coherence),
            turbulence = f(prev.dailyComposition.turbulence, next.dailyComposition.turbulence),
            particleDensity = f(prev.dailyComposition.particleDensity, next.dailyComposition.particleDensity),
            coreOpenness = f(prev.dailyComposition.coreOpenness, next.dailyComposition.coreOpenness),
            dispersion = f(prev.dailyComposition.dispersion, next.dailyComposition.dispersion),
            pulsePeriod = f(prev.dailyComposition.pulsePeriod, next.dailyComposition.pulsePeriod),
            depth = f(prev.dailyComposition.depth, next.dailyComposition.depth),
            brightness = f(prev.dailyComposition.brightness, next.dailyComposition.brightness),
            contrast = f(prev.dailyComposition.contrast, next.dailyComposition.contrast),
            accentIntensity = f(prev.dailyComposition.accentIntensity, next.dailyComposition.accentIntensity),
            structureComplexity = f(prev.dailyComposition.structureComplexity, next.dailyComposition.structureComplexity),
        ),
        momentState = next.momentState.copy(
            breathingPeriod = f(prev.momentState.breathingPeriod, next.momentState.breathingPeriod),
            noiseScale = f(prev.momentState.noiseScale, next.momentState.noiseScale),
        ),
    )
}
