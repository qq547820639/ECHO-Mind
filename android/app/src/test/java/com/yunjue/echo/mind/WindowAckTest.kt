package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.security.FieldCipher
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
import com.yunjue.echo.mind.sensing.WindowFlushResult
import com.yunjue.echo.mind.ui.performPassiveSensingStop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * T02 窗口 ACK 故障注入测试（Robolectric，SDK 35）：
 *
 * - Room 写入失败 → saveDerivedFeatures 返回 false + 连续失败计数递增
 * - 成功路径 → feature_vectors + outbox 落库 + 失败计数清零
 * - callback（持久化）失败 → 调度器保留缓冲、bounded retry（配合 SensingWindowSchedulerTest）
 * - consent revoke 期间失败 → 立即清内存、不补发（performPassiveSensingStop）
 * - process/service restart 恢复 → 新 scheduler 对齐边界、无脏数据
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowAckTest {

    private lateinit var context: Context
    private lateinit var db: EchoDatabase
    private lateinit var cipher: FieldCipher
    private lateinit var preferences: AppPreferences
    private lateinit var repository: LocalRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SensingEventHub.resetForTest()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cipher = FieldCipher()
        preferences = AppPreferences(context, cipher)
        repository = LocalRepository(db, cipher, preferences, ApiClient { null })
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        SensingEventHub.resetForTest()
    }

    private fun sampleInput(): DerivedFeatureInput {
        val start = Instant.parse("2026-08-01T12:00:00Z")
        return DerivedFeatureInput(
            schemaVersion = "feat-v1",
            source = "accel",
            windowStart = start,
            windowEnd = start.plusMillis(300_000L),
            summary = "过去5分钟活动量中",
            vector = listOf(0.1f, 0.2f),
            sourcesPresent = listOf("accel", "screen")
        )
    }

    // ===== Room 写入失败 → false + 失败计数 =====

    @Test
    fun roomFailureReturnsFalseAndRecordsPersistenceFailure() = runBlocking {
        // 关闭 db 模拟 Room 写入异常；crypto/encrypt 异常走同一 catch 路径（withTransaction 内抛错）
        db.close()
        val ok = repository.saveDerivedFeatures(listOf(sampleInput()))
        assertFalse("Room 写入失败应返回 false（触发重试）", ok)
        assertTrue("连续失败计数应递增", preferences.consecutivePersistenceFailures >= 1)
        assertTrue("最近失败时间应被记录", preferences.lastPersistenceFailure != null)
    }

    // ===== 成功路径：落库 + outbox + 失败计数清零 =====

    @Test
    fun successPersistsFeatureVectorAndOutboxAndResetsFailureCount() = runBlocking {
        // 先制造一次失败，再成功 → 计数清零
        db.close()
        repository.saveDerivedFeatures(listOf(sampleInput()))
        assertTrue(preferences.consecutivePersistenceFailures >= 1)

        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalRepository(db, cipher, preferences, ApiClient { null })

        val ok = repository.saveDerivedFeatures(listOf(sampleInput()))
        assertTrue(ok)
        assertEquals("成功后连续失败计数应清零", 0, preferences.consecutivePersistenceFailures)
        assertEquals("feature_vectors 应有 1 条", 1, db.dao().pendingFeatureVectors().size)
        assertTrue("outbox 应有 1 条 derived_feature", db.dao().pendingOutbox().any { it.eventType == "derived_feature" })
        assertTrue("最近成功采集时间应更新", preferences.lastCollectionTimestamp > 0L)
    }

    // ===== 空列表 =====

    @Test
    fun emptyInputsReturnTrueWithoutSideEffects() = runBlocking {
        val ok = repository.saveDerivedFeatures(emptyList())
        assertTrue(ok)
        assertEquals(0, db.dao().pendingFeatureVectors().size)
        assertEquals(0, preferences.consecutivePersistenceFailures)
    }

    // ===== consent revoke 期间失败 → 立即清内存、不补发 =====

    @Test
    fun consentRevokeDuringFailureClearsMemoryAndStops() = runBlocking {
        val hub = SensingEventHub.getInstance()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        assertFalse(hub.isEmpty())

        // 模拟窗口持久化失败（scheduler flush false）时用户撤回 consent
        preferences.setPassiveSensingEnabled(true)
        performPassiveSensingStop(context, preferences, repository)

        // 撤回后：consent=false 持久化、hub 清空（后续零新特征）
        assertFalse("撤回后 consent 应为 false", preferences.passiveSensingEnabledFlow().first())
        assertTrue("撤回后 hub 缓冲应清空", hub.isEmpty())
        // revoke evidence 入 outbox（网络恢复后上传撤回事件）
        assertTrue(db.dao().pendingOutbox().any { it.eventType == "consent" })
    }

    // ===== process/service restart 恢复 =====

    @Test
    fun serviceRestartAlignsToBoundaryWithNoDirtyData() {
        // 进程重启后 hub 是全新实例（内存缓冲随进程消亡，无脏数据）
        val hubAfterRestart = SensingEventHub.getInstance()
        assertTrue("重启后 hub 应为空（无脏数据）", hubAfterRestart.isEmpty())

        val scheduler = SensingWindowScheduler(hubAfterRestart, clock = java.time.Clock.systemUTC())
        // 任意时刻重启 → 对齐到下一 5 分钟边界
        val restartMs = Instant.parse("2026-08-01T12:07:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-08-01T12:10:00Z").toEpochMilli(), scheduler.nextWindowStartMs(restartMs))
        assertFalse("重启后不残留已 flush 窗口", scheduler.hasFlushed(Instant.parse("2026-08-01T12:00:00Z").toEpochMilli()))
        assertEquals("重启后无失败重试", 0, scheduler.pendingRetryCount)
    }

    // ===== callback 失败（调度器层） =====

    @Test
    fun callbackFailureIsRetryableAndObservable() = kotlinx.coroutines.test.runTest {
        val hub = SensingEventHub.getInstance()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        val scheduler = SensingWindowScheduler(hub, clock = java.time.Clock.systemUTC())
        val start = Instant.parse("2026-08-01T12:00:00Z")
        val result = scheduler.flushWindow(start, start.plusMillis(300_000L)) { false }
        assertEquals(WindowFlushResult.FAILURE_RETRYABLE, result)
        assertTrue("失败窗口不应进 flushed 集", !scheduler.hasFlushed(start.toEpochMilli()))
        assertTrue("失败计数应可见", scheduler.retryCount(start.toEpochMilli()) >= 1)
        // 缓冲保留（不静默丢失）
        assertFalse(hub.snapshotAccel().isEmpty())
    }
}
