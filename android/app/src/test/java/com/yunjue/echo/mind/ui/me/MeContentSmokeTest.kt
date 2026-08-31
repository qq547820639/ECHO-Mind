package com.yunjue.echo.mind.ui.me

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * V3 §AR/§AT — Me L0 四分区 + MeRoute 次级页 smoke test（Robolectric + Compose）：
 * L0 根层入口全部 ≤1 步（me_entry_* 锚点语义不变，宿主容器可换）/
 * 概念图（me_intelligence_map）与就地展开（me_domain_detail）已删除 /
 * Z2 状态网格与账户组路由到 me_page_* 次级页（全屏互斥，Back 返回根）/
 * 紧急支持行触发 onEmergency（SafetyScreen 直达）/ 通知行触发系统设置回调 /
 * SMARTMAP 快速管理页内路由 / 支持二次确认对话框 / 根消息渲染。
 *
 * 子领域全部以槽位注入（无 AppContainer / 子 ViewModel 依赖）。
 * 壁纸/Dream（me_entry_wallpaper/dream）已迁入 PRESENCE 页、
 * 导出/删除（me_entry_export/delete）已迁入 SENSING 危险区，
 * 行为断言由各自 Content smoke suite 承担。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** §AR：L0 根层常驻入口锚点（全部 ≤1 步可达）。 */
    private val rootEntryTags = listOf(
        "me_entry_echo",
        "me_entry_smartmap",
        "me_entry_data",
        "me_entry_memory",
        "me_entry_wrist",
        "me_entry_emergency",
        "me_entry_support",
        "me_entry_subscription",
        "me_entry_ai",
        "me_entry_notifications",
        "me_entry_about",
    )

    private class Recorder {
        val emergency = mutableListOf<Boolean>()
        val notifications = mutableListOf<Boolean>()

        fun actions() = MeControlActions(
            onEmergency = { emergency += true },
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
                        // 槽位收到真实快速管理条目（Task 2.2 不再传 emptyList()），由真实 Section 渲染
                        smartMap = { items ->
                            Column {
                                Text("slot-smartmap")
                                MeSmartMapSection(devices = SmartMapDevices(), quickAccessItems = items)
                            }
                        },
                    ),
                )
            }
        }
    }

    /** L1 次级页顶部返回（TopAppBar navigationIcon；返回根层）。 */
    private fun backToRoot() {
        compose.onNodeWithContentDescription("返回").performClick()
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
    fun legacyInPlaceExpansionIsGone() {
        // §BQ/§AR：概念图不再作为 Me 导航；MeDomain 就地展开机制（me_domain_detail）已删除
        setContent()
        compose.onNodeWithTag("me_intelligence_map").assertDoesNotExist()
        compose.onNodeWithTag("me_domain_detail").assertDoesNotExist()
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
    fun notificationEntryFiresPlatformAction() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNodeWithTag("me_entry_notifications").performScrollTo().performClick()
        assertEquals(listOf(true), recorder.notifications)
    }

    @Test
    fun stateGridEntriesRouteToSubPagesExclusively() {
        setContent()
        // Z2 网格 → SENSING 次级页（全屏替换；根层不再组合）
        compose.onNodeWithTag("me_entry_data").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_sensing").assertExists()
        compose.onNodeWithText("slot-data").assertExists()
        compose.onNodeWithText("我的").assertDoesNotExist()
        backToRoot()
        compose.onNodeWithTag("me_entry_echo").assertExists()
        // MEMORY / INTELLIGENCE / WRIST 同理（一次只有一个路由活跃）
        compose.onNodeWithTag("me_entry_memory").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_memory").assertExists()
        compose.onNodeWithText("slot-memory").assertExists()
        backToRoot()
        compose.onNodeWithTag("me_entry_ai").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_intelligence").assertExists()
        compose.onNodeWithText("slot-intelligence").assertExists()
        backToRoot()
        compose.onNodeWithTag("me_entry_wrist").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_wrist").assertExists()
        compose.onNodeWithText("slot-wrist").assertExists()
    }

    @Test
    fun identityHeaderOpensPresenceSubPage() {
        setContent()
        compose.onNodeWithTag("me_entry_echo").performClick()
        compose.onNodeWithTag("me_page_presence").assertExists()
        compose.onNodeWithText("slot-presence").assertExists()
    }

    @Test
    fun smartMapEntryOpensSmartMapPageWithQuickAccessRouting() {
        setContent()
        compose.onNodeWithTag("me_entry_smartmap").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_smartmap").assertExists()
        compose.onNodeWithText("slot-smartmap").assertExists()
        // 快速管理：真实条目（4 项）经槽位注入，SMARTMAP 页内 me_entry_* 行路由到对应次级页
        listOf("me_entry_wrist", "me_entry_data", "me_entry_memory", "me_entry_ai").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertExists()
        }
        compose.onNodeWithTag("me_entry_data").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_sensing").assertExists()
        compose.onNodeWithText("slot-data").assertExists()
    }

    @Test
    fun accountEntriesRouteToSubPages() {
        setContent()
        compose.onNodeWithTag("me_entry_subscription").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_subscription").assertExists()
        compose.onNodeWithText("slot-subscription").assertExists()
        backToRoot()
        compose.onNodeWithTag("me_entry_support").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_support").assertExists()
        compose.onNodeWithText("slot-support").assertExists()
        backToRoot()
        compose.onNodeWithTag("me_entry_about").performScrollTo().performClick()
        compose.onNodeWithTag("me_page_about").assertExists()
        compose.onNodeWithText("slot-about").assertExists()
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
