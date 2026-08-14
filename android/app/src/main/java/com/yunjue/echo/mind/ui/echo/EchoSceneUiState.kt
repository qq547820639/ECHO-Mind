package com.yunjue.echo.mind.ui.echo

import com.yunjue.echo.mind.actions.InterventionInputs
import com.yunjue.echo.mind.actions.InterventionLevel
import com.yunjue.echo.mind.actions.InterventionPolicy
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.model.PortraitFactDto
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.echoMaturity
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus

/**
 * ECHO Scene 统一 UI 状态（Master Prompt v2 §16）：
 * UI 只负责渲染；不在 Composable 内直接组合五个 repository。
 * 由纯函数 [assembleEchoSceneUiState] 装配（JVM 可测）。
 */
data class EchoSceneUiState(
    val presence: EchoPresenceState?,
    val sensing: SensingRuntimeStatus,
    /** Layer 1 一句话（AI 叙事 → 确定性 headline → 观察事实 fallback 之后）。 */
    val headline: String,
    /** 一句话的层级（决定是否展示 AI 依据行）。 */
    val headlineLevel: NarrativeFallbackLevel?,
    val headlineSources: List<DataSourceCategory>,
    /** Layer 2 人类可读证据（facts 对照）。 */
    val facts: List<PortraitFactDto>,
    val portraitStatus: PortraitStatus,
    val baselineDays: Int,
    val maturity: EchoMaturity,
    /** 是否已连接 AI（决定「连接 AI 后可获得更深入解释」提示）。 */
    val intelligenceAvailable: Boolean,
    /** Intervention Policy L2：是否展示「让自己慢一点」温和建议。 */
    val suggestedAction: Boolean,
)

/**
 * ECHO Scene 状态装配（纯函数）：
 * 输入 = 画像九态 + Presence + 感知六态 + 叙事结果 + AI 可用性；
 * 输出 = 单一 UI 状态。全部文案决策集中在此（禁止 UI 层另行硬编码）。
 */
fun assembleEchoSceneUiState(
    portraitState: PortraitUiState,
    presence: EchoPresenceState?,
    sensing: SensingRuntimeStatus,
    narrative: AiNarrativeService.NarrativeResult?,
    intelligenceAvailable: Boolean,
    suggestionsEnabled: Boolean,
    now: Long = System.currentTimeMillis(),
): EchoSceneUiState {
    val portrait = portraitState.portrait
    val baselineDays = portrait?.baselineDays ?: 0
    val maturity = echoMaturity(baselineDays)

    // Layer 1 一句话：AI 叙事（过门禁）> 确定性 headline > summary > 观察事实
    val aiLine = narrative?.takeIf {
        it.level == NarrativeFallbackLevel.AI_NARRATIVE && it.text.isNotBlank()
    }
    val headline = when {
        aiLine != null -> aiLine.text
        !portrait?.headline.isNullOrEmpty() -> portrait!!.headline.joinToString(" · ")
        !portrait?.summary.isNullOrBlank() -> portrait!!.summary
        else -> "ECHO 还在了解今天。"
    }
    val level = when {
        aiLine != null -> NarrativeFallbackLevel.AI_NARRATIVE
        !portrait?.headline.isNullOrEmpty() || !portrait?.summary.isNullOrBlank() ->
            NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE
        else -> NarrativeFallbackLevel.OBSERVATION_FACTS
    }

    // Intervention Policy：低置信/未知 → 不打扰（L0/L1 不出现建议）
    val suggested = InterventionPolicy.resolve(
        InterventionInputs(
            confidence = presence?.confidence ?: 0f,
            ambientKnown = presence != null && presence.maturity != EchoMaturity.SEED,
            suggestionsEnabled = suggestionsEnabled,
            nowMs = now,
        )
    ) >= InterventionLevel.L2_SUGGEST_WHEN_OPENED

    return EchoSceneUiState(
        presence = presence,
        sensing = sensing,
        headline = headline,
        headlineLevel = level,
        headlineSources = aiLine?.usedSources.orEmpty(),
        facts = portrait?.facts.orEmpty(),
        portraitStatus = portraitState.status,
        baselineDays = baselineDays,
        maturity = maturity,
        intelligenceAvailable = intelligenceAvailable,
        suggestedAction = suggested,
    )
}
