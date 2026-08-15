package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.model.MemorySensitivity
import com.yunjue.echo.mind.model.RetentionClass
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 32 R03 — EvidenceAssembler 人话化回归：
 * 内部存储格式（画像反馈/问答反馈/特殊时期）不得进入 AI Provider 上下文。
 * 与确定性路径（DeterministicPersonalAnswerProvider，R37）同源人话化。
 */
class EvidenceAssemblerHumanizeTest {

    private fun memory(type: MemoryType, content: String) = EchoMemory(
        id = "m1",
        userId = "u1",
        type = type,
        content = content,
        source = "user-feedback",
        confidence = 1f,
        createdAt = 1_000L,
        lastConfirmedAt = 0L,
        importance = 70,
        retentionClass = RetentionClass.SHORT_TERM,
        provenance = "context-from-correction:v1",
        deleted = false,
        sensitivity = if (type == MemoryType.CORRECTION) MemorySensitivity.SENSITIVE else MemorySensitivity.PERSONAL,
    )

    @Test
    fun correctionContentIsHumanizedForProvider() {
        val item = EvidenceAssembler.fromMemories(
            listOf(memory(MemoryType.CORRECTION, "画像反馈：不太像（原因：旅行）（原判断：最近明显变晚）")),
        ).single()
        assertTrue("内部格式不得进 Provider：${item.text}", !item.text.contains("画像反馈"))
        assertTrue("人话回放：${item.text}", item.text.contains("旅行") && item.text.contains("当时我说的是"))
    }

    @Test
    fun confirmedContentIsHumanizedForProvider() {
        val item = EvidenceAssembler.fromMemories(
            listOf(memory(MemoryType.USER_CONFIRMED, "问答反馈：像我（问：最近我是不是越来越晚？）")),
        ).single()
        assertTrue("内部格式不得进 Provider：${item.text}", !item.text.contains("问答反馈"))
        assertTrue("人话回放：${item.text}", item.text.contains("问「最近我是不是越来越晚？」的回答"))
    }

    @Test
    fun contextContentIsHumanizedForProvider() {
        val item = EvidenceAssembler.fromMemories(
            listOf(memory(MemoryType.CONTEXT, "特殊时期：旅行（见客户）@2026-01-25")),
        ).single()
        assertTrue("内部格式不得进 Provider：${item.text}", !item.text.contains("特殊时期"))
        assertTrue("人话上下文：${item.text}", item.text.contains("旅行") && item.text.contains("从 2026-01-25 开始"))
        assertTrue("类别正确：${item.category}", item.category == DataSourceCategory.CONTEXT_EXCEPTIONS)
    }
}
