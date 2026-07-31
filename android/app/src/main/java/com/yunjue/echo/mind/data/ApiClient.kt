package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

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
    fun post(path: String, jsonBody: String): Int {
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
            connection.responseCode
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
}
