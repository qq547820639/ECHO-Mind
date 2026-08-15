package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 77 §75/§79/§80 — Personal Correction 环路收官链锚点：
 * 纠错记忆 → 证据映射（USER_CORRECTIONS + SENSITIVE + 「你纠正过我的」）
 * → ContextRanker 头位 → EchoContextCompiler 注入（模型必须看到用户纠正）。
 */
class CorrectionLoopChainTest {

    private fun correctionMemory(content: String = "用户纠正：不是熬夜，是出差。") = EchoMemory(
        id = "mem_corr",
        userId = "u",
        type = MemoryType.CORRECTION,
        content = content,
        source = "user-feedback",
        confidence = 1f,
        createdAt = 1000L,
        lastConfirmedAt = 1000L,
        importance = 90,
        retentionClass = RetentionClass.LONG_TERM,
        provenance = "user-correction:v1",
    )

    private fun observationEvidence() = EvidenceItem(
        category = DataSourceCategory.PORTRAIT_HISTORY,
        label = "历史画像",
        text = "最近一周的屏幕使用偏高。",
        type = "observation",
        confidence = 0.95f,
    )

    @Test
    fun correctionMemoryMapsToUserCorrectionsSensitiveEvidence() {
        val evidence = EvidenceAssembler.fromMemories(listOf(correctionMemory())).single()
        assertEquals("correction", evidence.type)
        assertEquals(DataSourceCategory.USER_CORRECTIONS, evidence.category)
        assertEquals(EvidenceSensitivity.SENSITIVE, evidence.sensitivity)
        assertEquals("你纠正过我", evidence.label)
        assertEquals(1f, evidence.confidence)
        assertEquals("用户纠正：不是熬夜，是出差。", evidence.text)
    }

    @Test
    fun correctionOutranksHighConfidenceObservationInRanker() {
        val correction = EvidenceAssembler.fromMemories(listOf(correctionMemory())).single()
        val ranked = ContextRanker.rank(
            ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
            listOf(observationEvidence(), correction),
        )
        assertEquals("纠正永远最高", DataSourceCategory.USER_CORRECTIONS, ranked.first().item.category)
    }

    @Test
    fun compiledContextContainsCorrectionText() {
        val correction = EvidenceAssembler.fromMemories(listOf(correctionMemory())).single()
        val compiled = EchoContextCompiler.compile(
            task = ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            evidence = listOf(observationEvidence(), correction),
            question = "为什么最近不一样？",
        )
        assertTrue("编译上下文必须包含用户纠正内容", compiled.userContent.contains("用户纠正：不是熬夜，是出差。"))
        assertTrue("编译上下文必须包含纠正来源标签", compiled.userContent.contains("你纠正过我"))
        assertTrue(compiled.usedSources.contains(DataSourceCategory.USER_CORRECTIONS))
    }
}
