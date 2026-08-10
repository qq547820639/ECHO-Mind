package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.ConsentEntity
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.FeatureVectorEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T04.7/T02 数据库迁移与 v4 schema 验证：
 *
 * - v4 数据库包含全部保留表（原有 5 张 + consents + feature_vectors）
 * - **sensor_samples 已移除**（Room v4 不再注册 SensorSampleEntity；MIGRATION_3_4 DROP 表）
 * - FeatureVectorEntity DAO 插入/查询/标记同步
 * - ConsentDao 通过主 EchoDatabase 可用
 * - MIGRATION_3_4 对 v3 库执行后 sensor_samples 表不存在
 *
 * 注：Room 迁移 SQL 用 FrameworkSQLiteOpenHelperFactory 构造 v3 库验证。
 * 本机无 JDK 无法本地跑 gradle，本测试面向 CI（setup-java 17 + Robolectric）。
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

    private fun tableNames(): List<String> {
        db!!.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        ).use { cursor ->
            val tables = mutableListOf<String>()
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
            return tables
        }
    }

    @Test
    fun v4DatabaseHasAllExpectedTablesAndNoSensorSamples() {
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val tables = tableNames()
        // 原有 v2 表
        assertTrue("checkins 应存在", tables.contains("checkins"))
        assertTrue("journal_entries 应存在", tables.contains("journal_entries"))
        assertTrue("questionnaire_results 应存在", tables.contains("questionnaire_results"))
        assertTrue("practice_completions 应存在", tables.contains("practice_completions"))
        assertTrue("outbox_events 应存在", tables.contains("outbox_events"))
        // v3 保留表
        assertTrue("consents 应存在", tables.contains("consents"))
        assertTrue("feature_vectors 应存在", tables.contains("feature_vectors"))
        // v4 移除 sensor_samples（原始数据不落盘承诺）
        assertFalse("sensor_samples 表不应存在于 v4 schema", tables.contains("sensor_samples"))
    }

    @Test
    fun v4EntitiesDoNotRegisterSensorSampleEntity() {
        // 编译期保证 + 运行时断言：@Database entities 不含 SensorSampleEntity
        val entities = EchoDatabase::class.java
            .getAnnotation(androidx.room.Database::class.java)!!
            .entities
        val names = entities.map { it.simpleName }
        assertFalse("SensorSampleEntity 不应注册到 v4", "SensorSampleEntity" in names)
        assertTrue("FeatureVectorEntity 应注册", "FeatureVectorEntity" in names)
        assertTrue("ConsentEntity 应注册", "ConsentEntity" in names)
    }

    @Test
    fun migration34DropsSensorSamplesTable() {
        // 用 v3 库（含 sensor_samples 表）执行 MIGRATION_3_4，验证表被 DROP
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-34-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(sqLiteDatabase: SupportSQLiteDatabase) {
                        sqLiteDatabase.execSQL(
                            """CREATE TABLE sensor_samples (
                                id TEXT NOT NULL PRIMARY KEY,
                                userId TEXT NOT NULL,
                                source TEXT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                value TEXT NOT NULL,
                                createdAt INTEGER NOT NULL
                            )"""
                        )
                        sqLiteDatabase.execSQL(
                            """CREATE TABLE feature_vectors (
                                id TEXT NOT NULL PRIMARY KEY,
                                userId TEXT NOT NULL,
                                schemaVersion TEXT NOT NULL,
                                source TEXT NOT NULL,
                                windowStart INTEGER NOT NULL,
                                windowEnd INTEGER NOT NULL,
                                summaryCiphertext TEXT NOT NULL,
                                vector TEXT NOT NULL,
                                synced INTEGER NOT NULL DEFAULT 0,
                                createdAt INTEGER NOT NULL
                            )"""
                        )
                    }

                    override fun onUpgrade(sqLiteDatabase: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val rawDb = helper.writableDatabase
        try {
            // 迁移前 sensor_samples 存在
            assertTrue("v3 库应存在 sensor_samples 表", hasTable(rawDb, "sensor_samples"))
            // 执行 v3→v4 迁移
            MIGRATION_3_4.migrate(rawDb)
            // 迁移后 sensor_samples 被 DROP
            assertFalse("MIGRATION_3_4 应 DROP sensor_samples", hasTable(rawDb, "sensor_samples"))
            // 保留表不受影响
            assertTrue("feature_vectors 应保留", hasTable(rawDb, "feature_vectors"))
        } finally {
            rawDb.close()
        }
    }

    private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf<Any>(table)
        ).use { cursor ->
            return cursor.moveToFirst()
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
}
