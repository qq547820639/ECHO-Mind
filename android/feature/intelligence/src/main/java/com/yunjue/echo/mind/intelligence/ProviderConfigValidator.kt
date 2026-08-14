package com.yunjue.echo.mind.intelligence

/**
 * ERA 4 — Provider 配置校验（纯 Kotlin；社区版 BYOM 的第一道门）。
 *
 * API Key 永远不在校验错误文案里回显；Base URL 规范化供所有 OpenAI-compatible
 * 服务（官方 / 第三方兼容 / 自建网关 / 局域网端点）共用。
 */
data class ProviderConfigDraft(
    val providerType: ProviderType,
    val displayName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val extraHeaders: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 60,
)

/** 规范化 Base URL：去尾部斜杠、补 /v1、强制 https（localhost 例外）。 */
fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return url
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "https://$url"
    }
    // 已是 .../v1 或 .../v1/xxx 时不重复追加；否则统一追加 /v1
    val withoutV1 = if (url.endsWith("/v1")) url else url
    return if (url.endsWith("/v1")) url else "$withoutV1/v1"
}

/**
 * 配置校验（返回错误列表；空 = 通过）。
 * 不把 secret 写入任何日志/错误文案——错误只描述字段，不描述值。
 */
fun validateProviderConfig(config: ProviderConfigDraft): List<String> {
    val errors = mutableListOf<String>()
    if (config.apiKey.isBlank()) errors.add("请填写 API Key")
    if (config.baseUrl.isBlank()) errors.add("请填写 Base URL")
    else {
        val normalized = normalizeBaseUrl(config.baseUrl)
        if (!normalized.startsWith("https://") && !isPrivateLanUrl(normalized)) {
            // 非本机地址必须 HTTPS（设备 → Provider 直连链路的传输安全；
            // API Key 只在私网明文例外——局域网自建网关场景）
            errors.add("Base URL 必须使用 https（本机地址除外）")
        }
    }
    if (config.model.isBlank()) errors.add("请填写模型名")
    if (config.timeoutSeconds !in 5..300) errors.add("超时时间需在 5-300 秒之间")
    return errors
}

/**
 * ERA 40 安全复核：明文例外严格限定私网——
 * localhost/127.0.0.1、10/8、192.168/16、**172.16/12（RFC 1918）**。
 * 旧实现 `startsWith("http://172.")` 误放行公网 172.x（如 172.217.x）→ API Key 明文出网；
 * 现按 RFC 1918 精确判定。纯函数，单测锚定。
 */
internal fun isPrivateLanUrl(normalizedBaseUrl: String): Boolean {
    if (normalizedBaseUrl.startsWith("http://localhost")) return true
    if (normalizedBaseUrl.startsWith("http://127.0.0.1")) return true
    if (normalizedBaseUrl.startsWith("http://10.")) return true
    if (normalizedBaseUrl.startsWith("http://192.168.")) return true
    val rfc1918Prefix = Regex("^http://172\\.(1[6-9]|2[0-9]|3[0-1])\\.")
    return rfc1918Prefix.containsMatchIn(normalizedBaseUrl)
}
