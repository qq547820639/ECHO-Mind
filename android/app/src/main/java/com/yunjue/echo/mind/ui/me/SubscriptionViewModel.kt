package com.yunjue.echo.mind.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.OnboardingVerifyException
import com.yunjue.echo.mind.data.OnboardingVerifyResult
import com.yunjue.echo.mind.SyncWorker
import com.yunjue.echo.mind.me.SubscriptionEvent
import com.yunjue.echo.mind.me.SubscriptionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ERA 33 — SubscriptionViewModel：可选订阅开通业务持有者。
 *
 * ERA 13.1 §31 收口：Section 不再直接调用 onboardingRepository / featureFlagRepository /
 * SyncWorker（旧 SubscriptionSection 直接在 Composable 内编排）。
 *
 * 依赖全部经构造注入（verifyCode / refreshFlags / enqueueSync / statusSnapshot），
 * 单测无需 AppContainer。
 */
class SubscriptionViewModel(
    private val verifyCode: suspend (String) -> OnboardingVerifyResult,
    private val refreshFlags: suspend () -> Unit,
    private val enqueueSync: () -> Unit,
    private val statusSnapshot: () -> SubscriptionStatusSnapshot,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SubscriptionUiState().copy(
            localMode = statusSnapshot().localMode,
            subscriptionExpiresAt = statusSnapshot().subscriptionExpiresAt,
            subscriptionExpired = statusSnapshot().subscriptionExpired,
        )
    )
    val uiState: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    /** Section 唯一交互入口。 */
    fun onEvent(event: SubscriptionEvent) {
        when (event) {
            is SubscriptionEvent.UpdateBindCode ->
                _state.update { it.copy(bindCode = event.value, bindMessage = null, bindError = false) }
            SubscriptionEvent.Bind -> bind()
        }
    }

    private fun bind() {
        val code = uiState.value.bindCode.trim()
        if (code.length < 8) {
            _state.update { it.copy(bindMessage = "激活码格式不正确，请检查后重试。", bindError = true) }
            return
        }
        _state.update { it.copy(binding = true, bindMessage = null, bindError = false) }
        viewModelScope.launch {
            try {
                val result = verifyCode(code)
                if (result.restricted) {
                    _state.update { it.copy(binding = false, bindMessage = "该激活码已受限，请联系客服。", bindError = true) }
                } else {
                    refreshFlags()
                    enqueueSync()
                    applyStatusSnapshot()
                    _state.update {
                        it.copy(
                            binding = false,
                            bindMessage = "订阅已开通。云端同步与专业支持现在可用。",
                            bindError = false,
                            bindCode = "",
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        binding = false,
                        bindError = true,
                        bindMessage = when ((e as? OnboardingVerifyException)?.reason) {
                            "invalid_code" -> "激活码无效，请检查后重试，或联系客服获取订阅激活码。"
                            "restricted" -> "该激活码已受限，请联系客服。"
                            else -> "暂时无法验证激活信息，请检查网络后重试。"
                        }
                    )
                }
            }
        }
    }

    /** 开通成功后刷新订阅状态快照（本地模式 → 已订阅状态展示）。 */
    private fun applyStatusSnapshot() {
        val current = statusSnapshot()
        _state.update {
            it.copy(
                localMode = current.localMode,
                subscriptionExpiresAt = current.subscriptionExpiresAt,
                subscriptionExpired = current.subscriptionExpired,
            )
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SubscriptionViewModel(
                    verifyCode = { code -> container.onboardingRepository.verifyOnboardingCode(code) },
                    refreshFlags = {
                        runCatching { container.featureFlagRepository.fetchFeatureFlags() }
                    },
                    enqueueSync = { SyncWorker.enqueue(container.applicationContext) },
                    statusSnapshot = {
                        SubscriptionStatusSnapshot(
                            localMode = container.preferences.localMode,
                            subscriptionExpiresAt = container.preferences.subscriptionExpiresAt,
                            subscriptionExpired = container.preferences.subscriptionExpired,
                        )
                    },
                )
            }
        }
    }
}

/** 订阅状态快照（构造注入；UI 经 subscriptionStatusText 渲染文案）。 */
data class SubscriptionStatusSnapshot(
    val localMode: Boolean,
    val subscriptionExpiresAt: Long?,
    val subscriptionExpired: Boolean,
)
