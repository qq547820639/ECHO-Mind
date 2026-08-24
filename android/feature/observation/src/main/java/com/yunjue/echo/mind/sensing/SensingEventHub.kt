package com.yunjue.echo.mind.sensing

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内共享事件聚合层（T02 P0 + Phase 4 Immutable Window）。
 *
 * 职责：
 * - 统一接收 NotificationCollector（系统实例化的 NotificationListenerService）与各 Collector 的事件；
 * - 维护各 modality 的并发安全缓冲（accel / gyro / screen / notification / app_activity），
 *   供 FeatureExtractor 按 5 分钟窗口消费；
 * - **不保存通知正文**：通知仅保存最小化 metadata（timestamp / packageName / category），
 *   与 [NotificationCollector.NotificationMeta] 完全一致，无 title / text 字段；
 * - 提供 `snapshotAll()`（非破坏快照）与 `clearConsumed(snapshot)`（只清本窗口已消费项），
 *   供窗口 flush「先持久化成功、后清 consumed」的 ACK 语义使用；
 * - `clearAll()` 仅用于 consent revoke / 服务停止（不用于 flush 路径）。
 *
 * Phase 4（Immutable Window）：
 * - accel/gyro 样本类型从 FloatArray 升级为 [SensorSample]（带 timestampMs），
 *   精确窗口归属（Phase 4.1）；
 * - 快照不可变：flush 使用一次 [snapshotAll] 的结果传给 FeatureExtractor
 *   （不再"再读 live hub"），retry 重处理同一 snapshot；成功只清同一批事件。
 *
 * 纯 Kotlin 可单测：缓冲使用 [ConcurrentLinkedDeque]，不依赖任何 Android 框架类。
 *
 * 单一数据源约定（Batch A v0.6.2）：SensorCollector / ScreenCollector / AppActivityCollector
 * 不再保留本地缓冲，只写本 hub；snapshot() 委托本 hub。麦克风派生特征是唯一例外：
 * [MicCollector] 作为 MicDerivedFeatureSource 保留自身派生缓冲（mic canonical 源），
 * 不经过本 hub。
 */
class SensingEventHub {

    /** 采集 modality 枚举（sourceName 与后端 gap_finder.EXPECTED_SOURCES 对齐）。 */
    enum class Modality(val sourceName: String) {
        ACCEL("accel"),
        GYRO("gyro"),
        SCREEN("screen"),
        NOTIFICATION("notification"),
        APP_ACTIVITY("app_activity")
    }

    /**
     * 窗口 flush 快照（不可变，Phase 4）：记录某一时刻各 modality 缓冲的全部项。
     *
     * 传给 [clearConsumed] 后，只清快照内已消费项（data class 按值相等），
     * 不清快照之后新到项——「只清本窗口已消费项」语义。
     * retry 必须重处理**同一个**快照（不可变，绝不重新读 live hub）。
     */
    data class HubSnapshot(
        val accel: List<SensorSample>,
        val gyro: List<SensorSample>,
        val screen: List<ScreenCollector.ScreenEvent>,
        val notifications: List<NotificationCollector.NotificationMeta>,
        val appActivities: List<AppActivityCollector.AppActivity>
    )

    private val accelBuffer = ConcurrentLinkedDeque<SensorSample>()
    private val accelBufferSize = AtomicInteger(0)
    private val gyroBuffer = ConcurrentLinkedDeque<SensorSample>()
    private val gyroBufferSize = AtomicInteger(0)
    private val screenBuffer = ConcurrentLinkedDeque<ScreenCollector.ScreenEvent>()
    private val screenBufferSize = AtomicInteger(0)
    private val notificationBuffer = ConcurrentLinkedDeque<NotificationCollector.NotificationMeta>()
    private val notificationBufferSize = AtomicInteger(0)
    private val appActivityBuffer = ConcurrentLinkedDeque<AppActivityCollector.AppActivity>()
    private val appActivityBufferSize = AtomicInteger(0)

    // ===== 写入（Collector 侧） =====

    fun onAccelSample(sample: SensorSample) {
        accelBuffer.offerLast(sample)
        accelBufferSize.incrementAndGet()
        trim(accelBuffer, accelBufferSize, SensorCollector.MAX_BUFFER_SIZE)
    }

    fun onGyroSample(sample: SensorSample) {
        gyroBuffer.offerLast(sample)
        gyroBufferSize.incrementAndGet()
        trim(gyroBuffer, gyroBufferSize, SensorCollector.MAX_BUFFER_SIZE)
    }

    fun onScreenEvent(event: ScreenCollector.ScreenEvent) {
        screenBuffer.offerLast(event)
        screenBufferSize.incrementAndGet()
        trim(screenBuffer, screenBufferSize, ScreenCollector.MAX_BUFFER_SIZE)
    }

    /** 通知最小化 metadata 写入（timestamp / packageName / category，绝不写入正文）。 */
    fun onNotificationPosted(meta: NotificationCollector.NotificationMeta) {
        notificationBuffer.offerLast(meta)
        notificationBufferSize.incrementAndGet()
        trim(notificationBuffer, notificationBufferSize, NotificationCollector.MAX_BUFFER_SIZE)
    }

    fun onAppActivity(event: AppActivityCollector.AppActivity) {
        appActivityBuffer.offerLast(event)
        appActivityBufferSize.incrementAndGet()
        trim(appActivityBuffer, appActivityBufferSize, AppActivityCollector.MAX_BUFFER_SIZE)
    }

    // ===== 快照（FeatureExtractor 侧） =====

    fun snapshotAccel(): List<SensorSample> = accelBuffer.toList()
    fun snapshotGyro(): List<SensorSample> = gyroBuffer.toList()
    fun snapshotScreen(): List<ScreenCollector.ScreenEvent> = screenBuffer.toList()
    fun snapshotNotifications(): List<NotificationCollector.NotificationMeta> = notificationBuffer.toList()
    fun snapshotAppActivity(): List<AppActivityCollector.AppActivity> = appActivityBuffer.toList()

    /** 按 modality 取快照（泛型返回，调用方自行 cast）。 */
    fun snapshot(modality: Modality): List<Any> = when (modality) {
        Modality.ACCEL -> snapshotAccel()
        Modality.GYRO -> snapshotGyro()
        Modality.SCREEN -> snapshotScreen()
        Modality.NOTIFICATION -> snapshotNotifications()
        Modality.APP_ACTIVITY -> snapshotAppActivity()
    }

    /** 一次性非破坏快照全部 modality（窗口 flush 用，供 [clearConsumed] 消费）。 */
    fun snapshotAll(): HubSnapshot = HubSnapshot(
        accel = snapshotAccel(),
        gyro = snapshotGyro(),
        screen = snapshotScreen(),
        notifications = snapshotNotifications(),
        appActivities = snapshotAppActivity()
    )

    /**
     * 只清快照内已消费项（T02 窗口 ACK 语义 + Phase 4）：
     * data class 按值相等移除；快照之后新到项（下一窗口）不会被清除。
     */
    fun clearConsumed(snapshot: HubSnapshot) {
        snapshot.accel.forEach { if (accelBuffer.remove(it)) accelBufferSize.decrementAndGet() }
        snapshot.gyro.forEach { if (gyroBuffer.remove(it)) gyroBufferSize.decrementAndGet() }
        snapshot.screen.forEach { if (screenBuffer.remove(it)) screenBufferSize.decrementAndGet() }
        snapshot.notifications.forEach { if (notificationBuffer.remove(it)) notificationBufferSize.decrementAndGet() }
        snapshot.appActivities.forEach { if (appActivityBuffer.remove(it)) appActivityBufferSize.decrementAndGet() }
    }

    /** 是否完全无数据（所有 modality 缓冲均为空）。 */
    fun isEmpty(): Boolean =
        accelBufferSize.get() == 0 && gyroBufferSize.get() == 0 && screenBufferSize.get() == 0 &&
            notificationBufferSize.get() == 0 && appActivityBufferSize.get() == 0

    // ===== 清空 =====

    fun clearModality(modality: Modality) = when (modality) {
        Modality.ACCEL -> { accelBuffer.clear(); accelBufferSize.set(0) }
        Modality.GYRO -> { gyroBuffer.clear(); gyroBufferSize.set(0) }
        Modality.SCREEN -> { screenBuffer.clear(); screenBufferSize.set(0) }
        Modality.NOTIFICATION -> { notificationBuffer.clear(); notificationBufferSize.set(0) }
        Modality.APP_ACTIVITY -> { appActivityBuffer.clear(); appActivityBufferSize.set(0) }
    }

    /** consent revoke / 服务停止时安全清空全部缓冲（不用于窗口 flush 路径）。 */
    fun clearAll() {
        accelBuffer.clear(); accelBufferSize.set(0)
        gyroBuffer.clear(); gyroBufferSize.set(0)
        screenBuffer.clear(); screenBufferSize.set(0)
        notificationBuffer.clear(); notificationBufferSize.set(0)
        appActivityBuffer.clear(); appActivityBufferSize.set(0)
    }

    private fun <T> trim(buffer: ConcurrentLinkedDeque<T>, sizeCounter: AtomicInteger, maxSize: Int) {
        while (sizeCounter.get() > maxSize) {
            buffer.pollFirst()
            sizeCounter.decrementAndGet()
        }
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
        fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
