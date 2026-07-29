package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.model.CheckinInput
import com.yunjue.echo.mind.model.Severity
import kotlinx.coroutines.launch

@Composable
fun TodayScreen(repository: LocalRepository) {
    var questionnaireCode by remember { mutableStateOf<String?>(null) }
    questionnaireCode?.let { code ->
        QuestionnaireScreen(code, repository) { questionnaireCode = null }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by repository.observePendingCount().collectAsState(initial = 0)
    var mood by remember { mutableIntStateOf(3) }
    var stress by remember { mutableIntStateOf(3) }
    var energy by remember { mutableIntStateOf(3) }
    var sleep by remember { mutableIntStateOf(3) }
    var eventFlag by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var safetyMode by remember { mutableStateOf(false) }

    if (safetyMode) {
        SafetyScreen("已在本机建立高优先级事件；只有服务端确认后才可视为人工已收到。") { safetyMode = false }
        return
    }

    Page("今天") {
        Text("约 30–45 秒完成。1 表示较低，5 表示较高。")
        if (pending > 0) AssistChip(onClick = { SyncWorker.enqueue(context) }, label = { Text("待同步 $pending 项") })
        ScoreRow("整体感受", mood) { mood = it }
        ScoreRow("压力", stress) { stress = it }
        ScoreRow("精力", energy) { energy = it }
        ScoreRow("睡眠恢复", sleep) { sleep = it }
        Row { Checkbox(eventFlag, { eventFlag = it }); Text("今天发生了明显事件") }
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("可选备注") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                val result = repository.saveCheckin(CheckinInput(mood, stress, energy, sleep, eventFlag, false, note.ifBlank { null }))
                SyncWorker.enqueue(context)
                when (result.severity) {
                    Severity.RED -> safetyMode = true
                    Severity.YELLOW -> message = "已保存，并建议尽快使用人工支持入口进行核验。"
                    Severity.EXIT -> message = "已停止当前互动。"
                    Severity.NONE -> message = "已保存。趋势只用于个人回顾，不构成诊断。"
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存签到") }
        message?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { questionnaireCode = "phq9" }, modifier = Modifier.weight(1f)) { Text("PHQ-9") }
            OutlinedButton(onClick = { questionnaireCode = "gad7" }, modifier = Modifier.weight(1f)) { Text("GAD-7") }
        }
        Button(onClick = {
            scope.launch {
                repository.saveCheckin(CheckinInput(mood, stress, energy, sleep, true, true, "用户主动请求人工支持"))
                SyncWorker.enqueue(context)
                safetyMode = true
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("我需要人工帮助") }
    }
}

@Composable
private fun ScoreRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("$label：$value")
        Slider(value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(1, 5)) }, valueRange = 1f..5f, steps = 3)
    }
}
