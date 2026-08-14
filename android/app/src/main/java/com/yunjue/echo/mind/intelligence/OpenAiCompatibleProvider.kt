package com.yunjue.echo.mind.intelligence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * ERA 4 — OpenAI-compatible Provider（社区生态关键）。
 *
 * 与既有 ApiClient 同栈（HttpURLConnection + 阻塞 IO 移入 Dispatchers.IO）。
 * 支持官方服务 / 第三方兼容 / 自建网关 / 局域网端点。
 * 所有网络/状态映射经 [mapHttpStatusToProviderStatus] 纯函数（JVM 可测）。
 * API Key 只进入 Authorization 头，不写日志、不进错误文案。
 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val timeoutSeconds: Int = 60,
) : EchoReasoningProvider {

    private val normalizedBase: String = normalizeBaseUrl(baseUrl)

    override suspend fun healthCheck(): ProviderHealth = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection("$normalizedBase/models", "GET")
            val code = conn.responseCode
            val errorBody = if (code >= 400) readErrorBody(conn) else ""
            conn.disconnect()
            if (code == 200) {
                ProviderHealth(ProviderStatus.READY)
            } else {
                ProviderHealth(mapHttpStatusToProviderStatus(code, errorBody), "HTTP $code")
            }
        } catch (e: IOException) {
            ProviderHealth(ProviderStatus.NETWORK_ERROR, e.javaClass.simpleName)
        } catch (e: Exception) {
            ProviderHealth(ProviderStatus.PROVIDER_ERROR, e.javaClass.simpleName)
        }
    }

    override suspend fun listModels(): List<ModelDescriptor> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection("$normalizedBase/models", "GET")
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { id ->
                    ModelDescriptor(id = id, displayName = id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun reason(request: EchoReasoningRequest): EchoReasoningResponse = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection("$normalizedBase/chat/completions", "POST")
            val payload = JSONObject().apply {
                put("model", model)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", request.systemInstruction) })
                        put(JSONObject().apply { put("role", "user"); put("content", request.userContent) })
                    }
                )
                put("max_tokens", request.maxTokens)
                put("temperature", 0.3)
                // 结构化输出偏好（Provider 不支持 native 时由上层 validation/repair/retry/fallback 处理）
                if (request.outputSchemaHint != null) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val errorBody = readErrorBody(conn)
                conn.disconnect()
                return@withContext EchoReasoningResponse(
                    text = "",
                    model = model,
                    status = mapHttpStatusToProviderStatus(code, errorBody),
                    detail = "HTTP $code",
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val content = runCatching {
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
            }.getOrElse { "" }
            EchoReasoningResponse(
                text = content,
                structuredJson = content.takeIf { request.outputSchemaHint != null },
                model = model,
                status = ProviderStatus.READY,
            )
        } catch (e: IOException) {
            EchoReasoningResponse(text = "", model = model, status = ProviderStatus.NETWORK_ERROR, detail = e.javaClass.simpleName)
        } catch (e: Exception) {
            EchoReasoningResponse(text = "", model = model, status = ProviderStatus.PROVIDER_ERROR, detail = e.javaClass.simpleName)
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutSeconds * 1000
        conn.readTimeout = timeoutSeconds * 1000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readErrorBody(conn: HttpURLConnection): String = runCatching {
        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrDefault("")

    companion object {
        /**
         * HTTP code + 错误体 → ProviderStatus（纯函数，单测锚定）。
         * 语义（AI_PROVIDER_SPEC §5）：不把 raw 信息给普通用户。
         */
        fun mapHttpStatusToProviderStatus(code: Int, errorBody: String = ""): ProviderStatus = when {
            code == 401 || code == 403 -> ProviderStatus.AUTH_FAILED
            code == 404 -> ProviderStatus.MODEL_NOT_FOUND
            code == 402 || errorBody.contains("quota", ignoreCase = true) -> ProviderStatus.QUOTA_EXCEEDED
            code == 429 -> ProviderStatus.RATE_LIMITED
            code in 500..599 -> ProviderStatus.PROVIDER_ERROR
            else -> ProviderStatus.PROVIDER_ERROR
        }
    }
}
