package com.yunjue.echo.mind.ports

import com.yunjue.echo.mind.model.BaselineStatusDto
import com.yunjue.echo.mind.model.DailyPortraitDto
import java.time.LocalDate
import java.time.ZoneId

/**
 * ERA 13.2 §38 — Observation Ports。
 *
 * Domain interfaces ↑ data implementations（LocalPortraitDataSource/PortraitRepository 为 Adapter）。
 * intelligence / journey / presence 只依赖这些端口，不再 import data 实现类。
 */

/** 观察证据源：今日画像 + 画像时间线（本地只读）。 */
interface ObservationEvidenceSource {
    suspend fun computeToday(userId: String, today: LocalDate, zoneId: ZoneId): DailyPortraitDto?
    suspend fun computeTimeline(
        userId: String,
        days: Int,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): List<DailyPortraitDto>
}

/** 当前画像源（流式；ECHO Scene 消费今日画像状态）。 */
interface CurrentPortraitSource {
    fun observeTodayPortrait(): kotlinx.coroutines.flow.StateFlow<com.yunjue.echo.mind.model.PortraitUiState>
    suspend fun refreshTodayPortrait(networkAvailable: Boolean = true)
}

/** 画像历史源（时间线；Journey 消费）。 */
interface PortraitHistorySource {
    fun observePortraits(days: Int): kotlinx.coroutines.flow.StateFlow<com.yunjue.echo.mind.model.PortraitTimelineUiState>
    suspend fun refreshPortraits(days: Int)
}

/** 基线状态源（Journey 运行时快照 / intelligence BASELINE 证据）。 */
interface BaselineSource {
    suspend fun fetchBaselineStatus(): BaselineStatusDto?
}
