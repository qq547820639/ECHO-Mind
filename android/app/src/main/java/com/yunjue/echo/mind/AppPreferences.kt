package com.yunjue.echo.mind

import android.content.Context
import com.yunjue.echo.mind.security.FieldCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class AppPreferences(
    context: Context,
    private val cipher: FieldCipher,
    val passiveSensingPrefs: PassiveSensingPrefs = PassiveSensingPrefs(context)
) {
    private val prefs = context.getSharedPreferences("echo_mind_app_state", Context.MODE_PRIVATE)

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    var institutionCode: String
        get() = prefs.getString("institution_code", "") ?: ""
        set(value) = prefs.edit().putString("institution_code", value).apply()

    var userId: String
        get() = prefs.getString("user_id", "u_demo") ?: "u_demo"
        set(value) = prefs.edit().putString("user_id", value).apply()

    var accessToken: String?
        get() = prefs.getString("access_token_ciphertext", null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove("access_token_ciphertext")
            else putString("access_token_ciphertext", cipher.encrypt(value))
        }.apply()

    // ===== Skill 卡片下发缓存（T11.4） =====
    // 用 SharedPreferences 缓存 GET /v1/skills 的原始 JSON + 时间戳，避免 Room 迁移。
    // Skill 为只读下发数据，不加密存储；过期由 [LocalRepository.fetchSkills] 判定刷新。

    fun getSkillCacheJson(): String? = prefs.getString("skill_cache_json", null)
    fun getSkillCacheTimestamp(): Long = prefs.getLong("skill_cache_ts", 0L)
    fun setSkillCache(json: String) = prefs.edit()
        .putString("skill_cache_json", json)
        .putLong("skill_cache_ts", System.currentTimeMillis())
        .apply()

    // ===== P5 灰度回滚：feature flags 缓存（SharedPreferences） =====
    // 移动端拉取 GET /v1/config/flags 后缓存，端侧灰度联动：
    // - passive_sensing_enabled=false → PassiveSensingService 不启动
    // - skills_delivery_enabled=false → 隐藏 Skill 卡片区
    // 缓存为 JSON 字符串（与后端返回格式一致），读取时解析为 Map。
    // 无缓存时返回默认全 true（保守启用，避免网络问题导致功能不可用）。
    // 使用 SharedPreferences 而非 DataStore，因 PassiveSensingService 需同步读取。

    private val _featureFlagsFlow = MutableStateFlow(getFeatureFlagsSnapshot())
    val featureFlagsFlow: Flow<Map<String, Boolean>> = _featureFlagsFlow

    /** 同步写入 feature flags 缓存（commit，确保落盘后才返回）。 */
    fun setFeatureFlags(flags: Map<String, Boolean>) {
        val json = JSONObject().apply {
            flags.forEach { (k, v) -> put(k, v) }
        }.toString()
        prefs.edit().putString(KEY_FEATURE_FLAGS, json).commit()
        _featureFlagsFlow.value = parseFlagsJson(json)
    }

    /** 同步读取 feature flags 缓存；无缓存返回默认全 true。 */
    fun getFeatureFlagsSnapshot(): Map<String, Boolean> {
        val json = prefs.getString(KEY_FEATURE_FLAGS, null) ?: return defaultFlags()
        return parseFlagsJson(json)
    }

    private fun parseFlagsJson(json: String): Map<String, Boolean> = runCatching {
        val o = JSONObject(json)
        mapOf(
            "passive_sensing_enabled" to o.optBoolean("passive_sensing_enabled", true),
            "sandbox_enabled" to o.optBoolean("sandbox_enabled", true),
            "skills_delivery_enabled" to o.optBoolean("skills_delivery_enabled", true),
        )
    }.getOrDefault(defaultFlags())

    private fun defaultFlags() = mapOf(
        "passive_sensing_enabled" to true,
        "sandbox_enabled" to true,
        "skills_delivery_enabled" to true,
    )

    // ===== 被动采集相关（DataStore 存储，委托给 PassiveSensingPrefs） =====

    fun passiveSensingEnabledFlow(): Flow<Boolean> = passiveSensingPrefs.passiveSensingEnabled
    fun micEnabledFlow(): Flow<Boolean> = passiveSensingPrefs.micEnabled
    fun samplingConfigFlow(): Flow<String> = passiveSensingPrefs.samplingConfig

    suspend fun setPassiveSensingEnabled(enabled: Boolean) =
        passiveSensingPrefs.setPassiveSensingEnabled(enabled)

    suspend fun setMicEnabled(enabled: Boolean) =
        passiveSensingPrefs.setMicEnabled(enabled)

    suspend fun setSamplingConfig(config: String) =
        passiveSensingPrefs.setSamplingConfig(config)

    fun clearServiceState() {
        prefs.edit().clear().apply()
        _featureFlagsFlow.value = defaultFlags()
    }

    companion object {
        private const val KEY_FEATURE_FLAGS = "feature_flags_json"
    }
}
