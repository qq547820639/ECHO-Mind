package com.yunjue.echo.mind.sensing

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 进程内共享事件聚合层（T02 P0）。
 *
 * 职责：
 * - 统一接收 NotificationCollector（系统实例化的 NotificationListenerService）与各 Collector 的事件；
 * - 维护各 modality 的并发安全缓冲（accel / gyro / screen / notification / app_activity / mic_opt），
 *   供 FeatureExtractor 按 5 分钟窗口消费；
 * - **不保存通知正文**：通知仅保存最小化 metadata（timestamp / packageName / category），
 *   与 [NotificationCollector.NotificationMeta] 完全一致，无 title / text 字段；
 * - 提供 `snapshot(modality)` / `clearModality(modality)` / `clearAll()`，
 *   进程重启后由 `clearAll()` 安全清空（内存缓冲本就随进程消亡）。
 *
 * 纯 Kotlin 可单测：缓冲使用 [ConcurrentLinkedDeque]，不依赖任何 Android 框架类。
 */
class SensingEventHub {

    /** 采集 modality 枚举（sourceName 与后端 gap_finder.EXPECTED_SOURCES 对齐）。 */
    enum class Modality(val sourceName: String) {
        ACCEL("accel"),
        GYRO("gyro"),
        SCREEN("screen"),
        NOTIFICATION("notification"),
        APP_ACTIVITY("app_activity"),
        MIC_OPT("mic_opt")
    }

    private val accelBuffer = ConcurrentLinkedDeque<FloatArray>()
    private val gyroBuffer = ConcurrentLinkedDeque<FloatArray>()
    private val screenBuffer = ConcurrentLinkedDeque<ScreenCollector.ScreenEvent>()
    private val notificationBuffer = ConcurrentLinkedDeque<NotificationCollector.NotificationMeta>()
    private val appActivityBuffer = ConcurrentLinkedDeque<AppActivityCollector.AppActivity>()
    private val micDerivedBuffer = ConcurrentLinkedDeque<MicFeatureExtractor.MicDerivedFeature>()

    // ===== 写入（Collector 侧） =====

    fun onAccelSample(sample: FloatArray) {
        accelBuffer.offerLast(sample)
        trim(accelBuffer, SensorCollector.MAX_BUFFER_SIZE)
    }

    fun onGyroSample(sample: FloatArray) {
        gyroBuffer.offerLast(sample)
        trim(gyroBuffer, SensorCollector.MAX_BUFFER_SIZE)
    }

    fun onScreenEvent(event: ScreenCollector.ScreenEvent) {
        screenBuffer.offerLast(event)
        trim(screenBuffer, ScreenCollector.MAX_BUFFER_SIZE)
    }

    /** 通知最小化 metadata 写入（timestamp / packageName / category，绝不写入正文）。 */
    fun onNotificationPosted(meta: NotificationCollector.NotificationMeta) {
        notificationBuffer.offerLast(meta)
        trim(notificationBuffer, NotificationCollector.MAX_BUFFER_SIZE)
    }

    fun onAppActivity(event: AppActivityCollector.AppActivity) {
        appActivityBuffer.offerLast(event)
        trim(appActivityBuffer, AppActivityCollector.MAX_BUFFER_SIZE)
    }

    fun onMicDerivedFeature(feature: MicFeatureExtractor.MicDerivedFeature) {
        micDerivedBuffer.offerLast(feature)
        trim(micDerivedBuffer, MicCollector.MAX_BUFFER_SIZE)
    }

    // ===== 快照（FeatureExtractor 侧） =====

    fun snapshotAccel(): List<FloatArray> = accelBuffer.toList()
    fun snapshotGyro(): List<FloatArray> = gyroBuffer.toList()
    fun snapshotScreen(): List<ScreenCollector.ScreenEvent> = screenBuffer.toList()
    fun snapshotNotifications(): List<NotificationCollector.NotificationMeta> = notificationBuffer.toList()
    fun snapshotAppActivity(): List<AppActivityCollector.AppActivity> = appActivityBuffer.toList()
    fun snapshotMicDerived(): List<MicFeatureExtractor.MicDerivedFeature> = micDerivedBuffer.toList()

    /** 按 modality 取快照（泛型返回，调用方自行 cast）。 */
    fun snapshot(modality: Modality): List<Any> = when (modality) {
        Modality.ACCEL -> snapshotAccel()
        Modality.GYRO -> snapshotGyro()
        Modality.SCREEN -> snapshotScreen()
        Modality.NOTIFICATION -> snapshotNotifications()
        Modality.APP_ACTIVITY -> snapshotAppActivity()
        Modality.MIC_OPT -> snapshotMicDerived()
    }

    /** 是否完全无数据（所有 modality 缓冲均为空）。 */
    fun isEmpty(): Boolean =
        accelBuffer.isEmpty() && gyroBuffer.isEmpty() && screenBuffer.isEmpty() &&
            notificationBuffer.isEmpty() && appActivityBuffer.isEmpty() && micDerivedBuffer.isEmpty()

    // ===== 清空 =====

    fun clearModality(modality: Modality) = when (modality) {
        Modality.ACCEL -> accelBuffer.clear()
        Modality.GYRO -> gyroBuffer.clear()
        Modality.SCREEN -> screenBuffer.clear()
        Modality.NOTIFICATION -> notificationBuffer.clear()
        Modality.APP_ACTIVITY -> appActivityBuffer.clear()
        Modality.MIC_OPT -> micDerivedBuffer.clear()
    }

    /** 进程重启后安全清空全部缓冲（不无限增长）。 */
    fun clearAll() {
        accelBuffer.clear()
        gyroBuffer.clear()
        screenBuffer.clear()
        notificationBuffer.clear()
        appActivityBuffer.clear()
        micDerivedBuffer.clear()
    }

    private fun <T> trim(buffer: ConcurrentLinkedDeque<T>, maxSize: Int) {
        while (buffer.size > maxSize) buffer.pollFirst()
    }

    companion object {
        @Volatile
        private var instance: SensingEventHub? = null

        /** 进程内共享单例（application-scoped）。 */
        fun getInstance(): SensingEventHub =
            instance ?: synchronized(this) {
                instance ?: SensingEventHub().also { instance = it }
            }

        /** 仅测试使用：重置单例，避免跨测试状态泄漏。 */
        internal fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
