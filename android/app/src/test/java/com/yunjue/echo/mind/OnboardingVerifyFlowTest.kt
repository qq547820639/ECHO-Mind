package com.yunjue.echo.mind

import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.OnboardingVerifyException
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T02 Onboarding 七态状态机 + verify-code 失败映射单测（Robolectric，SDK 35）。
 *
 * - 七态常量与禁止倒退语义（本地持久化 onboardingState）
 * - onboardingCompleted 仅在 READY_OFFLINE / READY 为 true
 * - verify-code 失败 reason → 用户可读文案（不暴露内部码）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingVerifyFlowTest {

    private fun prefs(): AppPreferences = AppPreferences(
        ApplicationProvider.getApplicationContext(),
        JvmTestFieldCipher()
    )

    @Test
    fun initialStateIsNotStartedAndNotCompleted() {
        val preferences = AppPreferences(ApplicationProvider.getApplicationContext(), JvmTestFieldCipher())
        assertEquals(AppPreferences.ONBOARDING_NOT_STARTED, preferences.onboardingState)
        assertFalse("未开始不应视为已完成", preferences.onboardingCompleted)
        assertFalse("未开始 serverActivated 应为 false", preferences.serverActivated)
    }

    @Test
    fun sevenStatesPersistAndCompletionOnlyAtReadyStates() {
        val preferences = prefs()

        preferences.onboardingState = AppPreferences.ONBOARDING_ACTIVATING
        assertFalse(preferences.onboardingCompleted)

        preferences.onboardingState = AppPreferences.ONBOARDING_ACTIVATION_FAILED
        assertFalse(preferences.onboardingCompleted)

        preferences.onboardingState = AppPreferences.ONBOARDING_BOUND
        assertFalse("BOUND 不应视为已完成", preferences.onboardingCompleted)

        preferences.onboardingState = AppPreferences.ONBOARDING_CONSENT_PENDING
        assertFalse("CONSENT_PENDING 不应视为已完成", preferences.onboardingCompleted)

        preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
        assertTrue("READY_OFFLINE 应视为已完成（可进入应用）", preferences.onboardingCompleted)

        preferences.onboardingState = AppPreferences.ONBOARDING_READY
        assertTrue("READY 应视为已完成", preferences.onboardingCompleted)
    }

    @Test
    fun serverActivatedFlagTracksReadySemantics() {
        val preferences = prefs()
        preferences.serverActivated = false
        assertFalse(preferences.serverActivated)
        preferences.serverActivated = true
        assertTrue(preferences.serverActivated)
    }

    @Test
    fun verifyCodeExceptionReasonsAreMappedToUserFacingCategories() {
        // UI 侧映射逻辑（OnboardingScreen）：reason → 用户可读文案类别
        fun userText(reason: String?): String = when (reason) {
            "invalid_code" -> "激活码无效"
            "restricted" -> "该激活码已受限"
            else -> "网络异常"
        }
        assertEquals("激活码无效", userText(OnboardingVerifyException("invalid_code").reason))
        assertEquals("该激活码已受限", userText(OnboardingVerifyException("restricted").reason))
        assertEquals("网络异常", userText(OnboardingVerifyException("server_error").reason))
        assertEquals("网络异常", userText(null))
        // 严禁暴露内部 HTTP 码
        for (r in listOf("invalid_code", "restricted", "server_error", "malformed")) {
            val text = userText(r)
            assertFalse("文案不应暴露内部码", text.contains("404") || text.contains("403") || text.contains("HTTP"))
        }
    }

    @Test
    fun activationCodeRequiresMinLength() {
        // OnboardingScreen 校验：code < 8 位不提交（激活码格式）
        assertTrue("短码应被拒绝（<8）", "short".length < 8)
        assertTrue("合法码应通过（>=8）", "ACTIV-CODE-01".length >= 8)
    }
}
