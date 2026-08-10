package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SyncState
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.syncStateText

/**
 * 「今天」主界面（T11 改造）：无输入框。
 *
 * - 移除原 mood/stress/energy/sleep Slider、note 输入、PHQ-9/GAD-7 入口、人工帮助按钮。
 * - 改为渲染后端下发的 Skill 能力卡片（LazyColumn + [SkillCardHost]），每个「开始」按钮有真实执行行为。
 * - Skill 列表为空时按 [SkillFetchResult.coldStartHint] 展示分阶段冷启动文案。
 * - 同步状态按 [SyncState] 映射为用户文案（PRD 契约点 9），不暴露 HTTP status。
 * - PRD 契约点 1 收口：不再由 passiveSafety RED 切 [SafetyScreen]（行为派生特征不触发危机 UI）。
 */
@Composable
fun TodayScreen(repository: LocalRepository, onEmergency: () -> Unit, coordinator: SkillSessionCoordinator) {
    val context = LocalContext.current
    val pending by repository.observePendingCount().collectAsState(initial = 0)
    val (skillState, retry) = rememberSkillList(repository)

    val syncState = mapSyncState(
        pendingCount = pending,
        lastHttpCode = repository.lastSyncHttpCode(),
        networkAvailable = isNetworkAvailable(context),
        deadLetterCount = repository.deadLetterCount()
    )
    val syncLabel = syncStateText(syncState, pending)

    // P5 灰度回滚：拉取 feature flags 缓存 + 观察 skills_delivery_enabled。
    // flag 关闭时隐藏 Skill 卡片区，显示「能力下发已暂停」。
    LaunchedEffect(Unit) {
        runCatching { repository.fetchFeatureFlags() }
    }
    val featureFlags by repository.featureFlagsFlow.collectAsState(
        initial = mapOf("skills_delivery_enabled" to true)
    )
    val skillsDeliveryEnabled = featureFlags["skills_delivery_enabled"] ?: true

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("今天", style = MaterialTheme.typography.headlineMedium) }
        item {
            // 同步状态文案 + 计数（PRD 契约点 9）：已同步时不打扰
            if (pending > 0 || syncState != SyncState.SYNCED) {
                AssistChip(onClick = { SyncWorker.enqueue(context) }, label = { Text(syncLabel) })
            }
        }
        when {
            !skillsDeliveryEnabled -> item {
                // P5 灰度回滚：skills_delivery_enabled=false 时隐藏 Skill 卡片区
                Text(
                    stringResource(R.string.skills_delivery_paused),
                    Modifier.padding(top = 20.dp)
                )
            }
            skillState.loadFailed -> item {
                // 加载失败：显示「加载失败」+ 重试按钮（区别于冷启动空态）
                Column(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.cold_start_load_failed))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = retry) { Text(stringResource(R.string.cold_start_retry)) }
                }
            }
            skillState.skills == null -> item {
                // 加载中：spinner
                Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            skillState.skills.isEmpty() -> item {
                // 冷启动空态：按后端 cold_start_hint 分阶段文案
                val stage = skillState.coldStartHint ?: "stage_0"
                val resId = coldStartHint(stage, skillState.observationDays)
                Text(
                    if (stage == "stage_1_3") stringResource(resId, skillState.observationDays)
                    else stringResource(resId),
                    Modifier.padding(top = 20.dp)
                )
            }
            else -> items(skillState.skills) { skill ->
                // 每个「开始」按钮都有真实执行行为（SkillCardHost 内部状态机 + 上报）
                SkillCardHost(skill, repository, coordinator)
            }
        }
        // 底部小的紧急支持快捷入口：危机入口在 SUPPORT tab 常驻，此处提供一键跳转
        item {
            OutlinedButton(onClick = onEmergency, modifier = Modifier.fillMaxWidth()) { Text("紧急支持") }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
