package com.yunjue.echo.mind.ui.me

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.me.MeEvent
import com.yunjue.echo.mind.me.MeUiState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.learningPhaseHeadline
import com.yunjue.echo.mind.ui.Page
import com.yunjue.echo.mind.wearable.WearableConnectionState

/**
 * Me 我的（V3 §AR/§AT — 渐进披露：四分区根 + 九个全屏次级页）。
 *
 * L0 四分区（≤2 屏）：
 * 1. Z1 身份 —— 我的 ECHO（48dp 头像 + 成熟度/一句话状态；点按 → PRESENCE）
 * 2. Z2 状态网格 —— 2×2 卡片：智能地图 / 数据与感知 / 记忆 / 手环（每卡一行真实状态摘要）
 * 3. Z3 支持与安全 —— 紧急支持（一键直达 SafetyScreen）/ 专业支持
 * 4. Z4 账户与设置 —— 订阅 / AI / 通知 / 关于
 *
 * L1 全屏次级页（MeRoute；TopAppBar + back + BackHandler，复用 EchoAskScreen 范式）：
 * PRESENCE / SMARTMAP / SENSING / MEMORY / WRIST / INTELLIGENCE / SUBSCRIPTION / SUPPORT / ABOUT。
 * 危机入口（§AT）：红色「紧急支持」行直达全屏 SafetyScreen（一键）；全部入口距 Me 根 ≤1 步
 * （testTag：me_entry_* 锚点全保留）。
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

    // Z2 状态摘要：手环连接真值（§63 presentation state；不建立新业务 state）
    val wristRuntime by container.wearable.runtime.state.collectAsStateWithLifecycle()
    val wristStatusLine = when (wristRuntime.connection) {
        WearableConnectionState.CONNECTED ->
            "已连接${wristRuntime.device?.model?.let { " · $it" } ?: ""}"
        WearableConnectionState.CONNECTING -> "连接中"
        WearableConnectionState.DISCONNECTED -> "未连接设备"
    }

    MeScreenContent(
        state = state,
        onEvent = meVm::onEvent,
        actions = MeControlActions(
            onEmergency = onEmergency,
            onNotificationSettings = {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
        ),
        wristStatusLine = wristStatusLine,
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
            // SMARTMAP 页：智能地图接真实设备绑定；快速管理条目由 MeScreenContent 经槽位注入（不再传 emptyList()）
            smartMap = { items ->
                MeSmartMapSection(
                    devices = SmartMapDevices(
                        connectedCount = if (wristRuntime.connection == WearableConnectionState.CONNECTED) 1 else 0,
                        providerName = "本地模型",
                        providerLocation = "本地运行中",
                        privacyNote = "设备端运行保护隐私",
                    ),
                    quickAccessItems = items,
                )
            },
        ),
    )
}

/**
 * V3 §AR — Me 根四分区 + MeRoute 路由（state-in / event-out / action-out + 组合槽位）。
 * L1 次级页为全屏替换（TopAppBar + back + BackHandler）；一次只有一个路由活跃。
 */
@Composable
fun MeScreenContent(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    actions: MeControlActions,
    slots: MeSectionSlots,
    wristStatusLine: String = "未连接设备",
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

    // §63：presentation state（当前路由；String? 存取，进程重建恢复）
    var routeName by rememberSaveable { mutableStateOf<String?>(null) }
    val route = routeName?.let { runCatching { MeRoute.valueOf(it) }.getOrNull() }
    fun open(target: MeRoute) {
        routeName = target.name
    }

    if (route != null) {
        MeSubPage(
            title = route.pageTitle(),
            testTag = "me_page_${route.name.lowercase()}",
            onBack = { routeName = null },
        ) {
            when (route) {
                MeRoute.PRESENCE -> slots.presenceSettings()
                // 快速管理：真实条目经槽位注入 MeSmartMapSection（页内路由；me_entry_* 锚点语义不变）
                MeRoute.SMARTMAP -> slots.smartMap(meQuickAccessItems { open(it) })
                MeRoute.SENSING -> slots.dataAndSensing()
                MeRoute.MEMORY -> slots.whatEchoKnows()
                MeRoute.WRIST -> slots.wrist()
                MeRoute.INTELLIGENCE -> slots.intelligenceSettings()
                MeRoute.SUBSCRIPTION -> slots.subscription()
                MeRoute.SUPPORT -> slots.support()
                MeRoute.ABOUT -> slots.aboutCard()
            }
        }
        return
    }

    Page("我的") {
        // Z1 身份 —— 我的 ECHO（点按 → PRESENCE）
        Box(
            Modifier
                .fillMaxWidth()
                .clickable { open(MeRoute.PRESENCE) }
                .testTag("me_entry_echo"),
        ) { slots.identityHeader() }

        // Z2 状态网格 —— 2×2 卡片（每卡一行真实状态摘要）
        MeStateGrid(
            smartMapStatus = "感知${if (state.sensingEnabled) "开启" else "关闭"} · $wristStatusLine",
            sensingStatus = "感知${if (state.sensingEnabled) "已开启" else "已关闭"}" +
                " · 待同步 ${state.sync.pendingCount} 项",
            memoryStatus = "已存 ${state.memory.total} 条 · ${state.memory.uncertain} 条待确认",
            wristStatus = wristStatusLine,
            onOpenRoute = ::open,
        )

        // Z3 支持与安全（§AT：紧急一键直达全屏 SafetyScreen）
        MeGroup(title = "支持与安全") {
            MeListItem(
                title = "紧急支持",
                supporting = stringResource(R.string.crisis_not_emergency_service),
                testTag = "me_entry_emergency",
                destructive = true,
                onClick = actions.onEmergency,
            )
            MeGroupDivider()
            MeListItem(
                title = "专业支持",
                supporting = "请求人工支持",
                testTag = "me_entry_support",
                onClick = { open(MeRoute.SUPPORT) },
            )
        }

        // Z4 账户与设置
        MeGroup(title = "账户与设置") {
            MeListItem(
                title = "订阅",
                supporting = "云端同步与专业支持订阅",
                testTag = "me_entry_subscription",
                onClick = { open(MeRoute.SUBSCRIPTION) },
            )
            MeGroupDivider()
            MeListItem(
                title = "AI",
                supporting = "解读模型与连接",
                testTag = "me_entry_ai",
                onClick = { open(MeRoute.INTELLIGENCE) },
            )
            MeGroupDivider()
            MeListItem(
                title = "通知",
                supporting = "系统通知设置",
                testTag = "me_entry_notifications",
                onClick = actions.onNotificationSettings,
            )
            MeGroupDivider()
            MeListItem(
                title = "关于",
                supporting = "版本与构建信息",
                testTag = "me_entry_about",
                onClick = { open(MeRoute.ABOUT) },
            )
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** §AR：Me 根动作面（危机直达 / 系统通知设置经回调注入；纯渲染可测）。 */
data class MeControlActions(
    val onEmergency: () -> Unit,
    val onNotificationSettings: () -> Unit,
)

/** V3 §63：Me 全屏次级页路由（String name 存入 rememberSaveable）。 */
enum class MeRoute {
    PRESENCE, SMARTMAP, SENSING, MEMORY, WRIST,
    INTELLIGENCE, SUBSCRIPTION, SUPPORT, ABOUT,
}

/** 路由 → L1 页标题。 */
private fun MeRoute.pageTitle(): String = when (this) {
    MeRoute.PRESENCE -> "ECHO 出现在哪里"
    MeRoute.SMARTMAP -> "智能地图"
    MeRoute.SENSING -> "数据与感知"
    MeRoute.MEMORY -> "记忆"
    MeRoute.WRIST -> "手环"
    MeRoute.INTELLIGENCE -> "AI"
    MeRoute.SUBSCRIPTION -> "订阅"
    MeRoute.SUPPORT -> "专业支持"
    MeRoute.ABOUT -> "关于"
}

/** SMARTMAP 页快速管理条目（路由闭包由 MeScreenContent 提供；detekt LongParameterList 收敛于列表）。 */
private fun meQuickAccessItems(open: (MeRoute) -> Unit): List<QuickAccessItem> = listOf(
    QuickAccessItem(
        title = "手环",
        description = "连接与偏好",
        testTag = "me_entry_wrist",
        onClick = { open(MeRoute.WRIST) },
    ),
    QuickAccessItem(
        title = "数据与感知",
        description = "感知通道与数据权利",
        testTag = "me_entry_data",
        onClick = { open(MeRoute.SENSING) },
    ),
    QuickAccessItem(
        title = "记忆",
        description = "ECHO 记得什么，由你管理",
        testTag = "me_entry_memory",
        onClick = { open(MeRoute.MEMORY) },
    ),
    QuickAccessItem(
        title = "AI",
        description = "解读模型与连接",
        testTag = "me_entry_ai",
        onClick = { open(MeRoute.INTELLIGENCE) },
    ),
)

/** Me L1 次级页槽位集合（状态提升模式；detekt LongParameterList 收敛）。 */
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
    val smartMap: @Composable (List<QuickAccessItem>) -> Unit = {},
)

/**
 * Me L1 全屏次级页容器（EchoAskScreen 范式：TopAppBar + back + BackHandler + verticalScroll）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeSubPage(
    title: String,
    testTag: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize().testTag(testTag),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Spacer(Modifier.height(64.dp))
        }
    }
}

/** Z2 状态网格：2×2 卡片（每卡标题 + 一行真实状态摘要）。 */
@Composable
private fun MeStateGrid(
    smartMapStatus: String,
    sensingStatus: String,
    memoryStatus: String,
    wristStatus: String,
    onOpenRoute: (MeRoute) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeStateCard(
                title = "智能地图",
                status = smartMapStatus,
                testTag = "me_entry_smartmap",
                onClick = { onOpenRoute(MeRoute.SMARTMAP) },
                modifier = Modifier.weight(1f),
            )
            MeStateCard(
                title = "数据与感知",
                status = sensingStatus,
                testTag = "me_entry_data",
                onClick = { onOpenRoute(MeRoute.SENSING) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeStateCard(
                title = "记忆",
                status = memoryStatus,
                testTag = "me_entry_memory",
                onClick = { onOpenRoute(MeRoute.MEMORY) },
                modifier = Modifier.weight(1f),
            )
            MeStateCard(
                title = "手环",
                status = wristStatus,
                testTag = "me_entry_wrist",
                onClick = { onOpenRoute(MeRoute.WRIST) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Z2 状态卡：≥88dp 触达 + Role.Button 双语义。 */
@Composable
private fun MeStateCard(
    title: String,
    status: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().heightIn(min = 88.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(testTag)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * Organism Quality §33：经统一 renderer facade（显式 MINI/THUMBNAIL 低成本预设，
 * 不绕过 facade 另起渲染，也不启动高成本 full renderer）。
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
    val maturityLabel = learningPhaseHeadline(presence?.maturity ?: com.yunjue.echo.mind.model.EchoMaturity.SEED)
    val statusLine = com.yunjue.echo.mind.visual.surface.organismDescriptionFor(presence)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).testTag("me_echo_portrait")) {
            genome?.let { g ->
                // 统一 facade session + MINIMAL 质量（静态 canonical 帧——头像不需要帧动画）
                val density = LocalDensity.current
                val sizePx = with(density) { 48.dp.roundToPx() }
                val session = remember(g, maturityName) {
                    com.yunjue.echo.mind.presencevisual.EchoRendererFacade.createSession(
                        com.yunjue.echo.mind.presencevisual.EchoRenderSession.thumbnailRequest(
                            g, maturityName,
                            com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                        ),
                        sizePx, sizePx,
                    )
                }
                val clockNanos = remember { com.yunjue.echo.mind.presencevisual.EchoVisualClock.nowNanos() }
                Canvas(
                    Modifier.fillMaxSize().semantics { contentDescription = statusLine },
                ) {
                    session.draw(drawContext.canvas.nativeCanvas, clockNanos)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("我的 ECHO", style = MaterialTheme.typography.titleMedium)
            Text(
                "$maturityLabel · $statusLine",
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
