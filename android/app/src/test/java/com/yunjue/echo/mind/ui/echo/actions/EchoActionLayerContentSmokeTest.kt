package com.yunjue.echo.mind.ui.echo.actions

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yunjue.echo.mind.actions.EchoActionAvailability
import com.yunjue.echo.mind.actions.EchoActionKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 38 — EchoActionLayerContent smoke test：
 * L2 建议门禁 / 呼吸·暂停行动事件 / 「什么也不做」折叠语义 / 订阅能力槽位展开收起。
 * ERA 31 R29：默认折叠——Scene 常驻的只有安静的「想做点什么？」入口（§10）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoActionLayerContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        availability: EchoActionAvailability = EchoActionAvailability(),
        onStartAction: (EchoActionKind) -> Unit = {},
        skillsSection: @Composable () -> Unit = { Text("slot-skills") },
    ) {
        compose.setContent {
            MaterialTheme {
                EchoActionLayerContent(
                    availability = availability,
                    onStartAction = onStartAction,
                    skillsSection = skillsSection,
                )
            }
        }
    }

    private fun expandActions() {
        compose.onNode(hasClickAction() and hasText("想做点什么？")).performClick()
    }

    @Test
    fun collapsedByDefaultOnlyShowsQuietEntry() {
        setContent()
        compose.onNodeWithText("想做点什么？").assertExists()
        // 默认不出现按钮墙：呼吸/暂停/订阅槽位都藏在展开区内
        compose.onNodeWithText("1 分钟呼吸").assertDoesNotExist()
        compose.onNodeWithText("短暂离开屏幕").assertDoesNotExist()
        compose.onNodeWithText("更多能力（订阅）").assertDoesNotExist()
        compose.onNodeWithText("什么也不做").assertDoesNotExist()
    }

    @Test
    fun suggestedHintGatedByPolicyAndExpansion() {
        setContent(EchoActionAvailability(suggested = true))
        // 折叠时不显示（L2 = 打开时建议）
        compose.onNodeWithText("从今天的数据看，让自己慢一点可能有帮助。").assertDoesNotExist()
        expandActions()
        compose.onNodeWithText("从今天的数据看，让自己慢一点可能有帮助。").assertExists()
    }

    @Test
    fun noHintWhenNotSuggested() {
        setContent(EchoActionAvailability(suggested = false))
        expandActions()
        compose.onNodeWithText("从今天的数据看，让自己慢一点可能有帮助。").assertDoesNotExist()
    }

    @Test
    fun breathingAndPauseEmitStartAction() {
        val started = mutableListOf<EchoActionKind>()
        setContent(onStartAction = { started += it })
        expandActions()
        compose.onNode(hasClickAction() and hasText("1 分钟呼吸")).performClick()
        compose.onNode(hasClickAction() and hasText("短暂离开屏幕")).performClick()
        assertEquals(listOf(EchoActionKind.BREATHING, EchoActionKind.PAUSE), started)
    }

    @Test
    fun skillsSectionTogglesAndDoNothingCollapses() {
        setContent()
        compose.onNodeWithText("slot-skills").assertDoesNotExist()
        expandActions()
        compose.onNode(hasClickAction() and hasText("更多能力（订阅）")).performClick()
        compose.onNodeWithText("slot-skills").assertExists()
        compose.onNodeWithText("收起更多能力（订阅）").assertExists()
        // 「什么也不做」永远是合法选项：收起整个行动区（含订阅分区）
        compose.onNode(hasClickAction() and hasText("什么也不做")).performClick()
        compose.onNodeWithText("slot-skills").assertDoesNotExist()
        compose.onNodeWithText("1 分钟呼吸").assertDoesNotExist()
    }
}
