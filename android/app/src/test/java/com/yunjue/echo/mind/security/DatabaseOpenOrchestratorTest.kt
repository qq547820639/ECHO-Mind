package com.yunjue.echo.mind.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ERA 17 §91/§92 — DatabaseOpenOrchestrator 迁移链决策矩阵（纯 JVM）：
 *
 * fresh install / old DB 迁移全链 / 已迁移 fail-closed / 非错钥异常直抛 /
 * 迁移失败下次重试 / verify 失败 / legacy 不可用 fail-closed。
 */
class DatabaseOpenOrchestratorTest {

    private class WrongKeyException : Exception("file is not a database (wrong key)")

    private class NonKeyException : Exception("io failure")

    /** 记录事件顺序的假 DB。 */
    private class FakeDb(val events: MutableList<String>)

    private data class Harness(
        val newKey: ByteArray = ByteArray(32) { 1 },
        val legacyKey: ByteArray = ByteArray(32) { 2 },
        val rotatedKey: ByteArray = ByteArray(32) { 3 },
        var migrated: Boolean = false,
        var legacyAvailable: Boolean = true,
        var failNewOpen: Throwable? = WrongKeyException(),
        var failLegacyOpen: Throwable? = null,
        var failRekey: Throwable? = null,
        var verifyResult: Boolean = true,
        val events: MutableList<String> = mutableListOf(),
    ) {
        var rotateCalls = 0
        var markCalls = 0
        var retireCalls = 0
        var legacyDeriveCalls = 0

        fun run(): FakeDb {
            val io = DatabaseIo<FakeDb>(
                build = { key ->
                    events.add("build")
                    failNewOpen?.let { if (key === newKey) throw it }
                    failLegacyOpen?.let { if (key === legacyKey) throw it }
                    FakeDb(events)
                },
                rekey = { _, key ->
                    events.add("rekey")
                    failRekey?.let { throw it }
                    assertArrayEquals(rotatedKey, key)
                },
                verify = { db ->
                    events.add("verify")
                    verifyResult
                },
            )
            val inputs = DatabaseOpenInputs(
                deriveNew = {
                    events.add("deriveNew")
                    newKey
                },
                deriveLegacy = {
                    events.add("deriveLegacy")
                    legacyDeriveCalls++
                    if (legacyAvailable) legacyKey else null
                },
                isMigrated = {
                    events.add("isMigrated")
                    migrated
                },
                isWrongKey = { it is WrongKeyException },
            )
            val actions = DatabaseMigrationActions(
                rotateSecret = {
                    events.add("rotate")
                    rotateCalls++
                    rotatedKey
                },
                markMigrated = {
                    events.add("markMigrated")
                    markCalls++
                    migrated = true
                },
                retireAncient = {
                    events.add("retireAncient")
                    retireCalls++
                },
            )
            return DatabaseOpenOrchestrator.open(inputs, actions, io)
        }
    }

    @Test
    fun freshInstallOpensWithNewPathOnly() {
        val h = Harness(failNewOpen = null)
        val db = h.run()
        assertTrue(db.events.isNotEmpty())
        assertEquals(listOf("deriveNew", "build"), h.events)
        assertEquals(0, h.rotateCalls)
        assertEquals(0, h.markCalls)
        assertEquals(0, h.legacyDeriveCalls)
    }

    @Test
    fun oldDatabaseMigratesThroughFullChain() {
        val h = Harness()
        h.run()
        // §91 全链顺序：derive new → build(new) 失败 → isMigrated → derive legacy → build(legacy)
        // → rotate → rekey → verify → markMigrated → retireAncient
        assertEquals(
            listOf(
                "deriveNew", "build", "isMigrated", "deriveLegacy", "build",
                "rotate", "rekey", "verify", "markMigrated", "retireAncient"
            ),
            h.events
        )
        assertEquals(1, h.rotateCalls)
        assertEquals(1, h.markCalls)
        assertEquals(1, h.retireCalls)
        assertTrue(h.migrated)
    }

    @Test
    fun migratedDatabaseNeverFallsBackToLegacy() {
        val h = Harness(migrated = true)
        try {
            h.run()
            fail("已迁移库错误口令必须 fail-closed")
        } catch (expected: WrongKeyException) {
            // expected
        }
        // legacy 派生被退役：deriveLegacy 未被调用
        assertEquals(0, h.legacyDeriveCalls)
        assertEquals(0, h.rotateCalls)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun nonKeyErrorPropagatesImmediately() {
        val h = Harness(failNewOpen = NonKeyException())
        try {
            h.run()
            fail("非错钥异常必须直抛")
        } catch (expected: NonKeyException) {
            // expected
        }
        assertEquals(0, h.legacyDeriveCalls)
    }

    @Test
    fun rekeyFailureRethrowsOriginalAndDoesNotMarkMigrated() {
        val h = Harness(failRekey = IllegalStateException("rekey failure"))
        try {
            h.run()
            fail("rekey 失败必须抛原始错钥异常")
        } catch (expected: WrongKeyException) {
            // expected —— 下次启动可重试迁移
        }
        assertEquals(1, h.rotateCalls)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun verifyFailureRethrowsOriginalAndDoesNotMarkMigrated() {
        val h = Harness(verifyResult = false)
        try {
            h.run()
            fail("verify 失败必须抛原始错钥异常")
        } catch (expected: WrongKeyException) {
            // expected
        }
        assertEquals(0, h.markCalls)
    }

    @Test
    fun legacyUnavailableFailsClosed() {
        val h = Harness(legacyAvailable = false)
        try {
            h.run()
            fail("无 legacy 口令必须 fail-closed")
        } catch (expected: WrongKeyException) {
            // expected
        }
        assertEquals(0, h.rotateCalls)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun newKdfDerivationFailurePropagatesFailClosed() {
        // Keystore 不可用 → deriveNew 抛错 → 直抛（绝不回退明文/legacy）
        val io = DatabaseIo<FakeDb>(
            build = { throw AssertionError("新派生失败时不得尝试打开") },
            rekey = { _, _ -> },
            verify = { true },
        )
        val inputs = DatabaseOpenInputs(
            deriveNew = { throw IllegalStateException("AndroidKeyStore unavailable") },
            deriveLegacy = { throw AssertionError("不得回退 legacy") },
            isMigrated = { false },
            isWrongKey = { it is WrongKeyException },
        )
        val actions = DatabaseMigrationActions(
            rotateSecret = { throw AssertionError("不得轮换") },
            markMigrated = {},
            retireAncient = {},
        )
        try {
            DatabaseOpenOrchestrator.open(inputs, actions, io)
            fail("deriveNew 抛错必须直抛")
        } catch (expected: IllegalStateException) {
            assertEquals("AndroidKeyStore unavailable", expected.message)
        }
    }
}
