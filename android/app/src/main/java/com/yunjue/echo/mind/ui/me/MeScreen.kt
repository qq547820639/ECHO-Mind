package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.me.MeEvent
import com.yunjue.echo.mind.me.MeUiState
import com.yunjue.echo.mind.ui.Page
import com.yunjue.echo.mind.ui.dialIntent

/**
 * ERA 13.1 §31/§32 — MeScreen：Me 世界的根页面（含义：我的控制权）。
 *
 * 根页面只负责组合子领域（Subscription / Support / Data&Sensing / Presence /
 * Intelligence / Memory / About / Crisis）+ 支持请求二次确认对话框；
 * 业务全部在 MeViewModel 与各子 ViewModel（§33–§36），无 LaunchedEffect 编排。
 *
 * ERA 33 状态提升：MeScreen 只做 VM 收集 + 子领域装配；纯渲染在 MeScreenContent
 * （state-in / event-out + 组合槽位，Compose smoke test 无需 AppContainer）。
 */
@Composable
fun MeScreen(container: AppContainer) {
    val context = LocalContext.current
    val meVm: MeViewModel = viewModel(factory = MeViewModel.factory(container))
    val state by meVm.uiState.collectAsStateWithLifecycle()
    // visual-runtime：Me 首层 Personal Intelligence Map 的中心 organism 用全局唯一 Presence
    val presence by container.echoStateStore.state.collectAsStateWithLifecycle()
    // V3 §33/§91：Visual Lab 仅 debug 构建可进入（debug diagnostics 入口）
    var showVisualLab by remember { mutableStateOf(false) }
    if (showVisualLab && com.yunjue.echo.mind.BuildConfig.DEBUG) {
        com.yunjue.echo.mind.ui.debug.VisualLabScreen(onClose = { showVisualLab = false })
        return
    }

    // §12：节点点击进入对应领域（滚动锚点——各 Section 在 Page 内，Me 用安静滚动定位）
    MeScreenContent(
        state = state,
        onEvent = meVm::onEvent,
        slots = MeSectionSlots(
            intelligenceMap = { onOpenDomain ->
                MeIntelligenceMap(
                    presence = presence,
                    onOpenObservation = { onOpenDomain(MeDomain.OBSERVATION) },
                    onOpenIntelligence = { onOpenDomain(MeDomain.INTELLIGENCE) },
                    onOpenMemory = { onOpenDomain(MeDomain.MEMORY) },
                    onOpenPresence = { onOpenDomain(MeDomain.PRESENCE) },
                    organism = {
                        // V3 §H：genome 经唯一语义链（EchoVisualMapper → VisualGenomeCompiler）计算
                        val hourOfDay = remember { java.time.LocalTime.now().let { it.hour + it.minute / 60f } }
                        val reduceMotion = state.presence.reduceMotion
                        val genome = remember(presence, hourOfDay, reduceMotion) {
                            presence?.let {
                                com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                                    com.yunjue.echo.mind.presence.EchoVisualMapper.map(
                                        it, hourOfDay, reduceMotion = reduceMotion,
                                    ),
                                    it.identityGenome,
                                )
                            }
                        }
                        com.yunjue.echo.mind.presencevisual.EchoOrganism(
                            genome = genome,
                            surface = com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                            motion = if (reduceMotion) {
                                com.yunjue.echo.mind.visual.surface.MotionPolicy.REDUCED
                            } else {
                                com.yunjue.echo.mind.visual.surface.MotionPolicy.NORMAL
                            },
                            maturityName = presence?.maturity?.name ?: "SEED",
                            aggregateDescription = com.yunjue.echo.mind.visual.surface
                                .organismDescriptionFor(presence),
                        )
                    },
                )
            },
            crisisCard = { CrisisCard(context) },
            subscription = { SubscriptionSection(container) },
            support = {
                SupportSection(
                    escalations = state.escalations,
                    onRequestSupport = { meVm.onEvent(MeEvent.RequestSupportClicked) },
                )
            },
            dataAndSensing = { DataAndSensingSection(container, context) },
            presenceSettings = { PresenceSettingsSection(container) },
            wrist = { WristSection(container) },
            intelligenceSettings = { IntelligenceSettingsSection(container) },
            whatEchoKnows = { WhatEchoKnowsSection(container) },
            aboutCard = { AboutCard(onOpenVisualLab = { showVisualLab = true }) },
        ),
    )
}

/**
 * ERA 33 — Me 根页面纯状态内容（state-in / event-out + 组合槽位）。
 * 只消费 [MeUiState]；子领域以槽位注入（各 Section 的 VM 装配留在 MeScreen 调用侧）。
 */
@Composable
fun MeScreenContent(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    slots: MeSectionSlots,
) {
    if (state.showSupportConfirm) {
        AlertDialog(
            onDismissRequest = { onEvent(MeEvent.SupportDismissed) },
            title = { Text(stringResource(R.string.support_request_confirm_title)) },
            text = { Text(stringResource(R.string.support_request_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { onEvent(MeEvent.SupportConfirmed) }) {
                    Text(stringResource(R.string.support_request_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MeEvent.SupportDismissed) }) {
                    Text(stringResource(R.string.support_request_confirm_cancel))
                }
            }
        )
    }

    // §63：presentation state（一次只展开一个 major domain；不建立新业务 state）
    var expandedDomain by rememberSaveable { mutableStateOf(MeDomain.NONE.name) }
    val domain = runCatching { MeDomain.valueOf(expandedDomain) }.getOrDefault(MeDomain.NONE)

    Page("Me · 我的控制权") {
        // ERA 25 §44 信息架构 + V3 §62/§63：
        // 危机入口（安全常驻，契约冻结）→ Intelligence Map（顶部常驻）→
        // 选中 domain 在 Map 下展开；其他 domain compact。
        slots.crisisCard()
        slots.intelligenceMap { d -> expandedDomain = if (domain == d) MeDomain.NONE.name else d.name }

        // 选中 domain 的展开详情（Map 正下方）
        if (domain != MeDomain.NONE) {
            Column(Modifier.fillMaxWidth().testTag("me_domain_detail")) {
                when (domain) {
                    MeDomain.OBSERVATION -> slots.dataAndSensing()
                    MeDomain.MEMORY -> slots.whatEchoKnows()
                    MeDomain.INTELLIGENCE -> slots.intelligenceSettings()
                    MeDomain.PRESENCE -> {
                        slots.presenceSettings()
                        slots.wrist()
                    }
                    MeDomain.NONE -> Unit
                }
            }
        }

        // 其他 domain compact（一行安静入口；点击切换展开）
        MeDomain.entries.filter { it != MeDomain.NONE && it != domain }.forEach { d ->
            TextButton(
                onClick = { expandedDomain = d.name },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    when (d) {
                        MeDomain.OBSERVATION -> "感知世界 · Observation"
                        MeDomain.MEMORY -> "记住什么 · Memory"
                        MeDomain.INTELLIGENCE -> "如何思考 · Intelligence"
                        MeDomain.PRESENCE -> "存在方式 · Presence"
                        MeDomain.NONE -> ""
                    } + "  →",
                )
            }
        }

        // §64：更多控制（Subscription / Professional Support / About / Diagnostics 下沉；Crisis 不下沉）
        var moreControls by rememberSaveable { mutableStateOf(false) }
        TextButton(
            onClick = { moreControls = !moreControls },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (moreControls) "收起更多控制" else "更多控制") }
        if (moreControls) {
            slots.subscription()
            slots.support()
            slots.aboutCard()
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** V3 §63：Me 领域 presentation state（非业务 state；一次只展开一个 major domain）。 */
enum class MeDomain { NONE, OBSERVATION, MEMORY, INTELLIGENCE, PRESENCE }

/** Me 根页面子领域槽位集合（状态提升模式；detekt LongParameterList 收敛）。 */
data class MeSectionSlots(
    val crisisCard: @Composable () -> Unit,
    val subscription: @Composable () -> Unit,
    val support: @Composable () -> Unit,
    val dataAndSensing: @Composable () -> Unit,
    val presenceSettings: @Composable () -> Unit,
    val wrist: @Composable () -> Unit,
    val intelligenceSettings: @Composable () -> Unit,
    val whatEchoKnows: @Composable () -> Unit,
    val aboutCard: @Composable () -> Unit,
    /** §62：Intelligence Map（接收领域展开回调；Map 永远在顶部）。 */
    val intelligenceMap: @Composable (onOpenDomain: (MeDomain) -> Unit) -> Unit = {},
)

/** v3.2 §9：About / Diagnostics —— APK 构建来源可追溯（版本/提交/时间）。 */
@Composable
private fun AboutCard(onOpenVisualLab: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("ECHO Mind ${com.yunjue.echo.mind.BuildConfig.BUILD_VERSION}", style = MaterialTheme.typography.bodySmall)
            Text("构建提交：${com.yunjue.echo.mind.BuildConfig.GIT_COMMIT}", style = MaterialTheme.typography.bodySmall)
            Text("构建时间：${com.yunjue.echo.mind.BuildConfig.BUILD_TIMESTAMP}", style = MaterialTheme.typography.bodySmall)
            Text("数据默认只保存在本机；ECHO 是支持性工具，不是医生。", style = MaterialTheme.typography.bodySmall)
            // V3 §33/§91：ECHO Visual Lab 入口仅 debug 构建可见（debug diagnostics 入口）
            if (com.yunjue.echo.mind.BuildConfig.DEBUG) {
                OutlinedButton(onClick = onOpenVisualLab) { Text("ECHO Visual Lab (debug)") }
            }
        }
    }
}

/** 危机入口卡（安全资源常驻可达；Me 内也保留）。 */
@Composable
private fun CrisisCard(context: android.content.Context) {
    val crisis12356Desc = stringResource(R.string.crisis_call_12356_desc)
    val crisis110Desc = stringResource(R.string.crisis_call_110_desc)
    val crisis120Desc = stringResource(R.string.crisis_call_120_desc)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.crisis_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.crisis_not_emergency_service))
            Button(
                onClick = { context.startActivity(dialIntent("12356")) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = crisis12356Desc
                }
            ) { Text(stringResource(R.string.crisis_call_12356)) }
            OutlinedButton(
                onClick = { context.startActivity(dialIntent("110")) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = crisis110Desc
                }
            ) { Text(stringResource(R.string.crisis_call_110)) }
            OutlinedButton(
                onClick = { context.startActivity(dialIntent("120")) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = crisis120Desc
                }
            ) { Text(stringResource(R.string.crisis_call_120)) }
        }
    }
}
