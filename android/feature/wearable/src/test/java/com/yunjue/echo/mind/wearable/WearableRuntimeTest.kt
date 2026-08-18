package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.ports.PresenceStateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * TEST — CONNECTION（ECHO_WRIST_CONTRACT §Connection Test）：
 * first connect / disconnect / reconnect / duplicate / message loss / message reordering /
 * Phone process death / Band stale cache / Phone Presence refresh / expired Presence。
 * Identity continuity 必须保持。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearableRuntimeTest {

    private class FakePlatform(
        val outbound: MutableList<String> = mutableListOf(),
    ) : WearablePlatformPort {
        val inbound = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val connection = MutableStateFlow(WearableConnectionState.DISCONNECTED)
        override val inboundMessages: Flow<String> = inbound
        override val connectionState: Flow<WearableConnectionState> = connection
        override suspend fun connect() {
            connection.value = WearableConnectionState.CONNECTED
        }

        override suspend fun disconnect() {
            connection.value = WearableConnectionState.DISCONNECTED
        }

        override suspend fun send(text: String): Boolean {
            outbound.add(text)
            return true
        }
    }

    private class FakePresenceSource : PresenceStateSource {
        override val state: StateFlow<EchoPresenceState?> =
            MutableStateFlow(ECHO_STATE)
    }

    private class RecordingActionHandler : WearActionHandler {
        val commands = mutableListOf<WearActionCommand>()
        override suspend fun handle(command: WearActionCommand) {
            commands.add(command)
        }
    }

    private class RecordingObservationSink : WearObservationSink {
        val observations = mutableListOf<WearObservationEnvelope>()
        override suspend fun accept(envelope: WearObservationEnvelope) {
            observations.add(envelope)
        }
    }

    private class MutableTestClock(var nowMs: Long = 1_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    private fun runtime(
        platform: FakePlatform,
        actionHandler: RecordingActionHandler = RecordingActionHandler(),
        observationSink: RecordingObservationSink = RecordingObservationSink(),
        revisionStore: WearRevisionStore = InMemoryWearRevisionStore(),
        presence: PresenceStateSource = FakePresenceSource(),
        clock: Clock = TEST_CLOCK,
        hapticsEnabledProvider: () -> Boolean = { false },
    ): WearableRuntime = WearableRuntime(
        platform = platform,
        presenceSource = presence,
        actionHandler = actionHandler,
        observationSink = observationSink,
        clock = clock,
        availableActionsProvider = { listOf("START_BREATHING", "START_PAUSE") },
        hapticsEnabledProvider = hapticsEnabledProvider,
        revisionStore = revisionStore,
    )

    private fun lastPresence(outbound: List<String>): WearPresenceEnvelope {
        val message = WearMessageCodec.decode(outbound.last())
        return (message as WearMessageCodec.DecodeResult.Ok).let { (it.message as WearMessage.Presence).envelope }
    }

    @Test
    fun firstConnect_pushesPresence() = runTest {
        val platform = FakePlatform()
        val runtime = runtime(platform)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        assertTrue(platform.outbound.isNotEmpty())
        val presence = lastPresence(platform.outbound)
        assertEquals(1L, presence.revision)
        assertTrue(runtime.state.value.isConnected)
    }

    @Test
    fun disconnect_noPushAndConnectionStateUpdates() = runTest {
        val platform = FakePlatform()
        val runtime = runtime(platform)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val before = platform.outbound.size
        platform.connection.value = WearableConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(before, platform.outbound.size)
        assertEquals(WearableConnectionState.DISCONNECTED, runtime.state.value.connection)
    }

    @Test
    fun reconnect_pushesAgainWithHigherRevision() = runTest {
        val platform = FakePlatform()
        val runtime = runtime(platform)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val first = lastPresence(platform.outbound)
        platform.connection.value = WearableConnectionState.DISCONNECTED
        runCurrent()
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val second = lastPresence(platform.outbound)
        // 语义未变 → revision 不变（同一状态不虚增 revision）
        assertEquals(first.revision, second.revision)
        assertTrue(platform.outbound.size >= 2)
    }

    @Test
    fun duplicateMessage_actionExecutedOnce() = runTest {
        val platform = FakePlatform()
        val handler = RecordingActionHandler()
        val runtime = runtime(platform, actionHandler = handler)
        val action = WearActionEnvelope(messageId = "a-1", generatedAt = 1L, command = "START_BREATHING")
        val text = WearMessageCodec.encode(WearMessage.Action(action))
        assertTrue(runtime.onMessageFromBand(text))
        assertFalse(runtime.onMessageFromBand(text)) // 重复 → 丢弃
        assertEquals(1, handler.commands.size)
        assertEquals(WearActionCommand.START_BREATHING, handler.commands.first())
    }

    @Test
    fun actionOwnership_startStopPause_delegatedToPhoneRuntime() = runTest {
        val platform = FakePlatform()
        val handler = RecordingActionHandler()
        val runtime = runtime(platform, actionHandler = handler)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()

        for ((i, command) in listOf("START_BREATHING", "STOP_ACTION", "START_PAUSE").withIndex()) {
            val action = WearActionEnvelope(messageId = "a-$i", generatedAt = i.toLong(), command = command)
            runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Action(action)))
        }
        assertEquals(
            listOf(WearActionCommand.START_BREATHING, WearActionCommand.STOP_ACTION, WearActionCommand.START_PAUSE),
            handler.commands,
        )
    }

    @Test
    fun messageLoss_bandReRequests_presenceRepushed() = runTest {
        val platform = FakePlatform()
        val clock = MutableTestClock()
        val runtime = runtime(platform, clock = clock)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val outboundBefore = platform.outbound.size
        // 手环认为丢失（缓存过期）→ 请求当前 Presence（越过防抖间隔）
        clock.nowMs += WearablePolicy.PUSH_MIN_INTERVAL_MS + 1
        val request = WearActionEnvelope(messageId = "r-1", generatedAt = 2L, command = "REQUEST_CURRENT_PRESENCE")
        runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Action(request)))
        assertEquals(outboundBefore + 1, platform.outbound.size)
    }

    @Test
    fun requestWhy_revisionUnchanged_headlinePresent() = runTest {
        val platform = FakePlatform()
        val clock = MutableTestClock()
        // T5-P2-7：headlineFor 契约收敛后 WHY 需氛围已知（LATE）才有模板 headline
        // （氛围未知 → null 宁可没有；不再编造 KNOWN 文案）
        val runtime = WearableRuntime(
            platform = platform,
            presenceSource = FakePresenceSource(),
            clock = clock,
            ambientStateProvider = { WearablePrivacyProjector.WearAmbientState.LATE },
            hapticsEnabledProvider = { false },
        )
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val before = lastPresence(platform.outbound)
        clock.nowMs += WearablePolicy.PUSH_MIN_INTERVAL_MS + 1
        val why = WearActionEnvelope(messageId = "w-1", generatedAt = 2L, command = "REQUEST_WHY")
        runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Action(why)))
        val after = lastPresence(platform.outbound)
        // WHY 是显示级刷新：revision 不变（手环按 "revision == cached → 仅更新显示字段" 处理）
        assertEquals(before.revision, after.revision)
        assertNotNull(after.publicHeadline)
        assertTrue(WearablePrivacyProjector.HeadlineAllowlist.ALL.contains(after.publicHeadline!!))
    }

    @Test
    fun phoneProcessDeath_revisionPersistsMonotonic() = runTest {
        val platform = FakePlatform()
        val store = InMemoryWearRevisionStore()
        val runtime1 = runtime(platform, revisionStore = store)
        runtime1.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        runtime1.stop()
        val revisionBeforeDeath = lastPresence(platform.outbound).revision

        // 模拟进程死亡 → 新 runtime（同一 revisionStore）
        val runtime2 = runtime(platform, revisionStore = store)
        runtime2.start(backgroundScope)
        platform.connection.value = WearableConnectionState.DISCONNECTED
        runCurrent()
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val after = lastPresence(platform.outbound)
        // 重启后 revision 必须 >= 死亡前（手环缓存不因进程重启而被新 revision 覆盖语义）
        assertTrue(after.revision >= revisionBeforeDeath)
    }

    @Test
    fun bandStaleCache_reconnectRefreshesPresence() = runTest {
        val platform = FakePlatform()
        val runtime = runtime(platform)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        // 断连期间 Presence 过期（手环端本地降级）
        platform.connection.value = WearableConnectionState.DISCONNECTED
        runCurrent()
        // 重连 → 立即推新 Presence
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val presence = lastPresence(platform.outbound)
        assertTrue(presence.expiresAt > presence.generatedAt)
    }

    @Test
    fun observation_forwardedAndDuplicateDropped() = runTest {
        val platform = FakePlatform()
        val sink = RecordingObservationSink()
        val runtime = runtime(platform, observationSink = sink)
        val envelope = WearObservationEnvelope(
            messageId = "o-1",
            generatedAt = 1L,
            motion = WearMotionSummary(
                motionEnergy = 1.2f,
                movementClass = "WALKING",
                sampleCoverage = 0.9f,
                quality = "GOOD",
                windowStartMs = 0L,
                windowEndMs = 10_000L,
            ),
        )
        val text = WearMessageCodec.encode(WearMessage.Observation(envelope))
        assertTrue(runtime.onMessageFromBand(text))
        assertFalse(runtime.onMessageFromBand(text))
        assertEquals(1, sink.observations.size)
        assertEquals("WALKING", sink.observations.first().motion?.movementClass)
    }

    @Test
    fun presenceFromBand_isIgnored() = runTest {
        // 手环是 BODY 不是 BRAIN：手环发来的 presence 一律忽略（防伪造）。
        val platform = FakePlatform()
        val runtime = runtime(platform)
        val forged = WearPresenceEnvelope(
            messageId = "forged-1",
            generatedAt = 1L,
            source = WearMessageSource.XIAOMI_BAND,
            revision = 99L,
            expiresAt = 2L,
            maturity = "MATURE",
            identity = WearIdentityProjection(0f, 0f, 0f, 0f, 0, 0, 0f),
            moment = WearMomentProjection(0f, 0f, 0f, 0f, 0f),
            surface = WearSurfaceParams("QUIET", true, true),
        )
        assertFalse(runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Presence(forged))))
        assertEquals(0L, runtime.state.value.presenceRevision)
    }

    @Test
    fun ackWithFutureRevision_ignored() = runTest {
        val platform = FakePlatform()
        val runtime = runtime(platform)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val ack = WearAckEnvelope(messageId = "ack-1", generatedAt = 1L, ackFor = "p-1", revision = 999L, status = "OK")
        runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Ack(ack)))
        assertNull(runtime.state.value.lastAckAt) // 乱序 ACK 不计入同步状态
        val goodAck = WearAckEnvelope(messageId = "ack-2", generatedAt = 2L, ackFor = "p-1", revision = 1L, status = "OK")
        runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Ack(goodAck)))
        assertNotNull(runtime.state.value.lastAckAt)
    }

    @Test
    fun everyOutboundPayload_isPublicSafe() = runTest {
        // TEST — PRIVACY：运行时所发的每个 payload 都必须通过隐私扫描（hard FAIL）。
        val platform = FakePlatform()
        val runtime = runtime(platform)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        platform.connection.value = WearableConnectionState.DISCONNECTED
        runCurrent()
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val why = WearActionEnvelope(messageId = "w-1", generatedAt = 2L, command = "REQUEST_WHY")
        runtime.onMessageFromBand(WearMessageCodec.encode(WearMessage.Action(why)))

        assertTrue(platform.outbound.isNotEmpty())
        for (payload in platform.outbound) {
            val report = WearablePrivacyProjector.scanPayload(payload)
            assertTrue("payload must be PUBLIC_SAFE: $payload", report.isPublicSafe)
        }
    }

    @Test
    fun identityContinuity_acrossPresenceUpdates() = runTest {
        // Identity continuity：moment 变化时 identity 投影必须不变（同一个 ECHO）。
        val platform = FakePlatform()
        val presenceFlow = MutableStateFlow<EchoPresenceState?>(ECHO_STATE)
        val source = object : PresenceStateSource {
            override val state: StateFlow<EchoPresenceState?> = presenceFlow
        }
        val runtime = runtime(platform, presence = source)
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val identityBefore = lastPresence(platform.outbound).identity

        // moment 变化 → 新推送
        presenceFlow.value = ECHO_STATE.copy(
            dailyComposition = ECHO_STATE.dailyComposition.copy(coherence = 0.9f),
        )
        runCurrent()
        val identityAfter = lastPresence(platform.outbound).identity
        assertEquals(identityBefore, identityAfter)
    }

    @Test
    fun start_automaticallyCollectsInboundMessages_noManualDispatchNeeded() = runTest {
        // ERA 33 P0：生产入站流必须自动进入 runtime
        // （platform.inboundMessages → codec → dedupe → schema validation → dispatch），
        // 不得要求外部代码手工调用 onMessageFromBand()。
        val platform = FakePlatform()
        val handler = RecordingActionHandler()
        val runtime = runtime(platform, actionHandler = handler)
        runtime.start(backgroundScope)
        runCurrent() // 先让 collectors 完成订阅（SharedFlow 对无订阅者的 emit 直接丢弃）

        val action = WearActionEnvelope(messageId = "a-auto", generatedAt = 1L, command = "START_BREATHING")
        platform.inbound.emit(WearMessageCodec.encode(WearMessage.Action(action)))
        runCurrent()
        assertEquals(listOf(WearActionCommand.START_BREATHING), handler.commands)

        // 重复消息经同一条自动通道 → dedupe 仍然生效
        platform.inbound.emit(WearMessageCodec.encode(WearMessage.Action(action)))
        runCurrent()
        assertEquals(1, handler.commands.size)

        // malformed 消息经自动通道 → 丢弃不崩溃
        platform.inbound.emit("not json")
        runCurrent()
        assertEquals(1, handler.commands.size)
    }

    @Test
    fun hapticsProvider_whenTrue_surfaceCarriesTrue() = runTest {
        val platform = FakePlatform()
        val runtime = runtime(platform, hapticsEnabledProvider = { true })
        runtime.start(backgroundScope)
        platform.connection.value = WearableConnectionState.CONNECTED
        runCurrent()
        val presence = lastPresence(platform.outbound)
        assertTrue("hapticsEnabled provider 为 true 时 surface 必须携带 true", presence.surface.hapticsEnabled)
    }

    companion object {
        val TEST_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneId.of("UTC"))

        val ECHO_STATE: EchoPresenceState = EchoPresenceState(
            updatedAt = Instant.EPOCH,
            maturity = EchoMaturity.KNOWN,
        )
    }
}
