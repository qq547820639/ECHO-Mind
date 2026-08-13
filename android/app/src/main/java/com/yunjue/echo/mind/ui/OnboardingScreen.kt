package com.yunjue.echo.mind.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.sensing.PassiveSensingService
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * T12.6 L0 准入门禁：currentDanger / psychosisOrMania / substanceImpairment 任一为真即阻断进入。
 *
 * Phase 6.1（L0 解耦）：L0 从普通 Portrait Onboarding **移除**，不再参与本流程的按钮 enabled
 * 判定；本纯函数保留，供「支持」页主动进入安全流程时复用与单测断言（机构契约不变）。
 */
internal fun l0OnboardingBlocked(
    currentDanger: Boolean,
    psychosisOrMania: Boolean,
    substanceImpairment: Boolean
): Boolean = currentDanger || psychosisOrMania || substanceImpairment

/**
 * Onboarding 引导步骤（Phase 6.1 新主流程，PM 规格 §1）：
 * `WELCOME → PORTRAIT EXPLANATION → CORE DATA CONSENT → MINIMUM SENSING → BASELINE WARMING UP → DONE`
 *
 * - **L0 / EMERGENCY 步骤已从 enum 删除**：普通流程不再包含 L0 与紧急联系人整页；
 * - WELCOME 保留激活码交换（POST /v1/onboarding/verify-code）+ 18+/边界确认 + 紧急入口常驻；
 * - CORE DATA CONSENT 围绕被动行为节律 / 派生数据 / 基线 / 画像 / 撤回，
 *   **移除**「心理记录与量表信息」与麦克风（MIC 移到 MINIMUM SENSING）；
 * - MINIMUM SENSING 渐进授权：SENSOR/SCREEN 核心 → USAGE/NOTIFICATION optional → MIC 永远可选；
 * - 拒绝 = abstain（不阻断离开）：CORE DATA CONSENT 任一未勾选仅禁用「同意并继续」；
 *   全部跳过 optional 也可继续，只是 ECHO 不启动感知（Today 显示 SENSING_DISABLED）。
 *
 * 七态状态机映射保持（AppPreferences）：
 * NOT_STARTED → ACTIVATING → BOUND → CONSENT_PENDING → READY_OFFLINE（finishOnboarding 本地完成）。
 */
@Composable
fun OnboardingScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preferences = container.preferences

    var ageConfirmed by remember { mutableStateOf(false) }
    var boundaryConfirmed by remember { mutableStateOf(false) }

    // CORE DATA CONSENT：5 项核心同意（全部勾选才可继续；拒绝 = abstain）
    var coreChecks by remember { mutableStateOf(listOf(false, false, false, false, false)) }
    val allCoreChecked = coreChecks.all { it }

    // MINIMUM SENSING：渐进授权（SENSOR 核心；USAGE/NOTIFICATION/MIC optional）
    var sensorAuthorized by remember { mutableStateOf(false) }
    var usageAuthorized by remember { mutableStateOf(false) }
    var usageSkipped by remember { mutableStateOf(false) }
    var notificationAuthorized by remember { mutableStateOf(false) }
    var notificationSkipped by remember { mutableStateOf(false) }
    var micAuthorized by remember { mutableStateOf(false) }
    var micSkipped by remember { mutableStateOf(false) }

    // MIC 二次确认对话框 + RECORD_AUDIO 运行时权限（永远可选，默认关闭）
    var showMicConfirm by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micAuthorized = granted
        if (granted) micSkipped = false
    }

    // Android 13+ 持续通知权限（POST_NOTIFICATIONS）：被动采集前台服务的常驻通知
    // 依赖该权限才可见；拒绝仅让通知不可见（采集继续），属可选降级。
    // 初始状态按当前实际授权情况回填（避免已授权用户被显示为"未开启"）。
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

    // v0.7 UX：使用情况访问 / 通知使用权是「跳系统设置页授权」，无法拿到返回回调——
    // 用 pending 标记 + ON_RESUME 真实校验替代乐观置位（返回后按实际授权状态回填）。
    var pendingUsageVerify by remember { mutableStateOf(false) }
    var pendingNotifListenerVerify by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingUsageVerify) {
                    pendingUsageVerify = false
                    usageAuthorized = PassiveSensingService.hasUsageAccess(context)
                    if (!usageAuthorized) usageSkipped = true
                }
                if (pendingNotifListenerVerify) {
                    pendingNotifListenerVerify = false
                    notificationAuthorized = PassiveSensingService.hasNotificationAccess(context)
                    if (!notificationAuthorized) notificationSkipped = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showSafety by remember { mutableStateOf(false) }
    var step by remember {
        mutableStateOf(
            when (preferences.onboardingState) {
                // 进程中断恢复：BOUND / CONSENT_PENDING 从核心同意页继续
                AppPreferences.ONBOARDING_BOUND,
                AppPreferences.ONBOARDING_CONSENT_PENDING -> OnboardingStep.CORE_DATA_CONSENT
                else -> OnboardingStep.WELCOME
            }
        )
    }

    if (showSafety) {
        SafetyScreen(
            deliveryState = "尚未确认送达，请优先使用电话入口。",
            onBack = { showSafety = false }
        )
        return
    }

    fun finishOnboarding() {
        scope.launch {
            // v0.7 本地优先架构：默认本地模式（无账号门槛）。未绑定时生成本地用户标识，
            // 特征/画像按该标识隔离存储；开通订阅后（支持页）服务端返回新 userId，
            // 本地数据留在本机、不再上传（本地同意与订阅后的云端同意各自独立）。
            if (preferences.userId.isBlank()) {
                preferences.userId = "local_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            }
            // v0.6.1（P1-7）幂等：本地已提交过（重复点击/进程死亡重启）→ 直接推进，
            // 不重复入队 consent（服务端按 event_id 幂等，双保险）。
            if (preferences.onboardingLocalSubmitted) {
                preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
                preferences.serverActivated = false
                onComplete()
                return@launch
            }
            // Phase 6.1：核心同意围绕被动行为节律（passive_sensing consent）；
            // 不再提交 psychological_data / L0 / emergency_contact（L0 与紧急联系人移出主流程）。
            val sensingTurnedOn = sensorAuthorized || usageAuthorized || notificationAuthorized || micAuthorized
            if (coreChecks.all { it }) {
                container.consentRepository.savePassiveSensingConsent(granted = true)
                if (sensingTurnedOn) {
                    container.preferences.setPassiveSensingEnabled(true)
                    // v0.6.1（P0-3 B）：本地已 ON、服务端尚未接受 granted 证据 → 等待授权同步态
                    container.preferences.consentSyncPending = true
                }
            }
            if (micAuthorized) {
                try {
                    container.consentRepository.saveVoiceFeaturesConsent(true)
                } catch (_: Exception) {
                    // voice_features consent 上传失败不阻断 Onboarding（outbox 已尽力；可后续重试）
                }
                container.preferences.setMicEnabled(true)
            }
            preferences.onboardingState = AppPreferences.ONBOARDING_CONSENT_PENDING
            // 本地全部步骤完成 → READY_OFFLINE（服务端确认待网络恢复；serverActivated 由同步收敛）
            preferences.serverActivated = false
            preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
            preferences.onboardingLocalSubmitted = true
            // 02b 共享知识 1：consent granted → flag（拉取租户配置，失败 fail-closed）→ 真实启动服务
            if (sensingTurnedOn) {
                try {
                    container.featureFlagRepository.fetchFeatureFlags()
                } catch (_: Exception) {
                    // flag 拉取失败 fail-closed：服务启动门控内 flag=false 不启动
                }
                PassiveSensingService.start(context)
            } else {
                PassiveSensingService.stop(context)
            }
            SyncWorker.enqueue(context)
            onComplete()
        }
    }

    Page("开始使用") {
        when (step) {
            OnboardingStep.WELCOME -> {
                // Phase 6.1：Portrait Core 定位文案（PM 规格 §1.3.1 核心句，必须原文）。
                // 删除 legacy：「ECHO Mind 是心理健康记录、筛查提示和审核练习工具…」
                Text(
                    ONBOARDING_WELCOME_CORE_COPY,
                    style = MaterialTheme.typography.bodyLarge
                )
                CheckLine(ageConfirmed, { ageConfirmed = it }, "我已年满 18 周岁")
                CheckLine(boundaryConfirmed, { boundaryConfirmed = it }, "我理解专业判断和危机处置由人工承担")
                HorizontalDivider()
                // v0.7 本地优先架构：无账号/激活码门槛，本地模式默认开启。
                // 订阅为可选（支持页）；数据默认只保存在本机。
                Text("本机使用", style = MaterialTheme.typography.titleMedium)
                Text(
                    "无需账号和激活码即可开始。画像由手机本机数据生成，数据默认只保存在你的设备里。如需云端同步与专业支持，可稍后在「支持」页订阅（可选）。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { step = OnboardingStep.PORTRAIT_EXPLANATION },
                    enabled = ageConfirmed && boundaryConfirmed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始")
                }
                // 紧急入口（常驻，任何步骤可访问）
                OnboardingEmergencyEntry(onOpenSafety = { showSafety = true }, copy = EMERGENCY_HINT_COPY)
            }

            OnboardingStep.PORTRAIT_EXPLANATION -> {
                Text("ECHO 是怎么工作的", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ECHO 只比较今天的你和通常的你（Me vs Me）。它不用其他人的平均水平来判断你，也不会把行为数据解读成你的心理状态。",
                    style = MaterialTheme.typography.bodyLarge
                )
                HorizontalDivider()
                Text("数据链路", style = MaterialTheme.typography.titleSmall)
                Text("被动行为节律 → 个人基线 → 每日画像", style = MaterialTheme.typography.bodyMedium)
                Text("今天的我 vs 通常的我 → 差异", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("时间线", style = MaterialTheme.typography.titleSmall)
                Text(
                    "积累 7 天后你可以看到一周的趋势，积累 28 天后可以看到更长的时间线。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("7 / 28 天时间线", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("可撤回", style = MaterialTheme.typography.titleSmall)
                Text(
                    "所有同意都可以随时撤回；撤回后 ECHO 会停止学习，已生成的画像会保留在你可管理的范围内。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("所有同意可随时撤回", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { step = OnboardingStep.CORE_DATA_CONSENT },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("继续") }
                OnboardingEmergencyEntry(onOpenSafety = { showSafety = true })
            }

            OnboardingStep.CORE_DATA_CONSENT -> {
                Text("核心数据同意", style = MaterialTheme.typography.titleMedium)
                Text(
                    "为了让 ECHO 能了解你的日常节奏，需要你同意处理以下数据：",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                // Phase 6.1：核心同意围绕被动行为节律（拒绝 = abstain，不阻断离开）
                CheckLine(
                    coreChecks[0], { coreChecks = coreChecks.withIndexed(it, 0) },
                    "授权 ECHO 在后台采集加速度 / 陀螺仪等运动传感器数据，用于了解你一天的移动与作息节奏。"
                )
                CheckLine(
                    coreChecks[1], { coreChecks = coreChecks.withIndexed(it, 1) },
                    "传感器数据只在本机处理成行为摘要（如移动量、屏幕使用时长、应用切换次数），原始传感器数据不落盘、不上传。"
                )
                CheckLine(
                    coreChecks[2], { coreChecks = coreChecks.withIndexed(it, 2) },
                    "ECHO 会用你过去几天的数据学习「通常的你」，形成个人基线。"
                )
                CheckLine(
                    coreChecks[3], { coreChecks = coreChecks.withIndexed(it, 3) },
                    "每天生成「今天的你 vs 通常的你」的画像描述，只做行为观察，不做心理诊断。"
                )
                CheckLine(
                    coreChecks[4], { coreChecks = coreChecks.withIndexed(it, 4) },
                    "你可以随时撤回同意、申请导出或删除数据；撤回后 ECHO 停止学习。"
                )
                if (!allCoreChecked) {
                    Text(
                        "如果不授权这些数据，ECHO 将无法生成你的每日画像。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = { step = OnboardingStep.MINIMUM_SENSING },
                    enabled = allCoreChecked,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("同意并继续") }
                OnboardingEmergencyEntry(onOpenSafety = { showSafety = true })
            }

            OnboardingStep.MINIMUM_SENSING -> {
                Text("最小权限", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ECHO 只需要最少的权限就能开始工作。以下权限可以逐步开启，缺失的部分只会让画像少一些细节，不会让 ECHO 停止。",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                SensingCapabilityRow(
                    name = "运动传感器（加速度 / 陀螺仪）",
                    description = "用于了解移动与作息节奏。这是 ECHO 的核心。",
                    statusText = if (sensorAuthorized) "已开启" else "未开启",
                    onAuthorize = { sensorAuthorized = true }
                )
                SensingCapabilityRow(
                    name = "屏幕状态",
                    description = "用于了解一天中的屏幕使用分布。无需额外权限。",
                    statusText = "已开启（无需权限）"
                )
                SensingCapabilityRow(
                    name = "应用使用情况",
                    description = "用于了解你切换应用的次数与最常使用的应用时长（不读取应用内容）。",
                    statusText = when {
                        usageAuthorized -> "已开启"
                        usageSkipped -> "已跳过"
                        else -> "未开启（可跳过）"
                    },
                    onAuthorize = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        usageAuthorized = true
                        usageSkipped = false
                        pendingUsageVerify = true
                    },
                    onSkip = { usageSkipped = true; usageAuthorized = false; pendingUsageVerify = false }
                )
                SensingCapabilityRow(
                    name = "通知使用权",
                    description = "只统计通知数量与类别，不读取通知内容。",
                    statusText = when {
                        notificationAuthorized -> "已开启"
                        notificationSkipped -> "已跳过"
                        else -> "未开启（可跳过）"
                    },
                    onAuthorize = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        notificationAuthorized = true
                        notificationSkipped = false
                        pendingNotifListenerVerify = true
                    },
                    onSkip = { notificationSkipped = true; notificationAuthorized = false; pendingNotifListenerVerify = false }
                )
                SensingCapabilityRow(
                    name = "麦克风",
                    description = "可选，默认关闭。开启后仅在本机提取音量 / 语速等特征，不记录、不上传录音。",
                    statusText = when {
                        micAuthorized -> "已开启"
                        micSkipped -> "已跳过"
                        else -> "未开启（可跳过）"
                    },
                    onAuthorize = { showMicConfirm = true },
                    onSkip = { micSkipped = true; micAuthorized = false }
                )
                SensingCapabilityRow(
                    name = "持续运行通知",
                    description = "后台采集期间显示常驻通知，让你随时看到 ECHO 正在工作（Android 13+ 需授权）。拒绝后采集仍会继续，但通知不可见。",
                    statusText = if (notifPermAuthorized) "已开启" else "未开启（可跳过）",
                    onAuthorize = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notifPermAuthorized = true
                        }
                    },
                    onSkip = { notifPermAuthorized = false }
                )
                HorizontalDivider()
                // 规格 §1.3.4：底部「继续」在 SENSOR 可用或用户确认跳过 optional 后可用
                // （确认跳过 = 三个 optional 能力全部显式跳过；此时即使不开启 SENSOR 也可继续，
                //   对应 abstain 语义——ECHO 不启动感知，Today 显示 SENSING_DISABLED）
                val sensingCanContinue = sensorAuthorized || (usageSkipped && notificationSkipped && micSkipped)
                Button(
                    onClick = { step = OnboardingStep.BASELINE_WARMING_UP },
                    enabled = sensingCanContinue,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("继续") }
                OnboardingEmergencyEntry(onOpenSafety = { showSafety = true })
            }

            OnboardingStep.BASELINE_WARMING_UP -> {
                Text("基线学习中", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ECHO 需要积累几天数据来学习「通常的你」。这段时间里，「今天」页面会显示学习进度；基线形成后，它就会开始比较今天与平常的你。",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text("通常需要几天时间。", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { step = OnboardingStep.DONE },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("进入应用") }
                OnboardingEmergencyEntry(onOpenSafety = { showSafety = true })
            }

            OnboardingStep.DONE -> {
                Text("准备好了", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "准备好了。ECHO 会在后台安静地了解你的日常节奏，每天在「今天」页面告诉你：今天的你，和通常的你有什么不同。",
                    style = MaterialTheme.typography.bodyLarge
                )
                HorizontalDivider()
                // 已授权摘要：只列核心（被动行为节律），不再列出「心理数据、量表信息」
                Text("已开启：被动行为节律。可随时在「支持与设置」中查看或撤回。", style = MaterialTheme.typography.bodyMedium)
                // v0.7 本地优先：默认数据只保存在本机；订阅（可选，支持页）后画像与云端同步
                Text("你的数据默认只保存在本机。如需云端同步与专业支持，可稍后在「支持」页订阅。", style = MaterialTheme.typography.bodySmall)
                // 紧急入口常驻（DONE 页用 Button，PM 规格 §1.3.6）
                OnboardingEmergencyEntry(onOpenSafety = { showSafety = true }, prominent = true)
                Button(onClick = { finishOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("进入应用") }
            }
        }
    }

    if (showMicConfirm) {
        AlertDialog(
            onDismissRequest = { showMicConfirm = false },
            title = { Text("开启麦克风采集") },
            text = {
                Text(
                    "麦克风数据仅在本地端侧处理，用于提取音频特征（音量 / 语速 / 停顿 / 基频），" +
                        "不会上传录音原始数据。你可随时在系统设置中撤回录音权限。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicConfirm = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("同意并继续") }
            },
            dismissButton = {
                TextButton(onClick = { showMicConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** Onboarding 各步骤底部常驻紧急入口（PM 规格 §1.3.1 / §1.3.6：任何步骤可访问）。 */
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

/** MINIMUM SENSING 渐进授权每能力一行：能力名 + 说明 + 状态 + 授权/跳过。 */
@Composable
private fun SensingCapabilityRow(
    name: String,
    description: String,
    statusText: String,
    onAuthorize: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
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
        }
    }
}

/** CORE DATA CONSENT 核心句（PM 规格 §1.3.1 原文，单测锚点）。 */
internal const val ONBOARDING_WELCOME_CORE_COPY =
    "ECHO 会在你授权后安静地学习你的日常生活节奏。积累几天以后，它会告诉你今天和平常的自己有什么不同。它不会判断你的情绪，也不会做心理诊断。"

/** 紧急入口常驻文案（PM 规格 §1.3.1）。 */
internal const val EMERGENCY_HINT_COPY = "存在立即危险时，请直接联系身边可信任的人、110 或 120。"

/** Onboarding 引导步骤（Phase 6.1 新主流程六步；L0/EMERGENCY 已从 enum 移除）。 */
private enum class OnboardingStep { WELCOME, PORTRAIT_EXPLANATION, CORE_DATA_CONSENT, MINIMUM_SENSING, BASELINE_WARMING_UP, DONE }

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
