package com.yunjue.echo.mind.wearable

/**
 * Wear Message sealed 模型：协议里所有消息类型的和。
 * 序列化由 [WearMessageCodec] 完成（org.json，与项目现行约定一致）。
 */
sealed class WearMessage {
    abstract val messageId: String
    abstract val generatedAt: Long

    data class Presence(val envelope: WearPresenceEnvelope) : WearMessage() {
        override val messageId: String get() = envelope.messageId
        override val generatedAt: Long get() = envelope.generatedAt
    }

    data class Observation(val envelope: WearObservationEnvelope) : WearMessage() {
        override val messageId: String get() = envelope.messageId
        override val generatedAt: Long get() = envelope.generatedAt
    }

    data class Action(val envelope: WearActionEnvelope) : WearMessage() {
        override val messageId: String get() = envelope.messageId
        override val generatedAt: Long get() = envelope.generatedAt
    }

    data class Ack(val envelope: WearAckEnvelope) : WearMessage() {
        override val messageId: String get() = envelope.messageId
        override val generatedAt: Long get() = envelope.generatedAt
    }

    data class Capability(val envelope: WearCapabilityEnvelope) : WearMessage() {
        override val messageId: String get() = envelope.messageId
        override val generatedAt: Long get() = envelope.generatedAt
    }
}
