package com.yunjue.echo.mind.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.EveningReminderWorker
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.me.DataAndSensingEvent
import com.yunjue.echo.mind.me.DataAndSensingUiState
import com.yunjue.echo.mind.me.DataAndSensingAssemblyInputs
import com.yunjue.echo.mind.me.DataRightsInputs
import com.yunjue.echo.mind.me.MeSyncInputs
import com.yunjue.echo.mind.me.MicUiInputs
import com.yunjue.echo.mind.me.SensingUiInputs
import com.yunjue.echo.mind.me.assembleDataAndSensingUiState
import com.yunjue.echo.mind.me.combine9
import kotlinx.coroutines.flow.first
import com.yunjue.echo.mind.model.SensingCapability
import com.yunjue.echo.mind.sensing.capabilityState
import com.yunjue.echo.mind.ui.performPassiveSensingStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ERA 13.1 §33 — DataAndSensingViewModel：信任控制中心业务持有者。
 *
 * permission truth（系统真实能力状态）/ sensing runtime / usage access /
 * notification listener / mic opt-in / pause·resume / system repair / last active /
 * 数据权利（导出/删除/撤回）——全部在此，Section 只渲染。
 */
class DataAndSensingViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    private val _message = MutableStateFlow<String?>(null)
    private val _showMicConfirm = MutableStateFlow(false)
    private val _showLocalDeleteConfirm = MutableStateFlow(false)
    private val _reEnabling = MutableStateFlow(container.preferences.consentSyncPending)
    private val _localCounts = MutableStateFlow(com.yunjue.echo.mind.data.DataFootprint())

    /** ERA 68（ADR-062 第 3 轮）：能力边界事实（「ECHO 还不知道什么」聚合行；init 异步采集）。 */
    private val _knowsFacts = MutableStateFlow(com.yunjue.echo.mind.me.EchoKnowsFacts())

    /** 本地导出 JSON 一次性事件（UI 收集后分享）。 */
    private val _exportJson = MutableSharedFlow<String>()
    val exportJson: SharedFlow<String> = _exportJson

    private val sensingEnabled = container.preferences.passiveSensingEnabledFlow()
    private val micEnabled = container.preferences.micEnabledFlow()
    private val pendingCount = container.syncStateRepository.observePendingCount()

    /** 单一 UI 状态（纯函数装配；Section 只消费）。 */
    val uiState: StateFlow<DataAndSensingUiState> = combine9(
        sensingEnabled,
        micEnabled,
        pendingCount,
        _reEnabling,
        _message,
        _showMicConfirm,
        _showLocalDeleteConfirm,
        _localCounts,
        _knowsFacts,
    ).map { (sensing, mic, pending, reEnabling, message, showMic, showDelete, counts, facts) ->
        assembleDataAndSensingUiState(
            DataAndSensingAssemblyInputs(
                sensing = SensingUiInputs(enabled = sensing, reEnabling = reEnabling),
                mic = MicUiInputs(enabled = mic, showConfirm = showMic),
                knowsFacts = facts,
                showLocalDeleteConfirm = showDelete,
                eveningReminderEnabled = container.preferences.eveningReminderEnabled,
                capabilityStates = SensingCapability.entries.associateWith {
                    capabilityState(getApplication(), it, sensing)
                },
                pendingCount = pending,
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
                rights = DataRightsInputs(
                    footprint = counts,
                    localMode = container.preferences.localMode,
                    institutionCode = container.preferences.institutionCode,
                    userId = container.preferences.userId,
                ),
                message = message,
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DataAndSensingUiState(),
    )

    init {
        refreshLocalCounts()
        refreshKnowsFacts()
    }

    /** ERA 68：能力边界事实采集（感知/麦克风/Provider/基线；页面生命周期内一次快照）。 */
    private fun refreshKnowsFacts() {
        viewModelScope.launch {
            val baselineDays = withContext(Dispatchers.IO) {
                val userId = container.preferences.userId
                if (userId.isBlank()) {
                    0
                } else {
                    runCatching {
                        container.observation.localPortraitDataSource.computeToday(
                            userId = userId,
                            today = java.time.LocalDate.now(),
                            zoneId = java.time.ZoneId.systemDefault(),
                        ).baselineDays
                    }.getOrDefault(0)
                }
            }
            _knowsFacts.value = com.yunjue.echo.mind.me.EchoKnowsFacts(
                sensingEnabled = container.preferences.sensingActive,
                micEnabled = container.preferences.micEnabledFlow().first(),
                providerConfigured = container.echoRuntimeCoordinator.provider.value == com.yunjue.echo.mind.intelligence.ProviderStatus.READY,
                baselineDays = baselineDays,
            )
        }
    }

    /** Section 唯一交互入口。 */
    fun onEvent(event: DataAndSensingEvent) {
        when (event) {
            is DataAndSensingEvent.ToggleSensing -> toggleSensing(event.enabled)
            is DataAndSensingEvent.ToggleMic -> toggleMic(event.enabled)
            DataAndSensingEvent.MicConfirmRequested -> _showMicConfirm.value = true
            DataAndSensingEvent.MicConfirmDismissed -> _showMicConfirm.value = false
            is DataAndSensingEvent.MicPermissionResult -> onMicPermissionResult(event.granted)
            is DataAndSensingEvent.SetEveningReminder -> setEveningReminder(event.enabled)
            DataAndSensingEvent.RequestExport -> requestExport()
            DataAndSensingEvent.RequestDelete -> requestDelete()
            DataAndSensingEvent.ConfirmLocalDelete -> confirmLocalDelete()
            DataAndSensingEvent.DismissLocalDelete -> _showLocalDeleteConfirm.value = false
            DataAndSensingEvent.RevokeConsent -> revokeConsent()
            DataAndSensingEvent.SyncNow -> SyncWorker.enqueue(getApplication())
            DataAndSensingEvent.ConsumeMessage -> _message.value = null
        }
    }

    /** 本地数据导出：本地模式 → exportJson 一次性事件（UI 分享）；云端 → 创建导出请求。 */
    private fun requestExport() {
        viewModelScope.launch {
            if (container.preferences.localMode) {
                val json = runCatching {
                    container.localDataRights.exportLocalData(container.preferences.userId)
                }.getOrElse { """{"error":"export failed"}""" }
                _message.value = "已生成本地数据导出（仅本机处理，无上传）。"
                _exportJson.emit(json)
            } else {
                container.consentRepository.requestDataAction("export")
                SyncWorker.enqueue(getApplication())
                _message.value = "已创建数据导出请求。"
            }
        }
    }

    private fun toggleSensing(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                ServiceRevocationCoordinator.reEnablePassiveSensing(
                    getApplication(), container.preferences, container.consentRepository,
                    container.featureFlagRepository
                )
                _reEnabling.value = container.preferences.consentSyncPending
                _message.value = "被动感知已开启，等待授权同步…"
            } else {
                performPassiveSensingStop(getApplication(), container.preferences, container.consentRepository)
                _reEnabling.value = false
                _message.value = "已停止"
            }
        }
    }

    private fun toggleMic(checked: Boolean) {
        if (checked) {
            _showMicConfirm.value = true
            return
        }
        viewModelScope.launch {
            container.preferences.setMicEnabled(false)
            runCatching { container.consentRepository.saveVoiceFeaturesConsent(false) }
            SyncWorker.enqueue(getApplication())
            _message.value = "麦克风已关闭。"
        }
    }

    private fun onMicPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) {
                container.preferences.setMicEnabled(true)
                runCatching { container.consentRepository.saveVoiceFeaturesConsent(true) }
                SyncWorker.enqueue(getApplication())
                _message.value = "麦克风已开启（仅端侧处理，不会上传录音）。"
            } else {
                runCatching { container.consentRepository.saveVoiceFeaturesConsent(false) }
                SyncWorker.enqueue(getApplication())
                _message.value = "未授予录音权限，麦克风开关保持关闭。"
            }
        }
    }

    private fun setEveningReminder(enabled: Boolean) {
        container.preferences.eveningReminderEnabled = enabled
        if (enabled) {
            EveningReminderWorker.scheduleNext(getApplication())
        } else {
            EveningReminderWorker.cancel(getApplication())
        }
    }

    private fun requestDelete() {
        if (container.preferences.localMode) {
            _showLocalDeleteConfirm.value = true
            return
        }
        viewModelScope.launch {
            container.consentRepository.requestDataAction("delete")
            SyncWorker.enqueue(getApplication())
            _message.value = "已创建删除请求；依法需保留的数据可能不立即删除。"
        }
    }

    private fun confirmLocalDelete() {
        _showLocalDeleteConfirm.value = false
        viewModelScope.launch {
            runCatching { container.localDataRights.deleteLocalData(container.preferences.userId) }
            _message.value = "本地数据已删除。"
            refreshLocalCounts()
        }
    }

    private fun revokeConsent() {
        viewModelScope.launch {
            ServiceRevocationCoordinator.revokeService(
                getApplication(), container.preferences, container.consentRepository
            )
            _message.value = "已停止服务并提交撤回请求。"
        }
    }

    private fun refreshLocalCounts() {
        viewModelScope.launch {
            val userId = container.preferences.userId
            val footprint = withContext(Dispatchers.IO) {
                if (userId.isBlank()) {
                    com.yunjue.echo.mind.data.DataFootprint()
                } else {
                    runCatching { container.localDataRights.footprintSummary(userId) }
                        .getOrDefault(com.yunjue.echo.mind.data.DataFootprint())
                }
            }
            _localCounts.value = footprint
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DataAndSensingViewModel(container.applicationContext as Application, container)
            }
        }
    }
}
