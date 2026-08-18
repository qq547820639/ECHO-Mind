package com.yunjue.echo.mind.intelligence

import java.net.URI

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
 * ERA 40 安全复核：明文例外严格限定私网——解析 URL host 后精确判定（不做 DNS 解析）：
 * - host ∈ {localhost, 127.0.0.1, ::1}（本机）；
 * - host 为字面 IPv4 且属 10/8、172.16/12、192.168/16（RFC 1918）或 169.254/16（link-local）。
 * 域名伪装（10.evil.com / localhost.attacker.com / 192.168.0.1.evil.com 等合法公网域名）
 * 一律非私网 → 必须 HTTPS；旧前缀匹配实现已被此类向量绕过。纯函数，单测锚定。
 */
internal fun isPrivateLanUrl(normalizedBaseUrl: String): Boolean {
    val host = runCatching { URI(normalizedBaseUrl).host }.getOrNull() ?: return false
    if (host.equals("localhost", ignoreCase = true)) return true
    if (host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
    val octets = host.split(".")
    if (octets.size != 4) return false
    if (octets.any { it.isEmpty() || it.length > 3 || it.any { ch -> !ch.isDigit() } }) return false
    val a = octets[0].toInt()
    val b = octets[1].toInt()
    if (octets[2].toInt() > 255 || octets[3].toInt() > 255 || a > 255 || b > 255) return false
    return a == 10 ||
        a == 172 && b in 16..31 ||
        a == 192 && b == 168 ||
        a == 169 && b == 254
}
