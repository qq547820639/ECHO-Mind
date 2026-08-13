package com.yunjue.echo.mind

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.model.SyncState
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.6.2（Batch A）：SyncWorker 认证暂停 + 未知类型 telemetry 状态单测（Robolectric，SDK 35）。
 *
 * 覆盖：
 * - 401/403 → 持久化 authRequired 暂停态（doWork 开头据此直接 success，停止后台重试）
 * - 重新认证成功 → clearAuthBlocked 清除暂停态
 * - 未知事件类型 → dead-letter + unknown_type telemetry 计数（毒丸防毒化可观测）
 * - 批次级 mapSyncState：auth 暂停映射 BLOCKED_BY_AUTH，且优先于其他类别
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncWorkerAuthPauseTest {

    private lateinit var context: Context
    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context, JvmTestFieldCipher())
        preferences.clearAuthBlocked()
    }

    // ===== authRequired 暂停态 =====

    @Test
    fun authRequiredDefaultsToFalse() {
        assertFalse("初始不应处于认证暂停态", preferences.authRequired)
        assertTrue("lastAuthBlockedAt 初始应为 0", preferences.lastAuthBlockedAt == 0L)
    }

    @Test
    fun authBlockedPersistsAndClears() {
        // 批内遇 401/403 → 写入暂停态
        preferences.lastAuthBlockedAt = System.currentTimeMillis()
        assertTrue("写入后应处于认证暂停态", preferences.authRequired)
        // 重新认证成功 → 清除
        preferences.clearAuthBlocked()
        assertFalse("clearAuthBlocked 后应解除暂停", preferences.authRequired)
    }

    @Test
    fun authBlockedStateSurvivesPrefsReopen() {
        // 同一 SharedPreferences 存储：新实例仍读到暂停态（模拟进程内多次读取）
        preferences.lastAuthBlockedAt = System.currentTimeMillis()
        val reopened = AppPreferences(context, JvmTestFieldCipher())
        assertTrue("暂停态应持久化到 SharedPreferences", reopened.authRequired)
        reopened.clearAuthBlocked()
        assertFalse(reopened.authRequired)
    }

    @Test
    fun mapSyncStateAuthBlockedMapsToBlockedByAuth() {
        // 先持久化认证暂停态，再验证映射
        preferences.lastAuthBlockedAt = System.currentTimeMillis()
        assertEquals(
            SyncState.BLOCKED_BY_AUTH,
            mapSyncState(
                pendingCount = 5,
                networkAvailable = true,
                deadLetterCount = 0,
                authBlocked = preferences.authRequired,
                consentBlocked = false,
                retrying = false
            )
        )
    }

    @Test
    fun authPauseSuppressesRetryState() {
        // 即使批内同时有可重试失败，认证暂停仍映射为 BLOCKED_BY_AUTH（暂停优先，不再 retry 提示）
        assertEquals(
            SyncState.BLOCKED_BY_AUTH,
            mapSyncState(
                pendingCount = 5,
                networkAvailable = true,
                deadLetterCount = 0,
                authBlocked = true,
                consentBlocked = false,
                retrying = true
            )
        )
    }

    // ===== 未知事件类型 dead-letter + telemetry =====

    @Test
    fun unknownTypeRecordsDeadLetterAndTelemetry() {
        preferences.addDeadLetterEvent("weird_type:evt_1:unknown_type")
        assertEquals("unknown type 应计入 dead-letter", 1, preferences.deadLetterCount())

        preferences.recordUnknownTypeTelemetry("weird_type")
        preferences.recordUnknownTypeTelemetry("weird_type")
        assertEquals("unknown type telemetry 应累计计数", 2, preferences.unknownTypeTelemetryCount("weird_type"))
        assertEquals("其他类型计数不受影响", 0, preferences.unknownTypeTelemetryCount("another_type"))
    }

    @Test
    fun unknownTypeTelemetryIsPerEventType() {
        preferences.recordUnknownTypeTelemetry("t1")
        preferences.recordUnknownTypeTelemetry("t2")
        assertEquals(1, preferences.unknownTypeTelemetryCount("t1"))
        assertEquals(1, preferences.unknownTypeTelemetryCount("t2"))
    }
}
