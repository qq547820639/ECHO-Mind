package com.yunjue.echo.mind.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 租户 feature flags（灰度回滚）仓库（P5）。
 *
 * - [featureFlagsFlow]：缓存 Flow 透传（UI 观察联动 Skill 卡片显示/隐藏）。
 * - [fetchFeatureFlags]：拉取 GET /v1/config/flags 并写缓存；网络/解析失败时返回缓存，
 *   无缓存返回 **fail-closed 默认**（passive_sensing_enabled / sandbox_enabled = false，
 *   skills_delivery_enabled = true）。
 */
class FeatureFlagRepository(
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
) {
    val featureFlagsFlow: Flow<Map<String, Boolean>> = preferences.featureFlagsFlow

    suspend fun fetchFeatureFlags(): Map<String, Boolean> {
        return withContext(Dispatchers.IO) {
            val (code, body) = try {
                apiClient.get("/v1/config/flags")
            } catch (e: Exception) {
                return@withContext preferences.getFeatureFlagsSnapshot()
            }
            if (code in 200..299 && !body.isNullOrBlank()) {
                runCatching {
                    val o = JSONObject(body)
                    val flags = mapOf(
                        "passive_sensing_enabled" to o.optBoolean("passive_sensing_enabled", false),
                        "sandbox_enabled" to o.optBoolean("sandbox_enabled", false),
                        "skills_delivery_enabled" to o.optBoolean("skills_delivery_enabled", true),
                    )
                    preferences.setFeatureFlags(flags)
                    flags
                }.getOrDefault(preferences.getFeatureFlagsSnapshot())
            } else {
                preferences.getFeatureFlagsSnapshot()
            }
        }
    }
}
