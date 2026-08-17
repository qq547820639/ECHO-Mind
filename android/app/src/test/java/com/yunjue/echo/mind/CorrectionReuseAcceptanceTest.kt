package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.data.DeterministicPersonalAnswerProvider
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.FeatureVectorEntity
import com.yunjue.echo.mind.data.LocalPortraitDataSource
import com.yunjue.echo.mind.data.MemoryRepository
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.localportrait.LocalVectorIndices
import com.yunjue.echo.mind.memory.EchoCorrectionService
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * ERA 31 R32 — §22 Correction Reuse 关键验收（production 全链，Robolectric）：
 *
 * 用户在 Ask ECHO 说「不太像（原因：旅行）」→ EchoCorrectionService 写 CORRECTION +
 * CONTEXT 记忆（R18 桥梁）→ 未来「我说过最近在出差，这有没有影响？」由
 * DeterministicPersonalAnswerProvider（真实 Room + 真实画像数据源 + 真实 MemoryRepository）
 * 回答，且答案自然融入「旅行」上下文、依据如实标注——不是机械回放「你之前说过…」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorrectionReuseAcceptanceTest {

    private lateinit var context: Context
    private lateinit var db: EchoDatabase
    private lateinit var preferences: AppPreferences
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = AppPreferences(context, JvmTestFieldCipher())
        preferences.userId = "u_correction_reuse"
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedHistoryDays(days: Int) {
        val today = LocalDate.now(zone)
        for (i in 0 until days) {
            val date = today.minusDays((days - 1 - i).toLong())
            val startMs = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            val vector = MutableList(22) { 0f }
            vector[LocalVectorIndices.SCREEN_ON_COUNT] = 1f
            vector[LocalVectorIndices.SCREEN_ON_DURATION_MS] = 30 * 60000f
            db.dao().insertFeatureVector(
                FeatureVectorEntity(
                    id = "fv_${date}",
                    userId = preferences.userId,
                    schemaVersion = "passive-core-v1",
                    source = "screen",
                    windowStart = startMs,
                    windowEnd = startMs + 300_000L,
                    summaryCiphertext = "enc",
                    vector = JSONArray(vector).toString(),
                    synced = false,
                    createdAt = startMs,
                    sourcesPresentJson = JSONArray(listOf("screen")).toString(),
                )
            )
        }
    }

    @Test
    fun travelCorrectionNaturallyEntersFutureContextAnswer() = runBlocking {
        seedHistoryDays(20)

        val memoryRepository = MemoryRepository(db, preferences)
        val correctionService = EchoCorrectionService(
            memoryWriter = memoryRepository,
            correctionWriter = memoryRepository,
            memoryReader = memoryRepository,
        )

        // 用户纠正：「不太像 + 旅行」（§22 场景：「不太像，我最近在出差。」）
        correctionService.recordConversationFeedback(
            question = "最近我是不是越来越晚？",
            answer = "是的，最近明显更晚。",
            like = false,
            reason = "旅行",
        )

        // 未来问上下文感知问题——production 提供者（真实 DB 全链）
        val provider = DeterministicPersonalAnswerProvider(
            portraitDataSource = LocalPortraitDataSource(db),
            memoryReader = memoryRepository,
            userId = { preferences.userId },
            seasonDrift = { 0f },
            today = { LocalDate.now(zone) },
            zoneId = { zone },
        )
        val result = provider.answer("我说过最近在出差，这有没有影响？")
        assertNotNull("纠正后的上下文感知问题应可答", result)
        assertTrue(
            "回答应自然融入「旅行」上下文（不是机械回放）：${result!!.text}",
            result.text.contains("旅行"),
        )
        assertTrue(
            "依据应如实标注你告诉我的特殊日期：${result.usedSources}",
            DataSourceCategory.CONTEXT_EXCEPTIONS in result.usedSources,
        )
    }
}
