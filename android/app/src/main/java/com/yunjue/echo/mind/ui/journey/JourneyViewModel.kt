package com.yunjue.echo.mind.ui.journey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyNarrative
import com.yunjue.echo.mind.journey.JourneyPort
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyRuntimeSnapshot
import com.yunjue.echo.mind.journey.JourneySyncStatus
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.assembleJourneyUiState
import com.yunjue.echo.mind.journey.journeyWindowDays
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ERA 13 §23 — JourneyViewModel：Journey 唯一业务逻辑持有者。
 *
 * 分层：JourneyScreen → JourneyViewModel → JourneyRepository（应用服务）→ 数据实现。
 * Screen 不再直接持有任何 Repository / AiNarrativeService / ContextRetriever / AppPreferences；
 * 不再用 LaunchedEffect 编排业务（§27/§29）。
 */
class JourneyViewModel(
    app: Application,
    private val repository: JourneyPort,
) : AndroidViewModel(app) {

    private val _scale = MutableStateFlow(JourneyScale.DAY)
    private val _showEvidence = MutableStateFlow(false)
    private val _narrative = MutableStateFlow<JourneyNarrative?>(null)
    private val _runtime = MutableStateFlow<JourneyRuntimeSnapshot?>(null)

    private val consent = repository.consentFlow
    private val permissionEnabled = repository.permissionEnabledFlow
    private val journeySeed = repository.journeySeed()

    /** 画像时间线（随尺度切换窗口）。 */
    private val timeline: kotlinx.coroutines.flow.Flow<PortraitTimelineUiState> = _scale
        .map { journeyWindowDays(it) }
        .distinctUntilChanged()
        .flatMapLatest { days -> repository.timeline(days) }

    /** combine 中间态（5 流 + 1 流，因 combine 最多 5 个参数）。 */
    private data class CombineCore(
        val scale: JourneyScale,
        val timeline: PortraitTimelineUiState,
        val permissionEnabled: Boolean,
        val narrative: JourneyNarrative?,
        val runtime: JourneyRuntimeSnapshot?,
    )

    /** 单一 UI 状态（纯函数装配；Screen 只消费）。 */
    val uiState: StateFlow<JourneyUiState> = combine(
        _scale,
        timeline,
        permissionEnabled,
        _narrative,
        _runtime,
    ) { scale, tl, perm, narrative, runtime ->
        CombineCore(scale = scale, timeline = tl, permissionEnabled = perm, narrative = narrative, runtime = runtime)
    }.combine(_showEvidence) { core, showEvidence ->
        val snapshot = core.runtime
        val sync = JourneySyncStatus(
            lastCollectedAt = snapshot?.availability?.lastCollectedAt ?: 0L,
            lastSyncedAt = snapshot?.availability?.lastSyncedAt ?: 0L,
            pendingUploads = snapshot?.diagnostics?.pendingUploadCount ?: 0,
            persistenceFailures = snapshot?.diagnostics?.consecutivePersistenceFailures ?: 0,
            consent = core.permissionEnabled,
            permissionEnabled = core.permissionEnabled,
        )
        assembleJourneyUiState(
            scale = core.scale,
            timeline = core.timeline,
            permissionEnabled = core.permissionEnabled,
            narrative = core.narrative,
            runtimeAvailability = snapshot?.availability,
            runtimeDiagnostics = snapshot?.diagnostics,
            showEvidence = showEvidence,
            intelligenceAvailable = repository.intelligenceAvailable(),
            syncStatus = sync,
            journeySeed = journeySeed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = assembleJourneyUiState(
            scale = JourneyScale.DAY,
            timeline = PortraitTimelineUiState(),
            permissionEnabled = false,
            narrative = null,
            runtimeAvailability = null,
            runtimeDiagnostics = null,
            showEvidence = false,
            intelligenceAvailable = false,
            syncStatus = JourneySyncStatus(),
            journeySeed = journeySeed,
        ),
    )

    init {
        onEvent(JourneyEvent.Refresh)
        // 画像时间线变化 → 自动重新生成长期叙事（AI 失败自动降级，见 AiNarrativeService）
        viewModelScope.launch {
            timeline.map { it.portraits }
                .distinctUntilChanged()
                .collect { portraits ->
                    if (portraits.isNotEmpty()) {
                        _narrative.value = repository.narrativeFor(portraits)
                    }
                }
        }
    }

    /** Screen 唯一交互入口（§25）。 */
    fun onEvent(event: JourneyEvent) {
        when (event) {
            is JourneyEvent.SelectScale -> _scale.value = event.scale
            JourneyEvent.Refresh -> refresh()
            JourneyEvent.ToggleEvidence -> _showEvidence.value = !_showEvidence.value
            JourneyEvent.AskAboutPeriod, JourneyEvent.RetryNarrative -> regenerateNarrative()
        }
    }

    /** 画像反馈标记（读；供视觉记忆单元展示 ✓/✗）。 */
    fun feedback(date: String): Boolean? = repository.feedback(date)

    private fun refresh() {
        viewModelScope.launch {
            repository.refresh(journeyWindowDays(_scale.value))
            _runtime.value = repository.runtimeSnapshot(consent.first())
        }
    }

    private fun regenerateNarrative() {
        viewModelScope.launch {
            val portraits = uiState.value.timeline.portraits
            if (portraits.isNotEmpty()) {
                _narrative.value = repository.narrativeFor(portraits)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                JourneyViewModel(
                    container.applicationContext as Application,
                    container.journeyRepository,
                )
            }
        }
    }
}
