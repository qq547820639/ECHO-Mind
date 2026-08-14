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
 * `v1|updatedAtEpochMs|sensingStatus|maturity|activation|regularity|density|deviation|confidence|coverage|seed|accentHue`
 */
object EchoPresenceCodec {

    const val VERSION = "v1"

    fun encode(state: EchoPresenceState): String = listOf(
        VERSION,
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
    ).joinToString("|")

    /** 解析失败一律返回 null（fail-closed：渲染中性占位，绝不编造状态）。 */
    fun decode(raw: String?): EchoPresenceState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parts = raw.split("|")
            if (parts.size != 13 || parts[0] != VERSION) return null
            val sensing = SensingRuntimeStatus.entries.firstOrNull { it.name == parts[2] } ?: return null
            val maturity = EchoMaturity.entries.firstOrNull { it.name == parts[3] } ?: return null
            EchoPresenceState(
                updatedAt = Instant.ofEpochSecond(parts[1].toLong()),
                sensingStatus = sensing,
                maturity = maturity,
                rhythmState = RhythmState(
                    activityLevel = parts[4].toFloat(),
                    regularity = parts[5].toFloat(),
                    coverage = parts[9].toFloat(),
                    rhythmDelta = parts[12].toFloat(),
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
        }.getOrNull()
    }
}
