package com.yunjue.echo.mind.ui.me

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.EscalationEntity
import com.yunjue.echo.mind.me.MeEvent
import com.yunjue.echo.mind.me.MeUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 33 — Me 根页面纯状态渲染 smoke test（Robolectric + Compose）：
 * 九槽位组合矩阵 / 支持二次确认对话框（确认·取消事件）/ 根消息渲染 / 无对话框时不渲染。
 *
 * 子领域全部以槽位注入（无 AppContainer / 子 ViewModel 依赖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** §63/§64 后默认首屏可见：crisis + 紧凑 domain 入口 + 更多控制入口。 */
    private val defaultVisible = listOf("slot-crisis", "更多控制")
    /** 下沉到「更多控制」：subscription / support / about。 */
    private val moreControlSlots = listOf("slot-subscription", "slot-about")

    private fun setContent(
        state: MeUiState,
        events: MutableList<MeEvent>,
        supportSlot: @Composable () -> Unit = { Text("slot-support") },
    ) {
        compose.setContent {
            MaterialTheme {
                MeScreenContent(
                    state = state,
                    onEvent = { events += it },
                    slots = MeSectionSlots(
                        crisisCard = { Text("slot-crisis") },
                        subscription = { Text("slot-subscription") },
                        support = supportSlot,
                        dataAndSensing = { Text("slot-data") },
                        presenceSettings = { Text("slot-presence") },
                        wrist = { Text("slot-wrist") },
                        intelligenceSettings = { Text("slot-intelligence") },
                        whatEchoKnows = { Text("slot-memory") },
                        aboutCard = { Text("slot-about") },
                    ),
                )
            }
        }
    }

    @Test
    fun allSlotsComposedWithTitle() {
        setContent(MeUiState(), mutableListOf())
        compose.onNodeWithText("Me · 我的控制权").assertExists()
        defaultVisible.forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertExists()
        }
        // §86：首屏不是 settings wall——下沉槽位默认不渲染
        compose.onNodeWithText("slot-subscription").assertDoesNotExist()
        // §64：更多控制展开后可见
        compose.onNodeWithText("更多控制").performScrollTo().performClick()
        moreControlSlots.forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertExists()
        }
    }

    @Test
    fun domainExpansionIsExclusive() {
        setContent(MeUiState(), mutableListOf())
        // 默认无展开详情
        compose.onNodeWithText("slot-data").assertDoesNotExist()
        // 展开 Observation（compact 入口：感知世界 · Observation →）
        compose.onNodeWithText("感知世界 · Observation  →", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("slot-data").assertExists()
        compose.onNodeWithTag("me_domain_detail").assertExists()
        // 切到 Memory：Observation 详情收起（一次只展开一个）
        compose.onNodeWithText("记住什么 · Memory  →", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("slot-memory").assertExists()
        compose.onNodeWithText("slot-data").assertDoesNotExist()
    }

    @Test
    fun supportConfirmDialogShowsAndConfirmEmits() {
        val events = mutableListOf<MeEvent>()
        setContent(MeUiState(showSupportConfirm = true), events)
        compose.onNodeWithText(context.getString(R.string.support_request_confirm_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_request_confirm_ok)).performClick()
        assertTrue(events.contains(MeEvent.SupportConfirmed))
    }

    @Test
    fun supportConfirmDialogCancelAndDismissEmitDismissed() {
        val events = mutableListOf<MeEvent>()
        setContent(MeUiState(showSupportConfirm = true), events)
        compose.onNodeWithText(context.getString(R.string.support_request_confirm_cancel)).performClick()
        assertTrue(events.contains(MeEvent.SupportDismissed))
    }

    @Test
    fun noDialogWhenShowSupportConfirmFalse() {
        setContent(MeUiState(), mutableListOf())
        compose.onNodeWithText(context.getString(R.string.support_request_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun rootMessageRendered() {
        setContent(MeUiState(message = "根页面状态消息"), mutableListOf())
        compose.onNodeWithText("根页面状态消息").assertExists()
    }

    @Test
    fun supportSlotReceivesEmptyEscalations() {
        setContent(
            MeUiState(escalations = emptyList()),
            mutableListOf(),
            supportSlot = { SupportSection(escalations = emptyList(), onRequestSupport = {}) },
        )
        compose.onNodeWithText("更多控制").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.support_request_button)).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_recent_requests)).assertDoesNotExist()
    }

    @Test
    fun supportSlotReceivesEscalationsFromState() {
        val escalation = EscalationEntity(
            eventId = "evt-1",
            userId = "u1",
            trigger = "support",
            evidenceSummaryCiphertext = "",
            status = "QUEUED",
            serverEscalationId = null,
            serverStatusJson = null,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        setContent(
            MeUiState(escalations = listOf(escalation)),
            mutableListOf(),
            supportSlot = {
                SupportSection(
                    escalations = listOf(escalation),
                    onRequestSupport = {},
                )
            },
        )
        compose.onNodeWithText("更多控制").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.support_recent_requests)).assertExists()
        compose.onNodeWithText("• ${context.getString(R.string.esc_status_queued)}").assertExists()
    }
}
