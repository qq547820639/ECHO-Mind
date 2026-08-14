package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.OpenAiCompatibleProvider
import com.yunjue.echo.mind.intelligence.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERA 4：OpenAI-compatible HTTP 状态映射回归（纯函数；raw 错误不泄漏给用户）。
 */
class OpenAiCompatibleProviderTest {

    @Test
    fun authFailuresMapToAuthFailed() {
        assertEquals(ProviderStatus.AUTH_FAILED, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(401))
        assertEquals(ProviderStatus.AUTH_FAILED, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(403))
    }

    @Test
    fun notFoundMapsToModelNotFound() {
        assertEquals(ProviderStatus.MODEL_NOT_FOUND, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(404))
    }

    @Test
    fun quotaAndRateLimitAreDistinguished() {
        assertEquals(ProviderStatus.RATE_LIMITED, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(429))
        assertEquals(
            ProviderStatus.QUOTA_EXCEEDED,
            OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(429, "insufficient_quota")
        )
        assertEquals(ProviderStatus.QUOTA_EXCEEDED, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(402))
    }

    @Test
    fun serverErrorsMapToProviderError() {
        assertEquals(ProviderStatus.PROVIDER_ERROR, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(500))
        assertEquals(ProviderStatus.PROVIDER_ERROR, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(503))
        assertEquals(ProviderStatus.PROVIDER_ERROR, OpenAiCompatibleProvider.mapHttpStatusToProviderStatus(418))
    }
}
