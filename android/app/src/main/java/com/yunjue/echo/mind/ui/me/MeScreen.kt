package com.yunjue.echo.mind.ui.me

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.me.DataAndSensingEvent
import com.yunjue.echo.mind.me.MeEvent
import com.yunjue.echo.mind.me.MeUiState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.ui.Page

/**
 * Me 我的（V3 §AR/§AS — 分组控制中心）。
 *
 * 熟悉的 Android 设置范式（ListItem + 分组 + 分隔线 + chevron）取代概念图导航：
 * 1. 我的 ECHO —— 身份摘要卡（48dp ECHO 头像 + 成熟度/一句话状态；点按 → Presence 设置）
 * 2. 了解我的方式 —— 数据与感知 / 记忆 / AI
 * 3. ECHO 出现在哪里 —— 动态壁纸 / Dream 屏保 / 手环
 * 4. 我的数据 —— 导出 / 删除
 * 5. 其他 —— 通知 / 订阅 / 专业支持 / 关于
 *
 * 危机入口（§AT）：红色「紧急支持」行直达全屏 SafetyScreen（一键），不再内嵌 CrisisCard；
 * 领域深页（Data/Memory/Intelligence/Presence/Wrist）以既有 in-place domain 机制展开，
 * 全部入口距 Me 根 ≤1 步（testTag：me_entry_*）。
 */
@Composable
fun MeScreen(container: AppContainer, onEmergency: () -> Unit) {
    val context = LocalContext.current
    val meVm: MeViewModel = viewModel(factory = MeViewModel.factory(container))
    val state by meVm.uiState.collectAsStateWithLifecycle()
    val presence by container.echoStateStore.state.collectAsStateWithLifecycle()
    // V3 §33/§91：Visual Lab 仅 debug 构建可进入（debug diagnostics 入口）
    var showVisualLab by remember { mutableStateOf(false) }
    if (showVisualLab && com.yunjue.echo.mind.BuildConfig.DEBUG) {
        com.yunjue.echo.mind.ui.debug.VisualLabScreen(onClose = { showVisualLab = false })
        return
    }

    // §AS：根层「我的数据」直达动作复用数据分节同一 ViewModel（同 ViewModelStore 实例）
    val dataVm: DataAndSensingViewModel = viewModel(factory = DataAndSensingViewModel.factory(container))
    // 本地导出分享提升到 Me 根层收集：根层入口触发导出时数据分节可能尚未组合
    LaunchedEffect(dataVm) {
        dataVm.exportJson.collect { json ->
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, json)
            }
            runCatching { context.startActivity(Intent.createChooser(share, "导出本地数据")) }
        }
    }

    MeScreenContent(
        state = state,
        onEvent = meVm::onEvent,
        actions = MeControlActions(
            onEmergency = onEmergency,
            onExportData = { dataVm.onEvent(DataAndSensingEvent.RequestExport) },
            onDeleteData = { dataVm.onEvent(DataAndSensingEvent.RequestDelete) },
            onSelectWallpaper = {
                runCatching { context.startActivity(echoWallpaperSelectionIntent(context)) }
            },
            onDreamSettings = {
                runCatching { context.startActivity(dreamSettingsIntent()) }
            },
            onNotificationSettings = {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
        ),
        slots = MeSectionSlots(
            identityHeader = { MeEchoIdentity(presence, state.presence.reduceMotion) },
            subscription = { SubscriptionSection(container) },
            support = {
                SupportSection(
                    escalations = state.escalations,
                    onRequestSupport = { meVm.onEvent(MeEvent.RequestSupportClicked) },
                )
            },
            dataAndSensing = { DataAndSensingSection(container, context) },
            presenceSettings = { PresenceSettingsSection(container) },
            wrist = { WristSection(container) },
            intelligenceSettings = { IntelligenceSettingsSection(container) },
            whatEchoKnows = { WhatEchoKnowsSection(container) },
            aboutCard = { AboutCard(onOpenVisualLab = { showVisualLab = true }) },
        ),
    )
}

/**
 * V3 §AR — Me 根控制中心纯状态内容（state-in / event-out / action-out + 组合槽位）。
 * 分组列表常驻；领域深页在所属分组下方就地展开（一次只展开一个 domain）。
 */
@Composable
fun MeScreenContent(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    actions: MeControlActions,
    slots: MeSectionSlots,
) {
    if (state.showSupportConfirm) {
        AlertDialog(
            onDismissRequest = { onEvent(MeEvent.SupportDismissed) },
            title = { Text(stringResource(R.string.support_request_confirm_title)) },
            text = { Text(stringResource(R.string.support_request_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { onEvent(MeEvent.SupportConfirmed) }) {
                    Text(stringResource(R.string.support_request_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MeEvent.SupportDismissed) }) {
                    Text(stringResource(R.string.support_request_confirm_cancel))
                }
            }
        )
    }

    // §63：presentation state（一次只展开一个 domain；不建立新业务 state）
    var expandedDomain by rememberSaveable { mutableStateOf(MeDomain.NONE.name) }
    val domain = runCatching { MeDomain.valueOf(expandedDomain) }.getOrDefault(MeDomain.NONE)
    fun open(target: MeDomain) {
        expandedDomain = if (domain == target) MeDomain.NONE.name else target.name
    }

    Page("我的") {
        // 1. 我的 ECHO —— 身份摘要（点按 → Presence 设置）
        Box(
            Modifier
                .fillMaxWidth()
                .clickable { open(MeDomain.PRESENCE) }
                .testTag("me_entry_echo"),
        ) { slots.identityHeader() }

        // 危机入口（§AT：一键直达全屏 SafetyScreen；安全资源常驻可达）
        MeGroup {
            MeListItem(
                title = "紧急支持",
                supporting = stringResource(R.string.crisis_not_emergency_service),
                testTag = "me_entry_emergency",
                destructive = true,
                onClick = actions.onEmergency,
            )
        }

        // 2. 了解我的方式
        MeGroup(title = "了解我的方式") {
            MeListItem(
                title = "数据与感知",
                supporting = "权限、采集、同步与数据权利",
                testTag = "me_entry_data",
                onClick = { open(MeDomain.OBSERVATION) },
            )
            MeGroupDivider()
            MeListItem(
                title = "记忆",
                supporting = "ECHO 记得什么，由你管理",
                testTag = "me_entry_memory",
                onClick = { open(MeDomain.MEMORY) },
            )
            MeGroupDivider()
            MeListItem(
                title = "AI",
                supporting = "解读模型与连接",
                testTag = "me_entry_ai",
                onClick = { open(MeDomain.INTELLIGENCE) },
            )
            MeDomainDetail(domain, setOf(MeDomain.OBSERVATION, MeDomain.MEMORY, MeDomain.INTELLIGENCE), slots)
        }

        // 3. ECHO 出现在哪里
        MeGroup(title = "ECHO 出现在哪里") {
            MeListItem(
                title = "动态壁纸",
                supporting = "把 ECHO 设为手机动态壁纸",
                testTag = "me_entry_wallpaper",
                onClick = actions.onSelectWallpaper,
            )
            MeGroupDivider()
            MeListItem(
                title = "Dream · 充电屏保",
                supporting = "充电放在桌面时 ECHO 成为环境的一部分",
                testTag = "me_entry_dream",
                onClick = actions.onDreamSettings,
            )
            MeGroupDivider()
            MeListItem(
                title = "手环",
                supporting = "同一个 ECHO 的另一具身体",
                testTag = "me_entry_wrist",
                onClick = { open(MeDomain.WRIST) },
            )
            MeDomainDetail(domain, setOf(MeDomain.PRESENCE, MeDomain.WRIST), slots)
        }

        // 4. 我的数据
        MeGroup(title = "我的数据") {
            MeListItem(
                title = "导出数据",
                supporting = "导出本机保存的数据",
                testTag = "me_entry_export",
                onClick = actions.onExportData,
            )
            MeGroupDivider()
            MeListItem(
                title = "删除数据",
                supporting = "删除本机保存的数据（二次确认）",
                testTag = "me_entry_delete",
                onClick = {
                    // 删除确认对话框在数据与感知分节内呈现：强制展开该分节再触发删除流程
                    expandedDomain = MeDomain.OBSERVATION.name
                    actions.onDeleteData()
                },
            )
        }

        // 5. 其他
        MeGroup(title = "其他") {
            MeListItem(
                title = "通知",
                supporting = "系统通知设置",
                testTag = "me_entry_notifications",
                onClick = actions.onNotificationSettings,
            )
            MeGroupDivider()
            MeListItem(
                title = "订阅",
                supporting = "云端同步与专业支持订阅",
                testTag = "me_entry_subscription",
                onClick = { open(MeDomain.SUBSCRIPTION) },
            )
            MeGroupDivider()
            MeListItem(
                title = "专业支持",
                supporting = "请求人工支持",
                testTag = "me_entry_support",
                onClick = { open(MeDomain.SUPPORT) },
            )
            MeGroupDivider()
            MeListItem(
                title = "关于",
                supporting = "版本与构建信息",
                testTag = "me_entry_about",
                onClick = { open(MeDomain.ABOUT) },
            )
            MeDomainDetail(domain, setOf(MeDomain.SUBSCRIPTION, MeDomain.SUPPORT, MeDomain.ABOUT), slots)
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** §AR：Me 根控制中心动作面（危机直达/数据权利/系统意图经回调注入；纯渲染可测）。 */
data class MeControlActions(
    val onEmergency: () -> Unit,
    val onExportData: () -> Unit,
    val onDeleteData: () -> Unit,
    val onSelectWallpaper: () -> Unit,
    val onDreamSettings: () -> Unit,
    val onNotificationSettings: () -> Unit,
)

/** V3 §63：Me 领域 presentation state（非业务 state；一次只展开一个 domain）。 */
enum class MeDomain {
    NONE, OBSERVATION, MEMORY, INTELLIGENCE, PRESENCE, WRIST,
    SUBSCRIPTION, SUPPORT, ABOUT,
}

/** Me 根页面子领域槽位集合（状态提升模式；detekt LongParameterList 收敛）。 */
data class MeSectionSlots(
    val identityHeader: @Composable () -> Unit,
    val subscription: @Composable () -> Unit,
    val support: @Composable () -> Unit,
    val dataAndSensing: @Composable () -> Unit,
    val presenceSettings: @Composable () -> Unit,
    val wrist: @Composable () -> Unit,
    val intelligenceSettings: @Composable () -> Unit,
    val whatEchoKnows: @Composable () -> Unit,
    val aboutCard: @Composable () -> Unit,
)

/** 展开的领域深页就地渲染在所属分组下方（未展开/不属于本分组时不组合）。 */
@Composable
private fun MeDomainDetail(domain: MeDomain, hosts: Set<MeDomain>, slots: MeSectionSlots) {
    if (domain == MeDomain.NONE || domain !in hosts) {
        return
    }
    Column(Modifier.fillMaxWidth().testTag("me_domain_detail")) {
        when (domain) {
            MeDomain.OBSERVATION -> slots.dataAndSensing()
            MeDomain.MEMORY -> slots.whatEchoKnows()
            MeDomain.INTELLIGENCE -> slots.intelligenceSettings()
            MeDomain.PRESENCE -> slots.presenceSettings()
            MeDomain.WRIST -> slots.wrist()
            MeDomain.SUBSCRIPTION -> slots.subscription()
            MeDomain.SUPPORT -> slots.support()
            MeDomain.ABOUT -> slots.aboutCard()
            MeDomain.NONE -> Unit
        }
    }
}

/** 设置分组：小标题（可空）+ 组内条目（调用方以 [MeGroupDivider] 分隔）。 */
@Composable
private fun MeGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
            )
        }
        content()
    }
}

@Composable
private fun MeGroupDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

/** 标准设置行：标题 + 辅助说明 + chevron；点击整行（≥52dp 触达）。 */
@Composable
private fun MeListItem(
    title: String,
    supporting: String?,
    testTag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * 我的 ECHO 身份摘要（§AR：~48dp ECHO 头像 + 成熟度 + 一句话状态）。
 * 视觉沿用全局唯一 Presence（同一语义链 EchoVisualMapper → VisualGenomeCompiler）。
 */
@Composable
private fun MeEchoIdentity(presence: EchoPresenceState?, reduceMotion: Boolean) {
    val hourOfDay = remember { java.time.LocalTime.now().let { it.hour + it.minute / 60f } }
    val genome = remember(presence, hourOfDay, reduceMotion) {
        presence?.let {
            com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                com.yunjue.echo.mind.presence.EchoVisualMapper.map(it, hourOfDay, reduceMotion = reduceMotion),
                it.identityGenome,
            )
        }
    }
    val maturityName = presence?.maturity?.name ?: "SEED"
    val statusLine = com.yunjue.echo.mind.visual.surface.organismDescriptionFor(presence)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).testTag("me_echo_portrait")) {
            com.yunjue.echo.mind.presencevisual.EchoOrganism(
                genome = genome,
                modifier = Modifier.size(48.dp),
                surface = com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                motion = if (reduceMotion) {
                    com.yunjue.echo.mind.visual.surface.MotionPolicy.REDUCED
                } else {
                    com.yunjue.echo.mind.visual.surface.MotionPolicy.NORMAL
                },
                maturityName = maturityName,
                aggregateDescription = statusLine,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("我的 ECHO", style = MaterialTheme.typography.titleMedium)
            Text(
                "$maturityName · $statusLine",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** v3.2 §9：About / Diagnostics —— APK 构建来源可追溯（版本/提交/时间）。 */
@Composable
private fun AboutCard(onOpenVisualLab: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("ECHO Mind ${com.yunjue.echo.mind.BuildConfig.BUILD_VERSION}", style = MaterialTheme.typography.bodySmall)
            Text("构建提交：${com.yunjue.echo.mind.BuildConfig.GIT_COMMIT}", style = MaterialTheme.typography.bodySmall)
            Text("构建时间：${com.yunjue.echo.mind.BuildConfig.BUILD_TIMESTAMP}", style = MaterialTheme.typography.bodySmall)
            Text("数据默认只保存在本机；ECHO 是支持性工具，不是医生。", style = MaterialTheme.typography.bodySmall)
            // V3 §33/§91：ECHO Visual Lab 入口仅 debug 构建可见（debug diagnostics 入口）
            if (com.yunjue.echo.mind.BuildConfig.DEBUG) {
                OutlinedButton(onClick = onOpenVisualLab) { Text("ECHO Visual Lab (debug)") }
            }
        }
    }
}
