package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    MeScreenContent(
        state = state,
        onEvent = meVm::onEvent,
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
        aboutCard = { AboutCard() },
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
    crisisCard: @Composable () -> Unit,
    subscription: @Composable () -> Unit,
    support: @Composable () -> Unit,
    dataAndSensing: @Composable () -> Unit,
    presenceSettings: @Composable () -> Unit,
    wrist: @Composable () -> Unit,
    intelligenceSettings: @Composable () -> Unit,
    whatEchoKnows: @Composable () -> Unit,
    aboutCard: @Composable () -> Unit,
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

    Page("Me · 我的控制权") {
        // ERA 25 §44 信息架构：危机入口（安全常驻，契约冻结）→ ECHO Presence →
        // What ECHO Knows → AI Intelligence → Data & Sensing → Subscription → Support → About。
        // Me 不是 Settings：用户的 ECHO 与它知道什么排在最前，工程配置沉底。
        crisisCard()
        presenceSettings()
        wrist()
        whatEchoKnows()
        intelligenceSettings()
        dataAndSensing()
        subscription()
        support()
        aboutCard()
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** v3.2 §9：About / Diagnostics —— APK 构建来源可追溯（版本/提交/时间）。 */
@Composable
private fun AboutCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("ECHO Mind ${com.yunjue.echo.mind.BuildConfig.BUILD_VERSION}", style = MaterialTheme.typography.bodySmall)
            Text("构建提交：${com.yunjue.echo.mind.BuildConfig.GIT_COMMIT}", style = MaterialTheme.typography.bodySmall)
            Text("构建时间：${com.yunjue.echo.mind.BuildConfig.BUILD_TIMESTAMP}", style = MaterialTheme.typography.bodySmall)
            Text("数据默认只保存在本机；ECHO 是支持性工具，不是医生。", style = MaterialTheme.typography.bodySmall)
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
