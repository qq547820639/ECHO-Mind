package com.yunjue.echo.mind.wearable

/**
 * Wear Protocol v1 —— 公共常量与消息来源。
 *
 * 协议要求（ECHO_WRIST_CONTRACT §Protocol）：
 * small / versioned / forward compatible / idempotent / out-of-order safe / duplicate safe。
 *
 * 实现手段：
 * - 所有 envelope 携带 [WEAR_SCHEMA_V1] + messageId + generatedAt + source；
 * - 未知字段一律忽略（forward compatible）；
 * - messageId 去重 + presence revision 单调检查（duplicate / out-of-order safe）；
 * - ACK 与 PUSH 幂等（idempotent）。
 */
const val WEAR_SCHEMA_V1: Int = 1

/** 当前协议 schema 版本（手机与手环共同事实源）。 */
const val WEAR_SCHEMA_CURRENT: Int = WEAR_SCHEMA_V1

/** 消息来源：手机（BRAIN）或手环（BODY）。 */
enum class WearMessageSource {
    PHONE,
    XIAOMI_BAND,
}
