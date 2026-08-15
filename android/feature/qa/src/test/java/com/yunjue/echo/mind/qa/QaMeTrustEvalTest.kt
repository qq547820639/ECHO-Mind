package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.buildSelfModel
import com.yunjue.echo.mind.memory.echoKnowsLines
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 25（Batch 5）— Me / Trust Experience 状态层 eval：
 * - §45 What ECHO Knows 自然语言（无工程标识符泄漏）；
 * - §45 fixture 驱动（B 半年漂移 → 「我观察到…」级别的一行了解）；
 * - §37/§46 敏感纠正不出现在公开 knowsLines（信任边界）。
 */
class QaMeTrustEvalTest {

    private val ENGINEERING_TOKENS = listOf(
        "baseline_activation_start", "active_start_minute", "movement_index",
        "screen_on_minutes", "z-score", "z_score", "metric", "p25", "p75", "mad",
    )

    private val t0 = 1_700_000_000_000L

    private fun memory(id: String, type: MemoryType, content: String) = EchoMemory(
        id = id, userId = "qa-user", type = type, content = content, source = "fixture",
        confidence = 0.8f, createdAt = t0, lastConfirmedAt = t0, importance = 40,
        retentionClass = RetentionClass.LONG_TERM, provenance = "fixture:v1",
    )

    @Test
    fun knowsLinesNeverLeakEngineeringIdentifiers() {
        val memories = listOf(
            memory("o1", MemoryType.OBSERVATION, "工作日晚间结束时间持续后移"),
            memory("o2", MemoryType.OBSERVATION, "工作日晚间结束时间持续后移"),
            memory("o3", MemoryType.OBSERVATION, "工作日晚间结束时间持续后移"),
            memory("c1", MemoryType.CONTEXT, "特殊时期：travel（出差）@2026-03-06"),
        )
        val lines = echoKnowsLines(buildSelfModel(memories, t0))
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            for (token in ENGINEERING_TOKENS) {
                assertTrue("knowsLines 不得含工程标识符 $token（实际：$line）", token !in line)
            }
        }
    }

    @Test
    fun fixtureDrivenKnowsLinesArePersonalAndNatural() {
        // B（夜猫+漂移）的自我模型 → 自然语言了解行
        val profile = QaProfiles.B_NIGHT_OWL
        val timeline = QaTimeline(profile)
        val observations = listOf(
            memory("o1", MemoryType.OBSERVATION, "活跃起点通常在中午前后"),
            memory("o2", MemoryType.OBSERVATION, "活跃起点通常在中午前后"),
            memory("o3", MemoryType.OBSERVATION, "活跃起点通常在中午前后"),
            memory("o4", MemoryType.OBSERVATION, "活跃起点通常在中午前后"),
        )
        val context = memory(
            "c1", MemoryType.CONTEXT,
            "特殊时期：work_crunch（项目冲刺）@${timeline.dateOf(60)}",
        )
        val lines = echoKnowsLines(buildSelfModel(observations + context, t0))
        println("B knows: $lines")
        assertTrue("包含观察行", lines.any { it.startsWith("我观察到：") })
        assertTrue("包含上下文行（工作紧张期）", lines.any { it.contains("工作紧张期") })
        assertTrue("全部为自然语言（≤6 行）", lines.size in 1..6)
    }

    @Test
    fun sensitiveCorrectionsStayOutOfPublicKnowsLines() {
        val memories = listOf(
            memory("c1", MemoryType.CORRECTION, "别把我最近的晚睡当成常态，我在陪护家人"),
            memory("c2", MemoryType.CONTEXT, "特殊时期：user_defined（陪护家人）@2026-02-01"),
        )
        val model = buildSelfModel(memories, t0)
        assertTrue("纠正进入 corrections 域", model.corrections.single().contains("陪护家人"))
        val lines = echoKnowsLines(model)
        assertTrue("公开行非空（上下文行在列）", lines.isNotEmpty())
        assertTrue("公开行不含纠正原文（陪护家人）", lines.none { it.contains("陪护家人") })
    }
}
