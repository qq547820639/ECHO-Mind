package com.yunjue.echo.mind.ui.me

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yunjue.echo.mind.me.DataAndSensingEvent
import com.yunjue.echo.mind.me.DataAndSensingUiState
import com.yunjue.echo.mind.model.CapabilityState
import com.yunjue.echo.mind.model.SensingCapability
import com.yunjue.echo.mind.ui.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 33 — Data & Sensing 纯状态内容 smoke test（Robolectric + Compose）：
 * 能力状态行 / 三开关事件 / 感知开关经 onToggleSensing 回调（通知权限预检在调用侧）/
 * 麦克风二次确认 / 本机面板文案 / 数据权利三按钮 / 删除确认 / 立即同步 / 身份行。
 * 内容以 Page 包裹（与 MeScreenContent 真实组合一致，提供滚动容器）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataAndSensingContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(
        capabilities: Map<SensingCapability, CapabilityState> = emptyMap(),
        localMode: Boolean = true,
        showMicConfirm: Boolean = false,
        showLocalDeleteConfirm: Boolean = false,
        localWindows: Int = 0,
        localPortraits: Int = 0,
        userId: String = "",
    ) = DataAndSensingUiState(
        capabilityStates = capabilities,
        localMode = localMode,
        showMicConfirm = showMicConfirm,
        showLocalDeleteConfirm = showLocalDeleteConfirm,
        localWindows = localWindows,
        localPortraits = localPortraits,
        footprint = com.yunjue.echo.mind.data.DataFootprint(
            featureWindows = localWindows,
            portraits = localPortraits,
        ),
        userId = userId,
    )

    private fun setContent(
        uiState: DataAndSensingUiState,
        events: MutableList<DataAndSensingEvent>,
        onToggleSensing: (Boolean) -> Unit = {},
        onLaunchMicPermission: () -> Unit = {},
        onRecoverUsageAccess: () -> Unit = {},
        onRecoverNotificationAccess: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Page("Me · 我的控制权") {
                    DataAndSensingContent(
                        state = uiState,
                        onEvent = { events += it },
                        onToggleSensing = onToggleSensing,
                        onLaunchMicPermission = onLaunchMicPermission,
                        onRecoverUsageAccess = onRecoverUsageAccess,
                        onRecoverNotificationAccess = onRecoverNotificationAccess,
                    )
                }
            }
        }
    }

    @Test
    fun capabilityRowsRenderSystemTruth() {
        setContent(
            state(
                capabilities = mapOf(
                    SensingCapability.SENSOR to CapabilityState.UNAVAILABLE,
                    SensingCapability.USAGE to CapabilityState.DENIED,
                    SensingCapability.NOTIFICATION to CapabilityState.DENIED,
                    SensingCapability.MIC to CapabilityState.DENIED,
                    SensingCapability.SCREEN to CapabilityState.AVAILABLE,
                )
            ),
            mutableListOf()
        )
        compose.onNodeWithText("数据与感知").assertExists()
        compose.onNodeWithText("设备不支持").assertExists()
        compose.onNodeWithText("已开启").assertExists()
        // USAGE + NOTIFICATION + MIC 三行均为「未开启」
        compose.onAllNodesWithText("未开启").assertCountEquals(3)
        compose.onNodeWithText("开启使用情况访问").assertExists()
        compose.onNodeWithText("开启通知使用权").assertExists()
    }

    @Test
    fun eveningReminderSwitchEmitsSetEveningReminder() {
        val events = mutableListOf<DataAndSensingEvent>()
        setContent(state(), events)
        compose.onAllNodes(isToggleable())[0].performClick()
        assertTrue(events.contains(DataAndSensingEvent.SetEveningReminder(true)))
    }

    @Test
    fun sensingSwitchRoutesThroughOnToggleSensingCallback() {
        val events = mutableListOf<DataAndSensingEvent>()
        val toggles = mutableListOf<Boolean>()
        setContent(state(), events, onToggleSensing = { toggles += it })
        compose.onAllNodes(isToggleable())[1].performClick()
        assertEquals(listOf(true), toggles)
        assertTrue(events.isEmpty())
    }

    @Test
    fun micSwitchEmitsToggleMic() {
        val events = mutableListOf<DataAndSensingEvent>()
        setContent(state(), events)
        compose.onAllNodes(isToggleable())[2].performScrollTo().performClick()
        assertTrue(events.contains(DataAndSensingEvent.ToggleMic(true)))
    }

    @Test
    fun micConfirmDialogDismissesAndLaunchesSystemPermission() {
        val events = mutableListOf<DataAndSensingEvent>()
        var launched = false
        setContent(
            state(showMicConfirm = true),
            events,
            onLaunchMicPermission = { launched = true },
        )
        compose.onNodeWithText("同意并继续").performClick()
        assertTrue(launched)
        assertTrue(events.contains(DataAndSensingEvent.MicConfirmDismissed))
    }

    @Test
    fun localPanelRendersCountsAndLocalModeTruth() {
        setContent(state(localWindows = 3, localPortraits = 2), mutableListOf())
        compose.onNodeWithText("3 个学习窗口 · 2 张画像 · 0 条同意记录 · 0 条记忆 · 0 天视觉快照")
            .performScrollTo().assertExists()
        compose.onNodeWithText("0 次上传（本地模式，数据不出手机）").assertExists()
        // ERA 67：软删审计行语义在检查台明确标注
        compose.onNodeWithText(
            "记忆条数包含你已选择「忘记」、但依法保留待清理的行；「删除本地数据」会一并清除。",
        ).assertExists()
    }

    @Test
    fun dataRightsButtonsEmitEvents() {
        val events = mutableListOf<DataAndSensingEvent>()
        setContent(state(), events)
        compose.onNodeWithText("导出本地数据").performScrollTo().performClick()
        compose.onNodeWithText("申请删除数据").performScrollTo().performClick()
        compose.onNodeWithText("撤回同意并停止服务").performScrollTo().performClick()
        assertTrue(events.contains(DataAndSensingEvent.RequestExport))
        assertTrue(events.contains(DataAndSensingEvent.RequestDelete))
        assertTrue(events.contains(DataAndSensingEvent.RevokeConsent))
    }

    @Test
    fun deleteConfirmDialogEmitsConfirmAndDismiss() {
        val events = mutableListOf<DataAndSensingEvent>()
        setContent(state(showLocalDeleteConfirm = true), events)
        compose.onNodeWithText("删除本地数据").assertExists()
        compose.onNodeWithText("删除").performClick()
        assertTrue(events.contains(DataAndSensingEvent.ConfirmLocalDelete))
        compose.onNodeWithText("取消").performClick()
        assertTrue(events.contains(DataAndSensingEvent.DismissLocalDelete))
    }

    @Test
    fun syncNowAndIdentityLinesRenderAndEmit() {
        val events = mutableListOf<DataAndSensingEvent>()
        setContent(state(userId = "u-42"), events)
        compose.onNodeWithText("立即同步").performScrollTo().performClick()
        assertTrue(events.contains(DataAndSensingEvent.SyncNow))
        compose.onNodeWithText("绑定身份：未配置（本地模式）").performScrollTo().assertExists()
        compose.onNodeWithText("同步身份：u-42").performScrollTo().assertExists()
        compose.onNodeWithText("AI 身份提示：ECHO Mind 是支持性工具，不是医生。").assertExists()
    }

    @Test
    fun reEnablingHintRenderedWhenTrue() {
        setContent(state().copy(reEnabling = true), mutableListOf())
        compose.onNodeWithText("正在重新启用 · 等待授权同步").assertExists()
    }

    @Test
    fun recoveryCallbacksInvokedForUsageAndNotification() {
        var usage = false
        var notif = false
        setContent(
            state(
                capabilities = mapOf(
                    SensingCapability.USAGE to CapabilityState.DENIED,
                    SensingCapability.NOTIFICATION to CapabilityState.DENIED,
                )
            ),
            mutableListOf(),
            onRecoverUsageAccess = { usage = true },
            onRecoverNotificationAccess = { notif = true },
        )
        compose.onNode(hasClickAction() and hasText("开启使用情况访问")).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText("开启通知使用权")).performScrollTo().performClick()
        assertTrue(usage)
        assertTrue(notif)
    }

    @Test
    fun sensingOffToggleEmitsFalseThroughCallback() {
        val events = mutableListOf<DataAndSensingEvent>()
        val toggles = mutableListOf<Boolean>()
        setContent(state().copy(sensingEnabled = true), events, onToggleSensing = { toggles += it })
        compose.onAllNodes(isToggleable())[1].performClick()
        assertEquals(listOf(false), toggles)
    }
}
