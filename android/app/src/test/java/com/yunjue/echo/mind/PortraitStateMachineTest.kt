package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.PortraitStateInputs
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.mapServerStatus
import com.yunjue.echo.mind.model.resolveTodayPortraitState
import com.yunjue.echo.mind.model.todayLocalDateString
import com.yunjue.echo.mind.model.todayPortraitStateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Milestone F/H：Today Portrait 九态状态机 + 九态文案 + 时区安全日期（纯 JVM）。
 *
 * 覆盖（spec）：
 * - 感知开关关闭 → SENSING_DISABLED（最高优先级）
 * - 无网络 / 请求失败 → 有缓存 OFFLINE_CACHED、无缓存 ERROR
 * - 服务端 status → READY / WARMING_UP / EARLY_BASELINE / PARTIAL_DATA / LOW_CONFIDENCE
 * - Cold start 第 1 天 / 第 8 天状态映射（WARMING_UP / READY）
 * - 九态文案与 spec 逐字一致；禁止统一显示「暂无数据」
 * - timezone 修改后的 date 处理（同一瞬时在不同时区得到不同本地日期）
 */
class PortraitStateMachineTest {

    private fun inputs(
        sensingEnabled: Boolean = true,
        loading: Boolean = false,
        hasCache: Boolean = false,
        networkAvailable: Boolean = true,
        fetchFailed: Boolean = false,
        serverStatus: String? = null
    ) = PortraitStateInputs(
        sensingEnabled = sensingEnabled,
        loading = loading,
        hasCache = hasCache,
        networkAvailable = networkAvailable,
        fetchFailed = fetchFailed,
        serverStatus = serverStatus
    )

    // ---------- 状态判定（spec 优先级） ----------

    @Test
    fun sensingDisabledTakesPriorityOverEverything() {
        // 感知关闭时即使有缓存 + 在线，也必须 SENSING_DISABLED
        assertEquals(
            PortraitStatus.SENSING_DISABLED,
            resolveTodayPortraitState(inputs(sensingEnabled = false, hasCache = true, serverStatus = "READY"))
        )
        assertEquals(
            PortraitStatus.SENSING_DISABLED,
            resolveTodayPortraitState(inputs(sensingEnabled = false, loading = true))
        )
    }

    @Test
    fun loadingShowsSpinnerOnlyWithoutCache() {
        assertEquals(PortraitStatus.LOADING, resolveTodayPortraitState(inputs(loading = true, hasCache = false)))
        // 加载中但有缓存：先显示缓存（OFFLINE_CACHED，无离线横幅由 offline 标志控制）
        assertEquals(PortraitStatus.OFFLINE_CACHED, resolveTodayPortraitState(inputs(loading = true, hasCache = true)))
    }

    @Test
    fun offlineWithoutNetworkUsesCacheOrErrors() {
        // 无网络且 Room 有缓存 → OFFLINE_CACHED
        assertEquals(
            PortraitStatus.OFFLINE_CACHED,
            resolveTodayPortraitState(inputs(networkAvailable = false, hasCache = true))
        )
        // 无网络且无缓存 → ERROR（绝不伪装成功）
        assertEquals(
            PortraitStatus.ERROR,
            resolveTodayPortraitState(inputs(networkAvailable = false, hasCache = false))
        )
    }

    @Test
    fun fetchFailureUsesCacheOrErrors() {
        assertEquals(
            PortraitStatus.OFFLINE_CACHED,
            resolveTodayPortraitState(inputs(fetchFailed = true, hasCache = true))
        )
        assertEquals(
            PortraitStatus.ERROR,
            resolveTodayPortraitState(inputs(fetchFailed = true, hasCache = false))
        )
    }

    @Test
    fun serverStatusMapsToClientStates() {
        assertEquals(PortraitStatus.WARMING_UP, resolveTodayPortraitState(inputs(serverStatus = "WARMING_UP")))
        assertEquals(PortraitStatus.EARLY_BASELINE, resolveTodayPortraitState(inputs(serverStatus = "EARLY_BASELINE")))
        assertEquals(PortraitStatus.READY, resolveTodayPortraitState(inputs(serverStatus = "READY")))
        assertEquals(PortraitStatus.PARTIAL_DATA, resolveTodayPortraitState(inputs(serverStatus = "PARTIAL_DATA")))
        assertEquals(PortraitStatus.LOW_CONFIDENCE, resolveTodayPortraitState(inputs(serverStatus = "LOW_CONFIDENCE")))
    }

    @Test
    fun unknownOrMissingServerStatusFailsClosedToError() {
        assertEquals(PortraitStatus.ERROR, resolveTodayPortraitState(inputs(serverStatus = "UNKNOWN_STATUS")))
        assertEquals(PortraitStatus.ERROR, resolveTodayPortraitState(inputs(serverStatus = null)))
        assertEquals(PortraitStatus.ERROR, resolveTodayPortraitState(inputs(serverStatus = "")))
    }

    // ---------- Cold start 第 1 天 / 第 8 天映射（mapServerStatus） ----------

    @Test
    fun coldStartDay1MapsToWarmingUp() {
        // 第 1 天：服务端基线未成型 → WARMING_UP
        assertEquals(PortraitStatus.WARMING_UP, mapServerStatus("WARMING_UP"))
        assertNull("未知 status 应返回 null（由调用方 fail-closed）", mapServerStatus("SOME_NEW_STATUS"))
    }

    @Test
    fun coldStartDay8MapsToReady() {
        // 第 8 天：基线成型 → READY
        assertEquals(PortraitStatus.READY, mapServerStatus("READY"))
    }

    // ---------- 九态文案（spec 逐字一致，单测锚点） ----------

    @Test
    fun warmingUpCopyMatchesSpec() {
        assertEquals(
            "ECHO 正在慢慢了解你的日常节奏。再积累几天，就能开始比较“今天”和“平常的你”。",
            todayPortraitStateText(PortraitStatus.WARMING_UP)
        )
    }

    @Test
    fun partialDataCopyMatchesSpec() {
        assertEquals(
            "今天的数据还不完整，以下画像仅反映已经采集到的部分。",
            todayPortraitStateText(PortraitStatus.PARTIAL_DATA)
        )
    }

    @Test
    fun offlineCachedCopyMatchesSpec() {
        assertEquals("当前离线，显示最近一次生成的画像。", todayPortraitStateText(PortraitStatus.OFFLINE_CACHED))
    }

    @Test
    fun sensingDisabledCopyMatchesSpec() {
        assertEquals("被动感知已关闭。", todayPortraitStateText(PortraitStatus.SENSING_DISABLED))
    }

    @Test
    fun errorCopyMatchesSpec() {
        assertEquals("加载失败", todayPortraitStateText(PortraitStatus.ERROR))
    }

    @Test
    fun contentDrivenStatesHaveNoGenericCopy() {
        // LOADING 为 spinner；EARLY_BASELINE / LOW_CONFIDENCE / READY 为内容驱动 → 无统一文案
        assertEquals("", todayPortraitStateText(PortraitStatus.LOADING))
        assertEquals("", todayPortraitStateText(PortraitStatus.EARLY_BASELINE))
        assertEquals("", todayPortraitStateText(PortraitStatus.LOW_CONFIDENCE))
        assertEquals("", todayPortraitStateText(PortraitStatus.READY))
    }

    @Test
    fun neverShowsGenericNoDataCopy() {
        // spec：禁止统一显示「暂无数据」——九态文案中不得出现该字样
        for (status in PortraitStatus.entries) {
            val text = todayPortraitStateText(status)
            assertTrue(
                "状态 ${status.name} 文案不得为「暂无数据」：$text",
                text != "暂无数据" && !text.contains("暂无数据")
            )
        }
    }

    // ---------- timezone 修改后的 date 处理（Milestone H） ----------

    @Test
    fun sameInstantYieldsDifferentLocalDatesAcrossTimezones() {
        // 2026-08-10T16:30:00Z：上海已进入 8 月 11 日，洛杉矶（夏令时 UTC-7）仍是 8 月 10 日
        val instant = Instant.parse("2026-08-10T16:30:00Z")
        assertEquals("2026-08-11", todayLocalDateString(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-08-10", todayLocalDateString(instant, ZoneId.of("America/Los_Angeles")))
        assertEquals("2026-08-10", todayLocalDateString(instant, ZoneOffset.UTC))
    }

    @Test
    fun dateKeyChangesWithDeviceTimezoneSoStaleCacheIsNotReused() {
        // 端侧「今天」缓存键 = 本地时区日期；时区修改（例如 UTC → Asia/Shanghai）后，
        // 同一瞬时对应的键值不同 → 旧时区缓存的"今天"不再命中，触发重新拉取。
        val instant = Instant.parse("2026-08-10T17:00:00Z")
        val keyBefore = todayLocalDateString(instant, ZoneOffset.UTC)
        val keyAfter = todayLocalDateString(instant, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-08-10", keyBefore)
        assertEquals("2026-08-11", keyAfter)
        assertTrue("时区修改后缓存键必须变化", keyBefore != keyAfter)
    }
}
