package com.yunjue.echo.mind

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.ConsentEntity
import com.yunjue.echo.mind.data.DailyPortraitEntity
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.FeatureVectorEntity
import com.yunjue.echo.mind.data.LocalDataRights
import com.yunjue.echo.mind.data.LocalPortraitDataSource
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * 本地优先模式测试（Robolectric）：
 * - 本地模式 = 未订阅（无 access token）：画像由端侧引擎生成、数据只在本机；
 * - 服务端失败时回退本地画像（localComputed=true）；
 * - 本地模式下 outbox 静默（不产生任何上行）；
 * - 本地数据权利：导出/删除在本机完成（数据不出设备）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalModeTest {

    private lateinit var context: android.content.Context
    private lateinit var preferences: AppPreferences
    private lateinit var db: EchoDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context, JvmTestFieldCipher())
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(): PortraitRepository = PortraitRepository(
        db = db,
        preferences = preferences,
        apiClient = ApiClient(tokenProvider = { null }),
        outbox = Outbox(db, JvmTestFieldCipher(), localModeProvider = { preferences.localMode }),
        localDataSource = LocalPortraitDataSource(db)
    )

    private suspend fun insertTodayWindow() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli() + 8 * 60 * 60_000L
        val v = MutableList(22) { 0f }
        v[14] = 1f
        v[16] = 30 * 60000f
        db.dao().insertFeatureVector(
            FeatureVectorEntity(
                id = "fv_local_today",
                userId = preferences.userId,
                schemaVersion = "passive-core-v1",
                source = "screen",
                windowStart = startMs,
                windowEnd = startMs + 300_000L,
                summaryCiphertext = "enc",
                vector = JSONArray(v).toString(),
                synced = false,
                createdAt = Instant.now().toEpochMilli(),
                sourcesPresentJson = JSONArray(listOf("screen")).toString()
            )
        )
    }

    @Test
    fun localModeDerivedFromTokenPresence() {
        // 未订阅（无 token）→ 本地模式
        assertTrue("无 token 应为本地模式", preferences.localMode)
        // 订阅（有 token）→ 进入云端同步模式
        preferences.accessToken = "mock_token"
        assertFalse("有 token 应退出本地模式", preferences.localMode)
        preferences.accessToken = null
        assertTrue(preferences.localMode)
    }

    @Test
    fun localModeUsesLocalEngineWithoutNetwork() = runBlocking {
        preferences.userId = "local_mode_test"
        preferences.setPassiveSensingEnabled(true)
        insertTodayWindow()

        val repo = repository()
        repo.refreshTodayPortrait(networkAvailable = true)

        val state = repo.observeTodayPortrait().value
        assertTrue("本地模式应走端侧引擎（localComputed=true）", state.localComputed)
        assertEquals(false, state.offline)
        // 单日数据：冷启动状态由本地引擎给出（WARMING_UP）
        assertEquals(PortraitStatus.WARMING_UP, state.status)
        assertTrue(state.portrait?.summary?.isNotBlank() == true)
    }

    @Test
    fun serverFailureFallsBackToLocalEngine() = runBlocking {
        // 已订阅（非本地模式）但服务端不可达 → 本地引擎回退
        preferences.accessToken = "mock_token"
        preferences.userId = "u_offline_fallback"
        preferences.setPassiveSensingEnabled(true)
        insertTodayWindow()

        val repo = repository()
        repo.refreshTodayPortrait(networkAvailable = false)

        val state = repo.observeTodayPortrait().value
        assertTrue("服务端失败应回退本地引擎", state.localComputed)
        assertEquals(PortraitStatus.WARMING_UP, state.status)
    }

    @Test
    fun localModeOutboxStaysSilent() = runBlocking {
        // 本地模式：outbox 不写入（数据仅保存在本机，避免无界累积）
        val silentOutbox = Outbox(db, JvmTestFieldCipher(), localModeProvider = { true })
        silentOutbox.enqueue("evt_local", "consent", org.json.JSONObject().apply { put("granted", true) }, 20)
        assertEquals("本地模式下 outbox 应为空", 0, db.dao().pendingOutbox().size)

        val activeOutbox = Outbox(db, JvmTestFieldCipher(), localModeProvider = { false })
        activeOutbox.enqueue("evt_bound", "consent", org.json.JSONObject().apply { put("granted", true) }, 20)
        assertEquals("订阅后正常入队", 1, db.dao().pendingOutbox().size)
    }

    @Test
    fun localDataRightsExportAndDelete() = runBlocking {
        val cipher = JvmTestFieldCipher()
        val userId = "u_rights"
        preferences.userId = userId
        insertTodayWindow()
        db.dao().insertFeatureVector(
            FeatureVectorEntity(
                id = "fv_enc", userId = userId, schemaVersion = "passive-core-v1", source = "accel",
                windowStart = 0L, windowEnd = 300_000L, summaryCiphertext = cipher.encrypt("活动量中"),
                vector = "[0.1]", synced = false, createdAt = 1L, sourcesPresentJson = """["accel"]"""
            )
        )
        db.portraitDao().insert(
            DailyPortraitEntity(
                id = "2026-08-10_u_rights", localDate = "2026-08-10", userId = userId,
                status = "READY", confidence = "MEDIUM", headlineJson = "[]", summary = "本地画像",
                dimensionsJson = "{}", factsJson = "[]", coverageJson = null, timezoneUsed = null, fetchedAt = 1L
            )
        )
        db.consentDao().insertConsent(
            ConsentEntity(
                eventId = "c1", userId = userId, consentType = "passive_sensing",
                version = "path-a-consent-2026.07", granted = true, grantedAt = 1L, evidenceHash = "h"
            )
        )
        // ERA 47 覆盖复核：记忆 + Journey Canonical 快照纳入导出/删除五域
        db.memoryDao().upsert(
            com.yunjue.echo.mind.data.EchoMemoryEntity(
                id = "m1", userId = userId, type = "CORRECTION", content = "你纠正过我", source = "ui",
                confidence = 1f, createdAt = 1L, lastConfirmedAt = 2L, importance = 90,
                retentionClass = "LONG_TERM", provenance = "correction", deleted = false
            )
        )
        db.journeyCanonicalDao().upsert(
            com.yunjue.echo.mind.data.JourneyCanonicalDayEntity(
                id = "cd1", userId = userId, localDate = "2026-08-10", payload = "{}", createdAtEpochMs = 1L
            )
        )

        val rights = LocalDataRights(db, cipher)
        val json = rights.exportLocalData(userId)
        assertTrue("导出应含派生特征窗口", json.contains("derived_feature_windows"))
        assertTrue("导出应解密 summary 明文", json.contains("活动量中"))
        assertTrue("导出应含同意记录", json.contains("passive_sensing"))
        assertTrue("导出应含记忆（五域对齐）", json.contains("\"memories\"") && json.contains("你纠正过我"))
        assertTrue("导出应含 Journey Canonical 快照", json.contains("journey_canonical_days") && json.contains("cd1"))

        rights.deleteLocalData(userId)
        assertEquals("删除后无派生特征", 0, db.dao().allPassiveCoreRows(userId).size)
        assertEquals("删除后无画像缓存", 0, db.portraitDao().queryByDateRange(userId, "1900-01-01", "2999-12-31").size)
        assertEquals("删除后无同意记录", 0, db.consentDao().allByUser(userId).size)
        assertEquals("删除后无记忆", 0, db.memoryDao().allByUser(userId).size)
        assertEquals("删除后无 Canonical 快照", 0, db.journeyCanonicalDao().countByUser(userId))
    }

    @Test
    fun footprintSummaryCountsFiveDomains() = runBlocking {
        // ERA 66（ADR-062 第 1 轮）：数据权利体检台五域足迹（存储真值计数）
        val cipher = JvmTestFieldCipher()
        val userId = "u_footprint"
        preferences.userId = userId
        db.dao().insertFeatureVector(
            FeatureVectorEntity(
                id = "fv_fp", userId = userId, schemaVersion = "passive-core-v1", source = "accel",
                windowStart = 0L, windowEnd = 300_000L, summaryCiphertext = cipher.encrypt("x"),
                vector = "[0.1]", synced = false, createdAt = 1L, sourcesPresentJson = """["accel"]"""
            )
        )
        db.portraitDao().insert(
            DailyPortraitEntity(
                id = "2026-08-10_u_footprint", localDate = "2026-08-10", userId = userId,
                status = "READY", confidence = "MEDIUM", headlineJson = "[]", summary = "x",
                dimensionsJson = "{}", factsJson = "[]", coverageJson = null, timezoneUsed = null, fetchedAt = 1L
            )
        )
        db.consentDao().insertConsent(
            ConsentEntity(
                eventId = "c_fp", userId = userId, consentType = "passive_sensing",
                version = "v", granted = true, grantedAt = 1L, evidenceHash = "h"
            )
        )
        db.memoryDao().upsert(
            com.yunjue.echo.mind.data.EchoMemoryEntity(
                id = "m_fp", userId = userId, type = "CONTEXT", content = "出差", source = "ui",
                confidence = 1f, createdAt = 1L, lastConfirmedAt = 1L, importance = 50,
                retentionClass = "LONG_TERM", provenance = "ctx", deleted = false
            )
        )
        db.journeyCanonicalDao().upsert(
            com.yunjue.echo.mind.data.JourneyCanonicalDayEntity(
                id = "cd_fp", userId = userId, localDate = "2026-08-10", payload = "{}", createdAtEpochMs = 1L
            )
        )

        val rights = LocalDataRights(db, cipher)
        val footprint = rights.footprintSummary(userId)
        assertEquals(1, footprint.featureWindows)
        assertEquals(1, footprint.portraits)
        assertEquals(1, footprint.consents)
        assertEquals(1, footprint.memories)
        assertEquals(1, footprint.journeyCanonicalDays)
        assertEquals(5, footprint.total)

        rights.deleteLocalData(userId)
        assertEquals("删除后足迹应归零", 0, rights.footprintSummary(userId).total)
    }
}
