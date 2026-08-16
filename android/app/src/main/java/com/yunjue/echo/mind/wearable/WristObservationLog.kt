package com.yunjue.echo.mind.wearable

import java.time.Instant

/**
 * WristObservationLog —— 腕上观察的本地中立日志（诊断用）。
 *
 * 隐私纪律（ECHO_WRIST_PRIVACY.md §Raw Physiological Privacy + §Memory Writing Rule）：
 * - 只保留**中立摘要**（motion class/coverage/quality + device state），无 raw 样本；
 * - local only / short retention（内存有界队列，进程死亡即清）；
 * - 绝不写入 Memory / Journey / Portrait（观察 ≠ Presence；5 秒观察 → Memory 是绝对禁止）；
 * - raw samples：仅 Debug + explicit diagnostic consent（本类根本不接收 raw）。
 */
class WristObservationLog(private val capacity: Int = 64) {

    /** 中立腕上证据记录（provenance 固定 XIAOMI_BAND）。 */
    data class WristEvidenceRecord(
        val receivedAt: Instant,
        val wearing: String,
        val sleep: String,
        val movementClass: String?,
        val motionEnergy: Float?,
        val sampleCoverage: Float?,
        val quality: String,
        val batteryPercent: Int?,
        val charging: Boolean?,
        /** 是否被允许进入产品融合（真机验证门关闭 → 恒 false；UNKNOWN 保留）。 */
        val promoted: Boolean,
    )

    private val records = ArrayDeque<WristEvidenceRecord>()

    @Synchronized
    fun record(envelope: com.yunjue.echo.mind.wearable.WearObservationEnvelope, now: Instant = Instant.now()) {
        val motion = envelope.motion
        val device = envelope.deviceState
        records.addLast(
            WristEvidenceRecord(
                receivedAt = now,
                wearing = device?.wearing ?: "UNKNOWN",
                sleep = device?.sleep ?: "UNKNOWN",
                movementClass = motion?.movementClass,
                motionEnergy = motion?.motionEnergy,
                sampleCoverage = motion?.sampleCoverage,
                quality = motion?.quality ?: "UNKNOWN",
                batteryPercent = device?.batteryPercent,
                charging = device?.charging,
                promoted = false, // 真机验证门关闭：恒 false
            ),
        )
        while (records.size > capacity) records.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int = capacity): List<WristEvidenceRecord> =
        records.takeLast(limit)

    @Synchronized
    fun clear() {
        records.clear()
    }

    val size: Int get() = records.size
}
