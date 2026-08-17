package com.yunjue.echo.mind

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 48——SyncWorker outbox 生命周期矩阵补测：derived_feature 上传速率槽位。
 *
 * 滑动 1 分钟窗口 / 每窗口上限 20（DF_MAX_PER_WINDOW）：
 * - 新窗口：连续 20 次 acquire=true，第 21 次=false（调用方应 Result.retry() 延迟发送）；
 * - 计数跨调用持久化（同 SharedPreferences 递增）；
 * - 窗口过期（>60s）→ 新窗口重置计数。
 * 无 sleep：窗口重置路径直接改写 SharedPreferences 内的窗口起点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncWorkerRateLimitSlotTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun freshWindowAllowsExactlyTwentySlots() {
        repeat(20) { i ->
            assertTrue("第 ${i + 1} 次应取得槽位", SyncWorker.acquireDerivedFeatureSlot(context))
        }
        assertFalse("第 21 次应被限流", SyncWorker.acquireDerivedFeatureSlot(context))
    }

    @Test
    fun slotCountPersistsAcrossCalls() {
        repeat(3) { assertTrue(SyncWorker.acquireDerivedFeatureSlot(context)) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertEquals(3, prefs.getInt("df_count", 0))
        repeat(17) { assertTrue(SyncWorker.acquireDerivedFeatureSlot(context)) }
        assertEquals(20, prefs.getInt("df_count", 0))
        assertFalse(SyncWorker.acquireDerivedFeatureSlot(context))
    }

    @Test
    fun expiredWindowResetsCount() {
        repeat(20) { assertTrue(SyncWorker.acquireDerivedFeatureSlot(context)) }
        assertFalse(SyncWorker.acquireDerivedFeatureSlot(context))

        // 将窗口起点拨回 61 秒前 → 下一次 acquire 应开启新窗口并重置计数
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong("df_window_start", System.currentTimeMillis() - 61_000L).commit()

        assertTrue("窗口过期后应重新获得槽位", SyncWorker.acquireDerivedFeatureSlot(context))
        assertEquals("新窗口计数应从 1 重新开始", 1, prefs.getInt("df_count", -1))
        // 新窗口剩余 19 个槽位可继续消费
        repeat(19) { assertTrue("新窗口剩余槽位应可用", SyncWorker.acquireDerivedFeatureSlot(context)) }
        assertFalse("新窗口第 21 次应再次限流", SyncWorker.acquireDerivedFeatureSlot(context))
    }

    private companion object {
        const val PREFS = "echo_mind_sync_ratelimit"
    }
}
