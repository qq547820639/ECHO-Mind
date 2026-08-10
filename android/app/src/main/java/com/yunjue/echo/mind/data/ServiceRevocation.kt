package com.yunjue.echo.mind.data

import android.content.Context
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.sensing.PassiveSensingService
import com.yunjue.echo.mind.sensing.SensingEventHub
import kotlinx.coroutines.flow.first

/**
 * 服务撤回 / 同意状态唯一领域操作（v0.6.1，P0-3）。
 *
 * 所有 UI 入口（支持页开关、数据权利按钮、Onboarding）都必须经由本协调器，
 * 禁止各页面各自实现一半（旧实现中「撤回同意并停止服务」按钮不停止服务、
 * 不清 buffer、不关 mic，与总开关行为不一致）。
 *
 * revokeService() 原子/幂等地协调（对用户可感知的顺序保证）：
 *   1. 被动感知 OFF（本地持久化，UI 立即变）
 *   2. microphone OFF（本地持久化）
 *   3. 停止前台服务 + 各 Collector
 *   4. 清空内存感知缓冲（SensingEventHub）
 *   5. passive_sensing consent revoke 证据入 Outbox（版本化 evidence）
 *   6. voice_features consent revoke 证据入 Outbox（如适用）
 *   7. psychological/service consent revoke 证据入 Outbox（如适用）
 *   8. DSR revoke_service 入 Outbox（服务端撤销语义）
 *   9. 本地 UI 状态更新（sensingActive=false）并触发同步
 *
 * 重复调用幂等：本地状态已是 OFF / 服务已停时跳过重复副作用。
 *
 * 重新启用（re-enable，OFF→ON）也统一走这里：
 *   - 必须先产生 passive_sensing granted 证据并**先于**新 derived feature 入队
 *     （SyncWorker 按 priority + createdAt 排序，consent priority=600 高于 feature=20）；
 *   - 服务端尚未接受 granted consent 前，UI 显示「正在重新启用 / 等待授权同步」，
 *     不假装完全正常（避免 ingest 持续 412 时 UI 却显示已开启）。
 */
object ServiceRevocationCoordinator {

    /** 撤回并停止服务（幂等）。@return 是否发生了实际变更 */
    suspend fun revokeService(
        context: Context,
        preferences: AppPreferences,
        repository: LocalRepository,
        revokePsychologicalConsent: Boolean = true
    ): Boolean {
        val wasEnabled = preferences.passiveSensingPrefs.passiveSensingEnabled.first()
        val micWasEnabled = preferences.passiveSensingPrefs.micEnabled.first()
        val alreadyRevoked = preferences.serviceRevocationSubmitted
        // 1+2. 本地状态立即置 OFF（UI 第一性）
        preferences.setPassiveSensingEnabled(false)
        preferences.setMicEnabled(false)
        // 3+4. 停止服务/collector + 清空内存缓冲
        PassiveSensingService.stop(context)
        SensingEventHub.getInstance().clearAll()
        if (alreadyRevoked) {
            // 幂等重放：本地已提交过撤回 → 不重复入队证据（服务端按 event_id 幂等）
            preferences.sensingActive = false
            return false
        }
        // 5. passive_sensing revoke 证据
        repository.savePassiveSensingConsent(false)
        // 6. voice_features revoke 证据（如适用）
        runCatching { repository.saveVoiceFeaturesConsent(false) }
        // 7. psychological/service revoke 证据（如适用）
        if (revokePsychologicalConsent) {
            val userId = preferences.userId
            val evidence = java.security.MessageDigest.getInstance("SHA-256")
                .digest("path-a-consent-2026.07:$userId:false".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            repository.saveConsent(
                granted = false,
                evidenceHash = evidence,
                consentType = "psychological_data",
                version = "path-a-consent-2026.07",
                priority = 600
            )
        }
        // 8. DSR revoke_service（服务端撤回语义）
        repository.requestDataAction("revoke_service")
        // 9. 本地 UI 状态更新 + 触发同步
        preferences.sensingActive = false
        preferences.consentSyncPending = false
        preferences.serviceRevocationSubmitted = true
        SyncWorker.enqueue(context)
        return true
    }

    /**
     * 重新启用被动感知（OFF→ON，P0-3 B）：
     *
     * 1. 本地 consent 置 ON；
     * 2. **先**产生 passive_sensing granted 证据入 Outbox（同步顺序先于新特征）；
     * 3. 拉取租户 flag（fail-closed：flag 关闭则服务不启动）；
     * 4. 服务启动由 PassiveSensingService 三重门控决定；
     * 5. UI 层在服务端接受 granted consent 前显示「正在重新启用 / 等待授权同步」
     *    （由 AppPreferences.consentSyncPending 表达）。
     */
    suspend fun reEnablePassiveSensing(
        context: Context,
        preferences: AppPreferences,
        repository: LocalRepository
    ) {
        preferences.setPassiveSensingEnabled(true)
        // consent grant 证据必须早于新的 derived feature 入队（priority 600 > 20，
        // 且 SyncWorker 顺序处理；后端最新 consent 为 granted 后 ingest 不再 412）
        repository.savePassiveSensingConsent(true)
        // 标记"等待授权同步"：服务端接受前 UI 显示等待态
        preferences.consentSyncPending = true
        runCatching { repository.fetchFeatureFlags() }
        PassiveSensingService.start(context)
        SyncWorker.enqueue(context)
    }

    /**
     * 关闭被动感知总开关（只撤回 sensing，不撤回整个服务/DSR；支持页开关用）。
     * 幂等：重复关闭不重复入队 revoke 证据（由调用方判断 wasEnabled 决定是否调用）。
     */
    suspend fun disablePassiveSensingOnly(
        context: Context,
        preferences: AppPreferences,
        repository: LocalRepository
    ) {
        preferences.setPassiveSensingEnabled(false)
        PassiveSensingService.stop(context)
        SensingEventHub.getInstance().clearAll()
        repository.savePassiveSensingConsent(false)
        preferences.sensingActive = false
        SyncWorker.enqueue(context)
    }
}
