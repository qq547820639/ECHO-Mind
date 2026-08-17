package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.data.AppPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * ERA 32 R25：无凭证续期（POST /v1/auth/refresh，预认证端点）。
 *
 * access token（60 分钟 JWT）过期后，端侧在收到 401 时用轮换式 refresh token
 * 静默续期：成功后 access + refresh **成对替换**并解除认证暂停（authRequired）；
 * 旧 refresh token 立即作废（服务端轮换）。任一失败不改变任何本地状态。
 */
class AuthTokenRefresher(
    private val preferences: AppPreferences,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) {
    /** 尝试续期；成功返回 true（tokens 已替换）。失败返回 false，不改变状态。 */
    fun tryRefresh(): Boolean {
        val refresh = preferences.refreshToken ?: return false
        val userId = preferences.userId
        if (userId.isBlank()) return false
        val body = JSONObject().apply {
            put("user_id", userId)
            put("refresh_token", refresh)
        }.toString()
        return runCatching {
            val connection = URL(com.yunjue.echo.mind.BuildConfig.API_BASE_URL + "/v1/auth/refresh")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Request-ID", "mobile_${UUID.randomUUID()}")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                if (code !in 200..299 || response.isNullOrBlank()) return@runCatching false
                val parsed = parseRefreshResponse(response) ?: return@runCatching false
                preferences.accessToken = parsed.first
                preferences.refreshToken = parsed.second
                preferences.clearAuthBlocked()
                true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        /** 解析 /v1/auth/refresh 响应 → (accessToken, refreshToken)；任一字段缺失/空白 → null。 */
        internal fun parseRefreshResponse(body: String): Pair<String, String>? {
            val o = JSONObject(body)
            val access = o.optString("access_token")
            val refresh = o.optString("refresh_token")
            return if (access.isBlank() || refresh.isBlank()) null else access to refresh
        }
    }
}
