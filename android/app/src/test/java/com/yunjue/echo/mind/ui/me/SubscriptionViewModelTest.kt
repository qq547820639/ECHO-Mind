package com.yunjue.echo.mind.ui.me

import com.yunjue.echo.mind.data.OnboardingVerifyException
import com.yunjue.echo.mind.data.OnboardingVerifyResult
import com.yunjue.echo.mind.me.SubscriptionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ERA 33 — SubscriptionViewModel 业务矩阵（§31 收口后）：
 * 短码本地拦截 / 有效码开通 + 状态刷新 + 同步入队 / 受限码 / 异常映射 / 输入 trim。
 * 依赖全部构造注入（无 AppContainer / 无 Robolectric）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        verify: suspend (String) -> OnboardingVerifyResult = {
            OnboardingVerifyResult(userId = "u1", accessToken = "t")
        },
        refreshFlags: suspend () -> Unit = {},
        enqueueSync: () -> Unit = {},
        statusSnapshot: () -> SubscriptionStatusSnapshot = {
            SubscriptionStatusSnapshot(localMode = true, subscriptionExpiresAt = null, subscriptionExpired = false)
        },
    ) = SubscriptionViewModel(verify, refreshFlags, enqueueSync, statusSnapshot)

    @Test
    fun shortCodeShowsFormatErrorWithoutRepoCall() = runTest(mainDispatcher) {
        var calls = 0
        val viewModel = vm(verify = { calls++; OnboardingVerifyResult("u1", "t") })
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("123"))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals("激活码格式不正确，请检查后重试。", viewModel.uiState.value.bindMessage)
        assertFalse(viewModel.uiState.value.binding)
    }

    @Test
    fun codeIsTrimmedBeforeVerify() = runTest(mainDispatcher) {
        val received = mutableListOf<String>()
        val viewModel = vm(verify = { code -> received += code; OnboardingVerifyResult("u1", "t") })
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("  ABCD1234  "))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertEquals(listOf("ABCD1234"), received)
    }

    @Test
    fun validCodeOpensSubscriptionRefreshesStatusAndEnqueuesSync() = runTest(mainDispatcher) {
        var flagsRefreshed = 0
        var syncEnqueued = 0
        var localMode = true
        val viewModel = vm(
            refreshFlags = { flagsRefreshed++; localMode = false },
            enqueueSync = { syncEnqueued++ },
            statusSnapshot = {
                SubscriptionStatusSnapshot(localMode = localMode, subscriptionExpiresAt = null, subscriptionExpired = false)
            },
        )
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("ABCD1234"))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertEquals(1, flagsRefreshed)
        assertEquals(1, syncEnqueued)
        val state = viewModel.uiState.value
        assertFalse(state.binding)
        assertEquals("", state.bindCode)
        assertFalse(state.localMode)
        assertEquals("订阅已开通。云端同步与专业支持现在可用。", state.bindMessage)
    }

    @Test
    fun restrictedCodeShowsRestrictedMessageWithoutSideEffects() = runTest(mainDispatcher) {
        var flagsRefreshed = 0
        var syncEnqueued = 0
        val viewModel = vm(
            verify = { OnboardingVerifyResult("u1", "t", restricted = true) },
            refreshFlags = { flagsRefreshed++ },
            enqueueSync = { syncEnqueued++ },
        )
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("ABCD1234"))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertEquals(0, flagsRefreshed)
        assertEquals(0, syncEnqueued)
        assertEquals("该激活码已受限，请联系客服。", viewModel.uiState.value.bindMessage)
    }

    @Test
    fun invalidCodeExceptionMapsMessage() = runTest(mainDispatcher) {
        val viewModel = vm(verify = { throw OnboardingVerifyException("invalid_code") })
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("ABCD1234"))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertEquals("激活码无效，请检查后重试，或联系客服获取订阅激活码。", viewModel.uiState.value.bindMessage)
    }

    @Test
    fun genericFailureMapsNetworkMessage() = runTest(mainDispatcher) {
        val viewModel = vm(verify = { throw IllegalStateException("boom") })
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("ABCD1234"))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertEquals("暂时无法验证激活信息，请检查网络后重试。", viewModel.uiState.value.bindMessage)
    }

    @Test
    fun updateCodeClearsPreviousMessage() = runTest(mainDispatcher) {
        val viewModel = vm(verify = { throw OnboardingVerifyException("invalid_code") })
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("ABCD1234"))
        viewModel.onEvent(SubscriptionEvent.Bind)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.bindMessage != null)
        viewModel.onEvent(SubscriptionEvent.UpdateBindCode("EFGH5678"))
        assertNull(viewModel.uiState.value.bindMessage)
    }
}
