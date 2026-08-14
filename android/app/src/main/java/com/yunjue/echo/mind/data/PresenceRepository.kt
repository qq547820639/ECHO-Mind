package com.yunjue.echo.mind.data

import android.content.Context
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.PassiveSensingPrefs
import com.yunjue.echo.mind.presence.AmbientEngine
import com.yunjue.echo.mind.presence.BehaviorState
import com.yunjue.echo.mind.presence.EchoIdentityGenome
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoStateStore
import com.yunjue.echo.mind.presence.RhythmState
import com.yunjue.echo.mind.presence.echoMaturity
import com.yunjue.echo.mind.sensing.SensingRuntimeInputs
import com.yunjue.echo.mind.sensing.hasCoreSensorHardware
import com.yunjue.echo.mind.sensing.hasMicPermissionGranted
import com.yunjue.echo.mind.sensing.resolveSensingRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
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
        val baselineDays = inputs.baseline?.validDays ?: 0

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

        // 4. Identity Genome：稳定种子（userId 派生，长期一致；不随状态变化）
        val seed = preferences.userId.fold(0L) { acc, c -> acc * 31L + c.code }

        return EchoPresenceState(
            updatedAt = now,
            sensingStatus = runtime,
            maturity = echoMaturity(baselineDays),
            rhythmState = RhythmState(
                activityLevel = ambient.vector.activation,
                rhythmDelta = 0f, // ERA 4：跨日节奏漂移
                regularity = ambient.vector.regularity,
                coverage = ambient.coverage,
            ),
            behaviorState = BehaviorState(
                density = ambient.vector.density,
                deviation = ambient.vector.deviation,
            ),
            affectiveState = null, // AFFECTIVE_CONTRACT 未建立前恒 null
            confidence = ambient.vector.confidence,
            identityGenome = EchoIdentityGenome(seed = seed, accentHue = (seed and 0xFFFF).toFloat() / 65535f),
        )
    }
}
