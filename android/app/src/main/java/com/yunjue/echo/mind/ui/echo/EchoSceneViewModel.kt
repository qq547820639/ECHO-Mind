package com.yunjue.echo.mind.ui.echo

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.actions.EchoActionRuntime
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.EchoConversationController
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import com.yunjue.echo.mind.memory.EchoCorrectionService
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * v3 §11/§13 — EchoSceneViewModel：ECHO Scene 唯一业务逻辑持有者。
 *
 * 分层：Composable → ViewModel → UseCase/Coordinator → Repository。
 * Screen 只负责组合组件渲染；AI 请求 / 记忆写入 / 证据检索 / 行动运行全部在此。
 */
class EchoSceneViewModel(app: android.app.Application, private val container: AppContainer) : AndroidViewModel(app) {

    /** 对话链控制器（状态机：IDLE/COMPILING/WAITING/COMPLETE/FAILED/FALLBACK）。 */
    val conversation = EchoConversationController(
        retrieve = container.contextRetriever::retrieve,
        answer = { q, e, h -> container.aiNarrativeService.answerQuestion(q, e, h) },
    )

    /** 用户纠错统一入口（UI 不自己创建 MemoryEntity）。 */
    val corrections = EchoCorrectionService(
        memoryWriter = container.memoryRepository,
        correctionWriter = container.memoryRepository,
        memoryReader = container.memoryRepository,
    )

    /** Scene 内行动运行时（呼吸/暂停；建议由 InterventionPolicy 裁决）。 */
    val actionRuntime = EchoActionRuntime()

    /** 今日画像原始状态（九态渲染 + 反馈对象）。 */
    val portrait: StateFlow<PortraitUiState> = container.portraitRepository.observeTodayPortrait()

    /** 周小结消息（订阅/本地镜像）。 */
    val message = container.messageRepository.message

    private val _narrative = MutableStateFlow<AiNarrativeService.NarrativeResult?>(null)

    /** 单一 UI 状态（纯函数装配；Screen 只消费）。 */
    val uiState: StateFlow<EchoSceneUiState> = combine(
        portrait,
        container.echoRuntimeCoordinator.presence,
        container.echoRuntimeCoordinator.sensing,
        _narrative,
    ) { portraitState, presence, sensing, narrative ->
        assembleEchoSceneUiState(
            portraitState = portraitState,
            presence = presence,
            sensing = sensing,
            narrative = narrative,
            intelligenceAvailable = container.aiProviderManager.hasProvider(),
            suggestionsEnabled = container.preferences.presenceSuggestionsEnabled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = initialUiState(),
    )

    init {
        refresh()
        // 画像变化 → 重新生成今日一句话（AI 失败自动降级，见 AiNarrativeService）
        viewModelScope.launch {
            portrait.collectLatest { p ->
                if (p.status == PortraitStatus.READY || p.status == PortraitStatus.PARTIAL_DATA ||
                    p.status == PortraitStatus.OFFLINE_CACHED || p.status == PortraitStatus.EARLY_BASELINE ||
                    p.status == PortraitStatus.LOW_CONFIDENCE
                ) {
                    val evidence = container.contextRetriever.retrieve(ReasoningTaskId.GENERATE_NOW_INTERPRETATION)
                    val headline = p.portrait?.headline?.joinToString(" · ").orEmpty()
                    val summary = p.portrait?.summary.orEmpty()
                    val facts = p.portrait?.facts?.firstOrNull()?.let {
                        listOfNotNull(it.todayText, it.baselineText).joinToString("；")
                    }.orEmpty()
                    _narrative.value = container.aiNarrativeService.nowNarrative(
                        evidence = evidence,
                        deterministicText = headline.ifBlank { summary },
                        factsText = facts.ifBlank { "ECHO 还在了解今天。" },
                    )
                }
            }
        }
    }

    /** 缓存优先 → 后台刷新 → 平滑替换（画像 + 周小结 + 运行时全量刷新）。 */
    fun refresh() {
        viewModelScope.launch {
            container.portraitRepository.refreshTodayPortrait(networkAvailable = isNetworkAvailable(getApplication()))
            container.messageRepository.refresh()
            container.echoRuntimeCoordinator.refreshAll()
        }
    }

    fun retryPortrait() = refresh()

    // ===== 对话（EchoConversationController） =====
    fun ask(question: String) {
        viewModelScope.launch { conversation.ask(question) }
    }

    fun recordConversationFeedback(question: String, answer: String, like: Boolean, reason: String? = null) {
        viewModelScope.launch { corrections.recordConversationFeedback(question, answer, like, reason) }
    }

    // ===== 画像反馈（Correction Memory + Outbox 上报） =====
    fun recordPortraitFeedback(date: String, helpful: Boolean) {
        viewModelScope.launch { container.portraitRepository.recordPortraitFeedback(date, helpful) }
    }

    fun recordPortraitCorrection(date: String, reason: String, originalStatement: String?) {
        viewModelScope.launch { corrections.recordPortraitCorrection(date, reason, originalStatement) }
    }

    fun rebuildTodayPortrait() {
        viewModelScope.launch { container.portraitRepository.rebuildTodayPortrait() }
    }

    fun portraitFeedback(date: String): Boolean? = container.portraitRepository.portraitFeedback(date)

    fun consumeBaselineUnlocked(): Boolean = container.portraitRepository.consumeBaselineUnlocked()

    // ===== 行动（EchoActionRuntime） =====
    fun startAction(kind: EchoActionKind) = actionRuntime.start(kind)

    fun stopAction() = actionRuntime.stop()

    // ===== 感知重开（与 Me 页同一领域路径） =====
    fun reEnableSensing() {
        viewModelScope.launch {
            ServiceRevocationCoordinator.reEnablePassiveSensing(
                getApplication(), container.preferences, container.consentRepository, container.featureFlagRepository
            )
        }
    }

    fun dismissAiPrompt() {
        container.preferences.aiPromptDismissed = true
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EchoSceneViewModel(container.applicationContext as android.app.Application, container)
            }
        }
    }
}
