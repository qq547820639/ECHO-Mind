package com.yunjue.echo.mind.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer

private enum class Tab(val label: String) { TODAY("今天"), SKILLS("能力"), PRACTICE("练习"), TREND("趋势"), SUPPORT("支持") }

/**
 * 紧急支持 FAB 可见性：除 SUPPORT tab 外始终可见（危机入口常驻，T11.5）。
 * 抽成纯函数便于单测断言该不变量。
 */
internal fun shouldShowEmergencyFab(isSupportTab: Boolean): Boolean = !isSupportTab

@Composable
fun EchoMindApp(container: AppContainer) {
    var onboardingDone by remember { mutableStateOf(container.preferences.onboardingCompleted) }
    if (!onboardingDone) {
        OnboardingScreen(container) { onboardingDone = true }
        return
    }
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val context = LocalContext.current
    Scaffold(
        floatingActionButton = {
            // 紧急支持入口常驻：红色十字 FAB，任何非 SUPPORT tab 下可见，点击直达 SUPPORT
            if (shouldShowEmergencyFab(tab == Tab.SUPPORT)) {
                FloatingActionButton(
                    onClick = { tab = Tab.SUPPORT },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) { Text("✚") }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text("•") },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.TODAY -> TodayScreen(container.repository) { tab = Tab.SUPPORT }
                Tab.SKILLS -> SkillListScreen(container.repository)
                Tab.PRACTICE -> PracticeScreen(container.repository)
                Tab.TREND -> TrendScreen(container.repository)
                Tab.SUPPORT -> SupportScreen(container)
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
