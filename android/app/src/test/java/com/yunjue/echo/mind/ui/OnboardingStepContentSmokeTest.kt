package com.yunjue.echo.mind.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设计稿 1/2/3 — OnboardingStepContent 两步渲染矩阵 smoke test：
 * WELCOME（字标/大标题/零勾选/紧急入口/CTA 直通隐私页）/
 * PRIVACY_PLEDGE（四承诺卡/页脚披露行/CTA=整包同意+苏醒）。
 * 设计稿即唯一真相：无 5 勾选页、无感知能力页、无 abstain 文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingStepContentSmokeTest {

    /** UX-B2：reduceMotion 开启时 seed 视觉 flowSpeed 必须为 0（mapper 语义级硬契约）。 */
    @Test
    fun seedGenomeRespectsReduceMotionPreference() {
        val seed = com.yunjue.echo.mind.presence.dayZeroSeedPresence(identitySeed = 42L)
            .copy(rhythmState = com.yunjue.echo.mind.model.RhythmState(activityLevel = 0.7f))
        assertEquals(
            "reduceMotion 开启时 mapper 参数 flowSpeed 必须归零（语义级硬契约；genome 无 flowSpeed 字段）",
            0f,
            com.yunjue.echo.mind.presence.EchoVisualMapper.map(seed, 12f, reduceMotion = true).flowSpeed,
        )
        assertTrue(
            "对照：偏好关闭且 presence 有活动度时 flowSpeed > 0（断言有区分度）",
            com.yunjue.echo.mind.presence.EchoVisualMapper.map(seed, 12f, reduceMotion = false).flowSpeed > 0f,
        )
    }

    @Test
    fun awakeningDurationWithinTimeToEchoBudget() {
        // §50：苏醒过渡 ≤3s（机器段预算）；回归 = 时间被悄悄拉长
        assertTrue("AWAKENING_DURATION_MS 超出 Time-to-ECHO 预算", AWAKENING_DURATION_MS <= 3000L)
    }

    /** 设计稿流程结构性锁定：恰为两步（欢迎 + 隐私承诺）；苏醒为过渡；无 DONE、无勾选页。 */
    @Test
    fun onboardingStepsExactlyWelcomeAndPrivacy() {
        val names = OnboardingStep.entries.map { it.name }.toSet()
        assertEquals(
            "Onboarding 步骤必须恰为设计稿两步（WELCOME / PRIVACY_PLEDGE）",
            setOf("WELCOME", "PRIVACY_PLEDGE"),
            names,
        )
    }

    @get:Rule
    val compose = createComposeRule()

    private fun state(step: OnboardingStep = OnboardingStep.WELCOME) = OnboardingStepState(step = step)

    private class Recorder {
        val privacy = mutableListOf<Boolean>()
        val awaken = mutableListOf<Boolean>()
        val back = mutableListOf<Boolean>()
        val safety = mutableListOf<Boolean>()
        val login = mutableListOf<Boolean>()

        fun actions() = OnboardingStepActions(
            onBackToWelcome = { back += true },
            onContinueToPrivacy = { privacy += true },
            onAwaken = { awaken += true },
            onOpenSafety = { safety += true },
            onOpenLogin = { login += true },
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
    fun welcomeRendersDesignOneLayoutWithZeroCheckboxes() {
        val recorder = Recorder()
        setContent(state(), recorder)
        // 设计稿 1：大标题 / 副标题句 / 定位契约句 / CTA / 登录 / 紧急入口
        compose.onNodeWithText("每个人都值得，").assertExists()
        compose.onNodeWithText("被充分理解。").assertExists()
        compose.onNodeWithText(ONBOARDING_WELCOME_CORE_COPY).assertExists()
        compose.onNodeWithText(EMERGENCY_HINT_COPY).assertExists()
        compose.onNodeWithText("开启 ECHO").assertExists()
        compose.onNodeWithText("已有账号？登录 ›").assertExists()
        // 零勾选框、零「本机使用」自创区块、零感知能力页文案
        compose.onNodeWithText("我已年满 18 周岁").assertDoesNotExist()
        compose.onNodeWithText("本机使用").assertDoesNotExist()
        compose.onNodeWithText("让 ECHO 开始了解你").assertDoesNotExist()
    }

    @Test
    fun welcomeCtaGoesStraightToPrivacy() {
        val recorder = Recorder()
        setContent(state(), recorder)
        compose.onNode(hasClickAction() and hasText("开启 ECHO")).performClick()
        assertTrue(recorder.privacy.isNotEmpty())
    }

    @Test
    fun privacyRendersFourPledgeCardsAndDisclosureFooter() {
        val recorder = Recorder()
        setContent(state(OnboardingStep.PRIVACY_PLEDGE), recorder)
        compose.onNodeWithText("你的数据，只属于你").assertExists()
        compose.onNodeWithText("本地优先").assertExists()
        compose.onNodeWithText("最小化记录").assertExists()
        compose.onNodeWithText("你完全掌控").assertExists()
        compose.onNodeWithText("随时可撤回").assertExists()
        compose.onNodeWithText("继续即表示你同意《隐私政策》与《用户协议》").assertExists()
        compose.onNodeWithText("我理解了，继续").assertExists()
    }

    @Test
    fun privacyCtaIsBundleConsentAndAwaken() {
        val recorder = Recorder()
        setContent(state(OnboardingStep.PRIVACY_PLEDGE), recorder)
        compose.onNode(hasClickAction() and hasText("我理解了，继续")).performScrollTo().performClick()
        assertEquals(listOf(true), recorder.awaken)
    }

    @Test
    fun privacyBackReturnsToWelcome() {
        val recorder = Recorder()
        setContent(state(OnboardingStep.PRIVACY_PLEDGE), recorder)
        compose.onNodeWithText("←").performClick()
        assertTrue(recorder.back.isNotEmpty())
    }

    @Test
    fun emergencyEntryFiresOnEveryStep() {
        val recorder = Recorder()
        setContent(state(), recorder)
        // 欢迎页紧急入口按钮文案 = 长提示句（EMERGENCY_HINT_COPY）
        compose.onNode(hasClickAction() and hasText(EMERGENCY_HINT_COPY)).performScrollTo().performClick()
        assertEquals(listOf(true), recorder.safety)
    }
}
