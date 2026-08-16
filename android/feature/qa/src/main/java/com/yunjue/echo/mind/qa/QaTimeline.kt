package com.yunjue.echo.mind.qa
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.BehaviorState
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.EchoLifeSeason

import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.localportrait.LocalBaselineSnapshot
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.localportrait.buildLocalBaseline
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.presence.AmbientEngine
import com.yunjue.echo.mind.presence.AmbientResult
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualParameters
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.LifeSeasonTracker
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.buildDailyComposition
import com.yunjue.echo.mind.presence.buildMomentState
import com.yunjue.echo.mind.presence.computeLifeSeason
import com.yunjue.echo.mind.presence.deriveIdentityGenome
import java.time.LocalDate
import java.time.ZoneId

/**
 * Product Quality Era（ERA 19 §3/§4）— 单用户完整产品快照（某安装日）。
 *
 * 数据流与生产 [PresenceRepository] 完全同构：
 * 模拟观测 → buildLocalBaseline → AmbientEngine → 画像镜像 → computeLifeSeason
 * → LifeSeasonTracker（慢变化）→ Identity Genome → Daily/Moment → EchoVisualMapper → 场景帧。
 * 唯一差异：输入为确定性模拟观测（QaDaySimulator），且 tracker 按「天」喂入。
 */
data class QaDaySnapshot(
    val dayIndex: Int,
    val date: LocalDate,
    val aggregate: LocalDayAggregate,
    val baseline: LocalBaselineSnapshot?,
    val ambient: AmbientResult,
    val portrait: DailyPortraitDto?,
    val identity: EchoIdentityGenome,
    /** 原始 computeLifeSeason 输出（60 天窗口）。 */
    val seasonRaw: EchoLifeSeason,
    /** LifeSeasonTracker 生效值（含 transition 平滑）。 */
    val season: EchoLifeSeason,
    val tracker: LifeSeasonTracker,
    val presence: EchoPresenceState,
    val appVisual: EchoVisualParameters,
    val lockVisual: EchoVisualParameters,
)

class QaTimeline(
    val profile: QaProfileSpec,
    private val epoch: LocalDate = LocalDate.parse(QaProfiles.EPOCH_DATE),
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai"),
) {
    private val aggregateCache = HashMap<Int, LocalDayAggregate>()
    private val snapshotCache = HashMap<Int, QaDaySnapshot>()
    private var trackerState: LifeSeasonTracker? = null
    private var trackerDay = -1

    fun dateOf(dayIndex: Int): LocalDate = epoch.plusDays(dayIndex.toLong())

    /** 某日确定性观测（惰性缓存；同一 profile 跨实例逐字节一致）。 */
    fun aggregate(dayIndex: Int): LocalDayAggregate =
        aggregateCache.getOrPut(dayIndex) { QaDaySimulator.day(profile, dayIndex, dateOf(dayIndex)) }

    /** 某日基线（近 28 天有效日窗口，镜像 buildLocalBaseline 语义）。 */
    fun baselineFor(dayIndex: Int): LocalBaselineSnapshot? {
        val from = (dayIndex - 28).coerceAtLeast(0)
        val past = (from until dayIndex).map { aggregate(it) }
        return buildLocalBaseline(dateOf(dayIndex), past)
    }

    /** 某日画像（镜像后端 compute_dimensions）。 */
    fun portraitFor(dayIndex: Int): DailyPortraitDto? {
        val agg = aggregate(dayIndex)
        if (agg.coverageScore < QaDaySimulator.MIN_COVERAGE_FOR_START) return null
        return QaPortraitMirror.portrait(
            date = dateOf(dayIndex).toString(),
            today = agg,
            baseline = baselineFor(dayIndex),
            timezone = profile.timezone,
        )
    }

    /** 截至 dayIndex（含）的画像时间线；镜像生产 60 天窗口。 */
    fun portraitsUpTo(dayIndex: Int): List<DailyPortraitDto> =
        (0..dayIndex).mapNotNull { portraitFor(it) }.takeLast(60)

    /** 全历史画像时间线（年视图等长窗口场景；不截断）。 */
    fun allPortraitsUpTo(dayIndex: Int): List<DailyPortraitDto> =
        (0..dayIndex).mapNotNull { portraitFor(it) }

    /** 绝对活跃起点序列（date, minute），60 天窗口；喂给 computeLifeSeason 的慢漂移检测。 */
    fun wakeSeriesUpTo(dayIndex: Int): List<Pair<String, Double>> =
        (0..dayIndex).mapNotNull { i ->
            aggregate(i).activeStartMinute?.let { dateOf(i).toString() to it.toDouble() }
        }.takeLast(60)

    /** 身份稳定性输入（生产镜像：当日 ambient.regularity）。 */
    private fun identityFor(dayIndex: Int, ambient: AmbientResult): EchoIdentityGenome =
        deriveIdentityGenome(
            seed = profile.identitySeed,
            baselineStability = ambient.vector.regularity,
            motionPreference = profile.motionPreference,
        )

    /**
     * 某安装日的完整快照。要求按 dayIndex 单调递增调用（tracker 按天推进；
     * 随机访问请新建 QaTimeline 实例——确定性保证结果一致）。
     */
    fun snapshot(dayIndex: Int): QaDaySnapshot {
        snapshotCache[dayIndex]?.let { return it }
        require(dayIndex >= trackerDay) { "snapshot 必须单调递增调用（当前 $trackerDay，请求 $dayIndex）" }

        val date = dateOf(dayIndex)
        val agg = aggregate(dayIndex)
        val baseline = baselineFor(dayIndex)
        val ambient = AmbientEngine.compute(agg, baseline)
        val portrait = portraitFor(dayIndex)

        // 成长成熟度：日历语义（自苏醒 Day 0 起，当天 = 0 天 → SEED），
        // 镜像生产 PresenceRepository 的 awakenedAtEpochMs 锚点语义
        // （baseline.validDays 为 28 天窗口分桶日，不可用于 MATURE 判定）
        val calendarDays = dayIndex
        val seasonRaw = computeLifeSeason(portraitsUpTo(dayIndex), wakeSeriesUpTo(dayIndex), calendarDays = calendarDays)
        val tracker = if (trackerState == null) {
            LifeSeasonTracker.start(seasonRaw, date)
        } else {
            trackerState!!.update(seasonRaw, date)
        }
        trackerState = tracker
        trackerDay = dayIndex

        val identity = identityFor(dayIndex, ambient)
        val presence = EchoPresenceState(
            updatedAt = date.atStartOfDay(zone).toInstant(),
            maturity = echoMaturity(calendarDays),
            rhythmState = RhythmState(
                activityLevel = ambient.vector.activation,
                rhythmDelta = tracker.effective.drift,
                regularity = ambient.vector.regularity,
                coverage = ambient.coverage,
            ),
            behaviorState = BehaviorState(
                density = ambient.vector.density,
                deviation = ambient.vector.deviation,
            ),
            affectiveState = null,
            confidence = ambient.vector.confidence,
            identityGenome = identity,
            lifeSeason = tracker.effective,
            dailyComposition = buildDailyComposition(identity, ambient.vector),
            momentState = buildMomentState(ambient.vector, hourOfDay = 12f),
        )
        val appVisual = EchoVisualMapper.map(presence, 12f, SurfaceMode.APP)
        val lockVisual = EchoVisualMapper.map(presence, 12f, SurfaceMode.LOCK_SAFE)

        val snap = QaDaySnapshot(
            dayIndex = dayIndex,
            date = date,
            aggregate = agg,
            baseline = baseline,
            ambient = ambient,
            portrait = portrait,
            identity = identity,
            seasonRaw = seasonRaw,
            season = tracker.effective,
            tracker = tracker,
            presence = presence,
            appVisual = appVisual,
            lockVisual = lockVisual,
        )
        snapshotCache[dayIndex] = snap
        return snap
    }

    /** 从 Day 0 顺序推进到 dayIndex（随机访问入口：新实例从零重放）。 */
    fun snapshotAt(dayIndex: Int): QaDaySnapshot {
        snapshotCache[dayIndex]?.let { return it }
        for (d in 0..dayIndex) {
            if (snapshotCache.containsKey(d)) continue
            snapshot(d)
        }
        return snapshotCache.getValue(dayIndex)
    }

    companion object {
        /** 场景帧时间锚（与 Journey canonical 一致，保证帧可复现）。 */
        fun frameTimeSeconds(): Float = JOURNEY_CANONICAL_TIME_SECONDS

        /**
         * V3：QA 帧走 production organism 管线，与 Canonical 历史重建同一参数链
         * （EchoVisualMapper → EchoVisualParameters → genomeFromParams → compute）——
         * canonical roundtrip 与当日渲染同帧由单一映射保证。
         */
        fun computeFrame(snap: QaDaySnapshot, surface: SurfaceMode = SurfaceMode.APP): com.yunjue.echo.mind.visual.render.OrganismFrame {
            val params = when (surface) {
                SurfaceMode.APP -> snap.appVisual
                else -> snap.lockVisual
            }
            val genome = com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(
                params, snap.identity.seed,
            )
            return com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    genome,
                    when (surface) {
                        SurfaceMode.APP -> com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE
                        SurfaceMode.DREAM -> com.yunjue.echo.mind.visual.surface.EchoSurface.DREAM_AMBIENT
                        SurfaceMode.HOME_WALLPAPER ->
                            com.yunjue.echo.mind.visual.surface.EchoSurface.WALLPAPER_VISUAL_ONLY
                        else -> com.yunjue.echo.mind.visual.surface.EchoSurface.LOCK_PUBLIC_SAFE
                    },
                    frameTimeSeconds(),
                ),
                width = 1080f,
                height = 2340f,
                options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                    maturityName = snap.presence.maturity.name,
                ),
            )
        }
    }
}
