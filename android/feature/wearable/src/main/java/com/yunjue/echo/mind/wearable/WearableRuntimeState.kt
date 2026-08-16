package com.yunjue.echo.mind.wearable

import java.time.Instant

/**
 * 手机侧 wearable 运行时状态快照。
 *
 * 手环端没有任何独立状态源：本状态是手机 ECHO 的单一 wearable 面，
 * 由 [WearableRuntime] 维护并投影给 Me → "ECHO on Wrist" 与诊断面。
 */
data class WearableRuntimeState(
    val connection: WearableConnectionState = WearableConnectionState.DISCONNECTED,
    val device: WearableDeviceProfile? = null,
    /** Presence revision（单调增长；手机是 revision 唯一事实源）。 */
    val presenceRevision: Long = 0L,
    /** 最近一次向手环推送 Presence 的时间。 */
    val lastPresencePushedAt: Instant? = null,
    /** 最近一次收到手环 ACK 的时间。 */
    val lastAckAt: Instant? = null,
    /** 最近一次收到手环 WRIST_OBSERVATION 的时间。 */
    val lastObservationAt: Instant? = null,
    /** 最近一次传输错误（公开诊断，不含任何私密内容）。 */
    val lastTransportError: String? = null,
    /** 手环端应用是否已安装（vendor 未确认时 null = UNKNOWN，不猜）。 */
    val wearAppInstalled: Boolean? = null,
    /** 长跑仪表（24h 验证用；进程内计数，进程死亡后清零——设备侧长跑统计见 LONG_RUN_PROTOCOL）。 */
    val inboundMessageCount: Long = 0L,
    /** Presence 推送尝试次数（含发送失败；成功与否另见 lastTransportError）。 */
    val outboundPushCount: Long = 0L,
    /** CONNECTED → DISCONNECTED 转换次数（进程内）。 */
    val disconnectCount: Long = 0L,
) {
    val isConnected: Boolean get() = connection == WearableConnectionState.CONNECTED
}
