package com.yunjue.echo.mind.ui.me

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.yunjue.echo.mind.me.SubscriptionEvent
import com.yunjue.echo.mind.me.SubscriptionUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 33 — Subscription 纯状态内容 smoke test（Robolectric + Compose）：
 * 本地模式提示 / 激活码输入事件 / 订阅状态文案 / 开通按钮事件与禁用态 / 绑定中进度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: SubscriptionUiState,
        events: MutableList<SubscriptionEvent>,
    ) {
        compose.setContent {
            MaterialTheme {
                SubscriptionContent(state = state, onEvent = { events += it })
            }
        }
    }

    @Test
    fun localModeShowsHintAndButtonDisabledWhenBlank() {
        setContent(SubscriptionUiState(), mutableListOf())
        compose.onNodeWithText(
            "默认本地使用，数据只保存在本机。订阅后可获得云端同步备份、长周期分析与专业支持，输入订阅激活码完成开通。"
        ).assertExists()
        compose.onNode(hasClickAction() and hasText("开通订阅")).assertIsNotEnabled()
    }

    @Test
    fun codeInputEmitsUpdateBindCode() {
        val events = mutableListOf<SubscriptionEvent>()
        setContent(SubscriptionUiState(), events)
        compose.onNode(hasSetTextAction() and hasText("订阅激活码", substring = true))
            .performTextInput("ABCD1234")
        assertTrue(
            events.filterIsInstance<SubscriptionEvent.UpdateBindCode>()
                .any { it.value.contains("ABCD1234") }
        )
    }

    @Test
    fun nonLocalModeShowsSubscriptionStatus() {
        setContent(
            SubscriptionUiState(localMode = false, subscriptionExpiresAt = null),
            mutableListOf(),
        )
        compose.onNodeWithText("已订阅").assertExists()
    }

    @Test
    fun expiredSubscriptionShowsRenewText() {
        setContent(
            SubscriptionUiState(localMode = false, subscriptionExpiresAt = 0L, subscriptionExpired = true),
            mutableListOf(),
        )
        compose.onNodeWithText("订阅已到期，请续订").assertExists()
    }

    @Test
    fun bindButtonEmitsBindWhenCodePresent() {
        val events = mutableListOf<SubscriptionEvent>()
        setContent(SubscriptionUiState(bindCode = "ABCD1234"), events)
        compose.onNode(hasClickAction() and hasText("开通订阅")).performClick()
        assertTrue(events.contains(SubscriptionEvent.Bind))
    }

    @Test
    fun bindingStateShowsProgressAndDisablesButton() {
        setContent(SubscriptionUiState(bindCode = "ABCD1234", binding = true), mutableListOf())
        compose.onNode(hasClickAction() and hasText("正在验证…")).assertIsNotEnabled()
    }

    @Test
    fun bindMessageRendered() {
        setContent(
            SubscriptionUiState(bindMessage = "激活码格式不正确，请检查后重试。"),
            mutableListOf(),
        )
        compose.onNodeWithText("激活码格式不正确，请检查后重试。").assertExists()
    }
}
