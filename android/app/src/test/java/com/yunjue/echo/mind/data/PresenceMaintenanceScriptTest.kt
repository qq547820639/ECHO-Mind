package com.yunjue.echo.mind.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 46 §76 复核——维护序列执行锚点：
 * 四步固定顺序 / 任一步异常 fail-closed（不阻断后续步骤）/
 * 全步异常仍正常完成（worker 恒返回 success 的语义等价）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceMaintenanceScriptTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var failAt: String? = null

        fun step(name: String): suspend () -> Unit = {
            calls += name
            if (failAt == name) throw IllegalStateException("injected failure at $name")
        }
    }

    @Test
    fun stepsRunInFixedOrder() = runTest {
        val recorder = Recorder()
        PresenceMaintenanceScript(
            refresh = recorder.step("refresh"),
            snapshotToday = recorder.step("snapshotToday"),
            purgeExpired = recorder.step("purgeExpired"),
            derivePatterns = recorder.step("derivePatterns"),
        ).run()
        assertEquals(
            listOf("refresh", "snapshotToday", "purgeExpired", "derivePatterns"),
            recorder.calls,
        )
    }

    @Test
    fun midStepFailureDoesNotBlockRemainingSteps() = runTest {
        val recorder = Recorder().apply { failAt = "snapshotToday" }
        PresenceMaintenanceScript(
            refresh = recorder.step("refresh"),
            snapshotToday = recorder.step("snapshotToday"),
            purgeExpired = recorder.step("purgeExpired"),
            derivePatterns = recorder.step("derivePatterns"),
        ).run()
        assertEquals(
            listOf("refresh", "snapshotToday", "purgeExpired", "derivePatterns"),
            recorder.calls,
        )
    }

    @Test
    fun allStepsFailingStillCompletes() = runTest {
        val failures = listOf("refresh", "snapshotToday", "purgeExpired", "derivePatterns")
        var completed = false
        runCatching {
            PresenceMaintenanceScript(
                refresh = { throw IllegalStateException(failures[0]) },
                snapshotToday = { throw IllegalStateException(failures[1]) },
                purgeExpired = { throw IllegalStateException(failures[2]) },
                derivePatterns = { throw IllegalStateException(failures[3]) },
            ).run()
            completed = true
        }
        assertTrue("全步异常后脚本仍应正常完成（worker 恒 success）", completed)
    }
}
