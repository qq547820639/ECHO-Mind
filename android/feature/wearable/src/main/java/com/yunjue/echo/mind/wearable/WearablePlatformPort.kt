package com.yunjue.echo.mind.wearable

import kotlinx.coroutines.flow.Flow

/**
 * WearablePlatformPort —— 手机侧平台抽象。
 *
 * 实现边界（ECHO_WRIST_CONTRACT §Android Vendor Boundary）：
 * - vendor adapter（Xiaomi 穿戴 SDK）属于 :app adapter 层，不属于本 module；
 * - OSS 构建必须可运行：[NoopWearablePlatformAdapter] / FakeWearablePlatformAdapter；
 * - SDK 不可获得时不猜 class/interface 签名，标 BLOCKED_EXTERNAL_XIAOMI_SDK。
 *
 * 线上语义：
 * - [send] 返回是否已投递（尽力而为；重连/重发由上层 [WearableRuntime] 决定）；
 * - 所有消息为 JSON 文本（Wear Protocol v1）。
 */
interface WearablePlatformPort {
    /** 手环 → 手机 入站消息（JSON 文本；vendor adapter 负责字节 ↔ 文本边界）。 */
    val inboundMessages: Flow<String>

    /** 连接状态（vendor adapter 上报；Noop 恒 DISCONNECTED）。 */
    val connectionState: Flow<WearableConnectionState>

    suspend fun connect()

    suspend fun disconnect()

    /** 发送 JSON 文本到已配对手环。返回投递成功与否。 */
    suspend fun send(text: String): Boolean
}

/** 手环动作 → 手机 EchoActionRuntime 的接缝（:app 装配；本 module 不依赖 feature:actions）。 */
fun interface WearActionHandler {
    suspend fun handle(command: WearActionCommand)
}

/** 手环观察 → 手机 Observation 接缝（:app 装配到 Observation Adapter；观察 ≠ Presence）。 */
fun interface WearObservationSink {
    suspend fun accept(envelope: WearObservationEnvelope)
}

/**
 * Presence revision 持久化（手机进程死亡后 revision 必须继续单调增长，
 * 否则手环会按"revision <= cached → ignore"丢弃重启后的推送）。
 */
interface WearRevisionStore {
    fun load(): Long
    fun save(revision: Long)
}

/** 默认内存实现（进程内单调；:app 用 AppPreferences 实现持久化）。 */
class InMemoryWearRevisionStore : WearRevisionStore {
    private var value: Long = 0L
    override fun load(): Long = value
    override fun save(revision: Long) {
        value = revision
    }
}
