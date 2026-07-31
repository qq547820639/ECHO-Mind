package com.yunjue.echo.mind.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.ProfileDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** T12.3：日记录入已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val JOURNAL_DEPRECATION_NOTICE = "日记录入已停用，历史记录只读查看；新增内容请通过被动感知自动生成。"

@Composable
fun RecordScreen(repository: LocalRepository) {
    // T12.3：移除日记输入区（OutlinedTextField + saveJournal + 同步按钮），保留历史日记只读列表。
    val journals by repository.observeJournals().collectAsState(initial = emptyList())
    Page("记录") {
        Text(JOURNAL_DEPRECATION_NOTICE)
        HorizontalDivider()
        Text("最近记录", style = MaterialTheme.typography.titleMedium)
        if (journals.isEmpty()) {
            Text("暂无历史记录。")
        }
        journals.take(20).forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(repository.decryptJournal(row))
                    Text("本地修订 ${row.revision}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** T12.3：练习打卡已改为 Skill 驱动提示文案（同时作为单测的不变量锚点）。 */
internal const val PRACTICE_DEPRECATION_NOTICE = "练习打卡已改为能力卡片驱动，请在「能力」标签查看下发的 Skill。"

@Composable
fun PracticeScreen(repository: LocalRepository) {
    // T12.3：移除硬编码练习列表与打卡逻辑（PracticeRunner + recordPractice 调用）。
    // 练习改由 T11 下发的 Skill 卡片驱动，此处仅保留骨架提示，不再提供主动打卡入口。
    Page("练习") {
        Text(PRACTICE_DEPRECATION_NOTICE)
        Text("任何不适都可以立即停止；出现明显不适时请寻求专业帮助。")
    }
}

/**
 * T12.4：mood_hint → 数值映射（趋势折线图纵轴）。
 *
 * 平稳偏积极=4，平稳=3，偏低=2，未知/无数据=0。抽成纯函数便于单测。
 */
internal fun moodHintToValue(hint: String): Int = when (hint) {
    "平稳偏积极" -> 4
    "平稳" -> 3
    "偏低" -> 2
    else -> 0
}

/**
 * T12.4：将近 N 天叙事列表映射为折线图数值序列。
 *
 * 数据源为 GET /v1/narratives（被动感知每日叙事），不再依赖主动签到 mood。
 */
internal fun buildTrendValues(narratives: List<NarrativeDisplay>?): List<Float> {
    if (narratives.isNullOrEmpty()) return emptyList()
    return narratives.map { moodHintToValue(it.moodHint).toFloat() }
}

@Composable
fun TrendScreen(repository: LocalRepository) {
    // T12.4：数据源从「最近 14 次签到 mood」切换为「近 7 天叙事 mood_hint + 画像 traits」。
    var narratives by remember { mutableStateOf<List<NarrativeDisplay>?>(null) }
    var profile by remember { mutableStateOf<ProfileDisplay?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            narratives = runCatching { repository.fetchNarratives(7) }.getOrDefault(emptyList())
            profile = runCatching { repository.fetchProfile() }.getOrNull()
        }
    }
    val values = buildTrendValues(narratives)
    Page("趋势") {
        Text("趋势数据来自被动感知叙事与画像；趋势不是诊断。")
        // 画像 traits：observation_days / narrative_days_last_7 / recent_mood_hint
        profile?.let { p ->
            Text("观察天数 ${p.observationDays} · 近 7 天叙事 ${p.narrativeDaysLast7} 天 · 最近情绪 ${p.recentMoodHint}")
        } ?: Text("画像加载中…")
        HorizontalDivider()
        Text("近 7 天情绪提示", style = MaterialTheme.typography.titleMedium)
        if (values.size >= 2) {
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val stepX = size.width / (values.size - 1)
                // 数值范围 0..4：0 在底部，4 在顶部
                fun y(value: Float) = size.height - (value / 4f * size.height)
                for (i in 0 until values.lastIndex) {
                    drawLine(
                        color = Color.Gray,
                        start = Offset(i * stepX, y(values[i])),
                        end = Offset((i + 1) * stepX, y(values[i + 1])),
                        strokeWidth = 5f
                    )
                }
                values.forEachIndexed { i, value ->
                    drawCircle(Color.DarkGray, 7f, Offset(i * stepX, y(value)))
                }
            }
            narratives?.forEach { n -> Text("${n.date}：${n.moodHint}") }
        } else {
            Text("需要至少两天的被动感知叙事才显示趋势。")
        }
    }
}

@Composable
fun SupportScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by container.repository.observePendingCount().collectAsState(initial = 0)
    var message by remember { mutableStateOf<String?>(null) }

    // ===== 麦克风可选模块开关（T03.3） =====
    val micEnabled by container.preferences.micEnabledFlow().collectAsState(initial = false)
    var showMicConfirm by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                container.preferences.setMicEnabled(true)
                // P1.4：granted=true 后写 voice_features consent（含证据哈希）到 outbox
                // 走 SyncWorker 上传到后端，闭环麦克风授权证据链
                runCatching { container.repository.saveVoiceFeaturesConsent(true) }
                SyncWorker.enqueue(context)
                message = "麦克风已开启（仅端侧处理，不会上传录音）。"
            } else {
                // 权限拒绝：micEnabled 仍为 false，Switch 自动回弹
                // P1.4：权限拒绝时写 voice_features consent（granted=false）作为撤销证据
                runCatching { container.repository.saveVoiceFeaturesConsent(false) }
                SyncWorker.enqueue(context)
                message = "未授予录音权限，麦克风开关保持关闭。"
            }
        }
    }
    if (showMicConfirm) {
        AlertDialog(
            onDismissRequest = { showMicConfirm = false },
            title = { Text("开启麦克风采集") },
            text = {
                Text(
                    "麦克风数据仅在本地端侧处理，用于提取音频特征（音量 / 语速 / 停顿 / 基频），" +
                        "不会上传录音原始数据。你可随时在系统设置中撤回录音权限。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicConfirm = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("同意并继续") }
            },
            dismissButton = {
                TextButton(onClick = { showMicConfirm = false }) { Text("取消") }
            }
        )
    }

    Page("支持与设置") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("危机入口", style = MaterialTheme.typography.titleMedium)
                Text("只有收到服务端确认后，应用才会显示人工已连接。")
                Button(onClick = { context.startActivity(dialIntent("12356")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 12356") }
                OutlinedButton(onClick = { context.startActivity(dialIntent("110")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 110") }
                OutlinedButton(onClick = { context.startActivity(dialIntent("120")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 120") }
            }
        }
        Text("待同步：$pending")
        Button(onClick = { SyncWorker.enqueue(context) }, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
        HorizontalDivider()
        Text("数据权利", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            scope.launch { container.repository.requestDataAction("export"); SyncWorker.enqueue(context); message = "已创建数据导出请求。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请导出数据") }
        OutlinedButton(onClick = {
            scope.launch { container.repository.requestDataAction("delete"); SyncWorker.enqueue(context); message = "已创建删除请求；依法需保留的数据可能不立即删除。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请删除数据") }
        OutlinedButton(onClick = {
            scope.launch { container.repository.saveConsent(false, "mobile-revocation-evidence"); container.repository.requestDataAction("revoke_service"); SyncWorker.enqueue(context); message = "已创建撤回服务请求。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("撤回同意并停止服务") }
        message?.let { Text(it) }
        HorizontalDivider()
        Text("被动采集", style = MaterialTheme.typography.titleMedium)
        Text("麦克风采集为可选项，默认关闭。开启后仅在本地处理，不会上传录音。")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("麦克风采集")
            Switch(
                checked = micEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        // 开启前先弹二次确认对话框，确认后再请求权限
                        showMicConfirm = true
                    } else {
                        scope.launch {
                            container.preferences.setMicEnabled(false)
                            // P1.4：用户主动关闭开关 → 写 voice_features consent（granted=false）
                            // 作为撤销证据，与系统权限撤回路径一致
                            runCatching { container.repository.saveVoiceFeaturesConsent(false) }
                            SyncWorker.enqueue(context)
                            message = "麦克风已关闭。"
                        }
                    }
                }
            )
        }
        HorizontalDivider()
        Text("机构：${container.preferences.institutionCode.ifBlank { "未配置" }}")
        Text("同步身份：${container.preferences.userId}")
        Text("AI 身份提示：ECHO Mind 是支持性工具，不是医生。")
        Text("迫近危险时优先联系紧急服务和身边可信任的人。")
    }
}
