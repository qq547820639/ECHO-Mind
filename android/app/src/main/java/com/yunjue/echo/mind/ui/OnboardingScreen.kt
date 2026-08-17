package com.yunjue.echo.mind.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.SyncWorker
import com.yunjue.echo.mind.PassiveSensingService
import com.yunjue.echo.mind.sensing.hasCoreSensorHardware
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * T12.6 L0 准入门禁：currentDanger / psychosisOrMania / substanceImpairment 任一为真即阻断进入。
 *
 * L0 从普通 Portrait Onboarding **移除**，不参与本流程的按钮 enabled 判定；
 * 本纯函数保留，供「支持」页主动进入安全流程时复用与单测断言（机构契约不变）。
 */
internal fun l0OnboardingBlocked(
    currentDanger: Boolean,
    psychosisOrMania: Boolean,
    substanceImpairment: Boolean
): Boolean = currentDanger || psychosisOrMania || substanceImpairment

/** V3 §H：Onboarding 的 Seed ECHO genome（唯一语义链：EchoVisualMapper → VisualGenomeCompiler）。 */
@Composable
private fun rememberSeedGenome(
    seed: com.yunjue.echo.mind.model.EchoPresenceState,
): com.yunjue.echo.mind.visual.model.EchoVisualGenome = remember(seed) {
    val hourOfDay = java.time.LocalTime.now().let { it.hour + it.minute / 60f }
    com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
        com.yunjue.echo.mind.presence.EchoVisualMapper.map(seed, hourOfDay),
        seed.identityGenome,
    )
}

/**
 * ERA 1 Onboarding（Master Prompt PART 63）：
 * `WELCOME → PRIVACY_PLEDGE → CORE_SENSING → ECHO AWAKENING →（自动进入主界面）`
 *
 * - **无 DONE / 无「进入应用」**：最后一个核心授权成功后自动苏醒、自动进入 ECHO Scene；
 * - WELCOME：一句定位 + 18+/边界确认 + 紧急入口常驻；
 * - PRIVACY_PLEDGE：三句隐私承诺 + 5 项核心数据同意（拒绝 = abstain）；
 * - CORE_SENSING：核心能力 = 传感器 + 屏幕（均无需系统权限，展示硬件真实状态）+
 *   POST_NOTIFICATIONS（Android 13+ 可选）；USAGE / NOTIFICATION / MIC 属
 *   Enhancement / Sensitive Optional，**移出 onboarding**（核心体验后再提示或支持页单独授权）；
 * - 权限行的唯一事实来源 = 系统真实状态（硬件可用性 / 运行时权限），禁止乐观置位；
 * - AWAKENING：ECHO 图形苏醒（呼吸动画）→「ECHO 已开始了解你」→ 自动 finishOnboarding。
 *
 * 授权完成瞬间写入 [AppPreferences.awakenedAtEpochMs]（Day-0 SEED 的「已观察 N 分钟」锚点）。
 */
@Composable
fun OnboardingScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preferences = container.preferences

    // ERA 32 R26：rememberSaveable——配置变更（旋转/深浅色）不再丢失勾选进度；
    // 进程死亡恢复由 onboardingState（CONSENT_PENDING → 隐私承诺页）承担。
    var ageConfirmed by rememberSaveable { mutableStateOf(false) }
    var boundaryConfirmed by rememberSaveable { mutableStateOf(false) }

    // 核心数据同意：5 项（全部勾选才可继续；拒绝 = abstain）
    var coreChecks by rememberSaveable(
        stateSaver = Saver(
            save = { state -> state.joinToString("") { if (it) "1" else "0" } },
            restore = { saved -> saved.map { it == '1' } },
        )
    ) { mutableStateOf(listOf(false, false, false, false, false)) }
    val allCoreChecked = coreChecks.all { it }

    // Android 13+ 持续通知权限（可选降级：拒绝仅通知不可见，采集继续）。
    // 初始状态按系统当前真实授权回填；回调同样只信系统结果。
    var notifPermAuthorized by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> notifPermAuthorized = granted }

    var showSafety by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable {
        mutableStateOf(
            when (preferences.onboardingState) {
                // 进程中断恢复：BOUND / CONSENT_PENDING 从隐私承诺页继续
                AppPreferences.ONBOARDING_BOUND,
                AppPreferences.ONBOARDING_CONSENT_PENDING -> OnboardingStep.PRIVACY_PLEDGE
                else -> OnboardingStep.WELCOME
            }
        )
    }

    // ===== ECHO AWAKENING 状态：苏醒过渡结束后自动完成 onboarding =====
    var awakening by rememberSaveable { mutableStateOf(false) }

    if (showSafety) {
        SafetyScreen(
            deliveryState = "尚未确认送达，请优先使用电话入口。",
            onBack = { showSafety = false }
        )
        return
    }

    fun finishOnboarding(sensingOn: Boolean) {
        scope.launch {
            // v0.7 本地优先架构：默认本地模式（无账号门槛）。未绑定时生成本地用户标识。
            if (preferences.userId.isBlank()) {
                preferences.userId = "local_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            }
            // v0.6.1（P1-7）幂等：本地已提交过（重复点击/进程死亡重启）→ 直接推进。
            if (preferences.onboardingLocalSubmitted) {
                preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
                preferences.serverActivated = false
                onComplete()
                return@launch
            }
            // ERA 32 R26：abstain（暂不开启）不再写 granted 同意——
            // 同意证据只在真正开启采集时产生；旅程页「已关闭」状态如实反映 abstain。
            if (coreChecks.all { it } && sensingOn) {
                container.consentRepository.savePassiveSensingConsent(granted = true)
                container.preferences.setPassiveSensingEnabled(true)
                container.preferences.consentSyncPending = true
            }
            preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
            preferences.serverActivated = false
            preferences.onboardingLocalSubmitted = true
            if (sensingOn) {
                // 02b 共享知识 1：consent granted → flag（拉取失败 fail-closed）→ 真实启动服务
                // ERA 32 R22：本地模式不依赖远端 flag（服务门控本地豁免）——
                // 跳过拉取，避免无网首启在苏醒动画上白等连接超时（约 10 秒）。
                if (!preferences.localMode) {
                    try {
                        container.featureFlagRepository.fetchFeatureFlags()
                    } catch (_: Exception) {
                        // flag 拉取失败 fail-closed：服务启动门控内 flag=false 不启动
                    }
                }
                PassiveSensingService.start(context)
            } else {
                PassiveSensingService.stop(context)
            }
            SyncWorker.enqueue(context)
            onComplete()
        }
    }

    if (awakening) {
        AwakeningScreen(preferences = preferences, onFinished = {
            scope.launch { finishOnboarding(sensingOn = true) }
        })
        return
    }

    OnboardingStepContent(
        state = OnboardingStepState(
            step = step,
            ageConfirmed = ageConfirmed,
            boundaryConfirmed = boundaryConfirmed,
            coreChecks = coreChecks,
            notifPermAuthorized = notifPermAuthorized,
            sensorHardwareAvailable = hasCoreSensorHardware(context),
            // V3 §51/§53：Seed ECHO 视觉（与 Awakening/Home 同一 identitySeed）
            seedPresence = remember { com.yunjue.echo.mind.presence.dayZeroSeedPresence(preferences.identitySeed) },
        ),
        actions = OnboardingStepActions(
            onAgeConfirmed = { ageConfirmed = it },
            onBoundaryConfirmed = { boundaryConfirmed = it },
            onCoreCheck = { index, value -> coreChecks = coreChecks.withIndexed(value, index) },
            onContinueToPrivacy = {
                // ERA 32 R26：进入隐私承诺页即持久化进度（进程死亡后从这里恢复，
                // 不再回到 WELCOME 重来）
                preferences.onboardingState = AppPreferences.ONBOARDING_CONSENT_PENDING
                step = OnboardingStep.PRIVACY_PLEDGE
            },
            onContinueToCoreSensing = { step = OnboardingStep.CORE_SENSING },
            onAwaken = {
                // 苏醒瞬间锚点（Day-0 SEED 的「已观察 N 分钟」起点）
                preferences.awakenedAtEpochMs = System.currentTimeMillis()
                awakening = true
            },
            onAbstain = { scope.launch { finishOnboarding(sensingOn = false) } },
            onRequestNotifPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notifPermAuthorized = true
                }
            },
            onSkipNotifPermission = { notifPermAuthorized = false },
            onOpenSafety = { showSafety = true },
        ),
    )
}

/** ERA 1 Onboarding 三步（DONE / BASELINE_WARMING_UP 已删除）。 */
enum class OnboardingStep { WELCOME, PRIVACY_PLEDGE, CORE_SENSING }

/** ERA 38 — Onboarding 步骤纯状态（渲染输入；编排留在 OnboardingScreen）。 */
data class OnboardingStepState(
    val step: OnboardingStep,
    val ageConfirmed: Boolean,
    val boundaryConfirmed: Boolean,
    val coreChecks: List<Boolean>,
    val notifPermAuthorized: Boolean,
    val sensorHardwareAvailable: Boolean,
    /** V3 §51/§53：Seed ECHO（真实 identitySeed 派生；null = 不渲染视觉，测试友好）。 */
    val seedPresence: com.yunjue.echo.mind.model.EchoPresenceState? = null,
)

/** ERA 38 — Onboarding 步骤回调（state-in / event-out）。 */
data class OnboardingStepActions(
    val onAgeConfirmed: (Boolean) -> Unit,
    val onBoundaryConfirmed: (Boolean) -> Unit,
    val onCoreCheck: (Int, Boolean) -> Unit,
    val onContinueToPrivacy: () -> Unit,
    val onContinueToCoreSensing: () -> Unit,
    val onAwaken: () -> Unit,
    val onAbstain: () -> Unit,
    val onRequestNotifPermission: () -> Unit,
    val onSkipNotifPermission: () -> Unit,
    val onOpenSafety: () -> Unit,
)

/**
 * ERA 38 — Onboarding 纯步骤内容（state-in / event-out）。
 * 三步渲染矩阵 + 门禁判定（18+/边界 → 五同意 → 能力行真实状态）；
 * 苏醒过渡 / 安全屏 / 权限 launcher / 服务启动全部在 OnboardingScreen 编排层。
 */
@Composable
fun OnboardingStepContent(state: OnboardingStepState, actions: OnboardingStepActions) {
    val allCoreChecked = state.coreChecks.all { it }
    // V3 §50–§53：quiet 全屏场景（无 legacy Page wrapper / 无大 Card）
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        when (state.step) {
            OnboardingStep.WELCOME -> {
                // §51：Seed ECHO（260–300dp）是主要视觉
                state.seedPresence?.let { seed ->
                    Box(
                        Modifier.fillMaxWidth().height(280.dp).testTag("onboarding_visual"),
                    ) {
                        com.yunjue.echo.mind.presencevisual.EchoOrganism(
                            genome = rememberSeedGenome(seed),
                            modifier = Modifier.fillMaxSize(),
                            maturityName = seed.maturity.name,
                        )
                    }
                }
                // ERA 1 定位句（契约锚点保留：「不会判断情绪/不做心理诊断」，单测锁定）。
                Text(
                    ONBOARDING_WELCOME_CORE_COPY,
                    style = MaterialTheme.typography.bodyLarge
                )
                CheckLine(state.ageConfirmed, actions.onAgeConfirmed, "我已年满 18 周岁")
                CheckLine(state.boundaryConfirmed, actions.onBoundaryConfirmed, "我理解专业判断和危机处置由人工承担")
                HorizontalDivider()
                Text("本机使用", style = MaterialTheme.typography.titleMedium)
                Text(
                    "无需账号和激活码即可开始。画像由手机本机数据生成，数据默认只保存在你的设备里。如需云端同步与专业支持，可稍后在「支持」页订阅（可选）。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = actions.onContinueToPrivacy,
                    enabled = state.ageConfirmed && state.boundaryConfirmed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始")
                }
                OnboardingEmergencyEntry(onOpenSafety = actions.onOpenSafety, copy = EMERGENCY_HINT_COPY)
            }

            OnboardingStep.PRIVACY_PLEDGE -> {
                Text("隐私承诺", style = MaterialTheme.typography.titleMedium)
                // ERA 1 三句承诺（Master Prompt PART 63 / 产品宪法 §3）
                Text(
                    "ECHO 的承诺只有三句话：\n\n" +
                        "1. 原始数据不离开设备。原始传感器数据只在本机处理，不保存、不上传。\n" +
                        "2. 你随时可以暂停。所有同意都可以随时撤回，撤回后 ECHO 停止学习。\n" +
                        "3. ECHO 不会因为一个行为就定义你的心理状态。它只做行为观察，不做心理诊断。",
                    style = MaterialTheme.typography.bodyLarge
                )
                HorizontalDivider()
                Text("需要你同意的数据处理", style = MaterialTheme.typography.titleSmall)
                CheckLine(
                    state.coreChecks[0], { actions.onCoreCheck(0, it) },
                    "授权 ECHO 在后台采集加速度 / 陀螺仪等运动传感器数据，用于了解你一天的移动与作息节奏。"
                )
                CheckLine(
                    state.coreChecks[1], { actions.onCoreCheck(1, it) },
                    "传感器数据只在本机处理成行为摘要（如移动量、屏幕使用时长、应用切换次数），原始传感器数据不落盘、不上传。"
                )
                CheckLine(
                    state.coreChecks[2], { actions.onCoreCheck(2, it) },
                    "ECHO 会用你过去几天的数据学习「通常的你」，形成个人基线。"
                )
                CheckLine(
                    state.coreChecks[3], { actions.onCoreCheck(3, it) },
                    "每天生成「今天的你 vs 通常的你」的画像描述，只做行为观察，不做心理诊断。"
                )
                CheckLine(
                    state.coreChecks[4], { actions.onCoreCheck(4, it) },
                    "你可以随时撤回同意、申请导出或删除数据；撤回后 ECHO 停止学习。"
                )
                if (!allCoreChecked) {
                    // §52：CTA disabled + 中性提示；不要 red blame message
                    Text(
                        "完成以上同意后即可继续；不授权则无法生成每日画像。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    )
                }
                Button(
                    onClick = actions.onContinueToCoreSensing,
                    enabled = allCoreChecked,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("我理解并继续") }
                OnboardingEmergencyEntry(onOpenSafety = actions.onOpenSafety)
            }

            OnboardingStep.CORE_SENSING -> {
                // §53：Seed ECHO 约 210–240dp
                state.seedPresence?.let { seed ->
                    Box(
                        Modifier.fillMaxWidth().height(224.dp).testTag("onboarding_visual"),
                    ) {
                        com.yunjue.echo.mind.presencevisual.EchoOrganism(
                            genome = rememberSeedGenome(seed),
                            modifier = Modifier.fillMaxSize(),
                            maturityName = seed.maturity.name,
                        )
                    }
                }
                Text("让 ECHO 开始了解你", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ECHO 只需要最少的权限就能开始工作。以下核心能力已就绪；" +
                        "更多信息（应用使用情况、通知使用权、麦克风）可以在之后逐步开启。",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                // ERA 1：核心能力行以系统真实状态为唯一事实来源（硬件可用性，无乐观置位）。
                SensingCapabilityRow(
                    name = "运动传感器（加速度 / 陀螺仪）",
                    description = "用于了解移动与作息节奏。这是 ECHO 的核心，无需系统权限。",
                    statusText = if (state.sensorHardwareAvailable) "可用（无需权限）" else "此设备不可用"
                )
                SensingCapabilityRow(
                    name = "屏幕状态",
                    description = "用于了解一天中的屏幕使用分布。无需额外权限。",
                    statusText = "可用（无需权限）"
                )
                SensingCapabilityRow(
                    name = "持续运行通知",
                    description = "后台了解期间显示常驻通知，让你随时看到 ECHO 正在工作（Android 13+ 需授权）。拒绝后了解仍会继续，但通知不可见。",
                    statusText = if (state.notifPermAuthorized) "已开启" else "未开启（可跳过）",
                    onAuthorize = actions.onRequestNotifPermission,
                    onSkip = actions.onSkipNotifPermission
                )
                HorizontalDivider()
                Text(
                    "ECHO 会安静地在后台了解你的日常节奏，不会打扰你。",
                    style = MaterialTheme.typography.bodySmall
                )
                // ERA 1：主 CTA = 苏醒；不再有 DONE /「进入应用」。
                Button(
                    onClick = actions.onAwaken,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("让 ECHO 开始了解我") }
                // 拒绝 = abstain（不阻断离开）：不启动感知，直接进入应用
                TextButton(
                    onClick = actions.onAbstain,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("暂不开启") }
                OnboardingEmergencyEntry(onOpenSafety = actions.onOpenSafety)
            }
        }
    }
}

/**
 * ECHO 苏醒过渡（Master Prompt PART 63）：最后核心授权成功后
 * 按钮消失 → **真实的这个 ECHO**（identitySeed 派生的生产视觉）开始缓慢呼吸 →
 * 「ECHO 已开始了解你」→ 短暂停留后自动进入主界面。
 * 没有 DONE 页、没有「进入应用」——真正准备好的不是用户，是 ECHO。
 */
@Composable
private fun AwakeningScreen(preferences: AppPreferences, onFinished: () -> Unit) {
    // V3 §54：固定 2200ms 时间线（halo/filament/ring/first-breath/headline 分段进入），
    // 使用真实 identitySeed + dayZeroSeedPresence + production renderer（EchoOrganism）。
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> elapsedMs = (now - start) / 1_000_000L }
        }
    }
    val timeline = AwakeningTimeline.at(elapsedMs)
    if (timeline.finished) {
        LaunchedEffect(Unit) { onFinished() }
    }

    // ERA 31 R22/R31 + V3 §54：苏醒瞬间必须是「这个 ECHO」——Day-0 SEED presence 由真实
    // identitySeed 经 dayZeroSeedPresence 单一构建点派生；末帧与 Home 首帧同 identity。
    val seedPresence = remember {
        com.yunjue.echo.mind.presence.dayZeroSeedPresence(identitySeed = preferences.identitySeed)
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF040814)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.size(280.dp).testTag("onboarding_visual")) {
                com.yunjue.echo.mind.presencevisual.EchoOrganism(
                    genome = rememberSeedGenome(seedPresence),
                    modifier = Modifier.fillMaxSize(),
                    maturityName = seedPresence.maturity.name,
                    options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                        maturityName = "SEED",
                        haloScale = timeline.haloScale,
                        detailScale = timeline.detailScale,
                        ringAlphaScale = timeline.ringAlphaScale,
                        breathScaleOverride = timeline.breathScale,
                    ),
                )
            }
            Text(
                "ECHO 已开始了解你",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFE8ECF5).copy(alpha = timeline.headlineAlpha),
            )
            Text(
                "今天是我们认识的第一天。",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB9C0D4).copy(alpha = timeline.headlineAlpha),
            )
        }
    }
}

/** ERA 31 §50：苏醒过渡时长（Time-to-ECHO 机器段预算；>3s 视为回归）。 */
internal const val AWAKENING_DURATION_MS = 2200L

/** Onboarding 各步骤底部常驻紧急入口（任何步骤可访问）。 */
@Composable
private fun ColumnScope.OnboardingEmergencyEntry(
    onOpenSafety: () -> Unit,
    prominent: Boolean = false,
    copy: String = "紧急支持"
) {
    HorizontalDivider()
    if (prominent) {
        Button(onClick = onOpenSafety, modifier = Modifier.fillMaxWidth()) { Text(copy) }
    } else {
        TextButton(
            onClick = onOpenSafety,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text(copy) }
    }
}

/** 能力行：能力名 + 说明 + 真实状态 + 可选授权/跳过。 */
@Composable
private fun SensingCapabilityRow(
    name: String,
    description: String,
    statusText: String,
    onAuthorize: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null
) {
    // §53：quiet row（small status point；optional 未授权 = neutral，无红色失败语义）
    val ready = statusText.contains("可用") || statusText.contains("已开启")
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (ready) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(name, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
        if (onAuthorize != null || onSkip != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onAuthorize?.let {
                    Button(onClick = it) { Text("授权") }
                }
                onSkip?.let {
                    OutlinedButton(onClick = it) { Text("跳过") }
                }
            }
        }
        HorizontalDivider()
    }
}

/** 定位核心句（ERA 1 文案；单测锚点：必须含「不会判断情绪/不做心理诊断」契约句）。 */
internal const val ONBOARDING_WELCOME_CORE_COPY =
    "ECHO 会安静地生活在你的手机里，慢慢认识属于你的生活节律。它会告诉你今天和平常的自己有什么不同。它不会判断你的情绪，也不会做心理诊断。"

/** 紧急入口常驻文案（单测锚点）。 */
internal const val EMERGENCY_HINT_COPY = "存在立即危险时，请直接联系身边可信任的人、110 或 120。"

@Composable
private fun CheckLine(checked: Boolean, onChecked: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onChecked)
        Text(label, modifier = Modifier.weight(1f))
    }
}

/** List<Boolean> 便捷更新（核心同意勾选按索引更新）。 */
private fun List<Boolean>.withIndexed(value: Boolean, index: Int): List<Boolean> =
    mapIndexed { i, v -> if (i == index) value else v }
