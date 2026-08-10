package com.yunjue.echo.mind

import com.yunjue.echo.mind.data.SyncAction
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.parseRetryAfterSeconds
import com.yunjue.echo.mind.data.syncStateText
import com.yunjue.echo.mind.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02/T05 SyncWorker 状态机单测（纯 JVM）。
 *
 * - 事件类型 → 路径映射（含 skill_completion → /v1/skills/completions）
 * - 410 terminal：deprecated 类型删除 + 迁移；非 deprecated 类型 dead-letter
 * - max attempts：超限 → dead-letter，不再重试
 * - 坏事件不阻塞队列（每事件独立分类；401/403/412/422 → KEEP_PENDING）
 * - 429 Retry-After 解析
 * - SyncResult → SyncState 映射全分支 + 用户文案不含 HTTP status
 */
class SyncWorkerStateMachineTest {

    // ===== 路径映射 =====

    @Test
    fun resolvePathMapsAllKnownEventTypes() {
        assertEquals("/v1/checkins", SyncWorker.resolvePath("checkin"))
        assertEquals("/v1/escalations", SyncWorker.resolvePath("escalation"))
        assertEquals("/v1/onboarding/consents", SyncWorker.resolvePath("consent"))
        assertEquals("/v1/onboarding/l0", SyncWorker.resolvePath("l0"))
        assertEquals("/v1/onboarding/emergency-contact", SyncWorker.resolvePath("emergency_contact"))
        assertEquals("/v1/journals", SyncWorker.resolvePath("journal"))
        assertEquals("/v1/questionnaires/phq9/responses", SyncWorker.resolvePath("questionnaire:phq9"))
        assertEquals("/v1/practices/completions", SyncWorker.resolvePath("practice"))
        assertEquals("/v1/data-subject-requests", SyncWorker.resolvePath("dsr"))
        assertEquals("/v1/features/ingest", SyncWorker.resolvePath("derived_feature"))
        assertEquals("/v1/skills/completions", SyncWorker.resolvePath("skill_completion"))
        assertNull("未知类型应返回 null（调用方删除，避免毒化队列）", SyncWorker.resolvePath("unknown_type"))
    }

    @Test
    fun deprecatedEventTypesAreRecognized() {
        assertTrue(SyncWorker.isDeprecatedEventType("checkin"))
        assertTrue(SyncWorker.isDeprecatedEventType("journal"))
        assertTrue(SyncWorker.isDeprecatedEventType("questionnaire:phq9"))
        assertTrue(SyncWorker.isDeprecatedEventType("practice"))
        assertFalse(SyncWorker.isDeprecatedEventType("derived_feature"))
        assertFalse(SyncWorker.isDeprecatedEventType("skill_completion"))
        assertFalse(SyncWorker.isDeprecatedEventType("consent"))
    }

    // ===== 2xx / 409 → DELETE =====

    @Test
    fun successAndConflictAreDeleted() {
        assertEquals(SyncAction.DELETE, SyncWorker.classifySyncOutcome("derived_feature", 200, 0))
        assertEquals(SyncAction.DELETE, SyncWorker.classifySyncOutcome("derived_feature", 204, 3))
        assertEquals(SyncAction.DELETE, SyncWorker.classifySyncOutcome("skill_completion", 201, 0))
        assertEquals(SyncAction.DELETE, SyncWorker.classifySyncOutcome("derived_feature", 409, 0))
    }

    // ===== 410 Gone → deprecated 删除迁移 / 其他 dead-letter =====

    @Test
    fun deprecatedEvent410IsDeletedAndMigrated() {
        for (type in listOf("checkin", "journal", "questionnaire:phq9", "practice")) {
            assertEquals("$type 410 应 DELETE_AND_MIGRATE", SyncAction.DELETE_AND_MIGRATE,
                SyncWorker.classifySyncOutcome(type, 410, 0))
        }
    }

    @Test
    fun nonDeprecatedEvent410IsDeadLettered() {
        assertEquals(SyncAction.DEAD_LETTER, SyncWorker.classifySyncOutcome("derived_feature", 410, 0))
        assertEquals(SyncAction.DEAD_LETTER, SyncWorker.classifySyncOutcome("skill_completion", 410, 0))
    }

    // ===== 401/403 terminal 但不中断队列 =====

    @Test
    fun authFailuresKeepPendingWithoutBlockingQueue() {
        assertEquals(SyncAction.KEEP_PENDING, SyncWorker.classifySyncOutcome("derived_feature", 401, 0))
        assertEquals(SyncAction.KEEP_PENDING, SyncWorker.classifySyncOutcome("derived_feature", 403, 5))
        // 401/403 永不 dead-letter（等待重新登录后可补传）
        assertEquals(SyncAction.KEEP_PENDING, SyncWorker.classifySyncOutcome("derived_feature", 401, 99))
    }

    // ===== 412/422 → KEEP_PENDING，超限 dead-letter =====

    @Test
    fun consentAndValidationFailuresKeepPendingUntilMaxAttempts() {
        assertEquals(SyncAction.KEEP_PENDING, SyncWorker.classifySyncOutcome("derived_feature", 412, 0))
        assertEquals(SyncAction.KEEP_PENDING, SyncWorker.classifySyncOutcome("derived_feature", 422, 9))
        assertEquals(SyncAction.DEAD_LETTER, SyncWorker.classifySyncOutcome("derived_feature", 412, 10))
        assertEquals(SyncAction.DEAD_LETTER, SyncWorker.classifySyncOutcome("derived_feature", 422, 10))
    }

    // ===== 5xx/429 retry；超限 dead-letter（毒丸保护） =====

    @Test
    fun serverErrorsRetryUntilMaxAttempts() {
        assertEquals(SyncAction.RETRY, SyncWorker.classifySyncOutcome("derived_feature", 500, 0))
        assertEquals(SyncAction.RETRY, SyncWorker.classifySyncOutcome("derived_feature", 503, 9))
        assertEquals(SyncAction.DEAD_LETTER, SyncWorker.classifySyncOutcome("derived_feature", 500, 10))
    }

    @Test
    fun rateLimit429Retries() {
        assertEquals(SyncAction.RETRY, SyncWorker.classifySyncOutcome("derived_feature", 429, 0))
    }

    @Test
    fun unknownCodesRetry() {
        assertEquals(SyncAction.RETRY, SyncWorker.classifySyncOutcome("derived_feature", 418, 0))
    }

    // ===== Retry-After 解析 =====

    @Test
    fun retryAfterSecondsParsesNumericHeader() {
        assertEquals(30, parseRetryAfterSeconds("30"))
        assertEquals(0, parseRetryAfterSeconds("0"))
        assertNull(parseRetryAfterSeconds(null))
        assertNull(parseRetryAfterSeconds(""))
        assertNull(parseRetryAfterSeconds("not-a-number"))
        assertNull(parseRetryAfterSeconds("-5"))
    }

    // ===== SyncResult → SyncState 映射（PRD 契约点 9） =====

    @Test
    fun syncStateMappingCoversAllBranches() {
        // 2xx/409 + 无 pending → synced
        assertEquals(SyncState.SYNCED, mapSyncState(pendingCount = 0, lastHttpCode = 200, networkAvailable = true, deadLetterCount = 0))
        // pending > 0 + 正常 → pending
        assertEquals(SyncState.PENDING, mapSyncState(pendingCount = 3, lastHttpCode = null, networkAvailable = true, deadLetterCount = 0))
        // 网络异常（pending>0）→ offline
        assertEquals(SyncState.OFFLINE, mapSyncState(pendingCount = 3, lastHttpCode = null, networkAvailable = false, deadLetterCount = 0))
        // 401/403 → blocked_by_auth
        assertEquals(SyncState.BLOCKED_BY_AUTH, mapSyncState(pendingCount = 2, lastHttpCode = 401, networkAvailable = true, deadLetterCount = 0))
        assertEquals(SyncState.BLOCKED_BY_AUTH, mapSyncState(pendingCount = 2, lastHttpCode = 403, networkAvailable = true, deadLetterCount = 0))
        // 412 → blocked_by_consent
        assertEquals(SyncState.BLOCKED_BY_CONSENT, mapSyncState(pendingCount = 2, lastHttpCode = 412, networkAvailable = true, deadLetterCount = 0))
        // 5xx/429 → retrying
        assertEquals(SyncState.RETRYING, mapSyncState(pendingCount = 2, lastHttpCode = 500, networkAvailable = true, deadLetterCount = 0))
        assertEquals(SyncState.RETRYING, mapSyncState(pendingCount = 2, lastHttpCode = 429, networkAvailable = true, deadLetterCount = 0))
        // dead-letter 存在 → failed_terminal（旧版数据无需再上传）
        assertEquals(SyncState.FAILED_TERMINAL, mapSyncState(pendingCount = 1, lastHttpCode = null, networkAvailable = true, deadLetterCount = 2))
    }

    @Test
    fun syncStateTextNeverExposesHttpStatus() {
        val texts = listOf(
            syncStateText(SyncState.SYNCED, 0),
            syncStateText(SyncState.PENDING, 5),
            syncStateText(SyncState.SYNCING, 0),
            syncStateText(SyncState.OFFLINE, 3),
            syncStateText(SyncState.RETRYING, 2),
            syncStateText(SyncState.BLOCKED_BY_AUTH, 2),
            syncStateText(SyncState.BLOCKED_BY_CONSENT, 2),
            syncStateText(SyncState.FAILED_TERMINAL, 1)
        )
        // 严禁暴露 HTTP status / 内部码
        val forbidden = listOf("HTTP", "401", "403", "412", "422", "429", "500", "503")
        for (text in texts) {
            for (token in forbidden) {
                assertFalse("文案不应暴露内部码 $token，实际：$text", text.contains(token))
            }
        }
    }

    @Test
    fun pendingTextConveysLocalSafety() {
        assertTrue(syncStateText(SyncState.PENDING, 3).contains("3 项待上传"))
        assertTrue(syncStateText(SyncState.PENDING, 3).contains("数据仍安全保存在本机"))
        assertTrue(syncStateText(SyncState.OFFLINE, 0).contains("等待网络恢复"))
        assertTrue(syncStateText(SyncState.BLOCKED_BY_AUTH, 0).contains("登录已失效"))
    }
}
