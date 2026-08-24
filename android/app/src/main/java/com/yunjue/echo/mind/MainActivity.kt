package com.yunjue.echo.mind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.File

/** §BJ：release 导出的已清洗崩溃诊断文件名（filesDir 内，仅本地）。 */
private const val SANITIZED_CRASH_FILE = "echo_crash_sanitized.txt"

/** §BI：release 导出的初始化诊断文件名（filesDir 内，仅本地）。 */
private const val INIT_DIAGNOSTICS_FILE = "echo_init_diagnostics.txt"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // V3 §Z/§AA/§BH：edge-to-edge + 暗色系统栏。SystemBarStyle.dark = 深底亮色图标，
        // 等价于 WindowInsetsControllerCompat.isAppearanceLight(Status|Navigation)Bars = false。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // ERA 32 R18：上次未捕获崩溃的本地日志（无 adb 设备也能反馈完整堆栈）
        val crashText = runCatching {
            File(filesDir, EchoMindApplication.CRASH_LOG_FILE)
                .takeIf { it.exists() }?.readText()
        }.getOrNull()
        if (crashText != null) {
            setContent {
                com.yunjue.echo.mind.ui.theme.EchoMindTheme {
                    Surface {
                        CrashReportScreen(
                            text = crashText,
                            onClearAndRetry = {
                                runCatching { File(filesDir, EchoMindApplication.CRASH_LOG_FILE).delete() }
                                recreate()
                            },
                            onExit = { finishAffinity() },
                        )
                    }
                }
            }
            return
        }
        // ERA 32 R15（真机首启闪退修复，§60 crash-free runtime）：
        // 冷启动容器构建链含 fail-closed Keystore 初始化 + 受保护密钥供给 + SQLCipher 建库——
        // 构建失败不再闪退：显示可重试错误画面。§BI：release 仅异常类名 + 稳定错误码 +
        // API + 版本（message 不离开进程）；debug 保留原文便于本地定位。
        val result = runCatching { (application as EchoMindApplication).container }
        setContent {
            com.yunjue.echo.mind.ui.theme.EchoMindTheme {
                Surface {
                    result.fold(
                        onSuccess = { com.yunjue.echo.mind.ui.EchoMindApp(it) },
                        onFailure = { e ->
                            ContainerInitFailedScreen(
                                errorClass = e.javaClass.simpleName,
                                errorDetail = if (BuildConfig.DEBUG) e.message?.take(200).orEmpty() else "",
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

/** 上次崩溃的堆栈展示（仅本地）。§BJ：debug 显示全文可复制；release 只显示清洗摘要并支持导出。 */
@Composable
fun CrashReportScreen(
    text: String,
    onClearAndRetry: () -> Unit,
    onExit: () -> Unit,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // §BJ：release 渲染清洗摘要（异常类 + 首帧；无消息体/绝对路径）
    val displayText =
        if (isDebugBuild) text else remember(text) { CrashReportSanitizer.sanitize(text) }
    var exported by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("上次打开时出现了问题", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (isDebugBuild) {
                "请把下面的内容复制并反馈给开发团队（只保存在本机）。"
            } else {
                "下面是已清洗的摘要（不含详细消息与文件路径），可导出诊断文件反馈。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            displayText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        if (isDebugBuild) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制全文") }
        } else {
            OutlinedButton(onClick = {
                runCatching { File(context.filesDir, SANITIZED_CRASH_FILE).writeText(displayText) }
                    .onSuccess { exported = true }
            }) { Text(if (exported) "已导出（files/$SANITIZED_CRASH_FILE）" else "导出已清洗诊断") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClearAndRetry) { Text("清除并重试") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExit) { Text("退出") }
    }
}

/** 安全存储/数据库初始化失败时的兜底画面（不再闪退；数据未受影响——fail-closed 未降级）。 */
@Composable
fun ContainerInitFailedScreen(
    errorClass: String,
    errorDetail: String = "",
    onRetry: () -> Unit,
    onExit: () -> Unit,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
) {
    val context = LocalContext.current
    // §BI：稳定短错误码（类名哈希 16 进制），release 下替代 message 供定位
    val errorCode = remember(errorClass) { Integer.toHexString(errorClass.hashCode()) }
    var exported by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ECHO 没能安全地启动", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            if (isDebugBuild) {
                "本机安全存储初始化失败（$errorClass${if (errorDetail.isNotBlank()) "：$errorDetail" else ""}）。你的数据没有丢失，也没有被降级处理。\n" +
                    "请把这一行完整信息反馈给开发团队；重试可能无法解决，需按设备针对性修复。"
            } else {
                "本机安全存储初始化失败（$errorClass · 代码 $errorCode）。你的数据没有丢失，也没有被降级处理。\n" +
                    "可导出诊断报告反馈给开发团队；重试可能无法解决，需按设备针对性修复。"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isDebugBuild) {
                "设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）"
            } else {
                "环境：Android API ${android.os.Build.VERSION.SDK_INT} · v${BuildConfig.BUILD_VERSION}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
        Spacer(Modifier.height(8.dp))
        if (!isDebugBuild) {
            // §BI：release 导出已清洗诊断（类 + 码 + API + 版本 + 时间；无 message/路径/堆栈）
            OutlinedButton(onClick = {
                runCatching {
                    File(context.filesDir, INIT_DIAGNOSTICS_FILE).writeText(
                        buildString {
                            appendLine("class=$errorClass")
                            appendLine("code=$errorCode")
                            appendLine("api=${android.os.Build.VERSION.SDK_INT}")
                            appendLine("version=${BuildConfig.BUILD_VERSION}")
                            appendLine("timestamp=${System.currentTimeMillis()}")
                        }
                    )
                }.onSuccess { exported = true }
            }) { Text(if (exported) "已导出（files/$INIT_DIAGNOSTICS_FILE）" else "导出诊断报告") }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onExit) { Text("退出") }
    }
}

/**
 * §BJ：release 崩溃日志清洗（纯函数，便于单测）——
 * 只保留异常类行与首个栈帧；消息体（类名冒号后内容）与绝对路径（`xxx/` 前缀）剔除，总长封顶。
 */
object CrashReportSanitizer {
    private const val MAX_LENGTH = 2000
    private val PATH_PREFIX = Regex("""[^\s]*/""")
    private val EXCEPTION_CLASS_LINE = Regex("""^(?:[\w$.]+\.)*[\w$]*?(Exception|Error|Throwable)\b""")

    fun sanitize(raw: String): String {
        val kept = ArrayList<String>()
        var framesKept = 0
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("at ") -> {
                    if (framesKept == 0) {
                        kept += "at " + stripPaths(trimmed.removePrefix("at "))
                    }
                    framesKept++
                }
                EXCEPTION_CLASS_LINE.containsMatchIn(trimmed) ->
                    kept += stripPaths(trimmed.substringBefore(':'))
            }
        }
        val summary = kept.joinToString("\n")
        return if (summary.length > MAX_LENGTH) summary.take(MAX_LENGTH) + "…（已截断）" else summary
    }

    /** 剥离路径前缀（保留裸文件名，如 `/a/b/Foo.kt` → `Foo.kt`）。 */
    private fun stripPaths(s: String): String = PATH_PREFIX.replace(s, "")
}
