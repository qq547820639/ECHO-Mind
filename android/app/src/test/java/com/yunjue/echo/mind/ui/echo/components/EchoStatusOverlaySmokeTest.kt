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
 * ERA 35/§AC — EchoStatusOverlay smoke test：感知六态可信呈现（非 ACTIVE 才可见）。
 * AI provider 提示用例已删除（§AC：home 无 AI 营销）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoStatusOverlaySmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        sensing: SensingRuntimeStatus,
        onGoToMe: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                EchoStatusOverlay(
                    sensing = sensing,
                    onGoToMe = onGoToMe,
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
}
