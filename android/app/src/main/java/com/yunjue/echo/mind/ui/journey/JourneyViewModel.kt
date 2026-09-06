package com.yunjue.echo.mind.ui.journey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.MemoryRepository
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyMemoryAssemblyInputs
import com.yunjue.echo.mind.journey.JourneyNarrative
import com.yunjue.echo.mind.journey.JourneyPort
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyRuntimeSnapshot
import com.yunjue.echo.mind.journey.JourneySyncStatus
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.assembleJourneyUiState
import com.yunjue.echo.mind.journey.journeyWindowDays
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ERA 13 §23 + ERA 16 §83/§84 — JourneyViewModel：Journey 唯一业务逻辑持有者。
 *
 * 分层：JourneyScreen → JourneyViewModel → JourneyRepository（应用服务）→ 数据实现。
 * Screen 不再直接持有任何 Repository / AiNarrativeService / ContextRetriever / AppPreferences；
 * 不再用 LaunchedEffect 编排业务（§27/§29）。
 */
class JourneyViewModel(
    app: Application,
    private val repository: JourneyPort,
    /** 成长段记忆计数源（可空；未注入时计数恒 null → UI 弃权显示 "—"）。 */
    private val memoryRepository: MemoryRepository? = null,
) : AndroidViewModel(app) {

    private val _scale = MutableStateFlow(JourneyScale.DAY)
    private val _showEvidence = MutableStateFlow(false)
    private val _narrative = MutableStateFlow<JourneyNarrative?>(null)
    private val _runtime = MutableStateFlow<JourneyRuntimeSnapshot?>(null)
    /** ERA 16 §78/§86：用户自述特殊日期（date → kind）。 */
    private val _contextExceptions = MutableStateFlow<Map<String, String>>(emptyMap())
    /** ERA 16 §84：历史重建选中日期（null = 未选择）。 */
    private val _selectedDayDate = MutableStateFlow<String?>(null)

    private val consent = repository.consentFlow
    private val permissionEnabled = repository.permissionEnabledFlow
    private val journeySeed = repository.journeySeed()
    private val canonicalDays = repository.canonicalDays

    /** 画像时间线（随尺度切换窗口）。 */
    private val timeline: kotlinx.coroutines.flow.Flow<PortraitTimelineUiState> = _scale
        .map { journeyWindowDays(it) }
        .distinctUntilChanged()
        .flatMapLatest { days -> repository.timeline(days) }

    /** ERA 72 §108：365 天长历史装配只在记忆输入变化时执行（UI 轻量输入变化零重算）。 */
    private val memoryState: kotlinx.coroutines.flow.Flow<com.yunjue.echo.mind.journey.JourneyMemoryState> =
        combine(
            _scale,
            timeline,
            canonicalDays,
            _contextExceptions,
            _selectedDayDate,
        ) { scale, tl, canonical, exceptions, selectedDate ->
            com.yunjue.echo.mind.journey.assembleJourneyMemoryState(
                scale = scale,
                timeline = tl,
                memory = com.yunjue.echo.mind.journey.JourneyMemoryAssemblyInputs(
                    canonicalDays = canonical,
                    contextExceptions = exceptions,
                    selectedDayDate = selectedDate,
                ),
                identitySeed = journeySeed,
            )
        }.distinctUntilChanged()

    /** 成长段：已记住片段数（observeMemories 实时计数，排除软删；未注入记忆源时恒 null）。 */
    private val rememberedFragments: Flow<Int?> = memoryRepository
        ?.observeMemories()
        ?.map { list -> list.count { !it.deleted } }
        ?: flowOf(null)

    /** UI 轻量输入打包（combine 类型化重载上限 5 流；memoryState 重计算流单独成流）。 */
    private data class JourneyLightInputs(
        val permissionEnabled: Boolean,
        val narrative: JourneyNarrative?,
        val runtime: JourneyRuntimeSnapshot?,
        val showEvidence: Boolean,
        val rememberedFragmentsCount: Int?,
    )

    private val lightInputs: Flow<JourneyLightInputs> = combine(
        permissionEnabled,
        _narrative,
        _runtime,
        _showEvidence,
        rememberedFragments,
    ) { perm, narrative, runtime, showEvidence, remembered ->
        JourneyLightInputs(perm, narrative, runtime, showEvidence, remembered)
    }

    /** 单一 UI 状态（纯函数装配；Screen 只消费）。 */
    val uiState: StateFlow<JourneyUiState> = combine(memoryState, lightInputs) { mem, light ->
        val snapshot = light.runtime
        val sync = JourneySyncStatus(
            lastCollectedAt = snapshot?.availability?.lastCollectedAt ?: 0L,
            lastSyncedAt = snapshot?.availability?.lastSyncedAt ?: 0L,
            pendingUploads = snapshot?.diagnostics?.pendingUploadCount ?: 0,
            persistenceFailures = snapshot?.diagnostics?.consecutivePersistenceFailures ?: 0,
            consent = light.permissionEnabled,
            permissionEnabled = light.permissionEnabled,
        )
        assembleJourneyUiState(
            memoryState = mem,
            permissionEnabled = light.permissionEnabled,
            narrative = light.narrative,
            runtimeAvailability = snapshot?.availability,
            runtimeDiagnostics = snapshot?.diagnostics,
            showEvidence = light.showEvidence,
            intelligenceAvailable = repository.intelligenceAvailable(),
            syncStatus = sync,
            journeySeed = journeySeed,
        ).copy(rememberedFragmentsCount = light.rememberedFragmentsCount)
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

    /** Screen 唯一交互入口（§25 + §84）。 */
    fun onEvent(event: JourneyEvent) {
        when (event) {
            is JourneyEvent.SelectScale -> {
                _scale.value = event.scale
                _selectedDayDate.value = null
            }
            JourneyEvent.Refresh -> refresh()
            JourneyEvent.ToggleEvidence -> _showEvidence.value = !_showEvidence.value
            JourneyEvent.AskAboutPeriod, JourneyEvent.RetryNarrative -> regenerateNarrative()
            is JourneyEvent.SelectDay ->
                _selectedDayDate.value = if (_selectedDayDate.value == event.date) null else event.date
        }
    }

    /** 画像反馈标记（读；供视觉记忆单元展示 ✓/✗）。 */
    fun feedback(date: String): Boolean? = repository.feedback(date)

    private fun refresh() {
        viewModelScope.launch {
            repository.refresh(journeyWindowDays(_scale.value))
            _runtime.value = repository.runtimeSnapshot(consent.first())
            // ERA 16 §83：进入 Journey 即快照今天的 Canonical Daily State（幂等覆盖）
            runCatching { repository.snapshotToday() }
            // ERA 16 §86：用户自述特殊日期进入时间线
            _contextExceptions.value = runCatching { repository.contextExceptions() }.getOrDefault(emptyMap())
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
                    container.memoryRepository,
                )
            }
        }
    }
}
