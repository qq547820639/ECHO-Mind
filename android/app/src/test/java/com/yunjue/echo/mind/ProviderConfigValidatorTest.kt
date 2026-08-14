package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.ProviderConfigDraft
import com.yunjue.echo.mind.intelligence.ProviderType
import com.yunjue.echo.mind.intelligence.normalizeBaseUrl
import com.yunjue.echo.mind.intelligence.validateProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 4：Provider 配置校验回归（BYOM 的第一道门；secret 永不出现在错误文案）。
 */
class ProviderConfigValidatorTest {

    @Test
    fun normalizeBaseUrlCompletesSchemeAndVersion() {
        assertEquals("https://api.openai.com/v1", normalizeBaseUrl("api.openai.com"))
        assertEquals("https://api.openai.com/v1", normalizeBaseUrl("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1", normalizeBaseUrl("https://api.openai.com/"))
        // 已带 /v1 不重复追加
        assertEquals("https://x.com/v1", normalizeBaseUrl("https://x.com/v1"))
        // 本机端点保持 http
        assertEquals("http://localhost:11434/v1", normalizeBaseUrl("http://localhost:11434"))
        assertEquals("http://127.0.0.1:8080/v1", normalizeBaseUrl("http://127.0.0.1:8080/"))
    }

    private fun draft(
        apiKey: String = "sk-test",
        baseUrl: String = "https://api.openai.com",
        model: String = "gpt-4o-mini",
        timeout: Int = 60,
    ) = ProviderConfigDraft(
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        timeoutSeconds = timeout,
    )

    @Test
    fun validConfigPasses() {
        assertTrue(validateProviderConfig(draft()).isEmpty())
    }

    @Test
    fun missingFieldsAreReported() {
        val errors = validateProviderConfig(draft(apiKey = "", baseUrl = "", model = ""))
        assertTrue(errors.any { it.contains("API Key") })
        assertTrue(errors.any { it.contains("Base URL") })
        assertTrue(errors.any { it.contains("模型名") })
        // 错误文案不回显 secret 值
        assertTrue(errors.none { it.contains("sk-test") })
    }

    @Test
    fun insecurePublicUrlIsRejected() {
        val errors = validateProviderConfig(draft(baseUrl = "http://api.example.com"))
        assertTrue(errors.any { it.contains("https") })
    }

    @Test
    fun localEndpointsAllowHttp() {
        assertTrue(validateProviderConfig(draft(baseUrl = "http://localhost:11434")).isEmpty())
        assertTrue(validateProviderConfig(draft(baseUrl = "http://192.168.1.20:8080")).isEmpty())
        assertTrue(validateProviderConfig(draft(baseUrl = "http://10.0.0.5:11434")).isEmpty())
        // RFC 1918 172.16/12 内网段放行
        assertTrue(validateProviderConfig(draft(baseUrl = "http://172.16.0.2:11434")).isEmpty())
        assertTrue(validateProviderConfig(draft(baseUrl = "http://172.31.255.254:11434")).isEmpty())
    }

    @Test
    fun public172RangeIsRejectedAsInsecure() {
        // ERA 40：公网 172.x（如 172.217.x）不是私网——API Key 不得明文出网
        assertTrue(validateProviderConfig(draft(baseUrl = "http://172.217.16.1")).any { it.contains("https") })
        assertTrue(validateProviderConfig(draft(baseUrl = "http://172.15.0.1")).any { it.contains("https") })
        assertTrue(validateProviderConfig(draft(baseUrl = "http://172.32.0.1")).any { it.contains("https") })
    }

    @Test
    fun timeoutBoundsAreEnforced() {
        assertTrue(validateProviderConfig(draft(timeout = 4)).any { it.contains("超时") })
        assertTrue(validateProviderConfig(draft(timeout = 301)).any { it.contains("超时") })
    }
}
