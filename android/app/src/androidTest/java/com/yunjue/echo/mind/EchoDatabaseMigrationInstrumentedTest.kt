package com.yunjue.echo.mind

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunjue.echo.mind.data.EchoDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器数据库迁移测试（v0.7.2：关闭 connected-test「空通过」门）。
 *
 * - 2→8 全链：outbox 旧行保留、sensor_samples 被 DROP、portrait_daily 创建、
 *   sourcesPresentJson 列存在、v8 schema 上特征行可读写；
 * - 7→8 单步：纯加列幂等。
 * CI 在 API 34/36 模拟器执行（android-ci.yml connected-test）。
 */
@RunWith(AndroidJUnit4::class)
class EchoDatabaseMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EchoDatabase::class.java
    )

    @Test
    fun migrateFrom2To8PreservesDataAndAppliesChain() {
        helper.createDatabase(TEST_DB_2_8, 2).apply {
            execSQL(
                "INSERT INTO outbox_events (eventId, eventType, payloadCiphertext, priority, createdAtEpochMs, attempts) " +
                    "VALUES ('evt_legacy', 'consent', 'enc', 20, 0, 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(
            TEST_DB_2_8, 8, true,
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
        )
        db.query("SELECT eventId FROM outbox_events WHERE eventId='evt_legacy'").use { c ->
            assertTrue("2→8 后旧 outbox 行应保留", c.moveToFirst())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sensor_samples'").use { c ->
            assertFalse("3→4 应 DROP sensor_samples（原始数据不落盘承诺）", c.moveToFirst())
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='portrait_daily'").use { c ->
            assertTrue("6→7 应创建 portrait_daily", c.moveToFirst())
        }
        db.execSQL(
            "INSERT INTO feature_vectors (id, userId, schemaVersion, source, windowStart, windowEnd, summaryCiphertext, vector, synced, createdAt, sourcesPresentJson) " +
                "VALUES ('fv_v8', 'u_test', 'passive-core-v1', 'screen', 0, 300000, 'enc', '[]', 0, 0, '[\"screen\"]')"
        )
        db.query("SELECT sourcesPresentJson FROM feature_vectors WHERE id='fv_v8'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("v8 列应可写读", c.getString(0).contains("screen"))
        }
        db.close()
    }

    @Test
    fun migrateFrom7To8AddsSourcesPresentJsonColumn() {
        helper.createDatabase(TEST_DB_7_8, 7).apply { close() }
        val db = helper.runMigrationsAndValidate(TEST_DB_7_8, 8, true, MIGRATION_7_8)
        db.query("PRAGMA table_info(feature_vectors)").use { c ->
            val columns = mutableSetOf<String>()
            while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
            assertTrue("7→8 应加 sourcesPresentJson 列", columns.contains("sourcesPresentJson"))
        }
        db.close()
    }

    @Test
    fun migrateFrom8To11AppliesChainAndPreservesData() {
        helper.createDatabase(TEST_DB_8_11, 8).apply {
            // v8 时代已有记忆行：8→9 创建 echo_memories 前不存在；用 feature_vectors 做保留锚
            execSQL(
                "INSERT INTO feature_vectors (id, userId, schemaVersion, source, windowStart, windowEnd, summaryCiphertext, vector, synced, createdAt, sourcesPresentJson) " +
                    "VALUES ('fv_chain', 'u_test', 'passive-core-v1', 'screen', 0, 300000, 'enc', '[]', 0, 0, '[\"screen\"]')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(
            TEST_DB_8_11, 11, true,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
        )
        // v10：journey_canonical_days 表 + 索引存在且可读写
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='journey_canonical_days'").use { c ->
            assertTrue("9→10 应创建 journey_canonical_days", c.moveToFirst())
        }
        db.execSQL(
            "INSERT INTO journey_canonical_days (id, userId, localDate, payload, createdAtEpochMs) " +
                "VALUES ('cd_1', 'u_test', '2026-08-15', '{}', 0)"
        )
        db.query("SELECT payload FROM journey_canonical_days WHERE id='cd_1'").use { c ->
            assertTrue("canonical day 应可写读", c.moveToFirst())
        }
        // v11：echo_memories 复合索引存在（§109 防全表扫描退化）
        db.query("PRAGMA index_list(echo_memories)").use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            assertTrue("10→11 应建 userId_deleted_importance 索引", names.contains("index_echo_memories_userId_deleted_importance"))
            assertTrue("10→11 应建 userId_type_deleted 索引", names.contains("index_echo_memories_userId_type_deleted"))
        }
        // 旧数据保留锚
        db.query("SELECT id FROM feature_vectors WHERE id='fv_chain'").use { c ->
            assertTrue("8→11 后旧特征行应保留", c.moveToFirst())
        }
        db.close()
    }

    @Test
    fun migrateFrom10To11AddsMemoryIndicesOnly() {
        helper.createDatabase(TEST_DB_10_11, 10).apply { close() }
        val db = helper.runMigrationsAndValidate(TEST_DB_10_11, 11, true, MIGRATION_10_11)
        db.query("PRAGMA index_list(echo_memories)").use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            assertTrue(names.contains("index_echo_memories_userId_deleted_importance"))
            assertTrue(names.contains("index_echo_memories_userId_type_deleted"))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB_2_8 = "migration-test-2-8"
        private const val TEST_DB_7_8 = "migration-test-7-8"
        private const val TEST_DB_8_11 = "migration-test-8-11"
        private const val TEST_DB_10_11 = "migration-test-10-11"
    }
}
