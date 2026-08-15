package com.yunjue.echo.mind.presence

import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import java.time.Instant

/**
 * ERA 3 — EchoPresenceState 快照编解码（纯 Kotlin，无 org.json 依赖）。
 *
 * 用途：PresenceRepository 每次刷新后落盘最近一版快照；
 * Wallpaper / Dream 进程只读该快照（不初始化业务容器，不运行 Intelligence pipeline——
 * Master Prompt PART 60：Wallpaper 只消费 EchoPresenceState）。
 *
 * 格式 v1（'|' 分隔，无敏感字段；快照只含视觉/状态摘要，绝不含叙事文字）：
 * `v1|updatedAtEpochMs|sensingStatus|maturity|activation|regularity|density|deviation|confidence|coverage|seed|accentHue|rhythmDelta`
 *
 * ERA 53（§52 审计第 3 轮）格式 v2：补齐 Identity 四层（§52 跨进程真值）——
 * 进程死亡后 Wallpaper/Dream 恢复的 ECHO 与前台同一 Identity（全 8 项身份字段 +
 * LifeSeason 7 字段 + DailyComposition 12 字段 + MomentState 2 字段）。
 * 叙事字段仍永不入快照（Public Safe 构造保证）；v1 快照继续可解（新字段取结构默认，
 * 与 v1 时代的语义一致——回退旧推导路径）。
 */
object EchoPresenceCodec {

    const val VERSION_V1 = "v1"
    const val VERSION_V2 = "v2"

    fun encode(state: EchoPresenceState): String = listOf(
        VERSION_V2,
        state.updatedAt.epochSecond.toString(),
        state.sensingStatus.name,
        state.maturity.name,
        state.rhythmState.activityLevel.toString(),
        state.rhythmState.regularity.toString(),
        state.behaviorState.density.toString(),
        state.behaviorState.deviation.toString(),
        state.confidence.toString(),
        state.rhythmState.coverage.toString(),
        state.identityGenome.seed.toString(),
        state.identityGenome.accentHue.toString(),
        state.rhythmState.rhythmDelta.toString(),
        // Identity Genome 剩余 6 项（§53 形态连续性）
        state.identityGenome.colorFamily.toString(),
        state.identityGenome.textureFamily.toString(),
        state.identityGenome.coreTopology.toString(),
        state.identityGenome.symmetryTendency.toString(),
        state.identityGenome.orbitGeometry.toString(),
        state.identityGenome.motionPersonality.toString(),
        // Life Season 7 项（§56 跨进程保留节律漂移）
        state.lifeSeason.phaseIndex.toString(),
        state.lifeSeason.drift.toString(),
        state.lifeSeason.rhythmShift,
        state.lifeSeason.screenFragmentation,
        state.lifeSeason.activityVariability,
        state.lifeSeason.mobilityTrend,
        state.lifeSeason.regularityTrend,
        // Daily Composition 12 项（§58 当天稳定构图跨进程一致）
        state.dailyComposition.flowSpeed.toString(),
        state.dailyComposition.coherence.toString(),
        state.dailyComposition.turbulence.toString(),
        state.dailyComposition.particleDensity.toString(),
        state.dailyComposition.coreOpenness.toString(),
        state.dailyComposition.dispersion.toString(),
        state.dailyComposition.pulsePeriod.toString(),
        state.dailyComposition.depth.toString(),
        state.dailyComposition.brightness.toString(),
        state.dailyComposition.contrast.toString(),
        state.dailyComposition.accentIntensity.toString(),
        state.dailyComposition.structureComplexity.toString(),
        // Moment State 2 项（§59 分钟级调制）
        state.momentState.breathingPeriod.toString(),
        state.momentState.noiseScale.toString(),
    ).joinToString("|")

    /** 解析失败一律返回 null（fail-closed：渲染中性占位，绝不编造状态）。 */
    fun decode(raw: String?): EchoPresenceState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parts = raw.split("|")
            val sensing = parts.getOrNull(2)
                ?.let { name -> SensingRuntimeStatus.entries.firstOrNull { it.name == name } }
                ?: return null
            val maturity = parts.getOrNull(3)
                ?.let { name -> EchoMaturity.entries.firstOrNull { it.name == name } }
                ?: return null

            val version = parts.getOrNull(0) ?: return null
            if (version != VERSION_V1 && version != VERSION_V2) return null
            if (version == VERSION_V1 && parts.size != 13 || version == VERSION_V2 && parts.size < 40) return null

            val base = EchoPresenceState(
                updatedAt = Instant.ofEpochSecond(parts[1].toLong()),
                sensingStatus = sensing,
                maturity = maturity,
                rhythmState = RhythmState(
                    activityLevel = parts[4].toFloat(),
                    regularity = parts[5].toFloat(),
                    coverage = parts[9].toFloat(),
                    rhythmDelta = parts.getOrNull(12)?.toFloat() ?: 0f,
                ),
                behaviorState = BehaviorState(
                    density = parts[6].toFloat(),
                    deviation = parts[7].toFloat(),
                ),
                confidence = parts[8].toFloat(),
                identityGenome = EchoIdentityGenome(
                    seed = parts[10].toLong(),
                    accentHue = parts[11].toFloat(),
                ),
            )
            if (version != VERSION_V2 || parts.size < 40) return base // v1/旧快照：结构默认
            base.copy(
                identityGenome = base.identityGenome.copy(
                    colorFamily = parts[13].toInt(),
                    textureFamily = parts[14].toInt(),
                    coreTopology = parts[15].toFloat(),
                    symmetryTendency = parts[16].toFloat(),
                    orbitGeometry = parts[17].toFloat(),
                    motionPersonality = parts[18].toFloat(),
                ),
                lifeSeason = EchoLifeSeason(
                    phaseIndex = parts[19].toInt(),
                    drift = parts[20].toFloat(),
                    rhythmShift = parts[21],
                    screenFragmentation = parts[22],
                    activityVariability = parts[23],
                    mobilityTrend = parts[24],
                    regularityTrend = parts[25],
                ),
                dailyComposition = EchoDailyComposition(
                    flowSpeed = parts[26].toFloat(),
                    coherence = parts[27].toFloat(),
                    turbulence = parts[28].toFloat(),
                    particleDensity = parts[29].toFloat(),
                    coreOpenness = parts[30].toFloat(),
                    dispersion = parts[31].toFloat(),
                    pulsePeriod = parts[32].toFloat(),
                    depth = parts[33].toFloat(),
                    brightness = parts[34].toFloat(),
                    contrast = parts[35].toFloat(),
                    accentIntensity = parts[36].toFloat(),
                    structureComplexity = parts[37].toFloat(),
                ),
                momentState = EchoMomentState(
                    breathingPeriod = parts[38].toFloat(),
                    noiseScale = parts[39].toFloat(),
                ),
            )
        }.getOrNull()
    }
}
