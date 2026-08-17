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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V3 §AR/§AS/§AT — Me 分组控制中心 smoke test（Robolectric + Compose）：
 * 分组入口全部距根 ≤1 步（me_entry_* 锚点）/ 概念图（me_intelligence_map）已删除 /
 * 紧急支持行触发 onEmergency（SafetyScreen 直达）/ 数据·记忆·AI·壁纸·手环入口行为 /
 * 领域深页就地展开且互斥 / 支持二次确认对话框 / 根消息渲染。
 *
 * 子领域全部以槽位注入（无 AppContainer / 子 ViewModel 依赖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** §AR：根层常驻入口锚点（全部 ≤1 步可达）。 */
    private val rootEntryTags = listOf(
        "me_entry_echo",
        "me_entry_emergency",
        "me_entry_data",
        "me_entry_memory",
        "me_entry_ai",
        "me_entry_wallpaper",
        "me_entry_dream",
        "me_entry_wrist",
        "me_entry_export",
        "me_entry_delete",
        "me_entry_notifications",
        "me_entry_subscription",
        "me_entry_support",
        "me_entry_about",
    )

    private class Recorder {
        val emergency = mutableListOf<Boolean>()
        val export = mutableListOf<Boolean>()
        val delete = mutableListOf<Boolean>()
        val wallpaper = mutableListOf<Boolean>()
        val dream = mutableListOf<Boolean>()
        val notifications = mutableListOf<Boolean>()

        fun actions() = MeControlActions(
            onEmergency = { emergency += true },
            onExportData = { export += true },
            onDeleteData = { delete += true },
            onSelectWallpaper = { wallpaper += true },
            onDreamSettings = { dream += true },
            onNotificationSettings = { notifications += true },
        )
    }

    private fun setContent(
        state: MeUiState = MeUiState(),
        events: MutableList<MeEvent> = mutableListOf(),
        recorder: Recorder = Recorder(),
        supportSlot: @Composable () -> Unit = { Text("slot-support") },
    ) {
        compose.setContent {
            MaterialTheme {
                MeScreenContent(
                    state = state,
                    onEvent = { events += it },
                    actions = recorder.actions(),
                    slots = MeSectionSlots(
                        identityHeader = { Text("slot-identity") },
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
    fun groupedControlCenterRendersTitleAndAllEntriesOnRoot() {
        setContent()
        compose.onNodeWithText("我的").assertExists()
        compose.onNodeWithText("slot-identity").assertExists()
        rootEntryTags.forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertExists()
        }
    }

    @Test
    fun intelligenceMapIsGone() {
        // §BQ/§AR：概念图不再是 Me 导航（节点与文件均已删除）
        setContent()
        compose.onNodeWithTag("me_intelligence_map").assertDoesNotExist()
    }

    @Test
    fun crisisEntryFiresOnEmergencyOneTap() {
        // §AT：Me 内危机入口一键直达 SafetyScreen（onEmergency 回调）
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNodeWithTag("me_entry_emergency").performScrollTo().performClick()
        assertEquals(listOf(true), recorder.emergency)
    }

    @Test
    fun domainEntriesOpenSectionsInPlaceAndExclusively() {
        setContent()
        // 默认无展开详情
        compose.onNodeWithText("slot-data").assertDoesNotExist()
        // 数据与感知 → 深页就地出现
        compose.onNodeWithTag("me_entry_data").performScrollTo().performClick()
        compose.onNodeWithText("slot-data").assertExists()
        compose.onNodeWithTag("me_domain_detail").assertExists()
        // 记忆 → 前一深页收起（一次只展开一个）
        compose.onNodeWithTag("me_entry_memory").performScrollTo().performClick()
        compose.onNodeWithText("slot-memory").assertExists()
        compose.onNodeWithText("slot-data").assertDoesNotExist()
        // AI
        compose.onNodeWithTag("me_entry_ai").performScrollTo().performClick()
        compose.onNodeWithText("slot-intelligence").assertExists()
        compose.onNodeWithText("slot-memory").assertDoesNotExist()
        // 手环
        compose.onNodeWithTag("me_entry_wrist").performScrollTo().performClick()
        compose.onNodeWithText("slot-wrist").assertExists()
        compose.onNodeWithText("slot-intelligence").assertDoesNotExist()
    }

    @Test
    fun identityHeaderOpensPresenceDomain() {
        setContent()
        compose.onNodeWithTag("me_entry_echo").performClick()
        compose.onNodeWithText("slot-presence").assertExists()
        compose.onNodeWithTag("me_domain_detail").assertExists()
    }

    @Test
    fun surfaceEntriesFirePlatformActions() {
        // 壁纸选择 / Dream 设置 / 通知设置为既有系统动作直达（不展开分节）
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNodeWithTag("me_entry_wallpaper").performScrollTo().performClick()
        compose.onNodeWithTag("me_entry_dream").performScrollTo().performClick()
        compose.onNodeWithTag("me_entry_notifications").performScrollTo().performClick()
        assertEquals(listOf(true), recorder.wallpaper)
        assertEquals(listOf(true), recorder.dream)
        assertEquals(listOf(true), recorder.notifications)
        compose.onNodeWithTag("me_domain_detail").assertDoesNotExist()
    }

    @Test
    fun dataRightsEntriesFireExistingActions() {
        // §AS：导出 = 既有导出动作；删除 = 既有删除流程（经数据 ViewModel 回调注入）
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNodeWithTag("me_entry_export").performScrollTo().performClick()
        assertEquals(listOf(true), recorder.export)
        // 删除入口同时强制展开数据分节（确认对话框在分节内呈现）
        compose.onNodeWithTag("me_entry_delete").performScrollTo().performClick()
        assertEquals(listOf(true), recorder.delete)
        compose.onNodeWithText("slot-data").assertExists()
    }

    @Test
    fun otherGroupEntriesExpandSections() {
        setContent()
        compose.onNodeWithTag("me_entry_subscription").performScrollTo().performClick()
        compose.onNodeWithText("slot-subscription").assertExists()
        compose.onNodeWithTag("me_entry_support").performScrollTo().performClick()
        compose.onNodeWithText("slot-support").assertExists()
        compose.onNodeWithText("slot-subscription").assertDoesNotExist()
        compose.onNodeWithTag("me_entry_about").performScrollTo().performClick()
        compose.onNodeWithText("slot-about").assertExists()
        compose.onNodeWithText("slot-support").assertDoesNotExist()
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
        setContent()
        compose.onNodeWithText(context.getString(R.string.support_request_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun rootMessageRendered() {
        setContent(MeUiState(message = "根页面状态消息"))
        compose.onNodeWithText("根页面状态消息").assertExists()
    }

    @Test
    fun supportSlotReceivesEmptyEscalations() {
        setContent(
            MeUiState(escalations = emptyList()),
            supportSlot = { SupportSection(escalations = emptyList(), onRequestSupport = {}) },
        )
        compose.onNodeWithTag("me_entry_support").performScrollTo().performClick()
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
            supportSlot = {
                SupportSection(
                    escalations = listOf(escalation),
                    onRequestSupport = {},
                )
            },
        )
        compose.onNodeWithTag("me_entry_support").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.support_recent_requests)).assertExists()
        compose.onNodeWithText("• ${context.getString(R.string.esc_status_queued)}").assertExists()
    }
}
