package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.AppActivityCollector
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
 * - 多 modality 聚合：accel/gyro/screen/notification/app_activity 独立缓冲
 *   （Batch A v0.6.2：麦克风派生特征为 MicCollector 的 canonical 源，不经 hub）
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

        assertEquals(1, hub.snapshotAccel().size)
        assertEquals(1, hub.snapshotGyro().size)
        assertEquals(1, hub.snapshotScreen().size)
        assertEquals(1, hub.snapshotNotifications().size)
        assertEquals(1, hub.snapshotAppActivity().size)
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
        // （过滤 Compose 编译器合成的 $stable 字段）
        val fields = NotificationCollector.NotificationMeta::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
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

    // ===== T02 窗口 ACK：snapshotAll / clearConsumed =====

    @Test
    fun snapshotAllIsNonDestructive() {
        val hub = SensingEventHub()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(1L, "pkg", "social"))

        val snap = hub.snapshotAll()
        assertEquals(1, snap.accel.size)
        assertEquals(1, snap.notifications.size)
        // 非破坏：快照后缓冲仍在
        assertFalse("snapshotAll 后缓冲应保留（非破坏）", hub.isEmpty())
    }

    @Test
    fun clearConsumedRemovesOnlySnapshotItems() {
        val hub = SensingEventHub()
        val first = floatArrayOf(0f, 0f, 9.8f)
        hub.onAccelSample(first)

        val snap = hub.snapshotAll()
        // 快照之后新到项（下一窗口）不应被清除
        val second = floatArrayOf(1f, 1f, 9.8f)
        hub.onAccelSample(second)

        hub.clearConsumed(snap)
        // 快照内项（引用相等）被清；快照后新到项保留
        assertEquals(1, hub.snapshotAccel().size)
        // 再次 clear 新到项 → 清空
        hub.clearConsumed(hub.snapshotAll())
        assertTrue(hub.snapshotAccel().isEmpty())
    }

    @Test
    fun clearConsumedByValueEqualityForDataClasses() {
        val hub = SensingEventHub()
        val now = System.currentTimeMillis()
        val meta = NotificationCollector.NotificationMeta(now, "pkg", "social")
        hub.onNotificationPosted(meta)
        val snap = hub.snapshotAll()
        hub.clearConsumed(snap)
        assertTrue("data class 项按值相等清除", hub.snapshotNotifications().isEmpty())
    }
}
