package com.yunjue.echo.mind.ui.me

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yunjue.echo.mind.me.PresenceSettingsEvent
import com.yunjue.echo.mind.me.PresenceSettingsUiState
import com.yunjue.echo.mind.ui.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 33 — Presence Settings 纯状态内容 smoke test（Robolectric + Compose）：
 * 动态程度 chips / 夜间·减少动画·建议三开关 / 壁纸·屏保系统入口回调 / 锁屏隐私文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresenceSettingsContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: PresenceSettingsUiState,
        events: MutableList<PresenceSettingsEvent>,
        onSelectWallpaper: () -> Unit = {},
        onDreamSettings: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Page("Me · 我的控制权") {
                    PresenceSettingsContent(
                        state = state,
                        onEvent = { events += it },
                        onSelectWallpaper = onSelectWallpaper,
                        onDreamSettings = onDreamSettings,
                    )
                }
            }
        }
    }

    @Test
    fun motionChipsRenderSelectionAndEmitSetMotionLevel() {
        val events = mutableListOf<PresenceSettingsEvent>()
        setContent(PresenceSettingsUiState(), events)
        compose.onNodeWithText("默认").assertIsSelected()
        compose.onNode(hasClickAction() and hasText("明显")).performClick()
        assertTrue(events.contains(PresenceSettingsEvent.SetMotionLevel("LIVELY")))
        compose.onNode(hasClickAction() and hasText("安静")).performClick()
        assertTrue(events.contains(PresenceSettingsEvent.SetMotionLevel("QUIET")))
    }

    @Test
    fun nightModeSwitchEmitsSetNightMode() {
        val events = mutableListOf<PresenceSettingsEvent>()
        setContent(PresenceSettingsUiState(), events)
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        assertTrue(events.contains(PresenceSettingsEvent.SetNightMode(true)))
    }

    @Test
    fun reduceMotionSwitchEmitsSetReduceMotion() {
        val events = mutableListOf<PresenceSettingsEvent>()
        setContent(PresenceSettingsUiState(reduceMotion = true), events)
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        assertTrue(events.contains(PresenceSettingsEvent.SetReduceMotion(false)))
    }

    @Test
    fun suggestionsSwitchEmitsSetSuggestionsEnabled() {
        val events = mutableListOf<PresenceSettingsEvent>()
        setContent(PresenceSettingsUiState(), events)
        compose.onAllNodes(isToggleable())[2].performScrollTo().performClick()
        assertTrue(events.contains(PresenceSettingsEvent.SetSuggestionsEnabled(true)))
    }

    @Test
    fun wallpaperAndDreamSystemEntriesInvokeCallbacks() {
        val events = mutableListOf<PresenceSettingsEvent>()
        val wallpaper = mutableListOf<Boolean>()
        val dream = mutableListOf<Boolean>()
        setContent(
            PresenceSettingsUiState(),
            events,
            onSelectWallpaper = { wallpaper += true },
            onDreamSettings = { dream += true },
        )
        compose.onNode(hasClickAction() and hasText("选择 ECHO 壁纸")).performClick()
        compose.onNode(hasClickAction() and hasText("系统屏保设置")).performClick()
        assertEquals(listOf(true), wallpaper)
        assertEquals(listOf(true), dream)
        assertTrue(events.isEmpty())
    }

    @Test
    fun lockSafePrivacyDisclaimerRendered() {
        setContent(PresenceSettingsUiState(), mutableListOf())
        compose.onNodeWithText(
            "锁屏隐私：动态壁纸仅渲染视觉，不含任何文字——Public Safe 由构造保证。"
        ).assertExists()
    }
}
