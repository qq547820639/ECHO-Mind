package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.DeterministicPersonalAnswerProvider
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.FeatureVectorEntity
import com.yunjue.echo.mind.data.LocalPortraitDataSource
import com.yunjue.echo.mind.localportrait.LocalVectorIndices
import com.yunjue.echo.mind.model.EchoMemory
import com.yunjue.echo.mind.model.MemoryType
import com.yunjue.echo.mind.model.RetentionClass
import com.yunjue.echo.mind.ports.EchoMemoryReader
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
 * ERA 31 R18 — 确定性个人回答生产数据桥回归（Robolectric，SDK 35）。
 *
 * 上下文标签取自结构化 kind（「旅行」而非「特殊时期：旅行（…）」整串截断）：
 * 纠正桥（EchoCorrectionService R18）写入的 CONTEXT 记忆在上下文感知回答里
 * 自然呈现人类可读的标签。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeterministicPersonalAnswerProviderTest {

    private lateinit var context: Context
    private lateinit var db: EchoDatabase
    private val fixedToday: LocalDate = LocalDate.of(2026, 8, 15)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedWindow(userId: String, date: LocalDate, startHour: Int) {
        val startMs = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        val vector = MutableList(22) { 0f }
        vector[LocalVectorIndices.SCREEN_ON_COUNT] = 1f // 活跃窗口（activeStart 取自窗口起始时刻）
        vector[LocalVectorIndices.SCREEN_ON_DURATION_MS] = 30 * 60000f
        db.dao().insertFeatureVector(
            FeatureVectorEntity(
                id = "fv_${date}_${startHour}",
                userId = userId,
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

    @Test
    fun contextMemoryLabelUsesKindInTravelAnswer() = runBlocking {
        val userId = "u_provider_label"
        // 20 天窗口：每天 08:00 活跃
        for (i in 0..19) seedWindow(userId, fixedToday.minusDays(19L - i), 8)
        val memoryReader = object : EchoMemoryReader {
            override suspend fun memoriesByType(type: MemoryType): List<EchoMemory> =
                if (type == MemoryType.CONTEXT) {
                    listOf(
                        EchoMemory(
                            id = "ctx_1", userId = userId, type = MemoryType.CONTEXT,
                            content = "特殊时期：旅行（出差）@${fixedToday.minusDays(1)}",
                            source = "user-feedback", confidence = 1f,
                            createdAt = fixedToday.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                            lastConfirmedAt = 0L, importance = 70,
                            retentionClass = RetentionClass.LONG_TERM,
                            provenance = "context-from-correction:v1",
                        )
                    )
                } else {
                    emptyList()
                }
        }
        val provider = DeterministicPersonalAnswerProvider(
            portraitDataSource = LocalPortraitDataSource(db),
            memoryReader = memoryReader,
            userId = { userId },
            seasonDrift = { 0f },
            today = { fixedToday },
            zoneId = { zone },
        )
        val result = provider.answer("我说过最近在出差，这有没有影响？")
        assertNotNull("上下文窗口应进入回答", result)
        assertTrue("标签应为结构化 kind「旅行」：${result!!.text}", result.text.contains("旅行"))
        assertTrue("不应出现内容串截断：${result.text}", !result.text.contains("特殊时期"))
        // ERA 31 R24：依据如实标注（你告诉我的特殊日期），不再一律「历史画像」
        assertTrue(
            "上下文回答的来源应包含 CONTEXT_EXCEPTIONS：${result.usedSources}",
            com.yunjue.echo.mind.intelligence.DataSourceCategory.CONTEXT_EXCEPTIONS in result.usedSources,
        )
    }
}
