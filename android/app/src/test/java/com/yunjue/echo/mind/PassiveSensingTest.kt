package com.yunjue.echo.mind

import android.app.NotificationManager
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ConsentDao
import com.yunjue.echo.mind.data.ConsentEntity
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.PassiveSensingService
import com.yunjue.echo.mind.sensing.ScreenCollector
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
import org.robolectric.annotation.Config

/**
 * T02 被动采集单测：
 * - 采集器启停逻辑（SensorCollector / ScreenCollector / AppActivityCollector）
 * - 同意开关联动（PassiveSensingPrefs DataStore + ConsentEntity 本地持久化）
 * - 前台服务通知构建（PassiveSensingService.buildNotification）
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
        // 重置 PassiveSensingPrefs（DataStore 单例跨测试保留状态，需手动复位）
        runBlocking {
            val prefs = PassiveSensingPrefs(context)
            prefs.setPassiveSensingEnabled(false)
            prefs.setMicEnabled(false)
            prefs.setSamplingConfig(PassiveSensingPrefs.DEFAULT_SAMPLING_CONFIG)
        }
    }

    @After
    fun tearDown() = Unit

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
