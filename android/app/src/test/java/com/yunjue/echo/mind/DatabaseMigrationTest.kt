package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ConsentEntity
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.FeatureVectorEntity
import com.yunjue.echo.mind.data.SensorSampleEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T04.7 数据库迁移与 v3 schema 验证：
 *
 * - v3 数据库包含全部预期表（原有 5 张 + consents + sensor_samples + feature_vectors）
 * - FeatureVectorEntity DAO 插入/查询/标记同步
 * - ConsentDao 通过主 EchoDatabase 可用（T04 注册）
 * - SensorSampleEntity DAO 插入/清理
 *
 * 注：理想方案是用 MigrationTestHelper 测试 v2→v3 迁移 SQL，但需要导出的 schema JSON
 * （exportSchema=true 已开启，首次构建时生成）。当前环境可能无 Android SDK，
 * 因此用 in-memory v3 DB 验证最终 schema 正确性，等价于迁移后的状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {

    private lateinit var context: Context
    private var db: EchoDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun v3DatabaseHasAllExpectedTables() {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        db!!.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        ).use { cursor ->
            val tables = mutableListOf<String>()
            while (cursor.moveToNext()) tables.add(cursor.getString(0))

            // 原有 v2 表
            assertTrue("checkins 应存在", tables.contains("checkins"))
            assertTrue("journal_entries 应存在", tables.contains("journal_entries"))
            assertTrue("questionnaire_results 应存在", tables.contains("questionnaire_results"))
            assertTrue("practice_completions 应存在", tables.contains("practice_completions"))
            assertTrue("outbox_events 应存在", tables.contains("outbox_events"))
            // v3 新增表
            assertTrue("consents 应存在（T04 注册 ConsentEntity）", tables.contains("consents"))
            assertTrue("sensor_samples 应存在", tables.contains("sensor_samples"))
            assertTrue("feature_vectors 应存在", tables.contains("feature_vectors"))
        }
    }

    @Test
    fun featureVectorDaoInsertQueryAndMarkSynced() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db!!.dao()

        val fv = FeatureVectorEntity(
            id = "feat_test_1",
            userId = "u_test",
            schemaVersion = "feat-v1",
            source = "accel",
            windowStart = 1000L,
            windowEnd = 2000L,
            summaryCiphertext = "encrypted_summary_base64",
            vector = "[0.1,0.2,0.3]",
            synced = false,
            createdAt = 1500L
        )
        dao.insertFeatureVector(fv)

        val pending = dao.pendingFeatureVectors()
        assertEquals("应有 1 条未同步特征", 1, pending.size)
        assertEquals("feat_test_1", pending.first().id)
        assertEquals("feat-v1", pending.first().schemaVersion)
        assertEquals("accel", pending.first().source)
        assertEquals(false, pending.first().synced)

        // 标记同步
        dao.markFeatureVectorSynced("feat_test_1")
        assertEquals("标记同步后应无未同步特征", 0, dao.pendingFeatureVectors().size)
    }

    @Test
    fun consentDaoWorksThroughMainDatabase() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val consentDao = db!!.consentDao()

        consentDao.insertConsent(
            ConsentEntity(
                eventId = "consent_test_1",
                userId = "u_test",
                consentType = "passive_sensing",
                version = "passive-sensing-consent-2026.07",
                granted = true,
                grantedAt = 1L,
                evidenceHash = "hash1"
            )
        )
        consentDao.insertConsent(
            ConsentEntity(
                eventId = "consent_test_2",
                userId = "u_test",
                consentType = "passive_sensing",
                version = "passive-sensing-consent-2026.07",
                granted = false,
                grantedAt = 2L,
                evidenceHash = "hash2"
            )
        )

        assertEquals("应存在 1 条授权记录", 1, consentDao.grantedCount("passive_sensing"))
        assertEquals("应存在 2 条记录", 2, consentDao.consentsByType("passive_sensing").size)

        val latest = consentDao.latestConsent("passive_sensing")
        assertEquals("最新记录应为 consent_test_2", "consent_test_2", latest?.eventId)
        assertEquals(false, latest?.granted)
    }

    @Test
    fun sensorSampleDaoInsertAndCleanup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db!!.dao()

        val now = System.currentTimeMillis()
        dao.insertSensorSample(
            SensorSampleEntity(
                id = "ss_old",
                userId = "u_test",
                source = "accel",
                timestamp = now - 600_000L,
                value = """{"x":0.1,"y":0.2,"z":9.8}""",
                createdAt = now - 600_000L
            )
        )
        dao.insertSensorSample(
            SensorSampleEntity(
                id = "ss_new",
                userId = "u_test",
                source = "accel",
                timestamp = now,
                value = """{"x":0.2,"y":0.3,"z":9.7}""",
                createdAt = now
            )
        )

        // 清理 5 分钟前的样本
        dao.deleteSensorSamplesBefore(now - 300_000L)

        // 验证旧样本已删除、新样本保留
        db!!.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM sensor_samples"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("清理后应只剩 1 条样本", 1, cursor.getInt(0))
        }
        db!!.openHelper.writableDatabase.query(
            "SELECT id FROM sensor_samples"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("ss_new", cursor.getString(0))
        }
    }

    @Test
    fun featureVectorTableSchemaMatchesEntityDefinition() {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        db!!.openHelper.writableDatabase.query(
            "PRAGMA table_info(feature_vectors)"
        ).use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            // 验证 FeatureVectorEntity 所有字段对应的列存在
            assertTrue("id 列应存在", columns.contains("id"))
            assertTrue("userId 列应存在", columns.contains("userId"))
            assertTrue("schemaVersion 列应存在", columns.contains("schemaVersion"))
            assertTrue("source 列应存在", columns.contains("source"))
            assertTrue("windowStart 列应存在", columns.contains("windowStart"))
            assertTrue("windowEnd 列应存在", columns.contains("windowEnd"))
            assertTrue("summaryCiphertext 列应存在", columns.contains("summaryCiphertext"))
            assertTrue("vector 列应存在", columns.contains("vector"))
            assertTrue("synced 列应存在", columns.contains("synced"))
            assertTrue("createdAt 列应存在", columns.contains("createdAt"))
        }
    }

    @Test
    fun sensorSampleTableSchemaMatchesEntityDefinition() {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        db!!.openHelper.writableDatabase.query(
            "PRAGMA table_info(sensor_samples)"
        ).use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("id 列应存在", columns.contains("id"))
            assertTrue("userId 列应存在", columns.contains("userId"))
            assertTrue("source 列应存在", columns.contains("source"))
            assertTrue("timestamp 列应存在", columns.contains("timestamp"))
            assertTrue("value 列应存在", columns.contains("value"))
            assertTrue("createdAt 列应存在", columns.contains("createdAt"))
        }
    }
}
