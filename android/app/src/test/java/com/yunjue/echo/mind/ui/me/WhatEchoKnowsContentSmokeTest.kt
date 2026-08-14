package com.yunjue.echo.mind.ui.me

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yunjue.echo.mind.me.MemoryManagementEvent
import com.yunjue.echo.mind.me.MemoryManagementUiState
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.ui.Page
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 33 — What ECHO Knows 纯状态内容 smoke test（Robolectric + Compose）：
 * 空态提示 / 七分类行渲染 / 确认·忘记事件 / 类别过滤事件 /
 * 特殊时期对话框（种类选择 + 保存）/ 低置信临时解释标注。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatEchoKnowsContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun memory(
        id: String,
        type: MemoryType,
        content: String,
        confidence: Float = 0.8f,
    ) = EchoMemory(
        id = id,
        userId = "u1",
        type = type,
        content = content,
        source = "test",
        confidence = confidence,
        createdAt = 1L,
        lastConfirmedAt = 0L,
        importance = 50,
        retentionClass = RetentionClass.LONG_TERM,
        provenance = "test",
    )

    private fun setContent(
        state: MemoryManagementUiState,
        events: MutableList<MemoryManagementEvent>,
    ) {
        compose.setContent {
            MaterialTheme {
                Page("Me · 我的控制权") {
                    WhatEchoKnowsContent(state = state, onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun emptyStateShowsHint() {
        setContent(MemoryManagementUiState(), mutableListOf())
        compose.onNodeWithText("还没有长期记忆。ECHO 正在慢慢认识你。").assertExists()
    }

    @Test
    fun memoryRowRendersLabelContentAndActions() {
        val events = mutableListOf<MemoryManagementEvent>()
        setContent(
            MemoryManagementUiState(
                memories = listOf(memory("m1", MemoryType.OBSERVATION, "你通常在 23:30 左右入睡。"))
            ),
            events,
        )
        // 「观察到的事实」同时是过滤 chip 与记忆行标签（两处，语义一致）
        compose.onAllNodesWithText("观察到的事实").assertCountEquals(2)
        compose.onNodeWithText("你通常在 23:30 左右入睡。").performScrollTo().assertExists()
        compose.onNode(hasClickAction() and hasText("确认")).performScrollTo().performClick()
        assertTrue(events.contains(MemoryManagementEvent.Confirm("m1")))
        compose.onNode(hasClickAction() and hasText("忘记")).performScrollTo().performClick()
        assertTrue(events.contains(MemoryManagementEvent.Forget("m1")))
    }

    @Test
    fun editFlowEmitsEditEvent() {
        val events = mutableListOf<MemoryManagementEvent>()
        setContent(
            MemoryManagementUiState(
                memories = listOf(memory("m1", MemoryType.PREFERENCE, "喜欢安静的视觉"))
            ),
            events,
        )
        compose.onNode(hasClickAction() and hasText("编辑")).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText("保存")).performScrollTo().performClick()
        assertTrue(events.contains(MemoryManagementEvent.Edit("m1", "喜欢安静的视觉")))
    }

    @Test
    fun filterChipEmitsSetFilter() {
        val events = mutableListOf<MemoryManagementEvent>()
        setContent(MemoryManagementUiState(), events)
        compose.onNode(hasClickAction() and hasText("你纠正过我的")).performClick()
        assertTrue(events.contains(MemoryManagementEvent.SetFilter(MemoryType.CORRECTION)))
    }

    @Test
    fun contextExceptionDialogSelectKindAndSave() {
        val events = mutableListOf<MemoryManagementEvent>()
        setContent(MemoryManagementUiState(), events)
        compose.onNode(hasClickAction() and hasText("告诉 ECHO 一个特殊时期（如出差、考试周）"))
            .performScrollTo().performClick()
        compose.onNodeWithText("告诉 ECHO 一个特殊时期").assertExists()
        compose.onNode(hasClickAction() and hasText("考试周")).performClick()
        compose.onNode(hasClickAction() and hasText("保存")).performClick()
        assertTrue(events.contains(MemoryManagementEvent.AddContextException("考试周", "")))
    }

    @Test
    fun lowConfidenceTemporaryInterpretationMarkedUncertain() {
        setContent(
            MemoryManagementUiState(
                memories = listOf(
                    memory("m1", MemoryType.TEMPORARY_INTERPRETATION, "最近似乎更晚睡", confidence = 0.3f)
                )
            ),
            mutableListOf(),
        )
        compose.onNodeWithText("临时解释（还不确定）").performScrollTo().assertExists()
    }

    @Test
    fun nonEmptyMemoryHidesEmptyHint() {
        setContent(
            MemoryManagementUiState(memories = listOf(memory("m1", MemoryType.CONTEXT, "最近在出差"))),
            mutableListOf(),
        )
        compose.onNodeWithText("还没有长期记忆。ECHO 正在慢慢认识你。").assertDoesNotExist()
    }
}
