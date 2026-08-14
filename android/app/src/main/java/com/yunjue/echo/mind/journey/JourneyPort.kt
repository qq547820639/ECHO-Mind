package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * ERA 13 §26 — Journey 数据端口。
 *
 * JourneyViewModel 只依赖本端口；[JourneyRepository]（应用服务）是当前唯一实现。
 * ERA 13.2 全局 Domain Ports 收口时与此对齐（ObservationEvidenceSource 等）。
 * 测试以 fake 实现替换（§102 测试矩阵）。
 */
interface JourneyPort {
    val consentFlow: Flow<Boolean>
    val permissionEnabledFlow: Flow<Boolean>
    fun timeline(days: Int): StateFlow<PortraitTimelineUiState>
    suspend fun refresh(days: Int)
    suspend fun runtimeSnapshot(consent: Boolean): JourneyRuntimeSnapshot
    suspend fun narrativeFor(portraits: List<DailyPortraitDto>): JourneyNarrative
    fun feedback(date: String): Boolean?
    fun journeySeed(): Long
    fun intelligenceAvailable(): Boolean
}
