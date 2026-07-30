package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SyncWorker
import kotlinx.coroutines.launch

private val phqItems = listOf(
    "做事时提不起劲或没有兴趣", "感到心情低落、沮丧或绝望", "入睡困难、睡不安稳或睡眠过多",
    "感觉疲倦或没有活力", "食欲不振或吃得过多", "觉得自己很糟或让自己或家人失望",
    "难以集中注意力", "动作或说话速度异常，或烦躁不安", "出现伤害自己或不如死去的想法"
)
private val gadItems = listOf(
    "感到紧张、焦虑或急切", "不能停止或控制担忧", "对各种事情担忧过多", "很难放松",
    "坐立不安", "容易烦恼或急躁", "感到好像将有可怕的事情发生"
)

@Composable
fun QuestionnaireScreen(code: String, repository: LocalRepository, onBack: () -> Unit) {
    val items = if (code == "phq9") phqItems else gadItems
    val title = if (code == "phq9") "PHQ-9 状态筛查" else "GAD-7 状态筛查"
    val answers = remember(code) { mutableStateListOf(*Array(items.size) { 0 }) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    var safetyMode by remember { mutableStateOf(false) }
    if (safetyMode) {
        SafetyScreen("高风险题项已在本机建立人工复核请求；若危险迫近，请直接拨打紧急服务。") { safetyMode = false }
        return
    }
    Page(title) {
        Text("固定版本 1.0；用于筛查提示，不构成诊断。")
        items.forEachIndexed { index, item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${index + 1}. $item")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0..3).forEach { score ->
                            FilterChip(selected = answers[index] == score, onClick = { answers[index] = score }, label = { Text(score.toString()) })
                        }
                    }
                }
            }
        }
        Button(onClick = {
            scope.launch {
                val score = repository.saveQuestionnaire(code, answers.toList())
                SyncWorker.enqueue(context)
                if (score.urgentItem) safetyMode = true
                else result = "总分 ${score.score}。${score.message}；筛查不是诊断。"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("完成计分") }
        result?.let { Text(it) }
        TextButton(onClick = onBack) { Text("返回") }
    }
}
