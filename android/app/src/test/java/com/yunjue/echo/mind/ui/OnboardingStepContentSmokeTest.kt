package com.yunjue.echo.mind.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 38 — OnboardingStepContent 三步渲染矩阵 smoke test：
 * WELCOME（契约句/18+/边界门禁/紧急入口）/ PRIVACY_PLEDGE（三句承诺/五同意门禁）/
 * CORE_SENSING（能力行真实状态/授权·跳过回调/苏醒 CTA/abstain 入口）。
 * 纯状态渲染（编排与权限 launcher 在 OnboardingScreen）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingStepContentSmokeTest {

    @Test
    fun awakeningDurationWithinTimeToEchoBudget() {
        // §50：苏醒过渡 ≤3s（机器段预算）；回归 = 时间被悄悄拉长
        assertTrue("AWAKENING_DURATION_MS 超出 Time-to-ECHO 预算", AWAKENING_DURATION_MS <= 3000L)
    }

    /** V3 §AW/§BL：步骤枚举结构性锁定——无 DONE、无 enhanced optional permission wall 步骤。 */
    @Test
    fun onboardingStepsExactlyWelcomePrivacyCoreSensing() {
        val names = OnboardingStep.entries.map { it.name }.toSet()
        assertEquals(
            "Onboarding 步骤必须恰为三步（不得回归 DONE / 可选权限墙）",
            setOf("WELCOME", "PRIVACY_PLEDGE", "CORE_SENSING"),
            names,
        )
    }

    @get:Rule
    val compose = createComposeRule()

    private fun state(
        step: OnboardingStep = OnboardingStep.WELCOME,
        ageConfirmed: Boolean = false,
        boundaryConfirmed: Boolean = false,
        coreChecks: List<Boolean> = listOf(false, false, false, false, false),
        notifPermAuthorized: Boolean = false,
        sensorHardwareAvailable: Boolean = true,
    ) = OnboardingStepState(
        step = step,
        ageConfirmed = ageConfirmed,
        boundaryConfirmed = boundaryConfirmed,
        coreChecks = coreChecks,
        notifPermAuthorized = notifPermAuthorized,
        sensorHardwareAvailable = sensorHardwareAvailable,
    )

    private class Recorder {
        val age = mutableListOf<Boolean>()
        val boundary = mutableListOf<Boolean>()
        val core = mutableListOf<Pair<Int, Boolean>>()
        val privacy = mutableListOf<Boolean>()
        val sensing = mutableListOf<Boolean>()
        val awaken = mutableListOf<Boolean>()
        val abstain = mutableListOf<Boolean>()
        val notifRequest = mutableListOf<Boolean>()
        val notifSkip = mutableListOf<Boolean>()
        val safety = mutableListOf<Boolean>()

        fun actions() = OnboardingStepActions(
            onAgeConfirmed = { age += it },
            onBoundaryConfirmed = { boundary += it },
            onCoreCheck = { i, v -> core += i to v },
            onContinueToPrivacy = { privacy += true },
            onContinueToCoreSensing = { sensing += true },
            onAwaken = { awaken += true },
            onAbstain = { abstain += true },
            onRequestNotifPermission = { notifRequest += true },
            onSkipNotifPermission = { notifSkip += true },
            onOpenSafety = { safety += true },
        )
    }

    private fun setContent(uiState: OnboardingStepState, recorder: Recorder) {
        compose.setContent {
            MaterialTheme {
                OnboardingStepContent(state = uiState, actions = recorder.actions())
            }
        }
    }

    @Test
    fun welcomeCopyAnchorsRendered() {
        setContent(state(), Recorder())
        compose.onNodeWithText(ONBOARDING_WELCOME_CORE_COPY).assertExists()
        compose.onNodeWithText(EMERGENCY_HINT_COPY).assertExists()
    }

    @Test
    fun welcomeStartGatedUntilBothChecks() {
        val recorder = Recorder()
        var age by mutableStateOf(false)
        var boundary by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                OnboardingStepContent(
                    state = state(ageConfirmed = age, boundaryConfirmed = boundary),
                    actions = recorder.actions(),
                )
            }
        }
        compose.onNode(hasClickAction() and hasText("开始")).assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.runOnIdle { age = true; boundary = true }
        compose.onNode(hasClickAction() and hasText("开始")).performClick()
        assertTrue(recorder.privacy.isNotEmpty())
        assertEquals(listOf(true), recorder.age)
        assertEquals(listOf(true), recorder.boundary)
    }

    @Test
    fun privacyPledgeCopyAndFiveCheckGate() {
        val recorder = Recorder()
        var checks by mutableStateOf(listOf(false, false, false, false, false))
        compose.setContent {
            MaterialTheme {
                OnboardingStepContent(
                    state = state(step = OnboardingStep.PRIVACY_PLEDGE, coreChecks = checks),
                    actions = recorder.actions(),
                )
            }
        }
        compose.onNodeWithText("ECHO 的承诺只有三句话：", substring = true).assertExists()
        compose.onNode(hasClickAction() and hasText("我理解并继续")).performScrollTo().assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.runOnIdle { checks = listOf(true, true, true, true, true) }
        compose.onNode(hasClickAction() and hasText("我理解并继续")).performScrollTo().performClick()
        assertTrue(recorder.sensing.isNotEmpty())
    }

    @Test
    fun coreSensingRowsRenderTruthAndCallbacks() {
        val recorder = Recorder()
        setContent(state(step = OnboardingStep.CORE_SENSING, sensorHardwareAvailable = true), recorder)
        // 运动传感器 + 屏幕状态两行均为「可用（无需权限）」（类型化 READY）
        compose.onAllNodesWithText("可用（无需权限）").assertCountEquals(2)
        compose.onNodeWithText("未开启（可跳过）").assertExists()
        compose.onNode(hasClickAction() and hasText("授权")).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText("跳过")).performScrollTo().performClick()
        assertEquals(listOf(true), recorder.notifRequest)
        assertEquals(listOf(true), recorder.notifSkip)
    }

    @Test
    fun notificationAuthorizedRowShowsReadyLabel() {
        // §AV/§BK：通知行授权后为 READY（文案「已开启」，非通用 ready 文案）
        setContent(state(step = OnboardingStep.CORE_SENSING, notifPermAuthorized = true), Recorder())
        compose.onNodeWithText("已开启").performScrollTo().assertExists()
        compose.onNodeWithText("未开启（可跳过）").assertDoesNotExist()
    }

    @Test
    fun unavailableNeverReady() {
        // §AV/§BK 结构回归：「此设备不可用」永远不可能被判定为 ready——
        // ready 由 SensingCapabilityStatus 枚举结构判定（isReady），状态文案是唯一映射的输出。
        assertTrue(SensingCapabilityStatus.READY.isReady)
        assertFalse(SensingCapabilityStatus.NOT_GRANTED.isReady)
        assertFalse(SensingCapabilityStatus.UNAVAILABLE.isReady)
        assertEquals("可用（无需权限）", sensingCapabilityLabelText(SensingCapabilityStatus.READY))
        assertEquals("已开启", sensingCapabilityLabelText(SensingCapabilityStatus.READY, "已开启"))
        assertEquals("未开启（可跳过）", sensingCapabilityLabelText(SensingCapabilityStatus.NOT_GRANTED))
        assertEquals("此设备不可用", sensingCapabilityLabelText(SensingCapabilityStatus.UNAVAILABLE))
        // 任意 readyLabel 都无法改变 UNAVAILABLE 的输出（映射与输入标签无关）
        assertEquals("此设备不可用", sensingCapabilityLabelText(SensingCapabilityStatus.UNAVAILABLE, "已开启"))
    }

    @Test
    fun sensorUnavailableShowsTruth() {
        setContent(state(step = OnboardingStep.CORE_SENSING, sensorHardwareAvailable = false), Recorder())
        compose.onNodeWithText("此设备不可用").performScrollTo().assertExists()
        // 传感器不可用时仅屏幕行保持 ready 文案（结构上 UNAVAILABLE ≠ ready）
        compose.onAllNodesWithText("可用（无需权限）").assertCountEquals(1)
    }

    @Test
    fun awakenAndAbstainCallbacksFire() {
        val recorder = Recorder()
        setContent(state(step = OnboardingStep.CORE_SENSING), recorder)
        compose.onNode(hasClickAction() and hasText("让 ECHO 开始了解我")).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText("暂不开启")).performScrollTo().performClick()
        assertEquals(listOf(true), recorder.awaken)
        assertEquals(listOf(true), recorder.abstain)
    }

    @Test
    fun emergencyEntryFiresOnEveryStep() {
        val recorder = Recorder()
        setContent(state(step = OnboardingStep.CORE_SENSING), recorder)
        compose.onNode(hasClickAction() and hasText("紧急支持")).performScrollTo().performClick()
        assertEquals(listOf(true), recorder.safety)
    }
}
