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
import com.yunjue.echo.mind.ui.Page
import com.yunjue.echo.mind.ui.dialIntent

/**
 * ERA 13.1 §31/§32 — MeScreen：Me 世界的根页面（含义：我的控制权）。
 *
 * 根页面只负责组合子领域（Subscription / Support / Data&Sensing / Presence /
 * Intelligence / Memory / About / Crisis）+ 支持请求二次确认对话框；
 * 业务全部在 MeViewModel 与各子 ViewModel（§33–§36），无 LaunchedEffect 编排。
 */
@Composable
fun MeScreen(container: AppContainer) {
    val context = LocalContext.current
    val meVm: MeViewModel = viewModel(factory = MeViewModel.factory(container))
    val state by meVm.uiState.collectAsStateWithLifecycle()

    if (state.showSupportConfirm) {
        AlertDialog(
            onDismissRequest = { meVm.onEvent(MeEvent.SupportDismissed) },
            title = { Text(stringResource(R.string.support_request_confirm_title)) },
            text = { Text(stringResource(R.string.support_request_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { meVm.onEvent(MeEvent.SupportConfirmed) }) {
                    Text(stringResource(R.string.support_request_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { meVm.onEvent(MeEvent.SupportDismissed) }) {
                    Text(stringResource(R.string.support_request_confirm_cancel))
                }
            }
        )
    }

    Page("Me · 我的控制权") {
        CrisisCard(context)
        SubscriptionSection(container, context)
        SupportSection(
            escalations = state.escalations,
            onRequestSupport = { meVm.onEvent(MeEvent.RequestSupportClicked) },
        )
        DataAndSensingSection(container, context)
        PresenceSettingsSection(container)
        IntelligenceSettingsSection(container)
        WhatEchoKnowsSection(container)
        AboutCard()
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
