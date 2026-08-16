package com.yunjue.echo.mind.wearable

/**
 * Band → Phone 动作 envelope。
 *
 * v1 只允许核心动作（不建 generic command bus）。
 *
 * Action 所有权：Band UI → WearActionEnvelope → Phone → EchoActionRuntime →
 * Action State → Band + Phone surfaces。Breathing 与 Pause 是同一个 Action。
 * 手环不创建第二套 Action Engine。
 */
enum class WearActionCommand {
    /** 请求手机推当前 Presence（打开/刷新场景）。 */
    REQUEST_CURRENT_PRESENCE,

    /** 请求一条克制的公开表达（WHY）。 */
    REQUEST_WHY,

    /** 开始呼吸（同一个 BREATHING Action）。 */
    START_BREATHING,

    /** 结束当前行动 → 回 Ambient Scene。 */
    STOP_ACTION,

    /** 开始暂停（同一个 PAUSE Action）。 */
    START_PAUSE,
}

data class WearActionEnvelope(
    override val schemaVersion: Int = WEAR_SCHEMA_CURRENT,
    override val messageId: String,
    override val generatedAt: Long,
    override val source: WearMessageSource = WearMessageSource.XIAOMI_BAND,
    /** 动作命令名；未知命令 = 忽略（forward compatible，不建命令总线）。 */
    val command: String,
) : WearEnvelope {
    val parsedCommand: WearActionCommand? = WearActionCommand.entries.firstOrNull { it.name == command }
}

/**
 * ACK envelope：Band 对 Presence push 的确认。
 * 手机用于记录 last sync；revision 过期/重复的 ACK 被忽略（out-of-order safe）。
 */
data class WearAckEnvelope(
    override val schemaVersion: Int = WEAR_SCHEMA_CURRENT,
    override val messageId: String,
    override val generatedAt: Long,
    override val source: WearMessageSource = WearMessageSource.XIAOMI_BAND,
    /** 被确认的消息 id。 */
    val ackFor: String,
    /** 被确认消息的 revision（Presence 消息时有效，其余为 0）。 */
    override val revision: Long = 0L,
    /** OK / EXPIRED / UNSUPPORTED。 */
    val status: String,
) : WearEnvelope

/**
 * 能力交换 envelope（双向）：
 * - Phone → Band：手环可用的表面能力与协议版本（手环据此决定渲染/动作面）；
 * - Band → Phone：设备上报的能力画像（vendor 未验证项保持 UNKNOWN）。
 */
data class WearCapabilityEnvelope(
    override val schemaVersion: Int = WEAR_SCHEMA_CURRENT,
    override val messageId: String,
    override val generatedAt: Long,
    override val source: WearMessageSource,
    /** 协议 schema 版本（协商事实）。 */
    val protocolVersion: Int = WEAR_SCHEMA_CURRENT,
    /** 屏幕尺寸（设备上报或手机侧已知画像）。 */
    val screenWidth: Int,
    val screenHeight: Int,
    /** 能力列表：id -> SUPPORTED/UNSUPPORTED/UNKNOWN。 */
    val capabilities: Map<String, String> = emptyMap(),
) : WearEnvelope
