package com.yunjue.echo.mind.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.me.MeEvent
import com.yunjue.echo.mind.me.MeIntelligenceSummary
import com.yunjue.echo.mind.me.MeMemorySummary
import com.yunjue.echo.mind.me.MePresenceSummary
import com.yunjue.echo.mind.me.MeSyncStatus
import com.yunjue.echo.mind.me.MeAssemblyInputs
import com.yunjue.echo.mind.me.MeSyncInputs
import com.yunjue.echo.mind.me.assembleMeUiState
import com.yunjue.echo.mind.me.combine7
import com.yunjue.echo.mind.me.MeUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ERA 13.1 §31/§32 — MeViewModel：Me 根页面唯一业务持有者。
 *
 * MeScreen 不再直接持有 ConsentRepository/EscalationRepository/SyncWorker/权限编排/
 * 麦克风状态/感知生命周期。根页面只组合子领域 + 展示摘要。
 */
class MeViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    private val _message = MutableStateFlow<String?>(null)
    private val _showSupportConfirm = MutableStateFlow(false)

    private val sensingEnabled = container.preferences.passiveSensingEnabledFlow()
    private val micEnabled = container.preferences.micEnabledFlow()
    private val pendingCount = container.syncStateRepository.observePendingCount()
    private val escalations = container.escalationRepository.observeEscalations()
    private val memories = container.memoryRepository.observeMemories()

    /** 单一 UI 状态（纯函数装配；Screen 只消费）。 */
    val uiState: StateFlow<MeUiState> = combine7(
        sensingEnabled,
        micEnabled,
        pendingCount,
        escalations,
        memories,
        _message,
        _showSupportConfirm,
    ).map { (sensing, mic, pending, esc, mems, message, showSupportConfirm) ->
        assembleMeUiState(
            MeAssemblyInputs(
                sensingEnabled = sensing,
                micEnabled = mic,
                pendingCount = pending,
                escalations = esc,
                memories = mems,
                message = message,
                showSupportConfirm = showSupportConfirm,
                sync = MeSyncInputs(
                    networkAvailable = isNetworkAvailable(getApplication()),
                    deadLetterCount = container.preferences.deadLetterCount(),
                    authBlocked = container.preferences.authRequired,
                    consentBlocked = container.preferences.lastSyncErrorClass == "consent",
                    retrying = container.preferences.lastSyncErrorClass == "retryable",
                    lastCollectionTs = container.preferences.lastCollectionTimestamp,
                    lastSyncTs = container.preferences.lastSuccessfulSyncAt,
                    lastPartialSyncTs = container.preferences.lastPartialSyncAt,
                    lastPersistenceFailureTs = container.preferences.lastPersistenceFailure,
                    consecutiveFailures = container.preferences.consecutivePersistenceFailures,
                ),
                presence = MePresenceSummary(
                    motionLevel = container.preferences.presenceMotionLevel,
                    nightMode = container.preferences.presenceNightMode,
                    reduceMotion = container.preferences.presenceReduceMotion,
                    suggestionsEnabled = container.preferences.presenceSuggestionsEnabled,
                ),
                storedProvider = container.aiProviderManager.stored(),
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeUiState(
            sensingEnabled = false,
            micEnabled = false,
            sync = MeSyncStatus(),
            presence = MePresenceSummary(),
            intelligence = MeIntelligenceSummary(),
            memory = MeMemorySummary(),
        ),
    )

    init {
        // 人工支持状态刷新（本地模式跳过；与旧 LaunchedEffect(escalations.size) 语义一致）
        viewModelScope.launch {
            escalations.distinctUntilChanged().collect { list ->
                if (!container.preferences.localMode) {
                    list.filter { !it.serverEscalationId.isNullOrBlank() }.forEach { esc ->
                        runCatching {
                            container.escalationRepository.refreshEscalationStatus(esc.serverEscalationId!!)
                        }
                    }
                }
            }
        }
    }

    /** Screen 唯一交互入口。 */
    fun onEvent(event: MeEvent) {
        when (event) {
            MeEvent.RequestSupportClicked -> _showSupportConfirm.value = true
            MeEvent.SupportDismissed -> _showSupportConfirm.value = false
            MeEvent.SupportConfirmed -> {
                _showSupportConfirm.value = false
                requestSupport()
            }
            MeEvent.ConsumeMessage -> _message.value = null
        }
    }

    private fun requestSupport() {
        if (container.preferences.localMode) {
            _message.value = "尚未开通订阅，无法发送支持请求（数据仅保存在本机）。请先在上方开通订阅。"
            return
        }
        if (container.preferences.subscriptionExpired) {
            _message.value = "订阅已到期，请续订后再提交支持请求。"
            return
        }
        viewModelScope.launch {
            try {
                container.escalationRepository.requestHumanSupport()
                _message.value = "支持请求已保存，网络恢复后自动送达。"
            } catch (_: Exception) {
                _message.value = "请求暂时未能保存，请稍后重试。"
            }
            SyncWorker.enqueue(getApplication())
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MeViewModel(container.applicationContext as Application, container)
            }
        }
    }
}
