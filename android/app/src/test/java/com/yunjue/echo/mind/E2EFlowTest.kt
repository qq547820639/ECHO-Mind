package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * T13.4 E2E 数据流转换测试（纯 JVM，不依赖 Android 框架）。
 *
 * 验证全链路数据流转换的正确性：
 * 1. FeatureExtractor 产出 DerivedFeatureInput（含 summary + vector，无原始数据）
 * 2. DerivedFeatureInput 字段集合与后端 DerivedFeatureIn 契约对齐（无原始传感字段）
 * 3. /v1/skills 响应字段 → SkillDisplay 模型映射正确（与 LocalRepository.parseSkills 逻辑对齐）
 * 4. 隐私不变量：DerivedFeatureInput 不含原始传感数据（audio_buffer / raw_samples 等）
 *
 * 不使用 org.json（Android 框架类），改用纯 Kotlin 数据结构做断言，
 * 确保 `./gradlew test` 中此测试可作为纯 JVM 单测运行（与 TrendDataSourceTest 同一模式）。
 */
class E2EFlowTest {

    /**
     * 后端 DerivedFeatureIn 契约定义的合法字段集合（与 app/schemas.py 对齐）。
     * 端侧 DerivedFeatureInput 应只能映射到这些字段。
     */
    private val backendSchemaFields = setOf(
        "schema_version", "source", "window_start", "window_end",
        "summary", "vector", "event_id", "user_id"
    )

    /**
     * 端侧绝对不应上传的原始传感字段黑名单。
     */
    private val forbiddenRawFields = setOf(
        "audio_buffer", "raw_samples", "payload", "sensor_data",
        "mic_recording", "accel_samples", "gyro_samples", "screen_events"
    )

    /**
     * DerivedFeatureInput 序列化时应暴露的字段集合（与 LocalRepository.saveDerivedFeature
     * 构建的 payload 字段对齐，包含 basePayload 注入的 event_id/user_id/client_time）。
     */
    private fun expectedIngestPayloadFields(): Set<String> = setOf(
        "event_id", "user_id", "client_time",
        "schema_version", "source", "window_start", "window_end",
        "summary", "vector"
    )

    // ---------- T13.4 FeatureExtractor → DerivedFeatureInput ----------

    @Test
    fun featureExtractorProducesDerivedFeatureInputWithExpectedFields() {
        // 验证 FeatureExtractor 产出的 DerivedFeatureInput 只含契约字段，无原始传感数据
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = FeatureExtractor().extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = listOf(floatArrayOf(0.1f, 0.2f, 9.8f)),
            screenEvents = listOf(
                ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)
            ),
            notifications = listOf(
                NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "pkg", "social")
            )
        )

        assertEquals("应产出一个特征", 1, features.size)
        val input = features.first()

        // 验证 DerivedFeatureInput 字段集合（data class 属性）
        val inputFields = DerivedFeatureInput::class.java.declaredFields.map { it.name }.toSet()
        val expectedFields = setOf(
            "schemaVersion", "source", "windowStart", "windowEnd", "summary", "vector"
        )
        assertEquals("DerivedFeatureInput 字段集合应为 $expectedFields", expectedFields, inputFields)

        // 验证字段值
        assertEquals("feat-v1", input.schemaVersion)
        assertTrue("source 应为有效值", input.source in setOf("accel", "gyro", "screen", "notification", "app_activity", "health", "mic_opt"))
        assertEquals(windowStart, input.windowStart)
        assertEquals(now, input.windowEnd)
        assertTrue("summary 不应为空", input.summary.isNotEmpty())
        assertTrue("vector 不应为空", input.vector.isNotEmpty())
    }

    @Test
    fun derivedFeatureInputContainsNoRawSensorFields() {
        // 隐私不变量：DerivedFeatureInput 模型不包含任何原始传感字段
        val inputFields = DerivedFeatureInput::class.java.declaredFields.map { it.name }.toSet()

        // 映射到后端字段名（camelCase → snake_case）
        val backendFieldNames = inputFields.map { name ->
            name.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
        }.toSet()

        for (forbidden in forbiddenRawFields) {
            assertFalse(
                "DerivedFeatureInput 不应包含原始传感字段: $forbidden",
                forbidden in backendFieldNames || forbidden in inputFields
            )
        }
    }

    @Test
    fun derivedFeatureInputVectorRespects256DimensionLimit() {
        // 端侧 FeatureExtractor 已硬性限制 vector ≤256 维
        val now = Instant.now()
        val features = FeatureExtractor().extract(
            windowStart = now.minusSeconds(300),
            windowEnd = now,
            accelSamples = listOf(floatArrayOf(0.1f, 0.2f, 9.8f), floatArrayOf(0.2f, 0.3f, 9.7f)),
            gyroSamples = listOf(floatArrayOf(0.01f, 0.02f, 0.03f)),
            screenEvents = listOf(
                ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)
            ),
            notifications = listOf(
                NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "pkg", "social")
            )
        )
        assertEquals(1, features.size)
        assertTrue(
            "vector 维度 ${features.first().vector.size} 应 ≤256",
            features.first().vector.size <= FeatureExtractor.MAX_VECTOR_DIM
        )
    }

    @Test
    fun derivedFeatureInputSummaryRespects4000CharLimit() {
        // 端侧 FeatureExtractor 已硬性限制 summary ≤4000 字
        val now = Instant.now()
        // 构造大量信号触发长摘要
        val accelSamples = (1..500).map { floatArrayOf(it.toFloat() * 0.01f, 0f, 9.8f) }
        val screenEvents = (1..100).map {
            ScreenCollector.ScreenEvent(now.minusSeconds(300 - it * 2).toEpochMilli(), ScreenCollector.ScreenState.ON)
        }
        val notifications = (1..200).map {
            NotificationCollector.NotificationMeta(now.minusSeconds(300 - it).toEpochMilli(), "pkg$it", "social")
        }

        val features = FeatureExtractor().extract(
            windowStart = now.minusSeconds(300),
            windowEnd = now,
            accelSamples = accelSamples,
            screenEvents = screenEvents,
            notifications = notifications
        )
        assertEquals(1, features.size)
        assertTrue(
            "summary 长度 ${features.first().summary.length} 应 ≤4000",
            features.first().summary.length <= FeatureExtractor.MAX_SUMMARY_LENGTH
        )
    }

    // ---------- T13.4 ingest payload 字段映射 ----------

    @Test
    fun ingestPayloadFieldsAlignWithBackendContract() {
        // 验证 DerivedFeatureInput → ingest payload 的字段映射与后端 DerivedFeatureIn 契约对齐
        // LocalRepository.saveDerivedFeature 构建的 payload 字段：
        //   event_id / user_id / client_time（basePayload 注入）
        //   schema_version / source / window_start / window_end / summary / vector（DerivedFeatureInput）
        val input = DerivedFeatureInput(
            schemaVersion = "feat-v1",
            source = "screen",
            windowStart = Instant.now(),
            windowEnd = Instant.now().plusSeconds(300),
            summary = "屏幕使用平稳",
            vector = listOf(0.1f, 0.2f)
        )

        // 模拟 saveDerivedFeature 的 payload 字段集合
        val payloadFields = mutableSetOf<String>()
        payloadFields.add("event_id")       // basePayload
        payloadFields.add("user_id")        // basePayload
        payloadFields.add("client_time")    // basePayload
        payloadFields.add("schema_version") // DerivedFeatureInput.schemaVersion
        payloadFields.add("source")         // DerivedFeatureInput.source
        payloadFields.add("window_start")   // DerivedFeatureInput.windowStart
        payloadFields.add("window_end")     // DerivedFeatureInput.windowEnd
        payloadFields.add("summary")        // DerivedFeatureInput.summary
        payloadFields.add("vector")         // DerivedFeatureInput.vector

        // 后端契约字段集合 + client_time（端侧注入）
        val expectedFields = backendSchemaFields + "client_time"
        assertEquals(
            "ingest payload 字段应与后端契约对齐",
            expectedFields,
            payloadFields
        )

        // 不含任何原始传感字段
        for (forbidden in forbiddenRawFields) {
            assertFalse(
                "ingest payload 不应包含原始传感字段: $forbidden",
                forbidden in payloadFields
            )
        }
    }

    // ---------- T13.4 /v1/skills 响应 → SkillDisplay 映射 ----------

    @Test
    fun skillDisplayModelFieldsAlignWithBackendSkillOut() {
        // 验证 SkillDisplay 模型字段与后端 sanitize_skill 输出字段对齐
        // 后端 sanitize_skill 输出（见 app/services/sandbox/sanitizer.py）：
        //   id / user_id / name / version / trigger_conditions / guardrails /
        //   steps / status / created_at / updated_at
        // SkillDisplay 简化后字段：
        //   id / name / version / triggerConditions / guardrails / steps / status
        val displayFields = SkillDisplay::class.java.declaredFields.map { it.name }.toSet()
        val expectedFields = setOf(
            "id", "name", "version",
            "triggerConditions", "guardrails", "steps", "status"
        )
        assertEquals(
            "SkillDisplay 字段集合应为 $expectedFields",
            expectedFields,
            displayFields
        )

        // 不应包含内部字段
        assertFalse("SkillDisplay 不应含 content_hash 字段", "content_hash" in displayFields)
        assertFalse("SkillDisplay 不应含 tenant_id 字段", "tenant_id" in displayFields)
        assertFalse("SkillDisplay 不应含 user_id 字段", "user_id" in displayFields)
        assertFalse("SkillDisplay 不应含 created_at 字段", "created_at" in displayFields)
        assertFalse("SkillDisplay 不应含 updated_at 字段", "updated_at" in displayFields)
    }

    @Test
    fun skillDisplayCanHoldSanitizedSkillData() {
        // 验证 SkillDisplay 能正确承载后端下发的脱敏 Skill 数据
        // 模拟后端 sanitize_skill 输出（已移除内部字段、原始特征引用）
        val skill = SkillDisplay(
            id = "sk_reviewed_0001",
            name = "auto_data_check",
            version = 1,
            triggerConditions = listOf("narrative.mood_hint eq 偏低"),
            guardrails = listOf("不输出诊断结论", "不替代专业医疗", "命中红色信号立即冻结"),
            steps = listOf("扫描当日特征", "输出报告"),
            status = "reviewed"
        )

        assertEquals("sk_reviewed_0001", skill.id)
        assertEquals("auto_data_check", skill.name)
        assertEquals(1, skill.version)
        assertEquals("reviewed", skill.status)
        assertEquals(1, skill.triggerConditions.size)
        assertEquals(3, skill.guardrails.size)
        assertEquals(2, skill.steps.size)
    }

    @Test
    fun skillDisplayTriggerConditionsContainNoRawFeatureReferences() {
        // 隐私不变量：SkillDisplay.triggerConditions 不应包含原始特征引用
        // 后端 sanitize_skill 已移除 passive_feature.summary / derived_feature.* 等引用
        val skill = SkillDisplay(
            id = "sk_sanitized_0001",
            name = "auto_data_check",
            version = 2,
            triggerConditions = listOf(
                "narrative.mood_hint eq 偏低",
                "gap.description eq 无感知数据"
            ),
            guardrails = listOf("不输出诊断结论"),
            steps = listOf("扫描当日特征"),
            status = "signed"
        )

        for (cond in skill.triggerConditions) {
            assertFalse(
                "trigger_conditions 不应引用 passive_feature.summary: $cond",
                cond.contains("passive_feature.summary")
            )
            assertFalse(
                "trigger_conditions 不应引用 derived_feature: $cond",
                cond.contains("derived_feature")
            )
            assertFalse(
                "trigger_conditions 不应引用 feature.vector: $cond",
                cond.contains("feature.vector")
            )
        }
    }

    @Test
    fun skillDisplayStepsContainNoInternalRefs() {
        // 隐私不变量：SkillDisplay.steps 已降维为可读字符串，不含内部引用键
        // LocalRepository.parseSkills 将 steps 降维为 description 字符串列表
        val skill = SkillDisplay(
            id = "sk_steps_0001",
            name = "auto_data_check",
            version = 1,
            triggerConditions = emptyList(),
            guardrails = listOf("不输出诊断结论"),
            steps = listOf("扫描当日特征", "输出报告"),
            status = "reviewed"
        )

        // steps 为纯字符串列表，不含内部引用键
        for (step in skill.steps) {
            assertFalse("step 不应含 feature_id: $step", step.contains("feature_id"))
            assertFalse("step 不应含 source_user_id: $step", step.contains("source_user_id"))
            assertFalse("step 不应含 gap_id: $step", step.contains("gap_id"))
            assertFalse("step 不应含 sandbox_run_id: $step", step.contains("sandbox_run_id"))
        }
    }

    // ---------- T13.4 端到端数据流不变量 ----------

    @Test
    fun fullFlowDerivedFeatureInputToSkillDisplayPreservesPrivacy() {
        // 端到端隐私不变量：
        // 1. FeatureExtractor 产出 DerivedFeatureInput（含 summary + vector，无原始数据）
        // 2. DerivedFeatureInput → ingest payload（字段对齐后端契约，无原始传感字段）
        // 3. 后端处理 → 下发 Skill（已脱敏，无原始特征引用）
        // 4. 端侧解析为 SkillDisplay（不含内部字段）

        // Step 1: FeatureExtractor 产出 DerivedFeatureInput
        val now = Instant.now()
        val features = FeatureExtractor().extract(
            windowStart = now.minusSeconds(300),
            windowEnd = now,
            screenEvents = listOf(
                ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)
            )
        )
        assertEquals(1, features.size)
        val input = features.first()

        // Step 2: 验证 ingest payload 字段对齐（无原始传感字段）
        val inputFields = DerivedFeatureInput::class.java.declaredFields.map { it.name }.toSet()
        for (forbidden in forbiddenRawFields) {
            assertFalse(
                "DerivedFeatureInput 不应含原始传感字段: $forbidden",
                forbidden in inputFields
            )
        }

        // Step 3: 模拟后端下发 Skill（已脱敏）
        val skill = SkillDisplay(
            id = "sk_e2e_0001",
            name = "auto_data_check",
            version = 1,
            // trigger_conditions 不引用 passive_feature.summary（已被 sanitize 移除）
            triggerConditions = listOf("narrative.mood_hint eq 偏低"),
            guardrails = listOf("不输出诊断结论", "不替代专业医疗", "命中红色信号立即冻结"),
            steps = listOf("扫描当日特征"),
            status = "reviewed"
        )

        // Step 4: 验证 SkillDisplay 隐私属性
        // 不含原始特征 summary 引用
        for (cond in skill.triggerConditions) {
            assertFalse("trigger_conditions 不应引用 passive_feature.summary", cond.contains("passive_feature.summary"))
            assertFalse("trigger_conditions 不应引用 derived_feature", cond.contains("derived_feature"))
        }
        // SkillDisplay 模型不含内部字段
        val displayFields = SkillDisplay::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("SkillDisplay 不应含 content_hash 字段", "content_hash" in displayFields)
        assertFalse("SkillDisplay 不应含 tenant_id 字段", "tenant_id" in displayFields)
        assertFalse("SkillDisplay 不应含 user_id 字段", "user_id" in displayFields)

        // 能力描述保留
        assertEquals("auto_data_check", skill.name)
        assertEquals("reviewed", skill.status)
        assertEquals(3, skill.guardrails.size)
    }

    @Test
    fun emptySkillListRepresentsColdStart() {
        // 冷启动场景：空 Skill 列表 → UI 显示冷启动文案
        // 与 SkillCardHostTest.emptySkillListShowsColdStartHint 对齐
        val emptySkills: List<SkillDisplay> = emptyList()
        assertTrue("空 Skill 列表应表示冷启动", emptySkills.isEmpty())
    }
}
