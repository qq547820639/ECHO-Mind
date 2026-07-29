package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SafetyScreen(deliveryState: String, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    Page("现在优先确保安全") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("我很重视你现在的安全。普通对话已停止。", style = MaterialTheme.typography.titleMedium)
                Text("请尽量移动到更安全、有人在场的地方，并远离可能造成伤害的物品。")
                Text(deliveryState)
            }
        }
        Button(onClick = { context.startActivity(dialIntent("110")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 110") }
        Button(onClick = { context.startActivity(dialIntent("120")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 120") }
        OutlinedButton(onClick = { context.startActivity(dialIntent("12356")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 12356") }
        Text("迫近危险时优先联系紧急服务和身边可信任的人。12356 不应被理解为所有地区 7×24 的唯一兜底。")
        onBack?.let { TextButton(onClick = it) { Text("返回") } }
    }
}
