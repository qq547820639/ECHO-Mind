package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.MicFeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 SensingEventHub 单测（纯 JVM，不依赖 Android 框架）。
 *
 * - 多 modality 聚合：accel/gyro/screen/notification/app_activity/mic_opt 独立缓冲
 * - snapshot / clearModality / clearAll 行为
 * - **不保存通知正文**：NotificationMeta 数据类不含 text/title 字段（编译期 + 运行时断言）
 * - 单例语义：getInstance() 返回同一实例；resetForTest 后新建
 */
class SensingEventHubTest {

    @After
    fun tearDown() {
        SensingEventHub.resetForTest()
    }

    @Test
    fun singletonReturnsSameInstance() {
        assertSame(SensingEventHub.getInstance(), SensingEventHub.getInstance())
    }

    @Test
    fun singletonResetCreatesFreshInstance() {
        val first = SensingEventHub.getInstance()
        SensingEventHub.resetForTest()
        val second = SensingEventHub.getInstance()
        assertFalse("resetForTest 后应产生新实例", first === second)
    }

    @Test
    fun aggregatesMultipleModalitiesIndependently() {
        val hub = SensingEventHub()
        val now = System.currentTimeMillis()

        hub.onAccelSample(floatArrayOf(0.1f, 0.2f, 9.8f))
        hub.onGyroSample(floatArrayOf(0.01f, 0.02f, 0.03f))
        hub.onScreenEvent(ScreenCollector.ScreenEvent(now, ScreenCollector.ScreenState.ON))
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(now, "com.test", "social"))
        hub.onAppActivity(AppActivityCollector.AppActivity(now, "com.test"))
        hub.onMicDerivedFeature(MicFeatureExtractor().emptyFeature())

        assertEquals(1, hub.snapshotAccel().size)
        assertEquals(1, hub.snapshotGyro().size)
        assertEquals(1, hub.snapshotScreen().size)
        assertEquals(1, hub.snapshotNotifications().size)
        assertEquals(1, hub.snapshotAppActivity().size)
        assertEquals(1, hub.snapshotMicDerived().size)
        assertFalse(hub.isEmpty())
    }

    @Test
    fun snapshotByModalityReturnsBufferedData() {
        val hub = SensingEventHub()
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(1L, "pkg", "social"))
        val snap = hub.snapshot(SensingEventHub.Modality.NOTIFICATION)
        assertEquals(1, snap.size)
        val meta = snap.first() as NotificationCollector.NotificationMeta
        assertEquals("pkg", meta.packageName)
    }

    @Test
    fun clearModalityClearsOnlyThatModality() {
        val hub = SensingEventHub()
        val now = System.currentTimeMillis()
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(now, "pkg", "social"))
        hub.onScreenEvent(ScreenCollector.ScreenEvent(now, ScreenCollector.ScreenState.ON))

        hub.clearModality(SensingEventHub.Modality.NOTIFICATION)

        assertTrue(hub.snapshotNotifications().isEmpty())
        assertEquals("clear 指定 modality 不应影响其他缓冲", 1, hub.snapshotScreen().size)
    }

    @Test
    fun clearAllClearsEveryModality() {
        val hub = SensingEventHub()
        val now = System.currentTimeMillis()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        hub.onScreenEvent(ScreenCollector.ScreenEvent(now, ScreenCollector.ScreenState.ON))
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(now, "pkg", "social"))
        hub.onAppActivity(AppActivityCollector.AppActivity(now, "pkg"))

        hub.clearAll()

        assertTrue(hub.isEmpty())
        assertTrue(hub.snapshotAccel().isEmpty())
        assertTrue(hub.snapshotGyro().isEmpty())
        assertTrue(hub.snapshotScreen().isEmpty())
        assertTrue(hub.snapshotNotifications().isEmpty())
        assertTrue(hub.snapshotAppActivity().isEmpty())
        assertTrue(hub.snapshotMicDerived().isEmpty())
    }

    @Test
    fun bufferTrimsAtCapacity() {
        val hub = SensingEventHub()
        repeat(ScreenCollector.MAX_BUFFER_SIZE + 50) { i ->
            hub.onScreenEvent(ScreenCollector.ScreenEvent(i.toLong(), ScreenCollector.ScreenState.ON))
        }
        assertEquals("屏幕缓冲不应超过容量上限", ScreenCollector.MAX_BUFFER_SIZE, hub.snapshotScreen().size)
    }

    // ===== 隐私不变量：通知不保存正文 =====

    @Test
    fun notificationMetaHasNoTextOrTitleField() {
        // 运行时断言：hub 只保存最小化 metadata（timestamp/packageName/category）
        val fields = NotificationCollector.NotificationMeta::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("NotificationMeta 不应含 text 字段（不保存通知正文）", "text" in fields)
        assertFalse("NotificationMeta 不应含 title 字段（不保存通知标题）", "title" in fields)
        assertFalse("NotificationMeta 不应含 content 字段", "content" in fields)
        assertFalse("NotificationMeta 不应含 body 字段", "body" in fields)
        assertEquals(
            "NotificationMeta 只应有 3 个最小化字段",
            setOf("timestamp", "packageName", "category"),
            fields
        )
    }

    @Test
    fun hubNotificationBufferStoresMinimalMetadataOnly() {
        val hub = SensingEventHub()
        val meta = NotificationCollector.NotificationMeta(123L, "com.example.app", "social")
        hub.onNotificationPosted(meta)
        val stored = hub.snapshotNotifications().first()
        assertEquals(123L, stored.timestamp)
        assertEquals("com.example.app", stored.packageName)
        assertEquals("social", stored.category)
    }
}
