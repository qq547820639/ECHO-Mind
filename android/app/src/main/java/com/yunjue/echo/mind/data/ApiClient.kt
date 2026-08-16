package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 解析 Retry-After 头（RFC 7231：可为秒数或 HTTP-date）。
 * 端侧仅解析秒数形式；缺失 / 非数字 / 负数返回 null。
 */
internal fun parseRetryAfterSeconds(header: String?): Int? =
    header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

/**
 * HTTP 客户端：基于 HttpURLConnection，支持 POST / GET。
 *
 * @param tokenProvider 返回 access_token（可为 null）
 * @param connectTimeoutMs 连接超时，默认 10s
 * @param readTimeoutMs 读取超时，默认 15s
 * @param onUnauthorized ERA 32 R25：收到 401 时尝试静默续期；返回 true 则用新 token 重试一次。
 */
class ApiClient(
    private val tokenProvider: () -> String?,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val onUnauthorized: (() -> Boolean)? = null
) {
    /**
     * 统一 POST：返回 (code, body, retryAfterSeconds)。
     *
     * 所有上行路径统一走此入口；错误 body 解析由调用方按 taxonomy 处理。
     * 预认证端点（tokenProvider 为 null）不带 Authorization 头；超时、header、
     * X-Request-ID、Authorization 行为与原 post/postWithBody/postFull 完全一致。
     * ERA 32 R25：401 → onUnauthorized 续期成功 → 原请求重试一次（幂等事件语义安全）。
     */
    fun post(path: String, jsonBody: String): Triple<Int, String?, Int?> {
        var result = postOnce(path, jsonBody)
        if (result.first == 401 && onUnauthorized?.invoke() == true) {
            result = postOnce(path, jsonBody)
        }
        return result
    }

    private fun postOnce(path: String, jsonBody: String): Triple<Int, String?, Int?> {
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Request-ID", "mobile_${UUID.randomUUID()}")
            tokenProvider()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.doOutput = true
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
            Triple(code, body, parseRetryAfterSeconds(connection.getHeaderField("Retry-After")))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GET 请求，返回状态码 + 响应体（可能为 null）。
     * ERA 32 R25：401 → onUnauthorized 续期成功 → 原请求重试一次（GET 幂等）。
     */
    fun get(path: String): Pair<Int, String?> {
        var result = getOnce(path)
        if (result.first == 401 && onUnauthorized?.invoke() == true) {
            result = getOnce(path)
        }
        return result
    }

    private fun getOnce(path: String): Pair<Int, String?> {
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("X-Request-ID", "mobile_${UUID.randomUUID()}")
            tokenProvider()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
            Pair(code, body)
        } finally {
            connection.disconnect()
        }
    }

    // ===== Portrait（Milestone F/G/H：Today Portrait + Portrait Timeline） =====
    // Phase 1（Contract Closure）：改走 authenticated current-user 端点 /v1/me/*，
    // 由认证 Principal 确定 user（不再需要显式 user_id query param——
    // 旧 /v1/portraits/*?user_id= 路径要求必填 user_id，Android 不发送会 422）。
    // 阻塞式 HttpURLConnection，与既有 get/post 模式一致；
    // suspend 签名让调用方（PortraitRepository 等仓库）可统一在 IO 协程内调度。

    /** GET /v1/me/portraits/today：今日画像（含 date/status/summary/dimensions/facts 等）。 */
    suspend fun getTodayPortrait(): Pair<Int, String?> = get("/v1/me/portraits/today")

    /** GET /v1/me/portraits?days=7|28：批量画像（portraits 数组，元素同 today 结构）。 */
    suspend fun getPortraits(days: Int): Pair<Int, String?> = get("/v1/me/portraits?days=$days")

    /** GET /v1/me/baseline/status：基线状态（baseline_days/window/bucket_usage 等）。 */
    suspend fun getBaselineStatus(): Pair<Int, String?> = get("/v1/me/baseline/status")

    /** GET /v1/me/messages：分析消息（周小结；message 可为 null = 数据不足 abstain）。 */
    suspend fun getMessages(): Pair<Int, String?> = get("/v1/me/messages")

    /** GET /v1/me/subscription：订阅状态（subscribed/plan/expires_at/days_left）。 */
    suspend fun getSubscription(): Pair<Int, String?> = get("/v1/me/subscription")

    /** POST /v1/me/portraits/rebuild：服务端重算今日画像（当前用户，body 无需 user_id），响应同 today 结构。 */
    suspend fun rebuildPortrait(): Pair<Int, String?> =
        post("/v1/me/portraits/rebuild", "{}").let { (code, body, _) -> code to body }
}
