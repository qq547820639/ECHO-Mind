package com.yunjue.echo.mind.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SkillFetchResult
import com.yunjue.echo.mind.model.SkillDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 卡片 WebView 安全沙箱配置：禁用 JS / 文件访问 / DOM 存储。
 *
 * 卡片内容来自后端下发的 Skill 字段，虽经脱敏仍按不可信内容处理——
 * JS 与文件访问一律关闭，杜绝 XSS 或 file:// 越权读取。
 */
internal fun configureSandboxWebView(webView: WebView) {
    with(webView.settings) {
        javaScriptEnabled = false
        allowFileAccess = false
        domStorageEnabled = false
    }
}

/**
 * 由 [SkillDisplay] 生成卡片内联 HTML（不依赖外部资源）。
 *
 * 含 name / triggerConditions / steps / guardrails；所有动态文本经 [escapeHtml]
 * 转义后注入，防止后端字段意外携带 HTML 注入。
 */
internal fun buildSkillCardHtml(skill: SkillDisplay): String {
    val triggersHtml = skill.triggerConditions
        .joinToString("") { "<li>${escapeHtml(it)}</li>" }
        .takeIf { it.isNotEmpty() }
        ?.let { "<h3>触发条件</h3><ul>$it</ul>" }
        .orEmpty()
    val stepsHtml = skill.steps
        .mapIndexed { index, step -> "<li>${index + 1}. ${escapeHtml(step)}</li>" }
        .joinToString("")
        .takeIf { it.isNotEmpty() }
        ?.let { "<h3>步骤</h3><ul>$it</ul>" }
        .orEmpty()
    val guardrailsHtml = skill.guardrails
        .joinToString("") { "<li>${escapeHtml(it)}</li>" }
        .takeIf { it.isNotEmpty() }
        ?.let { "<h3>边界</h3><ul class=\"guard\">$it</ul>" }
        .orEmpty()
    return """
        <html><head><meta charset="utf-8"><style>
        body{font-family:sans-serif;color:#1a1a1a;margin:0;padding:0;}
        h2{font-size:18px;margin:0 0 6px 0;}
        .meta{color:#666;font-size:12px;margin-bottom:8px;}
        h3{font-size:14px;margin:12px 0 4px 0;}
        ul{margin:0;padding-left:18px;}
        li{font-size:14px;line-height:1.5;margin-bottom:4px;}
        .guard li{color:#444;}
        </style></head><body>
        <h2>${escapeHtml(skill.name)}</h2>
        <div class="meta">v${skill.version} · ${escapeHtml(skill.status)}</div>
        $triggersHtml
        $stepsHtml
        $guardrailsHtml
        </body></html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * P3 冷启动文案资源映射：按后端返回的 stage key 返回对应 [R.string] 资源 ID。
 *
 * - stage_0 → 系统正在了解你
 * - stage_1_3 → 已采集 N 天数据（[days] 用于格式化 %1$d 占位）
 * - stage_4_7 → 画像成型中
 * - stage_7_plus → 暂无新能力
 * - 未知 stage 兜底 stage_0
 *
 * 抽成纯函数便于单测覆盖 4 档映射。返回值为资源 ID，调用方用 [stringResource] 取文本。
 */
internal fun coldStartHint(stage: String, days: Int): Int = when (stage) {
    "stage_0" -> R.string.cold_start_stage_0
    "stage_1_3" -> R.string.cold_start_stage_1_3
    "stage_4_7" -> R.string.cold_start_stage_4_7
    "stage_7_plus" -> R.string.cold_start_stage_7_plus
    else -> R.string.cold_start_stage_0
}

/**
 * 拉取已下发 Skill 列表（IO 线程）；返回三态 [SkillFetchResult] + 重试回调。
 *
 * - 首次组合：skills=null + loadFailed=false → 加载中
 * - 拉取完成：由 [LocalRepository.fetchSkills] 决定成功/失败/空态
 * - 重试：调用方调返回的 lambda 触发重新拉取（retryKey 自增驱动 [LaunchedEffect]）
 *
 * 复用于 [TodayScreen] 与 [SkillListScreen]，避免两处重复拉取逻辑。
 */
@Composable
internal fun rememberSkillList(repository: LocalRepository): Pair<SkillFetchResult, () -> Unit> {
    var result by remember {
        mutableStateOf(SkillFetchResult(skills = null, coldStartHint = null, loadFailed = false))
    }
    var retryKey by remember { mutableStateOf(0) }
    LaunchedEffect(retryKey) {
        result = withContext(Dispatchers.IO) { repository.fetchSkills() }
    }
    return result to { retryKey++ }
}

/**
 * Skill 卡片宿主：用 [WebView] 安全沙箱渲染卡片内容，底部 Compose Button 触发 [onTrigger]。
 * "开始"按钮刻意放在 WebView 之外（原生 Compose），保证交互不依赖 JS。
 */
@Composable
fun SkillCardHost(skill: SkillDisplay, onTrigger: () -> Unit) {
    val html = remember(skill) { buildSkillCardHtml(skill) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureSandboxWebView(this)
                        // tag 记录当前已加载的 html，供 update 判重，避免无关重组重复加载
                        tag = html
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                update = { webview ->
                    // 仅在 html 变化时重新加载（如 skill 切换），避免父组件重组引起的闪烁
                    if (webview.tag != html) {
                        webview.tag = html
                        webview.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
            Button(onClick = onTrigger, modifier = Modifier.fillMaxWidth()) { Text("开始") }
        }
    }
}

/**
 * 「能力」Tab 全页 Skill 列表：拉取已下发 Skill，区分三态。
 *
 * - 加载中（skills==null + 未失败）→ CircularProgressIndicator
 * - 加载失败（loadFailed）→「加载失败」+ 重试按钮
 * - 空列表（冷启动）→ 按 [SkillFetchResult.coldStartHint] 分阶段文案
 * - 非空 → Skill 卡片列表
 *
 * 危机入口由全局紧急 FAB 常驻，此页不重复放置。
 */
@Composable
fun SkillListScreen(repository: LocalRepository) {
    val (skillState, retry) = rememberSkillList(repository)

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
        item { Text("能力", style = MaterialTheme.typography.headlineMedium) }
        when {
            !skillsDeliveryEnabled -> item {
                // P5 灰度回滚：skills_delivery_enabled=false 时隐藏 Skill 卡片区
                Text(
                    stringResource(R.string.skills_delivery_paused),
                    Modifier.padding(top = 40.dp)
                )
            }
            skillState.loadFailed -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.cold_start_load_failed))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = retry) { Text(stringResource(R.string.cold_start_retry)) }
                }
            }
            skillState.skills == null -> item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            skillState.skills.isEmpty() -> item {
                val stage = skillState.coldStartHint ?: "stage_0"
                val resId = coldStartHint(stage, skillState.observationDays)
                Text(
                    if (stage == "stage_1_3") stringResource(resId, skillState.observationDays)
                    else stringResource(resId),
                    Modifier.padding(top = 40.dp)
                )
            }
            else -> items(skillState.skills) { skill -> SkillCardHost(skill) {} }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
