package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoLifeSeason
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoMomentState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.echoMaturity

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

/**
 * 确定性伪随机（SplitMix64 终混；ERA 21 修复：旧 LCG 只使用 seed 低 31 位，
 * 100 个随机 seed 出现近撞脸概率过高——身份派生必须消费完整 64 位熵）。
 */
private fun identityRandom(seed: Long, index: Int): Float {
    var x = seed + index.toLong() * -7046029254386353131L
    x = (x xor (x ushr 30)) * -4658895280553007687L
    x = (x xor (x ushr 27)) * -7723592293110705685L
    x = x xor (x ushr 31)
    return (x ushr 40 and 0xFFFFFF).toFloat() / 16777215f
}

/** 全熵混合（salt 区分同一 seed 的独立序列空间）。 */
private fun identityMix(seed: Long, salt: Int): Long {
    var x = seed xor (salt.toLong() shl 40)
    x = (x xor (x ushr 30)) * -4658895280553007687L
    x = (x xor (x ushr 27)) * -7723592293110705685L
    return x xor (x ushr 31)
}

/**
 * ERA 31 — 身份纹理族（0..3）与轨道几何（0..1）的 seed 纯函数。
 * 提取为独立函数：identity 派生与**帧计算**共用同一事实源——
 * 帧渲染器不持 identityGenome，但必须消费纹理/轨道（Part 17：
 * 用户差异来自 texture/structure，而不是只换颜色）。
 */
fun seedTextureFamily(seed: Long): Int =
    ((identityMix(seed, 3) ushr 8) % 4).toInt().let { if (it < 0) it + 4 else it }

/** 轨道几何（0 = 环状，1 = 弥散）。 */
fun seedOrbitGeometry(seed: Long): Float = identityRandom(seed, 2)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * ERA 21 §15 — 视觉身份距离（0..1，越大越不像同一个 ECHO）。
 *
 * 覆盖全部七维：topology / symmetry / orbit / motion personality /
 * texture family / color family / accent hue（圆周色相差）。
 * 拒绝「只有颜色不同」的伪多样性：单改 hue 时距离增量被限制在
 * 颜色权重（20%）内，而拓扑/对称/轨道任一维的差异都会显著拉大距离。
 */
fun identityDistance(a: EchoIdentityGenome, b: EchoIdentityGenome): Float {
    fun hueDistance(h1: Float, h2: Float): Float {
        val d = abs(h1 - h2) % 1f
        return minOf(d, 1f - d)
    }
    val topology = abs(a.coreTopology - b.coreTopology)
    val symmetry = abs(a.symmetryTendency - b.symmetryTendency)
    val orbit = abs(a.orbitGeometry - b.orbitGeometry)
    val motion = abs(a.motionPersonality - b.motionPersonality)
    val texture = abs(a.textureFamily - b.textureFamily) / 3f
    val hue = hueDistance(a.accentHue, b.accentHue)
    val colorFamily = abs(a.colorFamily - b.colorFamily) / 4f
    return (
        topology * 0.18f +
            symmetry * 0.18f +
            orbit * 0.18f +
            motion * 0.14f +
            texture * 0.12f +
            hue * 0.10f +
            colorFamily * 0.10f
        ).coerceIn(0f, 1f)
}

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
        accentHue = (identityMix(seed, 1) ushr 40 and 0xFFFFFF).toFloat() / 16777215f,
        colorFamily = ((identityMix(seed, 2) ushr 8) % 5).toInt().let { if (it < 0) it + 5 else it },
        textureFamily = seedTextureFamily(seed),
        coreTopology = 0.4f + 0.6f * identityRandom(seed, 0),
        symmetryTendency = 0.3f + 0.7f * identityRandom(seed, 1),
        orbitGeometry = seedOrbitGeometry(seed),
        motionPersonality = motionPersonality,
    )
}

/**
 * ERA 31 R31 — Day-0 SEED presence 单一构建点（AwakeningScreen 用）。
 *
 * 苏醒瞬间与随后进入的 ECHO Scene 必须是**同一个 ECHO**：这里用与运行时 Day-0 相同的
 * 参数源（baselineStability = 0f = AmbientEngine 无数据时的 regularity 初值；
 * motionPreference = 用户偏好，onboarding 完成前恒为 DEFAULT），派生出的 Identity
 * 与 PresenceRepository 第一次刷新完全一致（accent/color/texture/topology/orbit 只依赖
 * seed，motionPersonality 同参同值——零切换感由契约保证，而不是碰巧长得像）。
 */
fun dayZeroSeedPresence(
    identitySeed: Long,
    motionPreference: PresenceMotionLevel = PresenceMotionLevel.DEFAULT,
    now: java.time.Instant = java.time.Instant.now(),
): EchoPresenceState = EchoPresenceState(
    updatedAt = now,
    maturity = EchoMaturity.SEED,
    identityGenome = deriveIdentityGenome(
        seed = identitySeed,
        baselineStability = 0f, // AmbientEngine 无数据初值 regularity = 0f（同源）
        motionPreference = motionPreference,
    ),
)

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
fun computeLifeSeason(portraits: List<DailyPortraitDto>): EchoLifeSeason =
    computeLifeSeason(portraits, emptyList(), calendarDays = 0)

/**
 * §56/§57 + ERA 21 §16 — 人生阶段计算（含绝对节律时间线）。
 *
 * 画像维度是「相对 28 天基线」的：基线随漂移缓慢重定位，单纯的
 * 绝对漂移（如半年内活跃起点后移 20 分钟）在维度上可能永远显示 SIMILAR。
 * [wakeMinutes] 提供 (date, activeStartMinute) 绝对分钟序列，
 * 用于捕捉**相对维度看不到的慢漂移**（两半窗口中位数差 ≥ [ABS_SHIFT_MINUTES]）。
 *
 * [calendarDays] 为「认识你多久」（日历天数，自 ECHO 苏醒起算；>0 时驱动
 * phaseIndex）。portrait.baselineDays 是 28 天窗口内的分桶有效日（≤20），
 * 用它判断 90+ 阶段永远达不到——phaseIndex 的日历语义由此参数接管；
 * 传 0 时回退旧语义（Journey 历史重建等无苏醒锚点的调用方）。
 */
fun computeLifeSeason(
    portraits: List<DailyPortraitDto>,
    wakeMinutes: List<Pair<String, Double>>,
    calendarDays: Int = 0,
): EchoLifeSeason {
    val sorted = portraits.sortedBy { it.date }

    val phaseIndex = if (calendarDays > 0) {
        when {
            calendarDays < 7 -> 0
            calendarDays < 30 -> 1
            calendarDays < 90 -> 2
            else -> 3
        }
    } else {
        val baselineDays = sorted.maxOfOrNull { it.baselineDays } ?: 0
        when {
            baselineDays < 7 -> 0
            baselineDays < 30 -> 1
            baselineDays < 90 -> 2
            else -> 3
        }
    }
    if (sorted.isEmpty()) return EchoLifeSeason(phaseIndex = phaseIndex, drift = 0f)

    // RHYTHM 活跃起点：LATE/EARLY 比例漂移
    val rhythmValues = { d: DailyPortraitDto ->
        when (d.dimensionValue("RHYTHM")) {
            "LATER" -> 1f
            "EARLIER" -> 0f
            "SIMILAR", "VERY_SIMILAR" -> 0.5f
            else -> null
        }
    }
    val relativeShift = trendLabel(sorted, rhythmValues, "later", "earlier", 0.25f)

    // 绝对分钟两半窗口漂移（ERA 21 §16：慢漂移捕捉；与相对维度互相补充）
    val absolute = absoluteWakeTrend(wakeMinutes)
    val rhythmShift = when {
        relativeShift != "stable" -> relativeShift
        else -> absolute.label
    }
    val absDrift = absolute.drift

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

    // 跨日漂移幅度：相对两半均值差 + 绝对分钟漂移（归一化）+ z 波动归一化
    val half = sorted.size / 2
    val firstRhythm = sorted.take(half).mapNotNull(rhythmValues).averageOrNull() ?: 0.5f
    val secondRhythm = sorted.drop(half).mapNotNull(rhythmValues).averageOrNull() ?: 0.5f
    val zSpread = sorted.mapNotNull { d -> d.dimensions.values.mapNotNull { it.z }.map { it.toFloat() }.averageOrNull() }
        .let { vals -> if (vals.size < 2) 0f else (vals.maxOrNull() ?: 0f) - (vals.minOrNull() ?: 0f) }
    val relativeDrift = (abs(secondRhythm - firstRhythm) * 0.7f + zSpread.coerceIn(0f, 2f) * 0.15f).coerceIn(0f, 1f)
    val drift = maxOf(relativeDrift, absDrift * 0.7f).coerceIn(0f, 1f)

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

/** 绝对活跃起点两半窗口中位数漂移（ERA 21 §16：慢漂移检测）。 */
private data class AbsoluteWakeTrend(val label: String, val drift: Float)

private const val ABS_SHIFT_MINUTES = 12.0
private const val ABS_DRIFT_NORMALIZER_MINUTES = 120.0

private fun absoluteWakeTrend(wakeMinutes: List<Pair<String, Double>>): AbsoluteWakeTrend {
    val sorted = wakeMinutes.sortedBy { it.first }
    if (sorted.size < 8) return AbsoluteWakeTrend("stable", 0f)
    val half = sorted.size / 2
    val first = medianOf(sorted.take(half).map { it.second }) ?: return AbsoluteWakeTrend("stable", 0f)
    val second = medianOf(sorted.drop(half).map { it.second }) ?: return AbsoluteWakeTrend("stable", 0f)
    val delta = second - first
    val label = when {
        delta > ABS_SHIFT_MINUTES -> "later"
        delta < -ABS_SHIFT_MINUTES -> "earlier"
        else -> "stable"
    }
    val drift = (abs(delta) / ABS_DRIFT_NORMALIZER_MINUTES).toFloat().coerceIn(0f, 1f)
    return AbsoluteWakeTrend(label, drift)
}

/** 中位数（偶长度取中间两值平均；异常日稳健）。 */
private fun medianOf(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
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
