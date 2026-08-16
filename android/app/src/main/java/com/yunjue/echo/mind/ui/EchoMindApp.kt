package com.yunjue.echo.mind.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer

/**
 * v2 §3：最终一级信息架构 —— 三个世界。
 *
 * ```text
 * ECHO    现在（ECHO Scene：Why / 问 ECHO / 行动）
 * Journey 我的时间（视觉记忆河流）
 * Me      我的控制权（Presence / AI Intelligence / What ECHO Knows / 数据与感知）
 * ```
 *
 * 旧「能力」Tab 已移除：基础行动在 ECHO Scene 内执行；
 * 订阅能力在 ECHO Scene「更多能力（订阅）」分区 + Me 中访问。
 * 危机入口（紧急 FAB）常驻：IA 精简绝不隐藏安全资源。
 *
 * v0.6.2（A3）无障碍不变量保持：导航态 rememberSaveable、图标 + contentDescription 双语义。
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    ECHO("ECHO", Icons.Filled.Home),
    JOURNEY("Journey", Icons.Filled.DateRange),
    ME("Me", Icons.Filled.Info)
}

/**
 * 紧急支持 FAB 可见性：除 Me tab 外始终可见（危机入口常驻，T11.5）。
 * 纯函数便于单测断言该不变量。
 */
internal fun shouldShowEmergencyFab(isSupportTab: Boolean): Boolean = !isSupportTab

@Composable
fun EchoMindApp(container: AppContainer) {
    // 导航态 rememberSaveable：进程重建/配置变更不丢失；Onboarding 完成即进入 ECHO 世界
    var onboardingDone by rememberSaveable { mutableStateOf(container.preferences.onboardingCompleted) }
    if (!onboardingDone) {
        OnboardingScreen(container) { onboardingDone = true }
        return
    }
    var tabName by rememberSaveable { mutableStateOf(Tab.ECHO.name) }
    val tab = runCatching { Tab.valueOf(tabName) }.getOrDefault(Tab.ECHO)
    Scaffold(
        floatingActionButton = {
            // 紧急支持入口常驻：红色 FAB，任何非 Me tab 下可见，点击直达 Me（支持区块）
            if (shouldShowEmergencyFab(tab == Tab.ME)) {
                FloatingActionButton(
                    onClick = { tabName = Tab.ME.name },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    // ERA 32 R26：警示图标（原「+」会被误读为新增/创建，与危机入口语义不符）
                    Icon(Icons.Filled.Warning, contentDescription = "紧急支持")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tabName = item.name },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.ECHO -> EchoSceneScreen(
                    container = container,
                    onGoToJourney = { tabName = Tab.JOURNEY.name },
                    onGoToMe = { tabName = Tab.ME.name },
                    onEmergency = { tabName = Tab.ME.name },
                )
                Tab.JOURNEY -> {
                    val journeyViewModel: com.yunjue.echo.mind.ui.journey.JourneyViewModel =
                        viewModel(factory = com.yunjue.echo.mind.ui.journey.JourneyViewModel.factory(container))
                    com.yunjue.echo.mind.ui.journey.JourneyScreen(
                        viewModel = journeyViewModel,
                        onGoToSupport = { tabName = Tab.ME.name }
                    )
                }
                Tab.ME -> com.yunjue.echo.mind.ui.me.MeScreen(container)
            }
        }
    }
}

@Composable
fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
        Spacer(Modifier.height(96.dp))
    }
}

fun dialIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
