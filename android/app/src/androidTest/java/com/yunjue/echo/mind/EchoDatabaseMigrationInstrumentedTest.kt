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

    companion object {
        private const val TEST_DB_2_8 = "migration-test-2-8"
        private const val TEST_DB_7_8 = "migration-test-7-8"
    }
}
