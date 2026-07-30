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

private enum class Tab(val label: String) { TODAY("今天"), RECORD("记录"), PRACTICE("练习"), TREND("趋势"), SUPPORT("支持") }

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
            if (tab != Tab.SUPPORT) {
                FloatingActionButton(onClick = { tab = Tab.SUPPORT }) { Text("支持") }
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
                Tab.TODAY -> TodayScreen(container.repository)
                Tab.RECORD -> RecordScreen(container.repository)
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
