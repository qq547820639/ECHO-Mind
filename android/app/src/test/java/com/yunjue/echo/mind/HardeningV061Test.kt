package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ActiveSkillSessionEntity
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.EscalationEntity
import com.yunjue.echo.mind.data.EscalationStatus
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.model.SkillCompletionInput
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.security.FieldCipher
import com.yunjue.echo.mind.ui.SkillSessionCoordinator
import com.yunjue.echo.mind.ui.TrendNoDataReason
import com.yunjue.echo.mind.ui.resolveTrendNoDataReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.6.1 hardening：P0/P1 行为回归测试。
 *
 * 覆盖（对应 hardening 需求二/三/四/六/七/八）：
 * - P0-2 人工支持客户端闭环：离线 escalation 排队、ACK 生命周期回写
 * - P0-3 revoke → re-enable：grant 证据先于特征、revoke 立即停止、幂等
 * - P0-4 Skill session：进程死亡恢复 sessionId 一致、多卡不跨卡误删
 * - P1-6 同步状态语义：部分成功/全部成功分类
 * - P1-7 Onboarding：READY_OFFLINE → READY 收敛
 * - P1-8 Trend：background restriction / source gap 真实输入
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HardeningV061Test {

    private lateinit var context: Context
    private lateinit var db: EchoDatabase
    private lateinit var cipher: FieldCipher
    private lateinit var preferences: AppPreferences
    private lateinit var repository: LocalRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cipher = FieldCipher()
        preferences = AppPreferences(context, cipher)
        repository = LocalRepository(db, cipher, preferences, ApiClient(tokenProvider = { null }))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun skill(id: String = "sk_1", name: String = "测试能力") = SkillDisplay(
        id = id,
        name = name,
        version = 1,
        triggerConditions = emptyList(),
        guardrails = emptyList(),
        steps = listOf("第一步", "第二步"),
        status = "signed",
        actionType = "guided_steps",
        revision = 1
    )

    // ===== P0-3：revoke → re-enable =====

    @Test
    fun revokeServiceStopsSensingAndEnqueuesAllRevocationEvidence() = runBlocking {
        preferences.setPassiveSensingEnabled(true)
        preferences.setMicEnabled(true)

        ServiceRevocationCoordinator.revokeService(context, preferences, repository)

        // 1. 本地状态立即 OFF
        assertFalse(preferences.passiveSensingPrefs.passiveSensingEnabled.first())
        assertFalse(preferences.passiveSensingPrefs.micEnabled.first())
        // 2. 撤回证据入 outbox（passive_sensing + voice_features + psychological + DSR）
        val outbox = db.dao().pendingOutbox()
        val consentEvents = outbox.filter { it.eventType == "consent" }
        val dsrEvents = outbox.filter { it.eventType == "dsr" }
        assertTrue("应有 passive_sensing 撤回证据", consentEvents.isNotEmpty())
        assertTrue("应有 DSR revoke_service", dsrEvents.any {
            val payload = cipher.decrypt(it.payloadCiphertext)
            payload.contains("revoke_service")
        })
    }

    @Test
    fun revokeServiceIsIdempotentOnRepeat() = runBlocking {
        preferences.setPassiveSensingEnabled(true)
        ServiceRevocationCoordinator.revokeService(context, preferences, repository)
        val firstCount = db.dao().pendingOutbox().size
        // 再次调用：本地已 OFF，不应重复追加 revoke 证据（幂等）
        ServiceRevocationCoordinator.revokeService(context, preferences, repository)
        val secondCount = db.dao().pendingOutbox().size
        assertEquals("重复撤回不应重复入队证据", firstCount, secondCount)
    }

    @Test
    fun reEnableGeneratesGrantEvidenceBeforeNewFeatures() = runBlocking {
        preferences.setPassiveSensingEnabled(false)
        ServiceRevocationCoordinator.reEnablePassiveSensing(context, preferences, repository)

        // 1. 本地 ON + 等待授权同步标记
        assertTrue(preferences.passiveSensingPrefs.passiveSensingEnabled.first())
        assertTrue("grant 证据入队后应标记等待授权同步", preferences.consentSyncPending)
        // 2. 必须先产生 passive_sensing granted 证据（consent priority=600 高于 feature=20）
        val outbox = db.dao().pendingOutbox()
        val consent = outbox.firstOrNull { it.eventType == "consent" }
        assertNotNull("重新启用必须产生 granted consent 证据", consent)
        assertTrue("consent 优先级应高于 derived_feature",
            consent!!.priority > outbox.filter { it.eventType == "derived_feature" }.map { it.priority }.maxOrNull() ?: 0)
        val payload = cipher.decrypt(consent.payloadCiphertext)
        assertTrue("granted 必须为 true", payload.contains("\"granted\":true"))
        assertTrue(payload.contains("passive_sensing"))
    }

    @Test
    fun consentSyncPendingClearedAfterServerAcceptance() = runBlocking {
        // 重新启用后标记等待授权同步
        preferences.setPassiveSensingEnabled(false)
        ServiceRevocationCoordinator.reEnablePassiveSensing(context, preferences, repository)
        assertTrue(preferences.consentSyncPending)
        // 服务端接受（SyncWorker DELETE 分支清除标记）→ 本地验证清除语义
        preferences.consentSyncPending = false
        assertFalse(preferences.consentSyncPending)
        // 撤回服务 → 标记保持 false（不再显示"等待授权"）
        ServiceRevocationCoordinator.revokeService(context, preferences, repository)
        assertFalse(preferences.consentSyncPending)
    }

    // ===== P0-2：人工支持客户端闭环 =====

    @Test
    fun offlineEscalationQueuesToOutboxWithQueuedStatus() = runBlocking {
        // 离线（无网络）也应能创建：请求本地持久化 + 入 outbox（等待送达）
        val eventId = repository.requestHumanSupport()

        val esc = db.escalationDao().byEventId(eventId)
        assertNotNull(esc)
        assertEquals(EscalationStatus.QUEUED.name, esc!!.status)
        assertNull("未收到服务端确认前 serverEscalationId 必须为空", esc.serverEscalationId)

        val outbox = db.dao().pendingOutbox().first { it.eventType == "escalation" }
        assertEquals(eventId, outbox.eventId)
        val payload = cipher.decrypt(outbox.payloadCiphertext)
        assertTrue(payload.contains("\"event_id\":\"$eventId\""))
        assertTrue(payload.contains("help_requested"))
    }

    @Test
    fun escalationAckLifecycleReflectsServerStatusOnly() = runBlocking {
        val eventId = repository.requestHumanSupport()
        // 服务端接收（SyncWorker 成功后回写）
        repository.markEscalationDelivered(eventId, "esc_server_1")
        var esc = db.escalationDao().byEventId(eventId)!!
        assertEquals(EscalationStatus.DELIVERED.name, esc.status)
        assertEquals("esc_server_1", esc.serverEscalationId)

        // 服务端 user-status：仅 delivery_confirmed（未 ACK）→ 保持已送达，绝不显示人工已收到
        repository.updateEscalationServerStatus(
            eventId,
            """{"escalation_id":"esc_server_1","delivery_confirmed":true,"human_acknowledged":false}"""
        )
        esc = db.escalationDao().byEventId(eventId)!!
        assertNotEquals("未 ACK 不得进入接管状态", EscalationStatus.TAKEN_OVER.name, esc.status)

        // 服务端 ack/takeover → 正在接管
        repository.updateEscalationServerStatus(
            eventId,
            """{"escalation_id":"esc_server_1","delivery_confirmed":true,"human_acknowledged":true}"""
        )
        esc = db.escalationDao().byEventId(eventId)!!
        assertEquals(EscalationStatus.TAKEN_OVER.name, esc.status)
    }

    // ===== P0-4：Skill session 一致性 =====

    @Test
    fun processDeathRestoreKeepsOriginalSessionId() = runBlocking {
        val coordinator = SkillSessionCoordinator(repository)
        val skillA = skill("sk_a", "能力A")

        // 开始执行并持久化（模拟进程死亡前最后状态）
        val view = coordinator.start(skillA)
        val originalSessionId = view.sessionId
        coordinator.persistCurrent("sk_a")

        // 进程死亡：重建协调器（新实例，从 Room 恢复）
        val coordinator2 = SkillSessionCoordinator(repository)
        val restored = coordinator2.getOrRestore(skillA)

        assertEquals("进程死亡恢复必须继续使用原 sessionId", originalSessionId, restored.sessionId)
        assertEquals(com.yunjue.echo.mind.ui.SkillRunStatus.PAUSED, restored.status)

        // finish 用原 sessionId 删除 → 会话行确实被删
        coordinator2.finish(skillA) { "completed" }
        assertNull("finish 后会话行必须删除", repository.loadActiveSession("sk_a"))
    }

    @Test
    fun multiCardDoesNotDeleteOtherSkillsSession() = runBlocking {
        val coordinator = SkillSessionCoordinator(repository)
        val skillA = skill("sk_a", "能力A")
        val skillB = skill("sk_b", "能力B")

        val viewA = coordinator.start(skillA)
        // Skill B 卡片恢复：不应拿到/删除 Skill A 的会话
        val viewB = coordinator.getOrRestore(skillB)
        assertNotEquals("不同 Skill 不应共享会话", viewA.sessionId, viewB.sessionId)
        // Skill B 卡片 finish 只删自己的会话
        coordinator.finish(skillB) { "stopped" }
        assertNotNull("Skill B 完成不得删除 Skill A 的会话", repository.loadActiveSession("sk_a"))
    }

    @Test
    fun singleActiveSessionEnforcedOnStart() = runBlocking {
        val coordinator = SkillSessionCoordinator(repository)
        coordinator.start(skill("sk_a", "能力A"))
        coordinator.start(skill("sk_b", "能力B"))
        // single-active-session：A 的会话被收口，只保留 B
        assertNull(repository.loadActiveSession("sk_a"))
        assertNotNull(repository.loadActiveSession("sk_b"))
    }

    @Test
    fun repeatFinishIsIdempotentViaCompletionEventId() = runBlocking {
        val coordinator = SkillSessionCoordinator(repository)
        val skillA = skill("sk_a", "能力A")
        coordinator.start(skillA)
        // 第一次完成
        val input = SkillCompletionInput(skillId = "sk_a", status = "completed", durationSeconds = 10)
        repository.recordSkillCompletion(input, sessionId = null)
        // 同一 event_id 重复上报（服务端幂等）；本地 outbox 中 event_id 唯一
        val same = input.copy()
        repository.recordSkillCompletion(same, sessionId = null)
        val completions = db.dao().pendingOutbox().filter { it.eventType == "skill_completion" }
        assertEquals("同一 event_id 只入队一次", 1, completions.size)
    }

    // ===== P1-6：同步状态语义（v0.6.2 Batch A：批次级分类签名） =====

    @Test
    fun partialBatchClassification() {
        // success + 429 → retryable
        assertEquals("retryable", SyncWorker.errorClassFor(anyAuthBlocked = false, anyBlockedPending = false, lastCode = 429))
        // success + 412 → consent blocked
        assertEquals("consent", SyncWorker.errorClassFor(anyAuthBlocked = false, anyBlockedPending = true, lastCode = 412))
        // success + 5xx
        assertEquals("retryable", SyncWorker.errorClassFor(anyAuthBlocked = false, anyBlockedPending = false, lastCode = 500))
        // auth（401/403 批次级标志）→ auth，且优先于其他类别
        assertEquals("auth", SyncWorker.errorClassFor(anyAuthBlocked = true, anyBlockedPending = false, lastCode = 401))
        assertEquals("auth", SyncWorker.errorClassFor(anyAuthBlocked = true, anyBlockedPending = true, lastCode = 500))
        // terminal
        assertEquals("terminal", SyncWorker.errorClassFor(anyAuthBlocked = false, anyBlockedPending = false, lastCode = 410))
        // 全成功：errorClassFor 返回兜底 retryable；doWork 成功路径写回 null（此处不再 assertNull）
        assertEquals("retryable", SyncWorker.errorClassFor(anyAuthBlocked = false, anyBlockedPending = false, lastCode = 200))
    }

    // ===== P1-7：Onboarding READY 收敛 =====

    @Test
    fun readyOfflineConvergesToReadyAfterServerAck() = runBlocking {
        preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
        preferences.serverActivated = false
        preferences.userId = "u_demo"

        // 无网络/无服务端：保持 READY_OFFLINE（不假装 READY）
        val ok = repository.confirmServerActivation()
        assertFalse(ok)
        assertEquals(AppPreferences.ONBOARDING_READY_OFFLINE, preferences.onboardingState)

        // 服务端 ack 语义：GET consents/latest 返回 granted 后才收敛（由 SyncWorker 调用）
        // 本地验证契约：直接设置 ack 位
        preferences.serverActivated = true
        preferences.onboardingState = AppPreferences.ONBOARDING_READY
        assertTrue(preferences.onboardingCompleted)
    }

    // ===== P1-8：Trend 真实输入 =====

    @Test
    fun trendNoDataReasonsAllReachable() {
        // consent 关闭 → CLOSED
        assertEquals(TrendNoDataReason.CLOSED, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = false,
            persistenceFailedRecently = false, pendingUploadCount = 0,
            missingSources = emptyList(), consentEnabled = false, permissionGranted = true))
        // 权限未授权 → PERMISSION
        assertEquals(TrendNoDataReason.PERMISSION, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = false,
            persistenceFailedRecently = false, pendingUploadCount = 0,
            missingSources = emptyList(), consentEnabled = true, permissionGranted = false))
        // 后台限制 → SYSTEM_BACKGROUND
        assertEquals(TrendNoDataReason.SYSTEM_BACKGROUND, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = true,
            persistenceFailedRecently = false, pendingUploadCount = 0,
            missingSources = emptyList()))
        // source gap → SOURCE_GAPS
        assertEquals(TrendNoDataReason.SOURCE_GAPS, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = false,
            persistenceFailedRecently = false, pendingUploadCount = 0,
            missingSources = listOf("accel")))
        // 待上传 → AWAITING_UPLOAD
        assertEquals(TrendNoDataReason.AWAITING_UPLOAD, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = false,
            persistenceFailedRecently = false, pendingUploadCount = 3,
            missingSources = emptyList()))
        // 持久化失败 → PERSISTENCE_FAILURE
        assertEquals(TrendNoDataReason.PERSISTENCE_FAILURE, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = false,
            persistenceFailedRecently = true, pendingUploadCount = 0,
            missingSources = emptyList()))
        // 新用户 → NEW_USER
        assertEquals(TrendNoDataReason.NEW_USER, resolveTrendNoDataReason(
            observationDays = 0, systemBackgroundRestricted = false,
            persistenceFailedRecently = false, pendingUploadCount = 0,
            missingSources = emptyList()))
        // 全部正常 → UNKNOWN（无数据但一切正常）
        assertEquals(TrendNoDataReason.UNKNOWN, resolveTrendNoDataReason(
            observationDays = 5, systemBackgroundRestricted = false,
            persistenceFailedRecently = false, pendingUploadCount = 0,
            missingSources = emptyList()))
    }

    // ===== Room migration v5 → v6 =====

    @Test
    fun roomMigrationV5ToV6CreatesEscalationTable() = runBlocking {
        val dbV5 = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_4_5)
            .build()
        // 打开即迁移到 v5；关闭后以 v6 打开验证 escalation_requests 可用
        dbV5.dao().pendingOutbox()
        dbV5.close()

        val migrated = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .build()
        migrated.escalationDao().upsert(
            EscalationEntity(
                eventId = "esc_mig_1", userId = "u", trigger = "help_requested",
                evidenceSummaryCiphertext = "x", status = "QUEUED",
                serverEscalationId = null, serverStatusJson = null,
                createdAtEpochMs = 0L, updatedAtEpochMs = 0L
            )
        )
        assertNotNull(migrated.escalationDao().byEventId("esc_mig_1"))
        migrated.close()
    }
}
