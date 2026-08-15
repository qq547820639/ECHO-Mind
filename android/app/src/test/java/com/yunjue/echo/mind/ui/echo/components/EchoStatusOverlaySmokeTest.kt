package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yunjue.echo.mind.model.RUNTIME_COPY_STARTING
import com.yunjue.echo.mind.model.RUNTIME_COPY_SYSTEM_PAUSED
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 35 — EchoStatusOverlay smoke test：感知六态可信呈现（非 ACTIVE 才可见）
 * + 初次 AI 非阻塞提示（未配置 + 未 dismiss）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoStatusOverlaySmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        sensing: SensingRuntimeStatus,
        intelligenceAvailable: Boolean = true,
        aiPromptDismissed: Boolean = true,
        onGoToMe: () -> Unit = {},
        onDismissAiPrompt: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                EchoStatusOverlay(
                    sensing = sensing,
                    intelligenceAvailable = intelligenceAvailable,
                    aiPromptDismissed = aiPromptDismissed,
                    onGoToMe = onGoToMe,
                    onDismissAiPrompt = onDismissAiPrompt,
                )
            }
        }
    }

    @Test
    fun startingShowsStatusText() {
        setContent(SensingRuntimeStatus.STARTING)
        compose.onNodeWithText(RUNTIME_COPY_STARTING).assertExists()
    }

    @Test
    fun systemPausedShowsStatusAndReasonEntry() {
        var wentToMe = false
        setContent(SensingRuntimeStatus.SYSTEM_PAUSED, onGoToMe = { wentToMe = true })
        compose.onNodeWithText(RUNTIME_COPY_SYSTEM_PAUSED).assertExists()
        compose.onNode(hasClickAction() and hasText("查看原因")).performClick()
        assertTrue(wentToMe)
    }

    @Test
    fun activeShowsNoStatusText() {
        setContent(SensingRuntimeStatus.ACTIVE)
        compose.onNodeWithText("ECHO 正在了解今天").assertDoesNotExist()
        compose.onNodeWithText("查看原因").assertDoesNotExist()
    }

    @Test
    fun aiPromptCardShowsWhenUnconfiguredAndNotDismissed() {
        var wentToMe = false
        var dismissed = false
        setContent(
            SensingRuntimeStatus.ACTIVE,
            intelligenceAvailable = false,
            aiPromptDismissed = false,
            onGoToMe = { wentToMe = true },
            onDismissAiPrompt = { dismissed = true },
        )
        compose.onNodeWithText("连接一个 AI，让 ECHO 更深入地理解你的变化。").assertExists()
        compose.onNode(hasClickAction() and hasText("连接 AI")).performClick()
        assertTrue(wentToMe)
        compose.onNode(hasClickAction() and hasText("以后再说")).performClick()
        assertTrue(dismissed)
    }

    @Test
    fun aiPromptCardHiddenWhenDismissed() {
        setContent(
            SensingRuntimeStatus.ACTIVE,
            intelligenceAvailable = false,
            aiPromptDismissed = true,
        )
        compose.onNodeWithText("连接一个 AI，让 ECHO 更深入地理解你的变化。").assertDoesNotExist()
    }

    @Test
    fun aiPromptCardHiddenWhenAiConfigured() {
        setContent(
            SensingRuntimeStatus.ACTIVE,
            intelligenceAvailable = true,
            aiPromptDismissed = false,
        )
        compose.onNodeWithText("连接一个 AI，让 ECHO 更深入地理解你的变化。").assertDoesNotExist()
    }
}
