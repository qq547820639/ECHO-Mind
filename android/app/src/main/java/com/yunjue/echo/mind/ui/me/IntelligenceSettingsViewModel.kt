package com.yunjue.echo.mind.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.intelligence.ProviderConfigDraft
import com.yunjue.echo.mind.intelligence.ProviderCredentialStore
import com.yunjue.echo.mind.intelligence.ProviderStatus
import com.yunjue.echo.mind.intelligence.ProviderType
import com.yunjue.echo.mind.intelligence.normalizeBaseUrl
import com.yunjue.echo.mind.intelligence.providerStatusText
import com.yunjue.echo.mind.intelligence.overall
import com.yunjue.echo.mind.intelligence.testConnectionDetail
import com.yunjue.echo.mind.me.IntelligenceSettingsEvent
import com.yunjue.echo.mind.me.combine7
import com.yunjue.echo.mind.me.IntelligenceSettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ERA 13.1 §34 — IntelligenceSettingsViewModel：provider config / connection test /
 * model selection / disconnect / advanced settings 全部在此；Section 只渲染。
 */
class IntelligenceSettingsViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    private val stored = container.aiProviderManager.stored()

    private val _draftBaseUrl = MutableStateFlow(stored?.baseUrl ?: "")
    private val _draftModel = MutableStateFlow(stored?.model ?: "")
    private val _draftApiKey = MutableStateFlow(stored?.apiKey ?: "")
    private val _status = MutableStateFlow<ProviderStatus?>(null)
    private val _busy = MutableStateFlow(false)
    private val _changeExpanded = MutableStateFlow(false)
    private val _testDetail = MutableStateFlow<String?>(null)

    val uiState: StateFlow<IntelligenceSettingsUiState> = combine7(
        _draftBaseUrl, _draftModel, _draftApiKey, _status, _busy, _changeExpanded, _testDetail
    ).map { (baseUrl, model, apiKey, status, busy, expanded, detail) ->
        IntelligenceSettingsUiState(
            providerConfigured = container.aiProviderManager.hasProvider(),
            model = container.aiProviderManager.stored()?.model,
            baseUrl = container.aiProviderManager.stored()?.baseUrl,
            status = status ?: if (stored != null) ProviderStatus.READY else null,
            statusText = status?.let { providerStatusText(it) }
                ?: if (stored != null) providerStatusText(ProviderStatus.READY) else null,
            testDetail = detail,
            busy = busy,
            changeExpanded = expanded,
            draftBaseUrl = baseUrl,
            draftModel = model,
            draftApiKey = apiKey,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = IntelligenceSettingsUiState(
            providerConfigured = stored != null,
            model = stored?.model,
            baseUrl = stored?.baseUrl,
            status = if (stored != null) ProviderStatus.READY else null,
            statusText = if (stored != null) providerStatusText(ProviderStatus.READY) else null,
            draftBaseUrl = stored?.baseUrl ?: "",
            draftModel = stored?.model ?: "",
            draftApiKey = stored?.apiKey ?: "",
        ),
    )

    /** Section 唯一交互入口。 */
    fun onEvent(event: IntelligenceSettingsEvent) {
        when (event) {
            is IntelligenceSettingsEvent.UpdateDraftBaseUrl -> _draftBaseUrl.value = event.value
            is IntelligenceSettingsEvent.UpdateDraftModel -> _draftModel.value = event.value
            is IntelligenceSettingsEvent.UpdateDraftApiKey -> _draftApiKey.value = event.value
            IntelligenceSettingsEvent.ToggleChangeExpanded ->
                _changeExpanded.value = !_changeExpanded.value
            IntelligenceSettingsEvent.TestConnection -> testConnection()
            IntelligenceSettingsEvent.SaveAndConnect -> saveAndConnect()
            IntelligenceSettingsEvent.Disconnect -> disconnect()
        }
    }

    private fun draft(): ProviderConfigDraft = ProviderConfigDraft(
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = _draftBaseUrl.value,
        model = _draftModel.value,
        apiKey = _draftApiKey.value,
    )

    private fun testConnection() {
        viewModelScope.launch {
            _busy.value = true
            _status.value = ProviderStatus.VALIDATING
            val result = container.aiProviderManager.testConnection(
                if (_changeExpanded.value) draft() else null
            )
            _status.value = result.overall
            _testDetail.value = testConnectionDetail(result)
            _busy.value = false
        }
    }

    private fun saveAndConnect() {
        viewModelScope.launch {
            _busy.value = true
            _status.value = ProviderStatus.VALIDATING
            val health = container.aiProviderManager.validate(draft())
            _status.value = health.status
            if (health.status == ProviderStatus.READY) {
                container.aiProviderManager.save(
                    ProviderCredentialStore.Stored(
                        type = ProviderType.OPENAI_COMPATIBLE,
                        displayName = "My AI",
                        baseUrl = normalizeBaseUrl(_draftBaseUrl.value),
                        model = _draftModel.value.trim(),
                        apiKey = _draftApiKey.value.trim(),
                    )
                )
            }
            _busy.value = false
            _changeExpanded.value = false
        }
    }

    private fun disconnect() {
        container.aiProviderManager.clear()
        _draftApiKey.value = ""
        _status.value = ProviderStatus.NOT_CONFIGURED
        _testDetail.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                IntelligenceSettingsViewModel(container.applicationContext as Application, container)
            }
        }
    }
}
