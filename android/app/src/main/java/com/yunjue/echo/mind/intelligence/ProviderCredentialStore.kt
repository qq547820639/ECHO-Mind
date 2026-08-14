package com.yunjue.echo.mind.intelligence

import android.content.Context
import android.content.SharedPreferences
import com.yunjue.echo.mind.security.FieldCipher

/**
 * ERA 4 — Provider Credential 加密存储（AI_PROVIDER_SPEC §6 硬性要求）。
 *
 * - API Key 用 Android Keystore 派生的 [FieldCipher] 加密后落盘（复用生产 cipher，不自研 crypto）；
 * - 与 EchoMemory / Portrait 数据 / 会话逻辑隔离：独立 SharedPreferences 文件；
 * - 不写日志、不上传 telemetry、不进 crash report、不进数据库 export、不进 Android backup
 *   （应用级 allowBackup=false 已保证）；
 * - 提供删除；切换 Provider 后清理无用 secret。
 */
class ProviderCredentialStore(context: Context, private val cipher: FieldCipher) {

    /** 内存态存储对象（apiKey 明文只存在于内存，仅用于发起请求）。 */
    data class Stored(
        val type: ProviderType,
        val displayName: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun save(stored: Stored) {
        prefs.edit()
            .putString(KEY_TYPE, stored.type.name)
            .putString(KEY_DISPLAY, stored.displayName)
            .putString(KEY_BASE_URL, stored.baseUrl)
            .putString(KEY_MODEL, stored.model)
            .putString(KEY_API_KEY_CIPHER, cipher.encrypt(stored.apiKey))
            .apply()
    }

    /** 读取（解密失败 fail-closed 返回 null——绝不带病态密钥请求）。 */
    fun load(): Stored? {
        val typeName = prefs.getString(KEY_TYPE, null) ?: return null
        val type = runCatching { ProviderType.valueOf(typeName) }.getOrNull() ?: return null
        val ciphertext = prefs.getString(KEY_API_KEY_CIPHER, null) ?: return null
        val apiKey = runCatching { cipher.decrypt(ciphertext) }.getOrNull() ?: return null
        return Stored(
            type = type,
            displayName = prefs.getString(KEY_DISPLAY, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            model = prefs.getString(KEY_MODEL, "") ?: "",
            apiKey = apiKey,
        )
    }

    fun exists(): Boolean = prefs.contains(KEY_API_KEY_CIPHER)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE = "echo_provider_credentials"
        private const val KEY_TYPE = "provider_type"
        private const val KEY_DISPLAY = "provider_display"
        private const val KEY_BASE_URL = "provider_base_url"
        private const val KEY_MODEL = "provider_model"
        private const val KEY_API_KEY_CIPHER = "provider_api_key_cipher"
    }
}
