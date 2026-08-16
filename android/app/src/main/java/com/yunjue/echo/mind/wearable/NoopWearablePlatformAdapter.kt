package com.yunjue.echo.mind.wearable

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * NoopWearablePlatformAdapter —— 无手环/无 SDK 时的平台适配器。
 *
 * OSS 构建纪律（ECHO_WRIST_CONTRACT §OSS/Proprietary SDK Boundary）：
 * ECHO 普通开源构建不能因为缺 Xiaomi proprietary SDK 而失败。
 * Noop：恒 DISCONNECTED、无入站、send 返回 false。手机没有手环时 ECHO 完全正常。
 */
class NoopWearablePlatformAdapter : WearablePlatformPort {
    override val inboundMessages: Flow<String> = flowOf()
    override val connectionState: Flow<WearableConnectionState> =
        flowOf(WearableConnectionState.DISCONNECTED)

    override suspend fun connect() {
        // Noop：无平台可连。
    }

    override suspend fun disconnect() {
        // Noop：无平台可断。
    }

    override suspend fun send(text: String): Boolean = false
}
