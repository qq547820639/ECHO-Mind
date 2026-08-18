package com.yunjue.echo.mind.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 40 —— isPrivateLanUrl 精确私网判定（防域名伪装绕过 HTTPS 强制）：
 * 只有本机名/字面私网 IP 可走明文 http；10.evil.com 等伪装域名必须 HTTPS。
 */
class ProviderConfigValidatorTest {

    @Test
    fun loopbackAndPrivateLanLiteralsArePrivate() {
        assertTrue(isPrivateLanUrl("http://localhost/v1"))
        assertTrue(isPrivateLanUrl("http://127.0.0.1/v1"))
        assertTrue(isPrivateLanUrl("http://[::1]/v1"))
        assertTrue(isPrivateLanUrl("http://10.3.2.1/v1"))
        assertTrue(isPrivateLanUrl("http://192.168.0.1/v1"))
        assertTrue(isPrivateLanUrl("http://172.16.0.1/v1"))
        assertTrue(isPrivateLanUrl("http://172.31.255.254/v1"))
        assertTrue(isPrivateLanUrl("http://169.254.1.1/v1"))
    }

    @Test
    fun domainSpoofingIsNotPrivateLan() {
        assertFalse(isPrivateLanUrl("http://10.evil.com/v1"))
        assertFalse(isPrivateLanUrl("http://localhost.attacker.com/v1"))
        assertFalse(isPrivateLanUrl("http://127.0.0.1.evil.com/v1"))
        assertFalse(isPrivateLanUrl("http://192.168.0.1.evil.com/v1"))
        assertFalse(isPrivateLanUrl("http://172.16.0.1.evil.com/v1"))
        assertFalse(isPrivateLanUrl("http://172.217.170.174/v1")) // 公网 172.x ≠ RFC 1918
        assertFalse(isPrivateLanUrl("http://8.8.8.8/v1"))
        assertFalse(isPrivateLanUrl("http://example.com/v1"))
        assertFalse(isPrivateLanUrl("not a url"))
    }

    @Test
    fun plainHttpAllowedOnlyForTrulyPrivateLan() {
        val spoofed = validateProviderConfig(draft("http://10.evil.com"))
        assertTrue(spoofed.any { it.contains("https") })
        val localhost = validateProviderConfig(draft("http://localhost"))
        assertTrue(localhost.none { it.contains("https") })
        val lan = validateProviderConfig(draft("http://10.3.2.1"))
        assertTrue(lan.none { it.contains("https") })
    }

    // ---------------------------------------------------------------- normalizeBaseUrl

    /** T5-P2-6：…/v1/xxx 形态不再被追加成 /v1/xxx/v1（KDoc 与实现对齐）。 */
    @Test
    fun normalizeBaseUrlDoesNotDuplicateV1Path() {
        assertEquals("https://gw.example.com/v1", normalizeBaseUrl("https://gw.example.com/v1"))
        assertEquals("https://gw.example.com/v1", normalizeBaseUrl("https://gw.example.com/v1/"))
        assertEquals("https://gw.example.com/v1/chat", normalizeBaseUrl("https://gw.example.com/v1/chat"))
        assertEquals("https://gw.example.com/v1/chat", normalizeBaseUrl("https://gw.example.com/v1/chat/"))
    }

    @Test
    fun normalizeBaseUrlAppendsV1OnlyWhenAbsent() {
        assertEquals("https://api.example.com/v1", normalizeBaseUrl("api.example.com"))
        assertEquals("https://api.example.com/v1", normalizeBaseUrl("https://api.example.com"))
        assertEquals("https://api.example.com/api/v1", normalizeBaseUrl("https://api.example.com/api"))
        assertEquals("http://localhost:8080/v1", normalizeBaseUrl("http://localhost:8080"))
        // /v10 不是 /v1 路径段 → 仍补 /v1
        assertEquals("https://api.example.com/v10/v1", normalizeBaseUrl("https://api.example.com/v10"))
    }

    @Test
    fun normalizeBaseUrlRoundTripIsIdempotent() {
        val once = normalizeBaseUrl("https://gw.example.com/v1/chat")
        assertEquals("二次规范化幂等（不再追加）", once, normalizeBaseUrl(once))
    }

    private fun draft(baseUrl: String) = ProviderConfigDraft(
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKey = "sk-test-not-a-real-key",
        model = "test-model",
    )
}
