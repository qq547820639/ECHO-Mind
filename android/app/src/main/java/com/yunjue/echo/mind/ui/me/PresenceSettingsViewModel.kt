package com.yunjue.echo.mind.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.me.PresenceSettingsEvent
import com.yunjue.echo.mind.me.PresenceSettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ERA 13.1 §35 — PresenceSettingsViewModel：wallpaper/dream 由系统 intent 触发（UI 侧）；
 * visual preferences（动态程度/夜间模式/减少动画/应用内建议）全部在此持有。
 */
class PresenceSettingsViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    private val _motionLevel = MutableStateFlow(container.preferences.presenceMotionLevel)
    private val _nightMode = MutableStateFlow(container.preferences.presenceNightMode)
    private val _reduceMotion = MutableStateFlow(container.preferences.presenceReduceMotion)
    private val _suggestionsEnabled = MutableStateFlow(container.preferences.presenceSuggestionsEnabled)

    val uiState: StateFlow<PresenceSettingsUiState> = combine(
        _motionLevel, _nightMode, _reduceMotion, _suggestionsEnabled
    ) { motion, night, reduce, suggestions ->
        PresenceSettingsUiState(
            motionLevel = motion,
            nightMode = night,
            reduceMotion = reduce,
            suggestionsEnabled = suggestions,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PresenceSettingsUiState(
            motionLevel = container.preferences.presenceMotionLevel,
            nightMode = container.preferences.presenceNightMode,
            reduceMotion = container.preferences.presenceReduceMotion,
            suggestionsEnabled = container.preferences.presenceSuggestionsEnabled,
        ),
    )

    /** Section 唯一交互入口（视觉偏好写入 preferences 单点）。 */
    fun onEvent(event: PresenceSettingsEvent) {
        when (event) {
            is PresenceSettingsEvent.SetMotionLevel -> {
                container.preferences.presenceMotionLevel = event.level
                _motionLevel.value = event.level
            }
            is PresenceSettingsEvent.SetNightMode -> {
                container.preferences.presenceNightMode = event.enabled
                _nightMode.value = event.enabled
            }
            is PresenceSettingsEvent.SetReduceMotion -> {
                container.preferences.presenceReduceMotion = event.enabled
                _reduceMotion.value = event.enabled
            }
            is PresenceSettingsEvent.SetSuggestionsEnabled -> {
                container.preferences.presenceSuggestionsEnabled = event.enabled
                _suggestionsEnabled.value = event.enabled
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PresenceSettingsViewModel(container.applicationContext as Application, container)
            }
        }
    }
}
