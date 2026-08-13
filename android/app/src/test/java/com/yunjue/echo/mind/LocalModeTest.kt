package com.yunjue.echo.mind

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.FeatureVectorEntity
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
 * - 本地模式 = 未绑定机构（无 access token）：画像由端侧引擎生成、数据只在本机；
 * - 服务端失败时回退本地画像（localComputed=true）；
 * - 本地模式下 outbox 静默（不产生任何上行）。
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
        // 未绑定机构（无 token）→ 本地模式
        assertTrue("无 token 应为本地模式", preferences.localMode)
        // 绑定机构（有 token）→ 进入云端同步模式
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
        // 已绑定机构（非本地模式）但服务端不可达 → 本地引擎回退
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
        assertEquals("绑定机构后正常入队", 1, db.dao().pendingOutbox().size)
    }
}
