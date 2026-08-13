package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** HTTP 响应（code + 可选 Retry-After 秒数）。 */
data class HttpResponse(
    val code: Int,
    val retryAfterSeconds: Int? = null
)

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
 */
class ApiClient(
    private val tokenProvider: () -> String?,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) {
    fun post(path: String, jsonBody: String): HttpResponse {
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
            val retryAfter = connection.getHeaderField("Retry-After")
            HttpResponse(connection.responseCode, parseRetryAfterSeconds(retryAfter))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GET 请求，返回状态码 + 响应体（可能为 null）。
     */
    fun get(path: String): Pair<Int, String?> {
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

    /**
     * POST 请求并返回响应体（预认证端点用，如 POST /v1/onboarding/verify-code）。
     * 与 [post] 的区别：返回 body 供解析，tokenProvider 为 null 时不带 Authorization 头。
     */
    fun postWithBody(path: String, jsonBody: String): Pair<Int, String?> {
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
            Pair(code, body)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * v0.6.1（P2-12）：统一 POST，返回 (code, body, retryAfterSeconds)。
     * SyncWorker 等上行路径统一走此入口；错误 body 解析由调用方按 taxonomy 处理。
     */
    fun postFull(path: String, jsonBody: String): Triple<Int, String?, Int?> {
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

    // ===== Portrait（Milestone F/G/H：Today Portrait + Portrait Timeline） =====
    // Phase 1（Contract Closure）：改走 authenticated current-user 端点 /v1/me/*，
    // 由认证 Principal 确定 user（不再需要显式 user_id query param——
    // 旧 /v1/portraits/*?user_id= 路径要求必填 user_id，Android 不发送会 422）。
    // 阻塞式 HttpURLConnection，与既有 get/postWithBody 模式一致；
    // suspend 签名让调用方（PortraitRepository 等仓库）可统一在 IO 协程内调度。

    /** GET /v1/me/portraits/today：今日画像（含 date/status/summary/dimensions/facts 等）。 */
    suspend fun getTodayPortrait(): Pair<Int, String?> = get("/v1/me/portraits/today")

    /** GET /v1/me/portraits?days=7|28：批量画像（portraits 数组，元素同 today 结构）。 */
    suspend fun getPortraits(days: Int): Pair<Int, String?> = get("/v1/me/portraits?days=$days")

    /** GET /v1/me/baseline/status：基线状态（baseline_days/window/bucket_usage 等）。 */
    suspend fun getBaselineStatus(): Pair<Int, String?> = get("/v1/me/baseline/status")

    /** POST /v1/me/portraits/rebuild：服务端重算今日画像（当前用户，body 无需 user_id），响应同 today 结构。 */
    suspend fun rebuildPortrait(): Pair<Int, String?> = postWithBody("/v1/me/portraits/rebuild", "{}")
}
