package com.yunjue.echo.mind

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensorCollector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Batch A v0.6.2（A1 单一数据源收敛）：collector → hub → window → extractor 全链路消费测试。
 *
 * 每个 modality 验证：事件从采集入口写入 [SensingEventHub] 后，经 5 分钟窗口
 * （extractFromHub）产出 [com.yunjue.echo.mind.model.DerivedFeatureInput]。
 * 覆盖 accel / gyro / screen / notification / app_activity（mic 为 MicCollector canonical 源，
 * 不经 hub，见 MicCollectorTest）。
 *
 * Robolectric SDK 35 运行，与 PassiveSensingTest 一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollectorConsumptionTest {

    private lateinit var context: Context
    private val extractor = FeatureExtractor()
    private val windowStart = Instant.now().minusSeconds(300)
    private val windowEnd = Instant.now()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SensingEventHub.resetForTest()
    }

    @After
    fun tearDown() {
        SensingEventHub.resetForTest()
    }

    // ===== accel：SensorCollector → hub → extractFromHub =====

    @Test
    fun accelSamplesFlowToHubAndProduceWindowFeature() {
        val hub = SensingEventHub()
        val collector = SensorCollector(context, hub)

        repeat(5) { i ->
            collector.onSensorChanged(
                sensorEvent(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0.1f * i, 0.2f, 9.8f))
            )
        }

        assertEquals("accel 样本应写入 hub", 5, hub.snapshotAccel().size)
        // 单一数据源：collector.snapshotAccel 委托 hub
        assertEquals(hub.snapshotAccel().size, collector.snapshotAccel().size)

        val features = extractor.extractFromHub(windowStart, windowEnd, hub)
        assertEquals("accel 样本应产出窗口特征", 1, features.size)
        assertEquals("accel", features.first().source)
        assertTrue("sourcesPresent 应含 accel", "accel" in features.first().sourcesPresent)
    }

    // ===== gyro：SensorCollector → hub → extractFromHub =====

    @Test
    fun gyroSamplesFlowToHubAndProduceWindowFeature() {
        val hub = SensingEventHub()
        val collector = SensorCollector(context, hub)

        repeat(3) { i ->
            collector.onSensorChanged(
                sensorEvent(Sensor.TYPE_GYROSCOPE, floatArrayOf(0.01f * i, 0.02f, 0.03f))
            )
        }

        assertEquals("gyro 样本应写入 hub", 3, hub.snapshotGyro().size)
        val features = extractor.extractFromHub(windowStart, windowEnd, hub)
        assertEquals("gyro 样本应产出窗口特征", 1, features.size)
        assertEquals("gyro", features.first().source)
    }

    // ===== screen：ScreenCollector 广播 → hub → extractFromHub =====

    @Test
    fun screenEventsFlowToHubAndProduceWindowFeature() {
        val hub = SensingEventHub()
        val collector = ScreenCollector(context, hub)
        collector.start()
        try {
            // Robolectric 对 RECEIVER_NOT_EXPORTED 系统广播投递不可靠；
            // 直接驱动采集器的 BroadcastReceiver（验证 collector→hub 写入逻辑）
            val receiverField = ScreenCollector::class.java.getDeclaredField("receiver")
            receiverField.isAccessible = true
            val receiver = receiverField.get(collector) as android.content.BroadcastReceiver
            receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
            receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        } finally {
            collector.stop()
        }
        assertEquals("屏幕事件应写入 hub", 2, hub.snapshotScreen().size)
        // 单一数据源：collector.snapshot 委托 hub
        assertEquals(hub.snapshotScreen().size, collector.snapshot().size)

        // 屏幕事件时间戳为 System.currentTimeMillis()（采集时刻），提取窗口需覆盖此刻
        val features = extractor.extractFromHub(windowStart, Instant.now().plusSeconds(10), hub)
        assertEquals(1, features.size)
        assertEquals("screen", features.first().source)
    }

    // ===== notification：NotificationCollector → hub → extractFromHub =====

    @Test
    fun notificationsFlowToHubAndProduceWindowFeature() {
        // NotificationCollector 由系统绑定，其数据经 hub 单例（SensingEventHub.getInstance()）共享
        val hub = SensingEventHub.getInstance()
        val collector = NotificationCollector()

        // 构造最小化 StatusBarNotification（仅 metadata 入库：timestamp/packageName/category）
        val notification = Notification.Builder(context, "test_ch")
            .setContentTitle("secret-title") // 仅测试构造用；hub 不保存 title
            .setContentText("secret-body")
            .build()
        // postTime 需落在 [windowStart, windowEnd) 窗口内（extractFromHub 按窗口过滤）
        val sbn = statusBarNotification(notification, postTime = windowEnd.toEpochMilli() - 60_000L)
        collector.onNotificationPosted(sbn)

        val meta = hub.snapshotNotifications()
        assertEquals("通知 metadata 应写入 hub", 1, meta.size)
        assertEquals("com.example.pkg", meta.first().packageName)
        // 隐私不变量：hub 快照不暴露标题/正文
        assertTrue("hub 只保存最小化 metadata（无 title）", hub.snapshotAll().notifications.none { it.toString().contains("secret-title") })

        // 通知 metadata 时间戳为 System.currentTimeMillis()（采集时刻），提取窗口需覆盖此刻
        val features = extractor.extractFromHub(windowStart, Instant.now().plusSeconds(10), hub)
        assertEquals(1, features.size)
        assertEquals("notification", features.first().source)
    }

    // ===== app_activity：hub → extractFromHub（pollOnce 依赖 UsageStatsManager，端侧轮询路径） =====

    @Test
    fun appActivityFlowToHubAndProduceWindowFeature() {
        val hub = SensingEventHub()
        // AppActivityCollector 通过 UsageStatsManager 轮询；无法可靠构造系统 stats，
        // 这里验证其数据源收敛：事件经 hub 写入后 extractFromHub 消费 app_activity
        val collector = AppActivityCollector(context, hub)
        val now = System.currentTimeMillis()
        hub.onAppActivity(AppActivityCollector.AppActivity(now - 1000, "com.test"))

        // 单一数据源：collector.snapshot 委托 hub
        assertEquals(hub.snapshotAppActivity().size, collector.snapshot().size)

        val features = extractor.extractFromHub(windowStart, windowEnd, hub)
        assertEquals(1, features.size)
        assertEquals("app_activity", features.first().source)
    }

    // ===== 辅助：构造 SensorEvent（Robolectric 无真实传感器；反射构造 Sensor 与 SensorEvent） =====

    private fun sensorEvent(sensorType: Int, values: FloatArray): SensorEvent {
        // Sensor 构造器为包级私有，mType 为私有字段：反射创建并写入类型
        val sensorCtor = Sensor::class.java.getDeclaredConstructor()
        sensorCtor.isAccessible = true
        val sensor = sensorCtor.newInstance()
        val typeField = Sensor::class.java.getDeclaredField("mType")
        typeField.isAccessible = true
        typeField.setInt(sensor, sensorType)
        // SensorEvent(Sensor, accuracy, timestamp, values) 在 SDK stub 中隐藏：反射调用
        val eventCtor = SensorEvent::class.java.getDeclaredConstructor(
            Sensor::class.java, Int::class.java, Long::class.java, FloatArray::class.java
        )
        eventCtor.isAccessible = true
        return eventCtor.newInstance(sensor, 0, System.currentTimeMillis(), values)
    }

    /**
     * 构造 StatusBarNotification：SDK stub 仅暴露 Parcel 构造器，运行时（Robolectric android-all）
     * 通过反射调用隐藏的 10 参构造器（pkg/opPkg/id/tag/uid/initialPid/notification/user/overrideGroupKey/postTime）。
     */
    private fun statusBarNotification(notification: Notification, postTime: Long): StatusBarNotification {
        val ctor = StatusBarNotification::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            Int::class.java,
            String::class.java,
            Int::class.java,
            Int::class.java,
            Notification::class.java,
            android.os.UserHandle::class.java,
            String::class.java,
            Long::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            "com.example.pkg",
            "com.example.pkg",
            1,
            "tag-1",
            android.os.Process.myUid(),
            0,
            notification,
            android.os.Process.myUserHandle(),
            null,
            postTime
        )
    }
}
