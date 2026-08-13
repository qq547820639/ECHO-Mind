package com.yunjue.echo.mind.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * 端侧画像数据源测试（Robolectric + 内存 Room v8）：
 * feature_vectors → 本地聚合/基线/画像 全链路 + 用户隔离 + v7 旧行回退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPortraitDataSourceTest {

    private lateinit var db: EchoDatabase
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertWindow(
        userId: String,
        localDate: LocalDate,
        localMinute: Int,
        source: String,
        vector: List<Float> = MutableList(22) { 0f },
        sourcesPresent: List<String> = listOf(source)
    ) {
        val startMs = localDate.atStartOfDay(zone).toInstant().toEpochMilli() + localMinute * 60_000L
        db.dao().insertFeatureVector(
            FeatureVectorEntity(
                id = "fv_${userId}_${localDate}_${localMinute}_${source}",
                userId = userId,
                schemaVersion = "passive-core-v1",
                source = source,
                windowStart = startMs,
                windowEnd = startMs + 300_000L,
                summaryCiphertext = "enc",
                vector = JSONArray(vector).toString(),
                synced = false,
                createdAt = startMs,
                sourcesPresentJson = JSONArray(sourcesPresent).toString()
            )
        )
    }

    @Test
    fun warmUpThenReadyAcrossDays() = runBlocking {
        val user = "u_local"
        val today = LocalDate.of(2026, 8, 10)
        val source = LocalPortraitDataSource(db)

        // 第 1 天：只有今天少量窗口 → WARMING_UP
        insertWindow(user, today, 8 * 60, "screen", MutableList(22) { 0f }.also {
            it[14] = 1f; it[16] = 60 * 60000f
        })
        val warming = source.computeToday(user, today, zone)
        assertEquals("WARMING_UP", warming.status)

        // 铺 28 天窗口：每天 >= 72 个唯一窗口（coverage >= 0.25）→ 有效日，基线成型
        for (offset in 1L..28L) {
            val day = today.minusDays(offset)
            for (h in 0 until 80) {
                val v = MutableList(22) { 0f }
                v[14] = 1f
                v[16] = 5 * 60000f
                v[17] = 2f
                insertWindow(user, day, 5 * h, "screen", v, listOf("screen", "notification"))
            }
            insertWindow(user, day, 10 * 60, "accel", MutableList(22) { 0f }.also { it[7] = 1.0f })
        }
        // 今天也铺足够窗口（>=0.4 coverage）
        for (h in 0 until 130) {
            val v = MutableList(22) { 0f }
            v[14] = 1f
            v[16] = 5 * 60000f
            v[17] = 2f
            insertWindow(user, today, h * 5 % 1400, "screen", v, listOf("screen", "notification"))
        }
        insertWindow(user, today, 10 * 60, "accel", MutableList(22) { 0f }.also { it[7] = 1.0f })

        val ready = source.computeToday(user, today, zone)
        assertTrue(
            "基线成型后应为 READY/PARTIAL_DATA，实际 ${ready.status}",
            ready.status == "READY" || ready.status == "PARTIAL_DATA"
        )
        assertTrue(ready.baselineDays >= 7)
        assertFalse(ready.summary.isBlank())
    }

    @Test
    fun timelineReturnsAscendingDates() = runBlocking {
        val user = "u_timeline"
        val today = LocalDate.of(2026, 8, 10)
        val source = LocalPortraitDataSource(db)
        for (offset in 1L..30L) {
            val day = today.minusDays(offset)
            for (h in 0 until 12) {
                val v = MutableList(22) { 0f }
                v[14] = 1f; v[16] = 5 * 60000f; v[17] = 2f
                insertWindow(user, day, 8 * 60 + h * 10, "screen", v, listOf("screen", "notification"))
            }
        }
        val timeline = source.computeTimeline(user, 7, today, zone)
        assertEquals(7, timeline.size)
        val dates = timeline.map { it.date }
        assertEquals(dates.sorted(), dates)
        assertTrue(timeline.all { it.date <= today.toString() })
    }

    @Test
    fun userIsolationInLocalQueries() = runBlocking {
        val userA = "u_iso_a"
        val userB = "u_iso_b"
        val today = LocalDate.of(2026, 8, 10)
        val source = LocalPortraitDataSource(db)
        insertWindow(userB, today, 8 * 60, "screen", MutableList(22) { 0f }.also {
            it[14] = 1f; it[16] = 60 * 60000f
        })
        // A 无任何数据：不被 B 的数据污染
        val dtoA = source.computeToday(userA, today, zone)
        assertEquals("WARMING_UP", dtoA.status) // 无数据 → 冷启动（validDays=0）
        val dtoB = source.computeToday(userB, today, zone)
        assertEquals("WARMING_UP", dtoB.status) // 单日不足基线，但今日有数据 → 事实句路径前的冷启动
    }

    @Test
    fun v7LegacyRowWithoutSourcesJsonFallsBack() {
        val parsed = LocalPortraitDataSource.parseSourcesJson(null, "screen")
        assertEquals(listOf("screen"), parsed)
        val parsedEmpty = LocalPortraitDataSource.parseSourcesJson("", "accel")
        assertEquals(listOf("accel"), parsedEmpty)
        val parsedValid = LocalPortraitDataSource.parseSourcesJson("""["screen","accel"]""", "screen")
        assertEquals(listOf("screen", "accel"), parsedValid)
    }

    @Test
    fun vectorJsonParseFailClosed() {
        assertEquals(emptyList<Float>(), LocalPortraitDataSource.parseVectorJson("not-json"))
        assertEquals(emptyList<Float>(), LocalPortraitDataSource.parseVectorJson(""))
        assertEquals(listOf(1.0f, 2.0f), LocalPortraitDataSource.parseVectorJson("[1,2]"))
    }

    @Test
    fun baselineStatusReadsLocalSnapshot() = runBlocking {
        val user = "u_bs"
        val today = LocalDate.of(2026, 8, 10)
        val source = LocalPortraitDataSource(db)
        for (offset in 1L..30L) {
            val day = today.minusDays(offset)
            for (h in 0 until 80) {
                val v = MutableList(22) { 0f }
                v[14] = 1f; v[16] = 5 * 60000f; v[17] = 2f
                insertWindow(user, day, 5 * h, "screen", v, listOf("screen", "notification"))
            }
        }
        val status = source.baselineStatus(user, today, zone)
        assertEquals("BASELINE_READY", status.status)
        assertTrue(status.baselineDays >= 7)
        assertEquals("base-v1", status.baselineVersion)
        assertEquals(today.minusDays(28).toString(), status.windowStart)
        assertEquals(today.minusDays(1).toString(), status.windowEnd)
    }
}
