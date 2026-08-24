package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.ports.PresenceStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * WearableRuntime —— 手机侧 wearable 运行时。
 *
 * 唯一职责：把同一个 ECHO 安全地投影到腕上。
 * - Presence revision 手机唯一事实源（单调增长）；
 * - 手环动作委托给手机 EchoActionRuntime（同一个 Action）；
 * - 手环观察转发给 Observation 接缝（观察 ≠ Presence，禁止直接改 EchoPresenceState）；
 * - duplicate / out-of-order / stale 全部在此层处理。
 *
 * 手机没有手环：本运行时静默闲置，ECHO 完全不受影响。
 */
class WearableRuntime(
    private val platform: WearablePlatformPort,
    private val presenceSource: PresenceStateSource,
    private val actionHandler: WearActionHandler = WearActionHandler { },
    private val observationSink: WearObservationSink = WearObservationSink { },
    private val clock: Clock = Clock.systemUTC(),
    private val availableActionsProvider: () -> List<String> = { emptyList() },
    private val ambientStateProvider: () -> WearablePrivacyProjector.WearAmbientState? = { null },
    private val motionSummaryEnabledProvider: () -> Boolean = { false },
    /** 触觉开关（默认 SILENT）；false → 手环端所有振动 no-op（见 WearSurfaceParams.hapticsEnabled）。 */
    private val hapticsEnabledProvider: () -> Boolean = { false },
    private val revisionStore: WearRevisionStore = InMemoryWearRevisionStore(),
) {
    private val _state = MutableStateFlow(WearableRuntimeState())
    val state: StateFlow<WearableRuntimeState> = _state

    // 单次 load revisionStore，避免构造器+start() 内重复 IO
    private val initialRevision: Long = revisionStore.load()
    private val revisionCounter = PresenceRevisionCounter(initialRevision)
    private var lastPushedAtMs: Long? = null
    private var lastSentRevision: Long = initialRevision
    private var lastFingerprint: String? = null
    private var wasConnected: Boolean = false
    private var messageCounter: Long = 0L
    private val seenMessageIds = ArrayDeque<String>()
    private var collectJob: Job? = null
    private var presenceJob: Job? = null
    private var inboundJob: Job? = null

    /** 去重窗口上限（超出淘汰最旧；防内存膨胀）。 */
    private val maxSeenMessageIds = 256

    /**
     * 启动运行时（幂等：先 stop 再重建全部 collectors）。
     *
     * Production flow：platform.inboundMessages 在此自动 collect（
     * WearMessageCodec → dedupe → schema validation → dispatch），
     * 生产代码不需要也不允许手工调用 [onMessageFromBand]；
     * 该方法保留为 internal/test entry。
     */
    fun start(scope: CoroutineScope) {
        stop()
        collectJob = scope.launch {
            platform.connectionState.collect { connection ->
                val previouslyConnected = wasConnected
                wasConnected = connection == WearableConnectionState.CONNECTED
                _state.value = _state.value.copy(
                    connection = connection,
                    // 长跑仪表：CONNECTED → DISCONNECTED 转换计数（进程内）。
                    disconnectCount = _state.value.disconnectCount +
                        if (previouslyConnected && connection == WearableConnectionState.DISCONNECTED) 1L else 0L,
                )
                if (connection == WearableConnectionState.CONNECTED) {
                    val trigger = if (previouslyConnected) {
                        WearablePolicy.PushTrigger.RECONNECT
                    } else {
                        WearablePolicy.PushTrigger.INITIAL_CONNECT
                    }
                    pushPresence(trigger)
                }
            }
        }
        inboundJob = scope.launch {
            platform.inboundMessages.collect { text ->
                _state.value = _state.value.copy(inboundMessageCount = _state.value.inboundMessageCount + 1)
                onMessageFromBand(text)
            }
        }
        presenceJob = scope.launch {
            presenceSource.state.collect { presence ->
                if (presence == null) return@collect
                val projection = WearPresenceProjector.project(
                    presence,
                    motionSummaryEnabled = motionSummaryEnabledProvider(),
                    hapticsEnabled = hapticsEnabledProvider(),
                )
                val fingerprint = PresenceRevisionCounter.fingerprint(presence)
                val changed = fingerprint != lastFingerprint
                lastFingerprint = fingerprint
                if (changed && _state.value.isConnected) {
                    pushPresence(WearablePolicy.PushTrigger.PRESENCE_REVISION_CHANGED)
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        presenceJob?.cancel()
        inboundJob?.cancel()
        collectJob = null
        presenceJob = null
        inboundJob = null
    }

    /**
     * 手环入站消息处理（vendor adapter 解码后调用；生产路径由 [start] 自动 collect）。
     * 返回 false 表示消息被丢弃（重复 / 过期 / 伪造来源）。
     */
    suspend fun onMessageFromBand(text: String): Boolean {
        return when (val result = WearMessageCodec.decode(text)) {
            is WearMessageCodec.DecodeResult.Ok -> handle(result.message)
            else -> false
        }
    }

    /** 用户显式刷新（Me 界面按钮）。 */
    suspend fun requestExplicitRefresh() {
        if (_state.value.isConnected) pushPresence(WearablePolicy.PushTrigger.EXPLICIT_REFRESH)
    }

    /** 动作可用性/动作状态变化（:app 在 EchoActionRuntime 变化时调用）。 */
    suspend fun notifyActionStateChanged() {
        if (_state.value.isConnected) pushPresence(WearablePolicy.PushTrigger.ACTION_CHANGED)
    }

    /** 隐私/表面状态变化（低电量、减弱动态切换）。 */
    suspend fun notifyPrivacyStateChanged() {
        if (_state.value.isConnected) pushPresence(WearablePolicy.PushTrigger.PRIVACY_CHANGED)
    }

    /** 手环端应用安装状态（vendor 确认后上报；null = UNKNOWN，不猜）。 */
    fun reportWearAppInstalled(installed: Boolean?) {
        _state.value = _state.value.copy(wearAppInstalled = installed)
    }

    fun reportDeviceProfile(profile: WearableDeviceProfile) {
        _state.value = _state.value.copy(device = profile)
    }

    private suspend fun handle(message: WearMessage): Boolean {
        if (!remember(message.messageId)) return false
        return when (message) {
            is WearMessage.Action -> handleAction(message.envelope)
            is WearMessage.Ack -> handleAck(message.envelope)
            is WearMessage.Observation -> handleObservation(message.envelope)
            is WearMessage.Capability -> handleCapability(message.envelope)
            // 手环是 BODY 不是 BRAIN：手环发来的 presence 一律忽略（防止伪造状态）。
            is WearMessage.Presence -> false
        }
    }

    private suspend fun handleAction(action: WearActionEnvelope): Boolean {
        if (action.source != WearMessageSource.XIAOMI_BAND) return false
        return when (action.parsedCommand) {
            WearActionCommand.REQUEST_CURRENT_PRESENCE -> {
                pushPresence(WearablePolicy.PushTrigger.BAND_REQUEST)
                true
            }
            WearActionCommand.REQUEST_WHY -> {
                pushPresence(WearablePolicy.PushTrigger.BAND_REQUEST, headlineRequested = true)
                true
            }
            WearActionCommand.START_BREATHING,
            WearActionCommand.STOP_ACTION,
            WearActionCommand.START_PAUSE,
            -> {
                actionHandler.handle(action.parsedCommand)
                true
            }
            null -> false // 未知命令：忽略（forward compatible，不建命令总线）
        }
    }

    private fun handleAck(ack: WearAckEnvelope): Boolean {
        if (ack.source != WearMessageSource.XIAOMI_BAND) return false
        // ACK 携带的 revision 超出已发送范围 → 乱序/伪造，忽略。
        if (ack.revision > lastSentRevision) return false
        _state.value = _state.value.copy(lastAckAt = Instant.now(clock))
        return true
    }

    private suspend fun handleObservation(observation: WearObservationEnvelope): Boolean {
        if (observation.source != WearMessageSource.XIAOMI_BAND) return false
        _state.value = _state.value.copy(lastObservationAt = Instant.now(clock))
        observationSink.accept(observation)
        return true
    }

    private fun handleCapability(capability: WearCapabilityEnvelope): Boolean {
        if (capability.source != WearMessageSource.XIAOMI_BAND) return false
        val current = _state.value.device
        _state.value = _state.value.copy(
            device = (current ?: WearableDeviceProfile(
                deviceId = "device",
                model = WearableDeviceProfile.BAND10_MODEL,
                screenWidth = capability.screenWidth,
                screenHeight = capability.screenHeight,
                capabilities = emptyList(),
            )).copy(
                screenWidth = capability.screenWidth,
                screenHeight = capability.screenHeight,
            ),
        )
        return true
    }

    /**
     * 组装并推送 Presence envelope。
     *
     * revision 纪律：
     * - 语义变化 → revision+1（PRESENCE_REVISION_CHANGED 触发）；
     * - REQUEST_WHY 等显示级刷新 → revision 不变，手环按
     *   "revision == cached → 仅更新显示字段并续期" 处理（协议 §WHY）。
     */
    private suspend fun pushPresence(
        trigger: WearablePolicy.PushTrigger,
        headlineRequested: Boolean = false,
    ) {
        val nowMs = clock.millis()
        if (!WearablePolicy.shouldPushPresence(trigger, lastPushedAtMs, nowMs)) return
        val presence: EchoPresenceState = presenceSource.state.value ?: return

        val projection = WearPresenceProjector.project(
            presence,
            motionSummaryEnabled = motionSummaryEnabledProvider(),
            hapticsEnabled = hapticsEnabledProvider(),
        )
        // WHY 门控在 headlineFor 契约内（默认 VISUAL_FIRST → null；T5-P2-7）
        val headline = WearablePrivacyProjector.headlineFor(
            presence, ambientStateProvider(), whyRequested = headlineRequested,
        )

        val changed = WearablePolicy.hasSemanticChange(lastProjection, projection)
        val revision = revisionCounter.advanceIfChanged(changed)
        revisionStore.save(revision)
        lastProjection = projection

        val envelope = WearPresenceEnvelope(
            messageId = nextMessageId(),
            generatedAt = nowMs,
            revision = revision,
            expiresAt = nowMs + WearablePolicy.PRESENCE_TTL_MS,
            maturity = projection.maturity,
            identity = projection.identity,
            moment = projection.moment,
            surface = projection.surface,
            publicHeadline = headline,
            availableActions = availableActionsProvider(),
        )
        val sent = platform.send(WearMessageCodec.encode(WearMessage.Presence(envelope)))
        _state.value = _state.value.copy(outboundPushCount = _state.value.outboundPushCount + 1)
        if (sent) {
            lastPushedAtMs = nowMs
            lastSentRevision = revision
            _state.value = _state.value.copy(
                presenceRevision = revision,
                lastPresencePushedAt = Instant.ofEpochMilli(nowMs),
                lastTransportError = null,
            )
        } else {
            _state.value = _state.value.copy(lastTransportError = "presence send failed")
        }
    }

    private var lastProjection: WearProjection? = null

    private fun nextMessageId(): String = "p-${clock.millis()}-${messageCounter++}"

    private fun remember(messageId: String): Boolean {
        if (messageId in seenMessageIds) return false
        seenMessageIds.addLast(messageId)
        while (seenMessageIds.size > maxSeenMessageIds) {
            seenMessageIds.removeFirst()
        }
        return true
    }
}
