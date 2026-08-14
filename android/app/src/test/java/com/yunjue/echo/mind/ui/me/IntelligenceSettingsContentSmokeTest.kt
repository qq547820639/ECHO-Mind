package com.yunjue.echo.mind.ui.me

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.yunjue.echo.mind.me.IntelligenceSettingsEvent
import com.yunjue.echo.mind.me.IntelligenceSettingsUiState
import com.yunjue.echo.mind.ui.Page
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 33 — Intelligence Settings 纯状态内容 smoke test（Robolectric + Compose）：
 * 未配置隐藏 Provider 详情 / 已配置展示 Model·Base URL / busy 禁用态 /
 * 更换 Provider 展开草稿字段 + 输入事件 / 断开连接事件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntelligenceSettingsContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: IntelligenceSettingsUiState,
        events: MutableList<IntelligenceSettingsEvent>,
    ) {
        compose.setContent {
            MaterialTheme {
                Page("Me · 我的控制权") {
                    IntelligenceSettingsContent(state = state, onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun unconfiguredHidesProviderDetailsAndTestEmitsEvent() {
        val events = mutableListOf<IntelligenceSettingsEvent>()
        setContent(IntelligenceSettingsUiState(), events)
        compose.onNodeWithText("Current provider：OpenAI Compatible").assertDoesNotExist()
        compose.onNodeWithText("断开连接").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasText("测试连接")).performClick()
        assertTrue(events.contains(IntelligenceSettingsEvent.TestConnection))
    }

    @Test
    fun configuredShowsModelBaseUrlAndDisconnectEmits() {
        val events = mutableListOf<IntelligenceSettingsEvent>()
        setContent(
            IntelligenceSettingsUiState(
                providerConfigured = true,
                model = "gpt-echo-1",
                baseUrl = "https://echo.local/v1",
            ),
            events,
        )
        compose.onNodeWithText("Model：gpt-echo-1").assertExists()
        compose.onNodeWithText("Base URL：https://echo.local/v1").assertExists()
        compose.onNode(hasClickAction() and hasText("断开连接")).performClick()
        assertTrue(events.contains(IntelligenceSettingsEvent.Disconnect))
    }

    @Test
    fun busyStateDisablesTestButtonAndShowsProgress() {
        setContent(IntelligenceSettingsUiState(busy = true), mutableListOf())
        compose.onNode(hasClickAction() and hasText("正在测试…")).assertIsNotEnabled()
    }

    @Test
    fun changeExpandedShowsDraftFieldsAndInputEmitsUpdate() {
        val events = mutableListOf<IntelligenceSettingsEvent>()
        setContent(
            IntelligenceSettingsUiState(changeExpanded = true, draftBaseUrl = "https://old"),
            events,
        )
        compose.onNode(hasSetTextAction() and hasText("https://old"))
            .performScrollTo()
            .performTextInput("https://new.local")
        assertTrue(
            events.filterIsInstance<IntelligenceSettingsEvent.UpdateDraftBaseUrl>()
                .any { it.value.contains("https://new.local") }
        )
        compose.onNodeWithText("保存并连接").performScrollTo().performClick()
        assertTrue(events.contains(IntelligenceSettingsEvent.SaveAndConnect))
    }

    @Test
    fun toggleExpandedEmitsEvent() {
        val events = mutableListOf<IntelligenceSettingsEvent>()
        setContent(IntelligenceSettingsUiState(), events)
        compose.onNode(hasClickAction() and hasText("更换 Provider")).performClick()
        assertTrue(events.contains(IntelligenceSettingsEvent.ToggleChangeExpanded))
    }
}
