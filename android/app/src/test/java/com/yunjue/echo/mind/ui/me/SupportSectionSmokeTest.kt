package com.yunjue.echo.mind.ui.me

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.EscalationEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 32 — Me → Support 纯状态渲染 smoke test（Robolectric + Compose）：
 * 请求按钮回调 / 空清单不显示历史标题 / 状态行渲染 / 未知状态回退。
 *
 * SupportSection 只消费 escalations + onRequestSupport，不依赖 AppContainer。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupportSectionSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun escalation(status: String) = EscalationEntity(
        eventId = "evt-$status",
        userId = "u1",
        trigger = "support",
        evidenceSummaryCiphertext = "",
        status = status,
        serverEscalationId = null,
        serverStatusJson = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    @Test
    fun requestButtonRendersAndEmitsCallback() {
        var requested = false
        compose.setContent {
            MaterialTheme {
                SupportSection(escalations = emptyList(), onRequestSupport = { requested = true })
            }
        }
        compose.onNodeWithText(context.getString(R.string.support_request_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_request_button)).performClick()
        assertTrue(requested)
        compose.onNodeWithText(context.getString(R.string.support_recent_requests)).assertDoesNotExist()
    }

    @Test
    fun escalationsRenderStatusRows() {
        compose.setContent {
            MaterialTheme {
                SupportSection(
                    escalations = listOf(escalation("QUEUED"), escalation("ACKNOWLEDGED")),
                    onRequestSupport = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.support_recent_requests)).assertExists()
        compose.onNodeWithText("• ${context.getString(R.string.esc_status_queued)}").assertExists()
        compose.onNodeWithText("• ${context.getString(R.string.esc_status_acknowledged)}").assertExists()
    }

    @Test
    fun unknownStatusFallsBackToUnknownLabel() {
        compose.setContent {
            MaterialTheme {
                SupportSection(escalations = listOf(escalation("WEIRD")), onRequestSupport = {})
            }
        }
        compose.onNodeWithText("• ${context.getString(R.string.esc_status_unknown)}").assertExists()
    }
}
