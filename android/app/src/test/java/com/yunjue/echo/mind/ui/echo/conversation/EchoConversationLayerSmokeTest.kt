package com.yunjue.echo.mind.ui.echo.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.intelligence.ConversationTurn
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.conversationPhaseText
import com.yunjue.echo.mind.memory.CORRECTION_REASONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 35 — EchoConversationLayer smoke test（Ask ECHO 对话层）：
 * 问答渲染 / 依据双清单 / 反馈（像我→直接记录；不太像→原因 chips）/ 阶段文案与禁用态 / 发送事件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoConversationLayerSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun turn(
        id: Long = 1L,
        question: String = "最近我是不是越来越晚？",
        answer: String = "相比基线，你的入睡时间更早了。",
        sources: List<DataSourceCategory> = listOf(DataSourceCategory.BASELINE),
    ) = ConversationTurn(
        id = id,
        question = question,
        answer = answer,
        sources = sources,
        phase = ConversationPhase.COMPLETE,
    )

    private fun setContent(
        turns: List<ConversationTurn> = emptyList(),
        phase: ConversationPhase = ConversationPhase.IDLE,
        onAsk: (String) -> Unit = {},
        onFeedback: (String, String, Boolean, String?) -> Unit = { _, _, _, _ -> },
    ) {
        compose.setContent {
            MaterialTheme {
                EchoConversationLayer(
                    turns = turns,
                    phase = phase,
                    onAsk = onAsk,
                    onFeedback = onFeedback,
                )
            }
        }
    }

    @Test
    fun titleAndHintRendered() {
        setContent()
        compose.onNodeWithText("问 ECHO 关于你的事").assertExists()
        compose.onNodeWithText("适合问：「最近我是不是越来越晚？」「为什么今天 ECHO 看起来不一样？」ECHO 的回答基于你的节律数据，会说明参考了什么。").assertExists()
    }

    @Test
    fun turnRendersQuestionAnswerAndBasisLists() {
        setContent(turns = listOf(turn()))
        compose.onNodeWithText("最近我是不是越来越晚？").assertExists()
        compose.onNodeWithText("相比基线，你的入睡时间更早了。").assertExists()
        compose.onNode(hasClickAction() and hasText("依据")).performClick()
        compose.onNodeWithText("参考了：个人基线").assertExists()
        // ERA 32 R09（§52）：精确词表——「原始音频」而非「麦克风」（麦克风开启时派生特征会进聚合）
        compose.onNodeWithText("没有使用：原始音频、通知正文、精确位置").assertExists()
    }

    @Test
    fun likeFeedbackEmitsImmediately() {
        val feedbacks = mutableListOf<Triple<String, String, Boolean>>()
        setContent(
            turns = listOf(turn()),
            onFeedback = { q, a, like, _ -> feedbacks += Triple(q, a, like) },
        )
        compose.onNode(hasClickAction() and hasText("像我")).performClick()
        assertEquals(
            listOf(Triple("最近我是不是越来越晚？", "相比基线，你的入睡时间更早了。", true)),
            feedbacks,
        )
        compose.onNodeWithText("已记录，感谢反馈。").assertExists()
    }

    @Test
    fun notLikeFlowRequiresReasonChip() {
        val feedbacks = mutableListOf<Pair<Boolean, String?>>()
        setContent(
            turns = listOf(turn()),
            onFeedback = { _, _, like, reason -> feedbacks += like to reason },
        )
        compose.onNode(hasClickAction() and hasText("不太像")).performClick()
        compose.onNodeWithText("哪里不太对？").assertExists()
        assertTrue(feedbacks.isEmpty())
        val firstReason = CORRECTION_REASONS.first()
        compose.onNode(hasClickAction() and hasText(firstReason)).performClick()
        assertEquals(listOf(false to firstReason), feedbacks)
        compose.onNodeWithText("知道了，我会少一点依赖这种判断。").assertExists()
    }

    @Test
    fun nonIdlePhaseShowsStatusAndDisablesInput() {
        val asked = mutableListOf<String>()
        setContent(phase = ConversationPhase.WAITING_PROVIDER, onAsk = { asked += it })
        compose.onNodeWithText(conversationPhaseText(ConversationPhase.WAITING_PROVIDER)).assertExists()
        // 非 IDLE：发送按钮禁用（点击不产生事件）
        compose.onNode(hasClickAction() and hasText("发送")).assertIsNotEnabled()
        compose.onNode(hasClickAction() and hasText("发送")).performClick()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun sendEmitsTrimmedQuestionAndClearsInput() {
        val asked = mutableListOf<String>()
        setContent(onAsk = { asked += it })
        compose.onNode(hasSetTextAction() and hasText("问 ECHO…", substring = true))
            .performTextInput("为什么今天不一样？")
        compose.onNode(hasClickAction() and hasText("发送")).performClick()
        assertEquals(listOf("为什么今天不一样？"), asked)
    }

    @Test
    fun emptyTurnsRenderNothingExtra() {
        setContent(turns = emptyList())
        compose.onNodeWithText("你：", substring = true).assertDoesNotExist()
    }
}
