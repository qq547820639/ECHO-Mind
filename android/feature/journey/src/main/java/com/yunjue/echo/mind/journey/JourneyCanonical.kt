package com.yunjue.echo.mind.journey
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.EchoVisualParameters

/**
 * ERA 16 §83/§84 — Canonical Daily State 与历史重建。
 *
 * Journey 最终不是 Trend，而是 Personal Visual Memory System：
 * - [JourneyCanonicalDay] 每天保存一份「当天 ECHO 长什么样」的最小事实
 *   （visual seed / visual params / identity reference / maturity / key evidence ids——
 *   只存参数，绝不存 bitmap）；
 * - [reconstructJourneyFrame] 一年以后仍能确定性渲染那一天（§84）；
 * - [JourneyCanonicalCodec] 为持久化编解码（纯 Kotlin，fail-closed：解析失败返回 null，
 *   渲染弥散占位，绝不编造状态）。
 */

/** §83 — 某一天的 ECHO 视觉事实（Canonical Daily State，无 bitmap）。 */
data class JourneyCanonicalDay(
    /** 本地日期 yyyy-MM-dd（肖像时间线的同一日期语义）。 */
    val date: String,
    /** 视觉种子（Identity Genome 同源 seed；保证跨年渲染与当天一致）。 */
    val visualSeed: Long,
    /** 当日 12:00 基准视觉参数（白天基准亮度，Journey 一致性优先）。 */
    val visualParams: EchoVisualParameters,
    /** 当天的身份参考（Identity Genome 快照；§86 长期身份演化数据源）。 */
    val identityReference: EchoIdentityGenome,
    /** 当天成熟度。 */
    val maturity: EchoMaturity,
    /** 关键证据 id（形如 portrait:yyyy-MM-dd；数量受限，仅存 id 不存内容）。 */
    val keyEvidenceIds: List<String>,
    /** 落盘时间（epoch ms）。 */
    val createdAtEpochMs: Long,
)

/** Canonical 快照基准时刻：固定正午 12:00（同一天永远同一帧，与昼夜曲线解耦）。 */
const val JOURNEY_CANONICAL_HOUR = 12f

/**
 * §83 — 从当前 Presence State 构建当天 Canonical Daily State。
 *
 * 视觉参数经正式冻结的 [EchoVisualMapper]（§62 唯一入口）映射；
 * identity 引用直接快照（§86 演化比较用）。
 */
fun buildCanonicalDay(
    date: String,
    state: EchoPresenceState,
    keyEvidenceIds: List<String>,
    createdAtEpochMs: Long,
): JourneyCanonicalDay = JourneyCanonicalDay(
    date = date,
    visualSeed = state.identityGenome.seed,
    visualParams = EchoVisualMapper.map(
        state = state,
        hourOfDay = JOURNEY_CANONICAL_HOUR,
    ),
    identityReference = state.identityGenome,
    maturity = state.maturity,
    keyEvidenceIds = keyEvidenceIds,
    createdAtEpochMs = createdAtEpochMs,
)

/**
 * §84 — 历史重建：一年以后仍能渲染某一天的 ECHO。
 *
 * 优先用 Canonical Daily State（确定性，跨年稳定）；缺失时回退当日画像派生参数；
 * 两者皆无 → null（渲染弥散占位，不编造）。同输入永远同一帧。
 */
fun reconstructJourneyFrame(
    canonical: JourneyCanonicalDay?,
    fallbackPortrait: DailyPortraitDto?,
    fallbackSeed: Long,
    width: Float,
    height: Float,
): com.yunjue.echo.mind.visual.render.OrganismFrame? {
    // V3：历史帧经同一 production organism 管线重建（存参数不存图原则不变；
    // maturity 取自 canonical/presence 真值——§13 乘数影响丰富度拓扑）。
    if (canonical != null) {
        val genome = JourneyOrganismVisuals.genomeFromParams(canonical.visualParams, canonical.visualSeed)
        return com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
            spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                JOURNEY_CANONICAL_TIME_SECONDS,
            ),
            width = width,
            height = height,
            options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                maturityName = canonical.maturity.name,
            ),
        )
    }
    val params = journeyDayParams(fallbackPortrait) ?: return null
    val genome = JourneyOrganismVisuals.genomeFromParams(params, fallbackSeed)
    return com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
        spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
            genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
            JOURNEY_CANONICAL_TIME_SECONDS,
        ),
        width = width,
        height = height,
        options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
            maturityName = portraitMaturityProxy(fallbackPortrait!!, earliestDate = null).name,
        ),
    )
}

/**
 * Canonical Daily State 持久化编解码。
 *
 * v2（V3 §H）：`v2|date|seed|maturity|params(18)|identity(8)|evidenceIdsCsv|createdAtEpochMs`
 * ——params 扩展为 canonical 18 字段（dataClarity/haloIntensity/momentIntensity/
 * filamentDensity/seasonPhase/dayComposition 进入持久化，roundtrip 无损）。
 * v1（历史快照）：params(12)，新字段解析为默认 0f（fail-closed 向后兼容）。
 */
object JourneyCanonicalCodec {

    const val VERSION = "v2"
    private const val VERSION_V1 = "v1"

    fun encode(day: JourneyCanonicalDay): String = buildString {
        append(VERSION).append('|')
        append(day.date).append('|')
        append(day.visualSeed).append('|')
        append(day.maturity.name).append('|')
        append(day.visualParams.flowSpeed).append('|')
        append(day.visualParams.coherence).append('|')
        append(day.visualParams.turbulence).append('|')
        append(day.visualParams.particleDensity).append('|')
        append(day.visualParams.coreOpenness).append('|')
        append(day.visualParams.dispersion).append('|')
        append(day.visualParams.pulsePeriodSeconds).append('|')
        append(day.visualParams.depth).append('|')
        append(day.visualParams.brightness).append('|')
        append(day.visualParams.contrast).append('|')
        append(day.visualParams.accentIntensity).append('|')
        append(day.visualParams.structureComplexity).append('|')
        append(day.visualParams.dataClarity).append('|')
        append(day.visualParams.haloIntensity).append('|')
        append(day.visualParams.momentIntensity).append('|')
        append(day.visualParams.filamentDensity).append('|')
        append(day.visualParams.seasonPhase).append('|')
        append(day.visualParams.dayComposition).append('|')
        append(day.identityReference.seed).append('|')
        append(day.identityReference.accentHue).append('|')
        append(day.identityReference.colorFamily).append('|')
        append(day.identityReference.textureFamily).append('|')
        append(day.identityReference.coreTopology).append('|')
        append(day.identityReference.symmetryTendency).append('|')
        append(day.identityReference.orbitGeometry).append('|')
        append(day.identityReference.motionPersonality).append('|')
        append(day.keyEvidenceIds.joinToString(",")).append('|')
        append(day.createdAtEpochMs)
    }

    /** 解析失败一律返回 null（fail-closed：历史渲染走弥散占位，绝不编造）。 */
    fun decode(raw: String?): JourneyCanonicalDay? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parts = raw.split("|")
            // v2 = 32 段（params 18）；v1 = 26 段（params 12，新字段默认 0）
            val v2 = parts.size == 32 && parts[0] == VERSION
            val v1 = parts.size == 26 && parts[0] == VERSION_V1
            if (!v1 && !v2) return null
            val date = parts[1]
            if (!CANONICAL_DATE_REGEX.matches(date)) return null
            val maturity = EchoMaturity.entries.firstOrNull { it.name == parts[3] } ?: return null
            val p = { i: Int -> parts[i].toFloat() }
            val evidence = parts[30].ifEmpty { "" }
                .split(",")
                .filter { it.isNotBlank() }
            JourneyCanonicalDay(
                date = date,
                visualSeed = parts[2].toLong(),
                visualParams = EchoVisualParameters(
                    flowSpeed = p(4),
                    coherence = p(5),
                    turbulence = p(6),
                    particleDensity = p(7),
                    coreOpenness = p(8),
                    dispersion = p(9),
                    pulsePeriodSeconds = p(10),
                    depth = p(11),
                    brightness = p(12),
                    contrast = p(13),
                    accentIntensity = p(14),
                    structureComplexity = p(15),
                    dataClarity = if (v2) p(16) else 0f,
                    haloIntensity = if (v2) p(17) else 0f,
                    momentIntensity = if (v2) p(18) else 0f,
                    filamentDensity = if (v2) p(19) else 0f,
                    seasonPhase = if (v2) p(20) else 0f,
                    dayComposition = if (v2) p(21) else 0f,
                ),
                identityReference = EchoIdentityGenome(
                    seed = parts[22].toLong(),
                    accentHue = p(23),
                    colorFamily = parts[24].toInt(),
                    textureFamily = parts[25].toInt(),
                    coreTopology = p(26),
                    symmetryTendency = p(27),
                    orbitGeometry = p(28),
                    motionPersonality = p(29),
                ),
                maturity = maturity,
                keyEvidenceIds = evidence,
                createdAtEpochMs = parts[31].toLong(),
            )
        }.getOrNull()
    }

    private val CANONICAL_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
}
