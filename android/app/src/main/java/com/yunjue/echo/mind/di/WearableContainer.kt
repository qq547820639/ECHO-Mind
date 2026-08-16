package com.yunjue.echo.mind.di

import android.content.Context
import com.yunjue.echo.mind.actions.EchoActionRuntime
import com.yunjue.echo.mind.ports.PresenceStateSource
import com.yunjue.echo.mind.wearable.FakeWearablePlatformAdapter
import com.yunjue.echo.mind.wearable.WearActionHandler
import com.yunjue.echo.mind.wearable.WearObservationSink
import com.yunjue.echo.mind.wearable.WearRevisionStore
import com.yunjue.echo.mind.wearable.WearableConnectionState
import com.yunjue.echo.mind.wearable.WearablePlatformPort
import com.yunjue.echo.mind.wearable.WearablePrefs
import com.yunjue.echo.mind.wearable.WearableRuntime
import com.yunjue.echo.mind.wearable.WristObservationLog
import com.yunjue.echo.mind.wearable.XiaomiWearVendorBoundary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * ERA 33 — WearableContainer：ECHO on Wrist 的 Application 层对象图。
 *
 * 边界（ECHO_WRIST_CONTRACT §Android Vendor Boundary / §OSS Boundary）：
 * - vendor SDK 适配属于 :app adapter 层（XiaomiWearVendorBoundary），不进 :feature:wearable；
 * - SDK 未集成 → Noop/Fake 适配器，OSS 构建不失败；
 * - 手环动作 → 手机 EchoActionRuntime（同一个 Action）；手环观察 → 中立日志（不写 Memory）。
 *
 * 手机没有手环：runtime 静默闲置（Noop 恒 DISCONNECTED），ECHO 完全不受影响。
 *
 * 依赖纪律：本容器只接收**最小依赖**（Context + PresenceStateSource + EchoActionRuntime），
 * 不接收整棵 Container 树——生产装配点在 AppContainer（composition root），
 * 集成测试可注入 Fake 平台/来源而不必构造数据库链。
 */
class WearableContainer(
    applicationContext: Context,
    private val presenceSource: PresenceStateSource,
    private val actionRuntime: EchoActionRuntime,
    /** 测试注入缝：初始平台适配器（默认 = 生产选择：用户断开 → Noop，否则 vendor boundary）。 */
    platformProvider: (() -> WearablePlatformPort)? = null,
) {
    private val appContext: Context = applicationContext.applicationContext

    val prefs = WearablePrefs(appContext)

    private val platformFlow = MutableStateFlow<WearablePlatformPort>(
        platformProvider?.invoke() ?: defaultPlatform(),
    )

    /** 生产初始平台：用户主动断开 → Noop（不自动重连）；否则走 vendor boundary（SDK 未集成 → Noop）。 */
    private fun defaultPlatform(): WearablePlatformPort =
        if (prefs.userDisconnected) {
            com.yunjue.echo.mind.wearable.NoopWearablePlatformAdapter()
        } else {
            XiaomiWearVendorBoundary.currentPlatformAdapter()
        }

    /** 当前平台适配器（QA/诊断可见；切换即时生效，运行时不重建）。 */
    val platform: WearablePlatformPort get() = platformFlow.value

    /** 委托平台：跟随 platformFlow 切换（connect/disconnect 与 QA 注入缝共用）。 */
    private val delegatingPlatform: WearablePlatformPort = object : WearablePlatformPort {
        override val inboundMessages: Flow<String> = platformFlow.flatMapLatest { it.inboundMessages }
        override val connectionState: Flow<WearableConnectionState> =
            platformFlow.flatMapLatest { it.connectionState }
        override suspend fun connect() = platformFlow.value.connect()
        override suspend fun disconnect() = platformFlow.value.disconnect()
        override suspend fun send(text: String): Boolean = platformFlow.value.send(text)
    }

    val observationLog = WristObservationLog()

    private val revisionStore: WearRevisionStore = object : WearRevisionStore {
        override fun load(): Long = prefs.presenceRevision
        override fun save(revision: Long) {
            prefs.presenceRevision = revision
        }
    }

    val runtime: WearableRuntime = WearableRuntime(
        platform = delegatingPlatform,
        presenceSource = presenceSource,
        actionHandler = WearActionHandler { command ->
            when (command) {
                com.yunjue.echo.mind.wearable.WearActionCommand.START_BREATHING ->
                    actionRuntime.start(com.yunjue.echo.mind.actions.EchoActionKind.BREATHING)
                com.yunjue.echo.mind.wearable.WearActionCommand.START_PAUSE ->
                    actionRuntime.start(com.yunjue.echo.mind.actions.EchoActionKind.PAUSE)
                com.yunjue.echo.mind.wearable.WearActionCommand.STOP_ACTION ->
                    actionRuntime.stop()
                else -> Unit // REQUEST_* 由 runtime 内部处理，不经过这里
            }
        },
        observationSink = WearObservationSink { envelope ->
            // consent-first：用户未开启"运动摘要"时不记录任何腕上观察。
            if (!prefs.motionSummaryEnabled) return@WearObservationSink
            // 观察 ≠ Presence：只进中立日志；绝不写 Memory / Journey / Portrait。
            observationLog.record(envelope)
        },
        availableActionsProvider = {
            val availability = actionRuntime.availability(
                confidence = presenceSource.state.value?.confidence ?: 0f,
                ambientKnown = true,
                suggestionsEnabled = false,
            )
            com.yunjue.echo.mind.wearable.WearablePrivacyProjector.availableActions(
                breathingAvailable = availability.breathing,
                pauseAvailable = availability.pause,
            )
        },
        motionSummaryEnabledProvider = { prefs.motionSummaryEnabled },
        hapticsEnabledProvider = { prefs.hapticsEnabled },
        revisionStore = revisionStore,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false

    /**
     * 启动运行时（幂等：重复调用 no-op）。
     *
     * 唯一生产启动点 = AppContainer 组合（Application scoped，进程存活期间运行；
     * 进程死亡 → 系统清掉 runtime；下次进程启动 → AppContainer 重建时恢复）。
     * 禁止 Activity / Screen / Me UI 负责启动。
     *
     * 启动后：
     * - inboundMessages 由 runtime 自动 collect（生产代码不手工调 onMessageFromBand）；
     * - 手环动作变化 → 推送（同一个 Action 的双面同步）。
     */
    fun start() {
        if (started) return
        started = true
        runtime.start(scope)
        scope.launch {
            actionRuntime.running.collect {
                runtime.notifyActionStateChanged()
            }
        }
    }

    /** QA / Integration Preview：切换 Fake 平台（不伪装 vendor 能力）。 */
    fun enableFakePlatformForQa(): FakeWearablePlatformAdapter {
        val fake = FakeWearablePlatformAdapter()
        platformFlow.value = fake
        return fake
    }

    /** 用户主动连接：恢复 vendor 平台并连接。 */
    suspend fun connect() {
        prefs.userDisconnected = false
        platformFlow.value = XiaomiWearVendorBoundary.currentPlatformAdapter()
        platformFlow.value.connect()
    }

    /** 用户主动断开：切 Noop 平台（手环端自然降级，不生成另一个 ECHO）。 */
    suspend fun disconnect() {
        platformFlow.value.disconnect()
        prefs.userDisconnected = true
        platformFlow.value = com.yunjue.echo.mind.wearable.NoopWearablePlatformAdapter()
    }

    /** 表面/隐私开关变化（佩戴/睡眠/运动摘要/触觉）→ 推送新 surface（PRIVACY_CHANGED 触发）。 */
    suspend fun notifySurfacePrefsChanged() {
        runtime.notifyPrivacyStateChanged()
    }
}
