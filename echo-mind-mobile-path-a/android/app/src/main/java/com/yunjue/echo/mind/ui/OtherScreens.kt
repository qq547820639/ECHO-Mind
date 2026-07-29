package com.yunjue.echo.mind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.model.JournalInput
import com.yunjue.echo.mind.model.PracticeDefinition
import com.yunjue.echo.mind.model.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(repository: LocalRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val journals by repository.observeJournals().collectAsState(initial = emptyList())
    var body by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var safetyMode by remember { mutableStateOf(false) }
    if (safetyMode) {
        SafetyScreen("日记内容触发本机安全旁路；只有服务端确认后才可视为人工已收到。") { safetyMode = false }
        return
    }
    Page("记录") {
        Text("情绪日记和重要事件默认在本地加密保存；同步需要有效机构令牌。")
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("写下此刻发生了什么") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                scope.launch {
                    val decision = repository.saveJournal(JournalInput(body = body.trim()))
                    SyncWorker.enqueue(context)
                    body = ""
                    if (decision.severity == Severity.RED) safetyMode = true
                    else message = if (decision.severity == Severity.YELLOW) "已保存，建议联系人工支持进一步核验。" else "已保存。"
                }
            },
            enabled = body.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存记录") }
        message?.let { Text(it) }
        HorizontalDivider()
        Text("最近记录", style = MaterialTheme.typography.titleMedium)
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

private val practices = listOf(
    PracticeDefinition("breathing-01", "两分钟缓慢呼吸", 2, listOf("坐稳或站稳，双脚有支撑", "按舒适节奏缓慢吸气", "呼气稍长于吸气，不憋气", "不适时立即停止")),
    PracticeDefinition("grounding-01", "五感锚定", 4, listOf("说出5个看到的东西", "说出4个能触碰到的感觉", "说出3个听到的声音", "说出2个闻到的气味", "说出1个能支持自己的事实")),
    PracticeDefinition("activation-01", "一个最小行动", 5, listOf("选择一个5分钟内可完成的小行动", "把行动拆成第一步", "设置明确结束点", "完成后记录感受，不评价好坏")),
    PracticeDefinition("sleep-01", "睡前降速", 8, listOf("降低屏幕亮度", "写下明天再处理的事项", "做两分钟舒缓伸展", "在固定时间结束互动"))
)

@Composable
fun PracticeScreen(repository: LocalRepository) {
    var active by remember { mutableStateOf<PracticeDefinition?>(null) }
    active?.let { PracticeRunner(it, repository) { active = null }; return }
    Page("练习") {
        Text("只提供经版本控制的结构化练习。任何不适都可以立即停止。")
        practices.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text("预计 ${item.durationMinutes} 分钟 · 内容包 v${item.version}")
                    TextButton(onClick = { active = item }) { Text("开始") }
                }
            }
        }
    }
}

@Composable
private fun PracticeRunner(item: PracticeDefinition, repository: LocalRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); seconds += 1 }
    }
    Page(item.title) {
        LinearProgressIndicator(progress = (step + 1f) / item.steps.size, modifier = Modifier.fillMaxWidth())
        Text("第 ${step + 1} / ${item.steps.size} 步", style = MaterialTheme.typography.labelLarge)
        Card(Modifier.fillMaxWidth()) { Text(item.steps[step], modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch { repository.recordPractice(item.id, "stopped", seconds); SyncWorker.enqueue(context); onBack() }
            }, modifier = Modifier.weight(1f)) { Text("停止") }
            Button(onClick = {
                if (step < item.steps.lastIndex) step += 1
                else scope.launch { repository.recordPractice(item.id, "completed", seconds); SyncWorker.enqueue(context); onBack() }
            }, modifier = Modifier.weight(1f)) { Text(if (step == item.steps.lastIndex) "完成" else "下一步") }
        }
        Text("练习不是治疗方案；出现明显不适时停止并寻求专业帮助。")
    }
}

@Composable
fun TrendScreen(repository: LocalRepository) {
    val checkins by repository.observeCheckins().collectAsState(initial = emptyList())
    val questionnaires by repository.observeQuestionnaires().collectAsState(initial = emptyList())
    val values = checkins.take(14).reversed().map { it.mood.toFloat() }
    Page("趋势") {
        Text("最近记录 ${checkins.size} 次。趋势不是诊断。")
        if (values.size >= 2) {
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val stepX = size.width / (values.size - 1)
                fun y(value: Float) = size.height - ((value - 1f) / 4f * size.height)
                for (i in 0 until values.lastIndex) {
                    drawLine(
                        color = Color.Gray,
                        start = Offset(i * stepX, y(values[i])),
                        end = Offset((i + 1) * stepX, y(values[i + 1])),
                        strokeWidth = 5f
                    )
                }
                values.forEachIndexed { i, value -> drawCircle(Color.DarkGray, 7f, Offset(i * stepX, y(value))) }
            }
            Text("上图仅展示个人整体感受的近期变化。")
        } else Text("完成至少两次签到后显示趋势。")
        checkins.take(14).forEach { Text("感受 ${it.mood} · 压力 ${it.stress} · 精力 ${it.energy} · 睡眠恢复 ${it.sleepRecovery}") }
        HorizontalDivider()
        Text("量表记录", style = MaterialTheme.typography.titleMedium)
        questionnaires.take(10).forEach { Text("${it.instrument.uppercase()}：${it.score} 分 · 筛查不是诊断") }
    }
}

@Composable
fun SupportScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by container.repository.observePendingCount().collectAsState(initial = 0)
    var message by remember { mutableStateOf<String?>(null) }
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
        Text("机构：${container.preferences.institutionCode.ifBlank { "未配置" }}")
        Text("同步身份：${container.preferences.userId}")
        Text("AI 身份提示：ECHO Mind 是支持性工具，不是医生。")
        Text("迫近危险时优先联系紧急服务和身边可信任的人。")
    }
}
