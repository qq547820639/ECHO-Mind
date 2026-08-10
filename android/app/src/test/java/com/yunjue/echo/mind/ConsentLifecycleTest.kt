package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.security.FieldCipher
import com.yunjue.echo.mind.sensing.SensingEventHub
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
 * T02/T05 consent 生命周期单测（Robolectric，SDK 35）。
 *
 * 验证关闭被动感知总开关的原子本地流程（PRD 契约点 3）：
 * 1. consent=false 已持久化（DataStore）
 * 2. 服务停止 + hub 缓冲清空（内存）
 * 3. revoke evidence（consent/passive_sensing/granted=false）入 outbox
 * 4. 网络不可用不阻塞本地停止（流程全部本地完成）
 * 5. feature flag fail-closed：无缓存时 passive_sensing_enabled/sandbox_enabled 默认 false
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsentLifecycleTest {

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
        repository = LocalRepository(db, cipher, preferences, ApiClient(tokenProvider = { null }))
        runBlocking {
            preferences.setPassiveSensingEnabled(true)
            preferences.setMicEnabled(true)
        }
    }

    @After
    fun tearDown() {
        db.close()
        SensingEventHub.resetForTest()
    }

    @Test
    fun atomicStopPersistsConsentFalseAndClearsHub() = runBlocking {
        val hub = SensingEventHub.getInstance()
        hub.onNotificationPosted(
            com.yunjue.echo.mind.sensing.NotificationCollector.NotificationMeta(
                System.currentTimeMillis(), "pkg", "social"
            )
        )
        assertFalse("关闭前 hub 不应为空", hub.isEmpty())

        performPassiveSensingStop(context, preferences, repository)

        // 1. consent=false 已持久化
        assertFalse("consent 应持久化为 false", preferences.passiveSensingEnabledFlow().first())
        // 麦克风：总关停止采集（服务门禁停止 micCollector），但独立 micEnabled 偏好保留
        // （重新开启主开关后可恢复；mic 实际采集由 PassiveSensingService 门禁统一控制）
        assertTrue("micEnabled 独立偏好保留（采集由主开关门禁控制）", preferences.micEnabledFlow().first())
        // 2. hub 缓冲清空（内存）
        assertTrue("hub 缓冲应已清空", hub.isEmpty())
        // 3. revoke evidence 入 outbox（consent/passive_sensing/granted=false）
        val pending = db.dao().pendingOutbox()
        val revoke = pending.firstOrNull { it.eventType == "consent" }
        assertTrue("outbox 应存在 consent 撤回事件", revoke != null)
        val payload = cipher.decrypt(revoke!!.payloadCiphertext)
        assertTrue("consent payload 应含 consent_type=passive_sensing", payload.contains("passive_sensing"))
        assertTrue("consent payload 应含 granted=false", payload.contains("\"granted\":false"))
    }

    @Test
    fun atomicStopWritesRevocableEvidenceHash() = runBlocking {
        performPassiveSensingStop(context, preferences, repository)
        val revoke = db.dao().pendingOutbox().first { it.eventType == "consent" }
        val payload = cipher.decrypt(revoke.payloadCiphertext)
        // 证据哈希可重算（固定盐 + userId + granted=false）
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("passive-sensing-consent-2026.07:u_demo:false".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue("revoke evidence 应可重算校验", payload.contains(expected))
    }

    @Test
    fun atomicStopDoesNotRequireNetwork() = runBlocking {
        // 流程全部本地完成：无网络依赖（SyncWorker.enqueue 仅入队，网络恢复后上传）
        performPassiveSensingStop(context, preferences, repository)
        assertFalse(preferences.passiveSensingEnabledFlow().first())
        assertTrue("撤回事件应入 outbox（本地），等待网络恢复后上传", db.dao().pendingOutbox().isNotEmpty())
    }

    @Test
    fun featureFlagsFailClosedWithoutCache() {
        // 无缓存/缺 key → passive_sensing_enabled 与 sandbox_enabled 默认 false（fail-closed）
        val snapshot = preferences.getFeatureFlagsSnapshot()
        assertFalse("passive_sensing_enabled 无缓存应默认 false", snapshot["passive_sensing_enabled"] ?: true)
        assertFalse("sandbox_enabled 无缓存应默认 false", snapshot["sandbox_enabled"] ?: true)
        assertTrue("skills_delivery_enabled 非隐私敏感可默认 true", snapshot["skills_delivery_enabled"] ?: false)
    }

    @Test
    fun featureFlagsParseMissingKeysFailClosed() {
        // 缓存 JSON 缺 key 时隐私 flag 应为 false
        preferences.setFeatureFlags(mapOf("skills_delivery_enabled" to true))
        val snapshot = preferences.getFeatureFlagsSnapshot()
        assertFalse("缺 passive_sensing_enabled key 应默认 false", snapshot["passive_sensing_enabled"] ?: true)
        assertFalse("缺 sandbox_enabled key 应默认 false", snapshot["sandbox_enabled"] ?: true)
        assertTrue(snapshot["skills_delivery_enabled"] ?: false)
    }

    @Test
    fun saveDerivedFeatureWithPassiveRedTermsReturnsNoneAndNoEscalation() = runBlocking {
        // PRD 契约点 1：行为派生特征（含旧被动 RED 词条）不得触发危机链路
        val input = com.yunjue.echo.mind.model.DerivedFeatureInput(
            schemaVersion = "feat-v1",
            source = "screen",
            windowStart = Instant.now(),
            windowEnd = Instant.now().plusSeconds(300),
            summary = "过去5分钟活动量低，用户被提及一次自杀倾向关键词。",
            vector = listOf(0f, 1f, 2f),
            sourcesPresent = listOf("screen")
        )
        val decision = repository.saveDerivedFeature(input)

        assertEquals("被动摘要不得返回 RED", Severity.NONE, decision.severity)
        assertFalse("被动摘要不得冻结生成", decision.freezeGeneration)
        // outbox 仅含 derived_feature，不产生 passive_red_signal 升级事件
        val pending = db.dao().pendingOutbox()
        assertTrue("应存在 derived_feature 事件", pending.any { it.eventType == "derived_feature" })
        assertTrue("不应产生 passive_red_signal 升级", pending.none { it.eventType == "escalation" })
    }
}
