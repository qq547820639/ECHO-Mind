package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.ProviderStatus
import com.yunjue.echo.mind.intelligence.TestConnectionResult
import com.yunjue.echo.mind.intelligence.overall
import com.yunjue.echo.mind.intelligence.testConnectionDetail
import com.yunjue.echo.mind.intelligence.testConnectionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 §39：Provider 测试连接结论映射回归（普通用户人话；不暴露 raw 错误）。
 */
class ProviderTestConnectionTest {

    @Test
    fun readyWhenAllStepsPass() {
        val result = TestConnectionResult(
            auth = ProviderStatus.READY,
            modelAvailable = true,
            structuredOutputSupported = true,
            basicRequest = ProviderStatus.READY,
        )
        assertEquals(ProviderStatus.READY, result.overall)
        assertTrue(testConnectionSummary(result).contains("成功"))
    }

    @Test
    fun authFailureDominates() {
        val result = TestConnectionResult(
            auth = ProviderStatus.AUTH_FAILED,
            modelAvailable = null,
            structuredOutputSupported = null,
            basicRequest = ProviderStatus.AUTH_FAILED,
        )
        assertEquals(ProviderStatus.AUTH_FAILED, result.overall)
        assertTrue(testConnectionSummary(result).contains("API Key"))
    }

    @Test
    fun missingModelReported() {
        val result = TestConnectionResult(
            auth = ProviderStatus.READY,
            modelAvailable = false,
            structuredOutputSupported = null,
            basicRequest = ProviderStatus.MODEL_NOT_FOUND,
        )
        assertEquals(ProviderStatus.MODEL_NOT_FOUND, result.overall)
        assertTrue(testConnectionSummary(result).contains("模型不存在"))
    }

    @Test
    fun unknownModelListIsNotFailure() {
        // 部分网关不暴露 /models：无法确认 ≠ 失败（诚实区分）
        val result = TestConnectionResult(
            auth = ProviderStatus.READY,
            modelAvailable = null,
            structuredOutputSupported = true,
            basicRequest = ProviderStatus.READY,
        )
        assertEquals(ProviderStatus.READY, result.overall)
        assertTrue(testConnectionDetail(result).contains("无法确认"))
    }

    @Test
    fun unstructuredOutputDegradesButStillConnected() {
        // 不支持结构化输出 → 仍 READY（上层用 validation/repair/fallback 链）
        val result = TestConnectionResult(
            auth = ProviderStatus.READY,
            modelAvailable = true,
            structuredOutputSupported = false,
            basicRequest = ProviderStatus.READY,
        )
        assertEquals(ProviderStatus.READY, result.overall)
        assertTrue(testConnectionDetail(result).contains("降级链"))
    }

    @Test
    fun notConfiguredIsHonest() {
        val result = TestConnectionResult(
            auth = ProviderStatus.NOT_CONFIGURED,
            modelAvailable = null,
            structuredOutputSupported = null,
            basicRequest = ProviderStatus.NOT_CONFIGURED,
        )
        assertEquals(ProviderStatus.NOT_CONFIGURED, result.overall)
        assertTrue(testConnectionSummary(result).contains("尚未测试"))
    }
}
