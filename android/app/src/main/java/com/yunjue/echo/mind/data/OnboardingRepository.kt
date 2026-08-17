package com.yunjue.echo.mind.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Onboarding 激活码交换结果（T02）：对应后端 POST /v1/onboarding/verify-code 响应。
 * 不暴露 tenant_id / role / external_ref 等内部字段。
 */
data class OnboardingVerifyResult(
    val userId: String,
    val accessToken: String,
    /** ERA 32 R25：轮换式刷新令牌（access token 过期后无码续期凭证）。 */
    val refreshToken: String = "",
    val consentVersions: Map<String, String> = emptyMap(),
    val l0Decision: String? = null,
    val restricted: Boolean = false,
    // v0.7 订阅生命周期：ISO 时间串 / 档位（可空 = 机构旧用户/无订阅变更）
    val subscriptionExpiresAt: String? = null,
    val subscriptionPlan: String? = null
)

/**
 * 激活码交换失败（T02）。
 * reason: invalid_code（404）/ restricted（403）/ server_error / malformed。
 * UI 据此展示用户可读文案，不暴露内部 HTTP 码。
 */
class OnboardingVerifyException(val reason: String) : Exception(reason)

/**
 * 激活码验证成功后的 Onboarding 状态解析（v0.7 本地优先，纯函数可单测）：
 * - 用户已完成本地引导（本地已提交）→ 保持 READY_OFFLINE，订阅开通不得回退
 *   Onboarding 状态（BOUND 会让 onboardingCompleted=false，下次启动重新出现引导页）；
 * - 否则（正常引导内验证）→ BOUND。
 */
internal fun resolvedOnboardingStateAfterBinding(localSubmitted: Boolean): String =
    if (localSubmitted) AppPreferences.ONBOARDING_READY_OFFLINE else AppPreferences.ONBOARDING_BOUND

/**
 * Onboarding 激活码交换 + READY 收敛仓库。
 *
 * [verifyOnboardingCode] 是唯一跨 bounded-context 点：账户切换时需清理上一账户的
 * Portrait 缓存，故显式注入 [PortraitDao]（不持有整个 [EchoDatabase]，避免重引入 God-object）。
 */
class OnboardingRepository(
    private val portraitDao: PortraitDao,
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
    private val outbox: com.yunjue.echo.mind.data.outbox.Outbox,
) {
    /**
     * 服务端 ack 依据收敛：GET /v1/onboarding/consents/latest 核对本地已提交的
     * passive_sensing（核心同意）已在服务端生效后，置 serverActivated=true 并推进 READY。
     * 幂等：已 READY 直接返回；网络失败保持 READY_OFFLINE（下次同步重试）。
     */
    suspend fun confirmServerActivation(): Boolean {
        val state = preferences.onboardingState
        if (state == AppPreferences.ONBOARDING_READY) return true
        return withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = apiClient.get("/v1/onboarding/consents/latest?user_id=${preferences.userId}")
                if (code in 200..299 && !body.isNullOrBlank()) {
                    val o = JSONObject(body)
                    val localPassive = preferences.passiveSensingPrefs.passiveSensingEnabled.first()
                    val serverPassive = o.optJSONObject("passive_sensing")
                    val passiveOk = if (localPassive) {
                        // ERA 32 R26：revoked_at 是 ISO 时间串——optBoolean 恒 false 会
                        // 把已撤回的同意误判为「未撤回」（fail-open）；改按字符串判空。
                        serverPassive?.optBoolean("granted") == true &&
                            serverPassive.optString("revoked_at").isNullOrEmpty()
                    } else {
                        serverPassive == null || serverPassive.optBoolean("granted") == false
                    }
                    if (passiveOk) {
                        preferences.serverActivated = true
                        preferences.onboardingState = AppPreferences.ONBOARDING_READY
                        preferences.consentSyncPending = false
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }.getOrDefault(false)
        }
    }

    /**
     * 激活码交换：POST /v1/onboarding/verify-code（预认证，无 Authorization 头）。
     * 成功 → 安全存储 userId + 加密 accessToken，推进 onboardingState=BOUND；
     * 失败 → 抛 [OnboardingVerifyException]。
     */
    suspend fun verifyOnboardingCode(code: String): OnboardingVerifyResult {
        return withContext(Dispatchers.IO) {
            val requestBody = JSONObject().apply { put("code", code) }.toString()
            val (httpCode, responseBody, _) = apiClient.post("/v1/onboarding/verify-code", requestBody)
            when {
                httpCode == 404 -> throw OnboardingVerifyException("invalid_code")
                httpCode == 403 -> throw OnboardingVerifyException("restricted")
                httpCode !in 200..299 || responseBody.isNullOrBlank() -> throw OnboardingVerifyException("server_error")
                else -> {
                    val result = runCatching { parseVerifyCodeResponse(responseBody) }
                        .getOrElse { throw OnboardingVerifyException("malformed") }
                    if (result.userId.isBlank() || result.accessToken.isBlank() || result.refreshToken.isBlank()) {
                        throw OnboardingVerifyException("malformed")
                    }
                    // Phase 3.3（Portrait Cache User Isolation）：账户切换时清理上一账户的画像缓存。
                    val previousUserId = preferences.userId
                    preferences.userId = result.userId
                    preferences.accessToken = result.accessToken
                    // ERA 32 R25：刷新令牌与 access token 成对存储（401 静默续期用）
                    preferences.refreshToken = result.refreshToken
                    if (previousUserId.isNotBlank() && previousUserId != result.userId) {
                        runCatching { portraitDao.deleteByUser(previousUserId) }
                    }
                    preferences.clearAuthBlocked()
                    // ERA 32 R26：绑定成功 = 本地→云端切换——补发本地模式期间
                    // 落库但未入队的事件（人工支持请求/派生特征），不再「永远等待送达」。
                    runCatching { outbox.reEnqueueLocalBacklog() }
                    // v0.7 订阅生命周期：verify-code 响应携带订阅状态 → 持久化（epoch ms）
                    preferences.subscriptionExpiresAt = result.subscriptionExpiresAt
                        ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                    preferences.subscriptionPlan = result.subscriptionPlan
                    preferences.onboardingState =
                        resolvedOnboardingStateAfterBinding(preferences.onboardingLocalSubmitted)
                    result
                }
            }
        }
    }

    private fun parseVerifyCodeResponse(body: String): OnboardingVerifyResult {
        val o = JSONObject(body)
        val consentVersions = o.optJSONObject("consent_versions")?.let { co ->
            co.keys().asSequence().associateWith { co.optString(it) }
        } ?: emptyMap()
        return OnboardingVerifyResult(
            userId = o.optString("user_id"),
            accessToken = o.optString("access_token"),
            refreshToken = o.optString("refresh_token"),
            consentVersions = consentVersions,
            l0Decision = o.optString("l0_decision").takeIf { it.isNotBlank() && it != "null" },
            restricted = o.optBoolean("restricted", false),
            subscriptionExpiresAt = o.optString("subscription_expires_at")
                .takeIf { it.isNotBlank() && it != "null" },
            subscriptionPlan = o.optString("subscription_plan")
                .takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
