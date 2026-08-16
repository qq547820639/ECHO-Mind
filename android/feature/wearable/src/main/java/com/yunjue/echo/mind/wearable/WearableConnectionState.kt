package com.yunjue.echo.mind.wearable

/**
 * 手机 ↔ 手环连接状态。
 *
 * 断连时不生成另一个 ECHO：手环保留缓存 Identity，Moment 缓慢降级
 * （QUIET / LOW_CERTAINTY），不显示红色 ERROR，不主动震动提醒"手机断开"。
 */
enum class WearableConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
