package com.yunjue.echo.mind.data
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.BehaviorState

import android.content.Context
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.data.PassiveSensingPrefs
import com.yunjue.echo.mind.presence.AmbientEngine
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.buildDailyComposition
import com.yunjue.echo.mind.presence.buildMomentState
import com.yunjue.echo.mind.presence.computeLifeSeason
import com.yunjue.echo.mind.presence.deriveIdentityGenome
import com.yunjue.echo.mind.presence.smoothPresenceState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoStateStore
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.SensingRuntimeInputs
import com.yunjue.echo.mind.sensing.hasCoreSensorHardware
import com.yunjue.echo.mind.sensing.hasMicPermissionGranted
import com.yunjue.echo.mind.model.resolveSensingRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ERA 2 — Presence Repository：EchoPresenceState 的组装入口。
 *
 * 全系统单一状态原则（Master Prompt PART 52/53）：
 * 本仓库是状态唯一组装方，发布到 [EchoStateStore]；
 * Today UI / Wallpaper / Dream 都是只读消费者，禁止各自计算。
 *
 * 低频更新：分钟级 refresh（Today 进入时 / 窗口持久化后触发），与渲染帧率无关。
 */
class PresenceRepository(
    private val dataSource: LocalPortraitDataSource,
    private val preferences: AppPreferences,
    private val passiveSensingPrefs: PassiveSensingPrefs,
    private val appContext: Context,
    private val store: EchoStateStore,
) :
    com.yunjue.echo.mind.ports.PresenceStateSource,
    com.yunjue.echo.mind.ports.PresenceStateWriter {
    companion object {
        /** 运行时采集心跳阈值：超过视为假活（区别于趋势页 3 天阈值）。 */
        const val RUNTIME_FRESH_MS = 60L * 60L * 1000L
    }

    private val _state = MutableStateFlow<EchoPresenceState?>(null)

    /** ERA 21 §18：日构图按日历日固化（跨日才重算）。 */
    private val dailyCompositionGate = com.yunjue.echo.mind.presence.DailyCompositionGate()

    /** 当前 Presence 状态（null = 尚未组装；消费者显示中性占位，禁止编造）。 */
    override val state: StateFlow<EchoPresenceState?> = _state

    /** 组装并发布最新状态（幂等；失败不抛——保持中性占位）。 */
    override suspend fun refresh() {
        val presence = assemble() ?: return
        _state.value = presence
        store.publish(presence)
        // ERA 3：落盘快照（Wallpaper / Dream 进程只读该快照，不初始化业务容器）
        preferences.echoPresenceSnapshot = com.yunjue.echo.mind.presence.EchoPresenceCodec.encode(presence)
    }

    private suspend fun assemble(): EchoPresenceState? {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()

        // 1. 证据：当日聚合 + 个人基线（本地只读）
        val inputs = dataSource.presenceInputs(preferences.userId, today, zone)

        // 2. Ambient：中性状态向量（数据不足 → UNKNOWN，不硬判）
        val ambient = AmbientEngine.compute(inputs.today, inputs.baseline)

        // 成长成熟度（ERA 21 修复）：baseline.validDays 是 28 天窗口内的分桶有效日
        // （weekday 桶 ≤ 20），用它判断 MATURE（≥28）永远达不到。
        // 成熟度语义 = 「认识你多久」→ 自苏醒锚点（awakenedAtEpochMs）起的日历天数。
        val calendarDays = maturityCalendarDays(preferences.awakenedAtEpochMs, today, zone)
            .coerceAtLeast(0)

        // 3. 感知运行时：系统真实状态为输入；「关闭」只能来自用户行为
        val micEnabled = passiveSensingPrefs.micEnabled.first()
        val runtime = resolveSensingRuntimeStatus(
            SensingRuntimeInputs(
                everAuthorized = preferences.onboardingCompleted,
                userConsentOn = passiveSensingPrefs.passiveSensingEnabled.first(),
                serviceStarted = preferences.sensingActive,
                hasEverCollected = preferences.lastCollectionTimestamp > 0L,
                collectionFresh = now.toEpochMilli() - preferences.lastCollectionTimestamp <= RUNTIME_FRESH_MS,
                coreSensorsAvailable = hasCoreSensorHardware(appContext),
                // 真正的降级信号：用户开启过麦克风但系统权限被撤回
                optionalCapabilityDegraded = micEnabled && !hasMicPermissionGranted(appContext),
                persistenceFailing = preferences.consecutivePersistenceFailures > 0,
            )
        )

        // 4. ERA 14 §53-§61：Long-term Identity 真实数据流
        // Identity Genome（installation random seed + 长期基线 + 视觉偏好；§54 禁身份设备指纹）
        val identity = deriveIdentityGenome(
            seed = preferences.identitySeed,
            baselineStability = ambient.vector.regularity,
            motionPreference = when (preferences.presenceMotionLevel) {
                "QUIET" -> PresenceMotionLevel.QUIET
                "LIVELY" -> PresenceMotionLevel.LIVELY
                else -> PresenceMotionLevel.DEFAULT
            },
        )
        // Life Season（§56/§57：近 60 天画像时间线；中性词表）
        // ERA 21 §16：同时取绝对活跃起点分钟——画像维度相对基线，慢漂移需绝对序列才能看见
        val timeline = runCatching {
            dataSource.computeTimelineWithAggregates(
                userId = preferences.userId,
                days = 60,
                endDate = today,
                zoneId = zone,
            )
        }.getOrDefault(Pair(emptyList<DailyPortraitDto>(), emptyMap<LocalDate, LocalDayAggregate>()))
        val portraits = timeline.first
        val wakeMinutes = timeline.second
            .filterValues { it.activeStartMinute != null }
            .toSortedMap()
            .map { (date, agg) -> date.toString() to agg.activeStartMinute!!.toDouble() }
        val season = computeLifeSeason(portraits, wakeMinutes, calendarDays = calendarDays)
        // Daily Composition（§58：日级稳定）+ Moment Modulation（§59：分钟级）
        // ERA 21 §18：日构图按日历日固化（同日 refresh 不重算，避免一天内构图漂移）；
        // Moment 继续消费当日向量（分钟级呼吸）
        val hourOfDay = now.atZone(zone).hour + now.atZone(zone).minute / 60f
        val daily = dailyCompositionGate.compositionFor(today) { buildDailyComposition(identity, ambient.vector) }
        val moment = buildMomentState(ambient.vector, hourOfDay)

        val assembled = EchoPresenceState(
            updatedAt = now,
            sensingStatus = runtime,
            maturity = echoMaturity(calendarDays),
            rhythmState = RhythmState(
                activityLevel = ambient.vector.activation,
                rhythmDelta = season.drift, // §61：真实跨日节律漂移（ERA 14 起非 0）
                regularity = ambient.vector.regularity,
                coverage = ambient.coverage,
            ),
            behaviorState = BehaviorState(
                density = ambient.vector.density,
                deviation = ambient.vector.deviation,
            ),
            affectiveState = null, // AFFECTIVE_CONTRACT 未建立前恒 null
            confidence = ambient.vector.confidence,
            identityGenome = identity,
            lifeSeason = season,
            dailyComposition = daily,
            momentState = moment,
        )

        // §60：视觉层平滑（interpolation；不瞬切）
        return smoothPresenceState(_state.value, assembled, alpha = 0.35f)
    }

    /** 自苏醒锚点起的日历天数（锚点缺失 = 0，成熟度保持 SEED 语义）。 */
    private fun maturityCalendarDays(awakenedAtEpochMs: Long, today: LocalDate, zone: ZoneId): Int {
        if (awakenedAtEpochMs <= 0L) return 0
        val awakenedDate = Instant.ofEpochMilli(awakenedAtEpochMs).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(awakenedDate, today).toInt()
    }
}
