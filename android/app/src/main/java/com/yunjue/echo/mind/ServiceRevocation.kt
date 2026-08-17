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
 * 禁止各页面各自实现一半。Step 3 起直接注入 [ConsentRepository] / [FeatureFlagRepository]，
 * 消除对旧聚合门面的隐蔽依赖。
 *
 * revokeService() 原子/幂等地协调：
 *   1. 被动感知 OFF → 2. microphone OFF → 3. 停止前台服务 + Collector
 *   4. 清空内存感知缓冲 → 5-8. 各 consent revoke 证据 + DSR 入 Outbox
 *   9. 本地 UI 状态更新并触发同步
 */
object ServiceRevocationCoordinator {

    /** 撤回并停止服务（幂等）。@return 是否发生了实际变更 */
    suspend fun revokeService(
        context: Context,
        preferences: AppPreferences,
        consentRepository: ConsentRepository,
        revokePsychologicalConsent: Boolean = true
    ): Boolean {
        val wasEnabled = preferences.passiveSensingPrefs.passiveSensingEnabled.first()
        val micWasEnabled = preferences.passiveSensingPrefs.micEnabled.first()
        val alreadyRevoked = preferences.serviceRevocationSubmitted
        preferences.setPassiveSensingEnabled(false)
        preferences.setMicEnabled(false)
        PassiveSensingService.stop(context)
        SensingEventHub.getInstance().clearAll()
        if (alreadyRevoked) {
            preferences.sensingActive = false
            return false
        }
        consentRepository.savePassiveSensingConsent(false)
        runCatching { consentRepository.saveVoiceFeaturesConsent(false) }
        if (revokePsychologicalConsent) {
            val userId = preferences.userId
            val evidence = java.security.MessageDigest.getInstance("SHA-256")
                .digest("path-a-consent-2026.07:$userId:false".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            consentRepository.saveConsent(
                granted = false,
                evidenceHash = evidence,
                consentType = "psychological_data",
                version = "path-a-consent-2026.07",
                priority = 600
            )
        }
        consentRepository.requestDataAction("revoke_service")
        preferences.sensingActive = false
        preferences.consentSyncPending = false
        preferences.serviceRevocationSubmitted = true
        SyncWorker.enqueue(context)
        return true
    }

    /**
     * 重新启用被动感知（OFF→ON，P0-3 B）：
     * 1. 本地 consent 置 ON；2. 先产生 granted 证据；3. 拉取租户 flag（fail-closed）；
     * 4. 服务启动由 PassiveSensingService 三重门控决定；5. consentSyncPending 表达等待授权同步。
     */
    suspend fun reEnablePassiveSensing(
        context: Context,
        preferences: AppPreferences,
        consentRepository: ConsentRepository,
        featureFlagRepository: FeatureFlagRepository
    ) {
        preferences.setPassiveSensingEnabled(true)
        // ERA 32 R22：复位撤回闩锁——否则「撤回→重开→再撤回」时第二次撤回
        // 会跳过 consent/DSR 证据写入，服务端永远停留在 granted。
        preferences.serviceRevocationSubmitted = false
        consentRepository.savePassiveSensingConsent(true)
        preferences.consentSyncPending = true
        runCatching { featureFlagRepository.fetchFeatureFlags() }
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
        consentRepository: ConsentRepository
    ) {
        preferences.setPassiveSensingEnabled(false)
        PassiveSensingService.stop(context)
        SensingEventHub.getInstance().clearAll()
        consentRepository.savePassiveSensingConsent(false)
        preferences.sensingActive = false
        SyncWorker.enqueue(context)
    }
}
