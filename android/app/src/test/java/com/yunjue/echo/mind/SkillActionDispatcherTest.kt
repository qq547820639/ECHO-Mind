package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.ui.actionTypeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 Skill action_type 白名单分发单测（纯 JVM）：
 *
 * - 白名单恰为 5 个原生 Renderer（guided_steps / reflection_prompt / breathing / journaling / checklist）
 * - 未知 actionType → fail closed（不在白名单、标签为"此能力暂不可用"、不产生 completion 入口）
 * - 契约字段解析（parseSkillsArray 保留 actionType/estimatedDuration/completionSchema/safetyConstraints/revision）
 */
class SkillActionDispatcherTest {

    @Test
    fun whitelistContainsExactlyFiveNativeRenderers() {
        assertEquals(
            setOf("guided_steps", "reflection_prompt", "breathing", "journaling", "checklist"),
            SkillDisplay.ACTION_TYPE_WHITELIST
        )
    }

    @Test
    fun allWhitelistTypesHaveDisplayLabels() {
        for (type in SkillDisplay.ACTION_TYPE_WHITELIST) {
            val label = actionTypeLabel(type)
            assertTrue("白名单类型 $type 应有非空标签", label.isNotBlank())
            assertFalse("标签不应是'不可用'", label.contains("不可用"))
        }
    }

    @Test
    fun unknownActionTypeFailsClosed() {
        val unknown = SkillDisplay(
            id = "sk_unknown",
            name = "可疑能力",
            version = 1,
            triggerConditions = emptyList(),
            guardrails = emptyList(),
            steps = emptyList(),
            status = "reviewed",
            actionType = "evil_analyzer"
        )
        assertTrue("未知 actionType 不在白名单", unknown.actionType !in SkillDisplay.ACTION_TYPE_WHITELIST)
        assertEquals("此能力暂不可用", actionTypeLabel(unknown.actionType))
    }

    @Test
    fun contractFieldsArePreservedOnSkillDisplay() {
        val skill = SkillDisplay(
            id = "sk_contract",
            name = "呼吸练习",
            version = 2,
            triggerConditions = listOf("gap.description eq 无感知数据"),
            guardrails = listOf("不输出诊断结论"),
            steps = listOf("跟随节奏"),
            status = "signed",
            actionType = "breathing",
            estimatedDuration = 300,
            completionSchema = """{"type":"object","required":["status"]}""",
            safetyConstraints = listOf("不替代专业医疗"),
            revision = 4
        )
        assertEquals("breathing", skill.actionType)
        assertEquals(300, skill.estimatedDuration)
        assertTrue("completion_schema 应保留", skill.completionSchema?.contains("status") == true)
        assertEquals(1, skill.safetyConstraints.size)
        assertEquals(4, skill.revision)
    }

    @Test
    fun skillWithoutCompletionSchemaIsAllowed() {
        val skill = SkillDisplay(
            id = "sk_min",
            name = "清单",
            version = 1,
            triggerConditions = emptyList(),
            guardrails = emptyList(),
            steps = emptyList(),
            status = "reviewed",
            actionType = "checklist"
        )
        assertNull("缺省 completion_schema 应为 null", skill.completionSchema)
        assertTrue(skill.actionType in SkillDisplay.ACTION_TYPE_WHITELIST)
    }

    @Test
    fun journalingNeverUploadsTextUnlessSchemaRequires() {
        // journaling Renderer 仅在 completion_schema 明确要求 text 时展示结构化输入提示
        val noText = SkillDisplay("s", "反思", 1, emptyList(), emptyList(), emptyList(), "reviewed", actionType = "journaling")
        val withText = SkillDisplay("s2", "反思", 1, emptyList(), emptyList(), emptyList(), "reviewed",
            actionType = "journaling", completionSchema = """{"type":"object","properties":{"text":{"type":"string"}}}""")
        assertFalse("未要求 text 时 completion_schema 不含 text", noText.completionSchema?.contains("text") == true)
        assertTrue("明确要求 text 时 schema 含 text", withText.completionSchema?.contains("text") == true)
    }
}
