package com.yunjue.echo.mind.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import kotlinx.coroutines.launch

/**
 * 底部导航 4 Tab（PRD v0.6 契约点 7）：今天 / 能力 / 趋势 / 支持。
 * 移除「练习」空壳 Tab；「我的练习」记录由「能力」页 Skill 执行反馈承载。
 *
 * v0.6.2（Batch A，A3 无障碍）：符号不再作为唯一语义——
 * - FAB 用 material 图标 + contentDescription="紧急支持"（TalkBack 可准确朗读）
 * - 每个 tab 用 material-icons-core 图标（Home/Star/DateRange/Info，BOM 管理版本；
 *   注：TrendingUp 属 material-icons-extended，core 集以 DateRange 表达"数据覆盖时间线"）
 * - 导航态用 rememberSaveable（enum 存 name 字符串），进程重建/配置变更不丢失
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("今天", Icons.Filled.Home),
    SKILLS("能力", Icons.Filled.Star),
    TREND("趋势", Icons.Filled.DateRange),
    SUPPORT("支持", Icons.Filled.Info)
}

/**
 * 紧急支持 FAB 可见性：除 SUPPORT tab 外始终可见（危机入口常驻，T11.5）。
 * 抽成纯函数便于单测断言该不变量。
 */
internal fun shouldShowEmergencyFab(isSupportTab: Boolean): Boolean = !isSupportTab

@Composable
fun EchoMindApp(container: AppContainer) {
    // v0.6.2（A3）：rememberSaveable 在进程重建/配置变更后保留导航态；enum 存 name 字符串（不引入 Navigation Compose）
    var onboardingDone by rememberSaveable { mutableStateOf(container.preferences.onboardingCompleted) }
    if (!onboardingDone) {
        OnboardingScreen(container) { onboardingDone = true }
        return
    }
    var tabName by rememberSaveable { mutableStateOf(Tab.TODAY.name) }
    val tab = runCatching { Tab.valueOf(tabName) }.getOrDefault(Tab.TODAY)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(
        floatingActionButton = {
            // 紧急支持入口常驻：红色 FAB，任何非 SUPPORT tab 下可见，点击直达 SUPPORT
            if (shouldShowEmergencyFab(tab == Tab.SUPPORT)) {
                FloatingActionButton(
                    onClick = { tabName = Tab.SUPPORT.name },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "紧急支持")
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
                Tab.TODAY -> TodayScreen(
                    portraitRepository = container.portraitRepository,
                    syncStateRepository = container.syncStateRepository,
                    skillRepository = container.skillRepository,
                    coordinator = container.skillSessionCoordinator,
                    onGoToSkills = { tabName = Tab.SKILLS.name },
                    onGoToTrend = { tabName = Tab.TREND.name },
                    onEmergency = { tabName = Tab.SUPPORT.name },
                    onReEnableSensing = {
                        // SENSING_DISABLED 态「重新开启」：与支持页开关同一领域路径
                        // （先产生 granted 证据再启动服务；证据优先级高于新特征）
                        scope.launch {
                            ServiceRevocationCoordinator.reEnablePassiveSensing(
                                context, container.preferences, container.consentRepository, container.featureFlagRepository
                            )
                        }
                    }
                )
                Tab.SKILLS -> SkillListScreen(container.skillRepository, container.featureFlagRepository, container.skillSessionCoordinator)
                Tab.TREND -> TrendScreen(container.portraitRepository, container.syncStateRepository, container.featureFlagRepository, onGoToSupport = { tabName = Tab.SUPPORT.name })
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
