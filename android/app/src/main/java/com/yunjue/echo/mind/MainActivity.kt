package com.yunjue.echo.mind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.ui.EchoMindApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ERA 32 R15（真机首启闪退修复，§60 crash-free runtime）：
        // 冷启动容器构建链含 fail-closed Keystore 初始化 + 受保护密钥供给 + SQLCipher 建库——
        // 此前任何设备侧异常（Keystore/原生库）都会在首帧前裸崩（「打开就闪退」，无任何可恢复出口）。
        // 现在构建失败不再闪退：显示可重试错误画面 + 异常类名（不含消息，避免路径等敏感信息泄漏）。
        val result = runCatching { (application as EchoMindApplication).container }
        setContent {
            MaterialTheme {
                Surface {
                    result.fold(
                        onSuccess = { EchoMindApp(it) },
                        onFailure = { e ->
                            ContainerInitFailedScreen(
                                errorClass = e.javaClass.simpleName.ifBlank { "初始化失败" },
                                onRetry = { recreate() },
                                onExit = { finishAffinity() },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 安全存储/数据库初始化失败时的兜底画面（不再闪退；数据未受影响——fail-closed 未降级）。 */
@Composable
fun ContainerInitFailedScreen(
    errorClass: String,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ECHO 没能安全地启动", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "本机安全存储初始化失败（$errorClass）。你的数据没有丢失，也没有被降级处理。\n" +
                "请重试；若反复出现，请把这一行信息反馈给开发团队。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExit) { Text("退出") }
    }
}
