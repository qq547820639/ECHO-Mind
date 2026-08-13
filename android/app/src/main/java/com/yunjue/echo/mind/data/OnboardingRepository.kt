package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
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
    val consentVersions: Map<String, String> = emptyMap(),
    val l0Decision: String? = null,
    val restricted: Boolean = false
)

/**
 * 激活码交换失败（T02）。
 * reason: invalid_code（404）/ restricted（403）/ server_error / malformed。
 * UI 据此展示用户可读文案，不暴露内部 HTTP 码。
 */
class OnboardingVerifyException(val reason: String) : Exception(reason)

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
                        serverPassive?.optBoolean("granted") == true && (serverPassive.isNull("revoked_at") || !serverPassive.optBoolean("revoked_at"))
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
            val (httpCode, responseBody) = apiClient.postWithBody("/v1/onboarding/verify-code", requestBody)
            when {
                httpCode == 404 -> throw OnboardingVerifyException("invalid_code")
                httpCode == 403 -> throw OnboardingVerifyException("restricted")
                httpCode !in 200..299 || responseBody.isNullOrBlank() -> throw OnboardingVerifyException("server_error")
                else -> {
                    val result = runCatching { parseVerifyCodeResponse(responseBody) }
                        .getOrElse { throw OnboardingVerifyException("malformed") }
                    if (result.userId.isBlank() || result.accessToken.isBlank()) {
                        throw OnboardingVerifyException("malformed")
                    }
                    // Phase 3.3（Portrait Cache User Isolation）：账户切换时清理上一账户的画像缓存。
                    val previousUserId = preferences.userId
                    preferences.userId = result.userId
                    preferences.accessToken = result.accessToken
                    if (previousUserId.isNotBlank() && previousUserId != result.userId) {
                        runCatching { portraitDao.deleteByUser(previousUserId) }
                    }
                    preferences.clearAuthBlocked()
                    preferences.onboardingState = AppPreferences.ONBOARDING_BOUND
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
            consentVersions = consentVersions,
            l0Decision = o.optString("l0_decision").takeIf { it.isNotBlank() && it != "null" },
            restricted = o.optBoolean("restricted", false)
        )
    }
}
