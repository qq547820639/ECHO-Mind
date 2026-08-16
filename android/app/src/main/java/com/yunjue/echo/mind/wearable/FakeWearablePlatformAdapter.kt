package com.yunjue.echo.mind.wearable

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FakeWearablePlatformAdapter —— QA / Integration Preview / 单元集成用可控适配器。
 *
 * 用途：无真机时验证 WearableRuntime 全链（连接/推送/ACK/观察/动作），
 * 以及 Me → "ECHO on Wrist" 界面状态。不伪装 vendor 能力：
 * device profile 由调用方显式注入，未注入的能力保持 UNKNOWN。
 */
class FakeWearablePlatformAdapter : WearablePlatformPort {
    private val inbound = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val connection = MutableStateFlow(WearableConnectionState.DISCONNECTED)

    override val inboundMessages: Flow<String> = inbound
    override val connectionState: Flow<WearableConnectionState> = connection

    /** 出站记录（测试断言用；不持久化）。 */
    val outbound: MutableList<String> = mutableListOf()

    var sendResult: Boolean = true

    override suspend fun connect() {
        connection.value = WearableConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        connection.value = WearableConnectionState.DISCONNECTED
    }

    override suspend fun send(text: String): Boolean {
        if (sendResult) outbound.add(text)
        return sendResult
    }

    /** 模拟手环入站消息（JSON 文本）。 */
    suspend fun injectFromBand(text: String) {
        inbound.emit(text)
    }

    fun setConnected(connected: Boolean) {
        connection.value =
            if (connected) WearableConnectionState.CONNECTED else WearableConnectionState.DISCONNECTED
    }
}
