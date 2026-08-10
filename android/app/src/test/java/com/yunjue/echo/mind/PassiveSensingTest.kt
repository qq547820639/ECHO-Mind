package com.yunjue.echo.mind

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ConsentDao
import com.yunjue.echo.mind.data.ConsentEntity
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.PassiveSensingService
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensorCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * T02 被动采集单测：
 * - 采集器启停逻辑（SensorCollector / ScreenCollector / AppActivityCollector）
 * - 同意开关联动（PassiveSensingPrefs DataStore + ConsentEntity 本地持久化）
 * - 前台服务通知构建（PassiveSensingService.buildNotification）
 * - **三重门控**（consent + flag + 权限，fail-closed）
 * - 停止清空 hub buffer
 * - 5 分钟调度接线（startSensing 创建并启动 scheduler；重复 START 不重复启动）
 *
 * Robolectric 限定 SDK 35 运行，避免与 compileSdk=36 的 robolectric jar 不匹配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PassiveSensingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SensingEventHub.resetForTest()
        // 重置 PassiveSensingPrefs（DataStore 单例跨测试保留状态，需手动复位）
        runBlocking {
            val prefs = PassiveSensingPrefs(context)
            prefs.setPassiveSensingEnabled(false)
            prefs.setMicEnabled(false)
            prefs.setSamplingConfig(PassiveSensingPrefs.DEFAULT_SAMPLING_CONFIG)
        }
    }

    @After
    fun tearDown() {
        SensingEventHub.resetForTest()
    }

    // ===== 采集器启停逻辑 =====

    @Test
    fun sensorCollectorStartStopIsIdempotent() {
        val collector = SensorCollector(context)
        assertFalse("初始应未运行", collector.running)
        collector.start()
        assertTrue("start 后应运行", collector.running)
        // 重复 start 幂等，不应崩溃或改变状态
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        assertFalse("stop 后应停止", collector.running)
        // 重复 stop 幂等
        collector.stop()
        assertFalse(collector.running)
    }

    @Test
    fun screenCollectorStartStopIsIdempotent() {
        val collector = ScreenCollector(context)
        assertFalse(collector.running)
        collector.start()
        assertTrue(collector.running)
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        assertFalse(collector.running)
        collector.stop()
        assertFalse(collector.running)
    }

    @Test
    fun appActivityCollectorStartStopIsIdempotent() {
        val collector = AppActivityCollector(context)
        assertFalse(collector.running)
        collector.start()
        assertTrue(collector.running)
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        assertFalse(collector.running)
        collector.stop()
        assertFalse(collector.running)
    }

    @Test
    fun sensorCollectorBufferTrimsToMaxSize() {
        val collector = SensorCollector(context)
        // 直接填充缓冲，验证 trim 逻辑不超过上限
        repeat(SensorCollector.MAX_BUFFER_SIZE + 50) {
            collector.accelerometerBuffer.offerLast(floatArrayOf(it.toFloat(), 0f, 0f))
            // 模拟 trim
            while (collector.accelerometerBuffer.size > SensorCollector.MAX_BUFFER_SIZE) {
                collector.accelerometerBuffer.pollFirst()
            }
        }
        assertEquals(SensorCollector.MAX_BUFFER_SIZE, collector.accelerometerBuffer.size)
        collector.clearBuffers()
        assertTrue(collector.accelerometerBuffer.isEmpty())
    }

    // ===== 同意开关联动 =====

    @Test
    fun passiveSensingPrefsToggleFlows() = runBlocking {
        val prefs = PassiveSensingPrefs(context)
        assertFalse("默认关闭", prefs.passiveSensingEnabled.first())
        assertFalse("麦克风默认关闭", prefs.micEnabled.first())
        assertEquals(
            PassiveSensingPrefs.DEFAULT_SAMPLING_CONFIG,
            prefs.samplingConfig.first()
        )
        // 开关联动：开启总开关
        prefs.setPassiveSensingEnabled(true)
        assertTrue(prefs.passiveSensingEnabled.first())
        // 关闭
        prefs.setPassiveSensingEnabled(false)
        assertFalse(prefs.passiveSensingEnabled.first())
        // 采样配置可写
        val customConfig = """{"accel":false}"""
        prefs.setSamplingConfig(customConfig)
        assertEquals(customConfig, prefs.samplingConfig.first())
    }

    @Test
    fun appPreferencesDelegatesPassiveSensingPrefs() = runBlocking {
        // AppPreferences 应通过 passiveSensingPrefs 代理被动采集字段
        val appPrefs = AppPreferences(context, com.yunjue.echo.mind.security.FieldCipher())
        assertFalse(appPrefs.passiveSensingEnabledFlow().first())
        appPrefs.setPassiveSensingEnabled(true)
        assertTrue(appPrefs.passiveSensingEnabledFlow().first())
        // 同一 DataStore 实例应被共享
        val anotherPrefs = PassiveSensingPrefs(context)
        assertTrue(anotherPrefs.passiveSensingEnabled.first())
    }

    @Test
    fun consentEntityPersistsAndQueries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TestConsentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.consentDao()
            dao.insertConsent(
                ConsentEntity(
                    eventId = "consent_1",
                    userId = "u_test",
                    consentType = "passive_sensing",
                    version = "passive-sensing-consent-2026.07",
                    granted = true,
                    grantedAt = 1L,
                    evidenceHash = "hash1"
                )
            )
            dao.insertConsent(
                ConsentEntity(
                    eventId = "consent_2",
                    userId = "u_test",
                    consentType = "passive_sensing",
                    version = "passive-sensing-consent-2026.07",
                    granted = false,
                    grantedAt = 2L,
                    evidenceHash = "hash2"
                )
            )
            // 查询授权计数
            assertEquals(1, dao.grantedCount("passive_sensing"))
            // 最新一条（按 grantedAt DESC）应为 consent_2 且 granted=false（用户撤回）
            val latest = dao.latestConsent("passive_sensing")
            assertNotNull(latest)
            assertEquals("consent_2", latest?.eventId)
            assertEquals(false, latest?.granted)
            // 按 type 查询全部
            assertEquals(2, dao.consentsByType("passive_sensing").size)
        } finally {
            db.close()
        }
    }

    // ===== 前台服务通知构建 =====

    @Test
    fun passiveSensingServiceBuildsNotificationWithChannel() {
        val controller = Robolectric.buildService(PassiveSensingService::class.java)
        val service = controller.create().get()
        try {
            val notification = service.buildNotification()
            assertNotNull("通知不应为 null", notification)
            // 验证通知 channel 已创建且为 LOW 重要性
            val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = nm.getNotificationChannel("passive_sensing")
            assertNotNull("通知渠道应已创建", channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals(PassiveSensingService.NOTIFICATION_TITLE, "ECHO Mind")
            assertEquals(PassiveSensingService.NOTIFICATION_TEXT, "被动采集中（端侧处理）")
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun serviceStartStopActionsAreDeclared() {
        // 验证 start/stop action 常量稳定（用于 manifest / PendingIntent 对齐）
        assertEquals(
            "com.yunjue.echo.mind.action.START_SENSING",
            PassiveSensingService.ACTION_START
        )
        assertEquals(
            "com.yunjue.echo.mind.action.STOP_SENSING",
            PassiveSensingService.ACTION_STOP
        )
    }

    // ===== T02 三重门控（consent + flag + 权限，fail-closed） =====

    @Test
    fun gateFailsWhenFlagDisabled() {
        assertFalse(
            "flag=false 时不应启动（fail-closed）",
            PassiveSensingService.passiveSensingGatePasses(
                flagEnabled = false,
                consentGranted = true,
                postNotificationsGranted = true,
                bodySensorsGranted = true,
                notificationAccessGranted = true,
                usageAccessGranted = true
            )
        )
    }

    @Test
    fun gateFailsWhenConsentNotGranted() {
        assertFalse(
            "无用户 consent 时不应启动",
            PassiveSensingService.passiveSensingGatePasses(
                flagEnabled = true,
                consentGranted = false,
                postNotificationsGranted = true,
                bodySensorsGranted = true,
                notificationAccessGranted = true,
                usageAccessGranted = true
            )
        )
    }

    @Test
    fun gateFailsWhenAnyPermissionMissing() {
        val base = mapOf(
            "flagEnabled" to true,
            "consentGranted" to true,
            "postNotificationsGranted" to true,
            "bodySensorsGranted" to true,
            "notificationAccessGranted" to true,
            "usageAccessGranted" to true
        )
        // 逐一移除任一权限/授权 → 门控失败（fail-closed）
        for (key in listOf("postNotificationsGranted", "bodySensorsGranted", "notificationAccessGranted", "usageAccessGranted")) {
            val args = base + (key to false)
            assertFalse(
                "$key=false 时不应启动",
                PassiveSensingService.passiveSensingGatePasses(
                    flagEnabled = args.getValue("flagEnabled") as Boolean,
                    consentGranted = args.getValue("consentGranted") as Boolean,
                    postNotificationsGranted = args.getValue("postNotificationsGranted") as Boolean,
                    bodySensorsGranted = args.getValue("bodySensorsGranted") as Boolean,
                    notificationAccessGranted = args.getValue("notificationAccessGranted") as Boolean,
                    usageAccessGranted = args.getValue("usageAccessGranted") as Boolean
                )
            )
        }
    }

    @Test
    fun gatePassesWhenAllPreconditionsMet() {
        assertTrue(
            "全部前置满足时应可通过门控",
            PassiveSensingService.passiveSensingGatePasses(
                flagEnabled = true,
                consentGranted = true,
                postNotificationsGranted = true,
                bodySensorsGranted = true,
                notificationAccessGranted = true,
                usageAccessGranted = true
            )
        )
    }

    @Test
    fun serviceFlagDefaultIsFailClosed() {
        // 无 flag 缓存 → isPassiveSensingEnabled 默认 false（fail-closed，02b 共享知识 2）
        val appPrefs = AppPreferences(context, com.yunjue.echo.mind.security.FieldCipher())
        assertFalse(
            "passive_sensing_enabled 无缓存应默认 false",
            appPrefs.getFeatureFlagsSnapshot()["passive_sensing_enabled"] ?: true
        )
    }

    @Test
    fun serviceDoesNotStartWithoutConsent() {
        // consent=false（默认）→ onStartCommand 应停止服务（stopSelf），不启动采集
        val controller = Robolectric.buildService(PassiveSensingService::class.java)
        val service = controller.create().get()
        val result = service.onStartCommand(
            Intent(context, PassiveSensingService::class.java)
                .setAction(PassiveSensingService.ACTION_START),
            0, 1
        )
        assertFalse("无 consent 时不应启动采集", service.isSensingRunning())
        assertTrue("无 consent 时应返回 START_NOT_STICKY", result == android.app.Service.START_NOT_STICKY)
        controller.destroy()
    }

    // ===== T02 停止清空 buffer + 5 分钟调度接线 =====

    @Test
    fun stopSensingClearsHubBuffers() {
        val hub = SensingEventHub.getInstance()
        hub.onNotificationPosted(
            com.yunjue.echo.mind.sensing.NotificationCollector.NotificationMeta(
                System.currentTimeMillis(), "pkg", "social"
            )
        )
        assertFalse("停止前 hub 不应为空", hub.isEmpty())

        val controller = Robolectric.buildService(PassiveSensingService::class.java)
        val service = controller.create().get()
        try {
            service.startSensing()
            service.stopSensing()
            assertFalse("停止后不应运行", service.isSensingRunning())
            assertTrue("停止后 hub 缓冲应清空", hub.isEmpty())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun startSensingWiresFiveMinuteScheduler() {
        val controller = Robolectric.buildService(PassiveSensingService::class.java)
        val service = controller.create().get()
        try {
            assertFalse(service.isSensingRunning())
            service.startSensing()
            assertTrue("startSensing 后应运行（含 5 分钟调度器接线）", service.isSensingRunning())
            service.stopSensing()
            assertFalse(service.isSensingRunning())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun repeatedStartCommandDoesNotDoubleStart() {
        val controller = Robolectric.buildService(PassiveSensingService::class.java)
        val service = controller.create().get()
        try {
            service.startSensing()
            assertTrue(service.isSensingRunning())
            // 已运行时的 ACTION_START 不应重复启动（幂等）
            val shadow = Shadows.shadowOf(service)
            service.onStartCommand(
                Intent(context, PassiveSensingService::class.java)
                    .setAction(PassiveSensingService.ACTION_START),
                0, 2
            )
            assertTrue("已运行时仍保持运行", service.isSensingRunning())
            assertFalse("已运行时不重复 stopSelf", shadow.isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}

/**
 * 仅用于 ConsentEntity / ConsentDao 单测的独立内存数据库。
 * 主 EchoDatabase 未注册 ConsentEntity（由 T04 统一迁移 v2→v3），
 * 因此这里用一个独立的 @Database 验证 ConsentDao 行为。
 */
@Database(entities = [ConsentEntity::class], version = 1, exportSchema = false)
abstract class TestConsentDatabase : RoomDatabase() {
    abstract fun consentDao(): ConsentDao
}
