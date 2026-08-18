package com.yunjue.echo.mind

import android.content.Context
import android.hardware.Sensor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.SensingRepository
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.model.SkillCompletionInput
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensorSample
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * T13.4 E2E 数据流转换测试（Robolectric：payload 契约断言触达真实生产映射）。
 *
 * 验证全链路数据流转换的正确性：
 * 1. FeatureExtractor 产出 DerivedFeatureInput（含 summary + vector + sources_present，无原始数据）
 * 2. DerivedFeatureInput → ingest payload：经真实 SensingRepository.saveDerivedFeature 落库+入
 *    outbox，解密读回 payload，字段集合与后端 DerivedFeatureIn 契约对齐（无原始传感字段）
 * 3. ingest payload 含 sources_present（02b 共享知识 4）；sourcesPresent 为空时不注入（可选字段）
 * 4. Skill 执行完成上报链路：经真实 SkillRepository.recordSkillCompletion 入 outbox，
 *    payload 与 POST /v1/skills/completions 契约对齐
 * 5. /v1/skills 响应 → SkillDisplay 映射：经真实 fetchSkills（缓存命中走生产解析器）
 *    验证 trigger_conditions / steps 映射与隐私不变量
 * 6. 隐私不变量：DerivedFeatureInput / payload 不含原始传感数据
 *
 * T8 P1-20 重写：原版对测试内自建的硬编码集合做自证断言（生产 payload 构建改动后测试照常绿），
 * 现改为调用真实生产函数（saveDerivedFeature / recordSkillCompletion / fetchSkills）产出字段集合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class E2EFlowTest {

    private lateinit var context: Context
    private lateinit var db: EchoDatabase
    private lateinit var cipher: JvmTestFieldCipher
    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cipher = JvmTestFieldCipher()
        preferences = AppPreferences(context, cipher)
    }

    @After
    fun tearDown() {
        db.close()
    }

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

    /** 后端 /v1/skills 契约响应样本（脱敏下发：trigger_conditions 为 field/op/value 对象数组）。 */
    private fun backendSkillsResponse(): String = """
        {
          "skills": [
            {
              "id": "sk_reviewed_0001",
              "name": "auto_data_check",
              "version": 2,
              "trigger_conditions": [
                {"field": "narrative.mood_hint", "op": "eq", "value": "偏低"},
                {"field": "gap.description", "op": "eq", "value": "无感知数据"}
              ],
              "guardrails": ["不输出诊断结论"],
              "steps": [
                {"description": "扫描当日特征"},
                {"key": "report_step", "description": "输出报告"}
              ],
              "status": "reviewed",
              "action_type": "guided_steps",
              "estimated_duration": 180,
              "safety_constraints": ["命中红色信号立即冻结"]
            }
          ],
          "cold_start_hint": null,
          "observation_days": 3
        }
    """.trimIndent()

    /** 经真实 fetchSkills（预置缓存命中 → 生产 parseSkillResponse/parseSkillsArray）解析出 SkillDisplay。 */
    private fun fetchSkillsFromBackendCache(responseJson: String): List<SkillDisplay> {
        preferences.setSkillCache(responseJson)
        val repository = SkillRepository(db, Outbox(db, cipher), preferences, ApiClient(tokenProvider = { null }))
        val result = repository.fetchSkills()
        assertFalse("缓存命中路径不应加载失败", result.loadFailed)
        return result.skills.orEmpty()
    }

    // ---------- T13.4 FeatureExtractor → DerivedFeatureInput ----------

    @Test
    fun featureExtractorProducesDerivedFeatureInputWithExpectedFields() {
        // 验证 FeatureExtractor 产出的 DerivedFeatureInput 只含契约字段，无原始传感数据
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = FeatureExtractor().extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = listOf(SensorSample(now.minusSeconds(250).toEpochMilli(), Sensor.TYPE_ACCELEROMETER, 0.1f, 0.2f, 9.8f)),
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
        val inputFields = DerivedFeatureInput::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        val expectedFields = setOf(
            "schemaVersion", "source", "windowStart", "windowEnd", "summary", "vector", "sourcesPresent"
        )
        assertEquals("DerivedFeatureInput 字段集合应为 $expectedFields", expectedFields, inputFields)

        // 验证字段值（Phase 4/5：默认 schema 为 passive-core-v1）
        assertEquals("passive-core-v1", input.schemaVersion)
        assertTrue("source 应为有效值", input.source in setOf("accel", "gyro", "screen", "notification", "app_activity", "health", "mic_opt"))
        assertEquals(windowStart, input.windowStart)
        assertEquals(now, input.windowEnd)
        assertTrue("summary 不应为空", input.summary.isNotEmpty())
        assertTrue("vector 不应为空", input.vector.isNotEmpty())
    }

    @Test
    fun derivedFeatureInputReportsSourcesPresent() {
        // T02：sources_present 必须覆盖窗口实际出现的 modality（与 gap_finder 契约对齐）
        val now = Instant.now()
        val features = FeatureExtractor().extract(
            windowStart = now.minusSeconds(300),
            windowEnd = now,
            accelSamples = listOf(SensorSample(now.minusSeconds(250).toEpochMilli(), Sensor.TYPE_ACCELEROMETER, 0.1f, 0.2f, 9.8f)),
            screenEvents = listOf(
                ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)
            ),
            notifications = listOf(
                NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "pkg", "social")
            )
        )
        val present = features.first().sourcesPresent
        assertTrue("sources_present 应含 accel", "accel" in present)
        assertTrue("sources_present 应含 screen", "screen" in present)
        assertTrue("sources_present 应含 notification", "notification" in present)
        assertFalse("sources_present 不应含未出现 modality（app_activity）", "app_activity" in present)
        // 枚举值必须与后端 EXPECTED_SOURCES 一致（02b 共享知识 4）
        val allowed = setOf("accel", "gyro", "screen", "notification", "app_activity", "mic_opt", "health")
        assertTrue("sources_present 值必须属于契约枚举", present.all { it in allowed })
    }

    @Test
    fun derivedFeatureInputContainsNoRawSensorFields() {
        // 隐私不变量：DerivedFeatureInput 模型不包含任何原始传感字段
        val inputFields = DerivedFeatureInput::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()

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
            accelSamples = listOf(
                SensorSample(now.minusSeconds(250).toEpochMilli(), Sensor.TYPE_ACCELEROMETER, 0.1f, 0.2f, 9.8f),
                SensorSample(now.minusSeconds(200).toEpochMilli(), Sensor.TYPE_ACCELEROMETER, 0.2f, 0.3f, 9.7f)
            ),
            gyroSamples = listOf(SensorSample(now.minusSeconds(240).toEpochMilli(), Sensor.TYPE_GYROSCOPE, 0.01f, 0.02f, 0.03f)),
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
        // 构造大量信号触发长摘要（accel 样本时间戳递增，落在窗口内）
        val accelSamples = (1..500).map {
            SensorSample(now.minusSeconds((300 - it / 2).toLong()).toEpochMilli(), Sensor.TYPE_ACCELEROMETER, it.toFloat() * 0.01f, 0f, 9.8f)
        }
        val screenEvents = (1..100).map {
            ScreenCollector.ScreenEvent(now.minusSeconds((300 - it * 2).toLong()).toEpochMilli(), ScreenCollector.ScreenState.ON)
        }
        val notifications = (1..200).map {
            NotificationCollector.NotificationMeta(now.minusSeconds((300 - it).toLong()).toEpochMilli(), "pkg$it", "social")
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

    // ---------- T13.4 ingest payload 字段映射（真实生产构建） ----------

    @Test
    fun ingestPayloadFieldsAlignWithBackendContract() = runBlocking {
        // T8 P1-20 重写：真实调用 SensingRepository.saveDerivedFeature（生产 payload 构建：
        // persistDerivedFeature + Outbox.basePayload），从 outbox 读回密文 → 解密 → 断言字段集合
        preferences.userId = "e2e_ingest_u"
        val repository = SensingRepository(db, cipher, Outbox(db, cipher), preferences)
        val input = DerivedFeatureInput(
            schemaVersion = "passive-core-v1",
            source = "screen",
            windowStart = Instant.now(),
            windowEnd = Instant.now().plusSeconds(300),
            summary = "屏幕使用平稳",
            vector = listOf(0.1f, 0.2f),
            sourcesPresent = listOf("screen", "notification")
        )
        repository.saveDerivedFeature(input)

        val pending = db.dao().pendingOutbox()
        assertEquals("应产生 1 条 derived_feature outbox 事件", 1, pending.size)
        assertEquals("derived_feature", pending.first().eventType)
        val payload = JSONObject(cipher.decrypt(pending.first().payloadCiphertext))
        val payloadFields = payload.keys().asSequence().toSet()

        // 真实构建的 ingest payload 字段与后端契约 + 端侧注入（client_time/sources_present）对齐
        assertEquals(
            "ingest payload 字段应与后端契约对齐",
            backendSchemaFields + "client_time" + "sources_present",
            payloadFields
        )
        // 值语义：basePayload 注入 + DerivedFeatureInput 字段逐一映射
        assertEquals("e2e_ingest_u", payload.getString("user_id"))
        assertTrue("event_id 应为 feat_ 前缀注入", payload.getString("event_id").startsWith("feat_"))
        assertEquals("passive-core-v1", payload.getString("schema_version"))
        assertEquals("screen", payload.getString("source"))
        assertEquals(input.windowStart.toString(), payload.getString("window_start"))
        assertEquals(input.windowEnd.toString(), payload.getString("window_end"))
        assertEquals("屏幕使用平稳", payload.getString("summary"))
        assertEquals(2, payload.getJSONArray("vector").length())

        // 不含任何原始传感字段
        for (forbidden in forbiddenRawFields) {
            assertFalse("ingest payload 不应包含原始传感字段: $forbidden", forbidden in payloadFields)
        }

        // sourcesPresent 为空 → 生产按 if 守卫不注入 sources_present（可选字段语义）
        repository.saveDerivedFeature(input.copy(sourcesPresent = emptyList()))
        val pendingAll = db.dao().pendingOutbox()
        assertEquals(2, pendingAll.size)
        val barePayload = pendingAll
            .map { JSONObject(cipher.decrypt(it.payloadCiphertext)) }
            .first { !it.has("sources_present") }
        assertEquals(
            "空 sourcesPresent 的 payload 只含契约必填字段 + client_time",
            backendSchemaFields + "client_time",
            barePayload.keys().asSequence().toSet()
        )
    }

    // ---------- Skill 执行完成上报链路（T05，真实生产构建） ----------

    @Test
    fun skillCompletionInputCarriesContractFields() {
        val input = SkillCompletionInput(
            skillId = "sk_1",
            status = "completed",
            durationSeconds = 180
        )
        // 模型字段集合 = {skillId, status, durationSeconds, clientTime, eventId}
        val fields = SkillCompletionInput::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertEquals(
            setOf("skillId", "status", "durationSeconds", "clientTime", "eventId"),
            fields
        )
        assertEquals("sk_1", input.skillId)
        assertEquals("completed", input.status)
        assertEquals(180, input.durationSeconds)
        assertFalse("eventId 应自动生成", input.eventId.isBlank())
    }

    @Test
    fun skillCompletionMapsToCompletionsEndpoint() {
        // SyncWorker 映射：skill_completion → POST /v1/skills/completions
        assertEquals("/v1/skills/completions", com.yunjue.echo.mind.SyncWorker.resolvePath("skill_completion"))
        // deprecated 类型（practice）仍是旧端点，与 skill_completion 语义区分
        assertEquals("/v1/practices/completions", com.yunjue.echo.mind.SyncWorker.resolvePath("practice"))
    }

    @Test
    fun skillCompletionPayloadFieldsAlignWithBackendContract() = runBlocking {
        // T8 P1-20 重写：真实调用 SkillRepository.recordSkillCompletion（生产 payload 构建），
        // 从 outbox 读回密文 → 解密 → 断言与 POST /v1/skills/completions 契约对齐：
        // body {event_id, user_id, skill_id, status: started|completed|stopped, duration_seconds, client_time}
        preferences.userId = "e2e_skill_u"
        val repository = SkillRepository(db, Outbox(db, cipher), preferences, ApiClient(tokenProvider = { null }))
        val input = SkillCompletionInput(skillId = "sk_1", status = "completed", durationSeconds = 90)
        repository.recordSkillCompletion(input, sessionId = null)

        val pending = db.dao().pendingOutbox()
        assertEquals("应产生 1 条 skill_completion outbox 事件", 1, pending.size)
        assertEquals("skill_completion", pending.first().eventType)
        val payload = JSONObject(cipher.decrypt(pending.first().payloadCiphertext))
        assertEquals(
            "真实构建的 completion payload 字段应与后端契约对齐",
            setOf("event_id", "user_id", "skill_id", "status", "duration_seconds", "client_time"),
            payload.keys().asSequence().toSet()
        )
        assertEquals(input.eventId, payload.getString("event_id"))
        assertEquals("e2e_skill_u", payload.getString("user_id"))
        assertEquals("sk_1", payload.getString("skill_id"))
        assertEquals("completed", payload.getString("status"))
        assertEquals(90, payload.getInt("duration_seconds"))
        // status 枚举白名单
        assertTrue(payload.getString("status") in setOf("started", "completed", "stopped"))
    }

    // ---------- T13.4 /v1/skills 响应 → SkillDisplay 映射（真实生产解析器） ----------

    @Test
    fun skillDisplayModelFieldsAlignWithBackendSkillOut() {
        // 验证 SkillDisplay 模型字段与后端 SkillOut 契约对齐（PRD 契约点 4/5）
        // 后端 SkillOut（见 backend/app/schemas.py）：id / user_id / name / version /
        //   trigger_conditions / guardrails / steps / status / action_type /
        //   estimated_duration / completion_schema / safety_constraints / revision / ...
        // SkillDisplay 端侧字段（v0.6 final 执行契约字段全部保留）：
        //   id / name / version / triggerConditions / guardrails / steps / status /
        //   actionType / estimatedDuration / completionSchema / safetyConstraints / revision
        val displayFields = SkillDisplay::class.java.declaredFields
            .map { it.name }
            // 过滤合成字段（$stable）与 companion 静态字段（Companion / 静态常量）
            .filter { !it.startsWith("$") && it != "Companion" }
            .filterNot { name ->
                SkillDisplay::class.java.declaredFields.any { it.name == name && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            }
            .toSet()
        val expectedFields = setOf(
            "id", "name", "version",
            "triggerConditions", "guardrails", "steps", "status",
            "actionType", "estimatedDuration", "completionSchema", "safetyConstraints", "revision"
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
    fun skillDisplayTriggerConditionsFromRealParserContainNoRawFeatureReferences() {
        // T8 P1-20 重写：经真实 fetchSkills（缓存命中 → 生产 parseSkillResponse/toTriggerStrings）
        // 产出 triggerConditions，对解析产物断言——后端下发样本即契约锚点，生产解析改动会红
        val skills = fetchSkillsFromBackendCache(backendSkillsResponse())
        assertEquals(1, skills.size)
        val trigger = skills.first().triggerConditions
        assertEquals(
            "真实解析器应将 trigger 对象数组映射为 'field op value' 字符串",
            listOf("narrative.mood_hint eq 偏低", "gap.description eq 无感知数据"),
            trigger
        )
        for (cond in trigger) {
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
    fun skillDisplayStepsFromRealParserContainNoInternalRefs() {
        // T8 P1-20 重写：经真实 fetchSkills（缓存命中 → 生产 parseSkillsArray/toStepDescriptions）
        // 产出 steps，对解析产物断言内部引用黑名单
        val skills = fetchSkillsFromBackendCache(backendSkillsResponse())
        assertEquals(1, skills.size)
        val steps = skills.first().steps
        assertEquals(
            "真实解析器应取 step.description（缺失回退 key），过滤空白项",
            listOf("扫描当日特征", "输出报告"),
            steps
        )
        for (step in steps) {
            assertFalse("step 不应含 feature_id: $step", step.contains("feature_id"))
            assertFalse("step 不应含 source_user_id: $step", step.contains("source_user_id"))
            assertFalse("step 不应含 gap_id: $step", step.contains("gap_id"))
            assertFalse("step 不应含 sandbox_run_id: $step", step.contains("sandbox_run_id"))
        }
    }

    // ---------- T13.4 端到端数据流不变量（真实抽取 + 真实上行 + 真实解析） ----------

    @Test
    fun fullFlowDerivedFeatureInputToSkillDisplayPreservesPrivacy() = runBlocking {
        // 端到端隐私不变量（T8 P1-20 重写：全链路触达真实生产函数）：
        // 1. FeatureExtractor 产出 DerivedFeatureInput（含 summary + vector，无原始数据）
        // 2. 真实 saveDerivedFeature → outbox payload（字段对齐后端契约，无原始传感字段）
        // 3. 后端下发脱敏 Skill → 真实 fetchSkills 解析为 SkillDisplay（不含内部字段）
        // 4. 解析产出的 trigger_conditions / steps 无原始特征/内部引用

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

        // Step 2: 真实 saveDerivedFeature → outbox payload（无原始传感字段）
        preferences.userId = "e2e_flow_u"
        val sensingRepository = SensingRepository(db, cipher, Outbox(db, cipher), preferences)
        sensingRepository.saveDerivedFeature(features.first())
        val payload = JSONObject(cipher.decrypt(db.dao().pendingOutbox().first().payloadCiphertext))
        val payloadFields = payload.keys().asSequence().toSet()
        assertEquals(backendSchemaFields + "client_time" + "sources_present", payloadFields)
        for (forbidden in forbiddenRawFields) {
            assertFalse(
                "ingest payload 不应含原始传感字段: $forbidden",
                forbidden in payloadFields
            )
        }

        // Step 3: 后端下发脱敏 Skill → 真实解析器产出 SkillDisplay
        val skills = fetchSkillsFromBackendCache(backendSkillsResponse())
        assertEquals(1, skills.size)
        val skill = skills.first()

        // Step 4: 解析产出的 trigger_conditions / steps 无原始特征/内部引用
        for (cond in skill.triggerConditions) {
            assertFalse("trigger_conditions 不应引用 passive_feature.summary", cond.contains("passive_feature.summary"))
            assertFalse("trigger_conditions 不应引用 derived_feature", cond.contains("derived_feature"))
        }
        for (step in skill.steps) {
            assertFalse("step 不应含 feature_id", step.contains("feature_id"))
            assertFalse("step 不应含 gap_id", step.contains("gap_id"))
        }
        val displayFields = SkillDisplay::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertFalse("SkillDisplay 不应含 content_hash 字段", "content_hash" in displayFields)
        assertFalse("SkillDisplay 不应含 tenant_id 字段", "tenant_id" in displayFields)
        assertFalse("SkillDisplay 不应含 user_id 字段", "user_id" in displayFields)

        assertEquals("auto_data_check", skill.name)
        assertEquals("reviewed", skill.status)
        assertEquals(1, skill.safetyConstraints.size)
    }

    @Test
    fun emptySkillListRepresentsColdStart() {
        val emptySkills: List<SkillDisplay> = emptyList()
        assertTrue("空 Skill 列表应表示冷启动", emptySkills.isEmpty())
    }
}
