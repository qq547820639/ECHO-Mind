package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.OnboardingVerifyException
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.sensing.PassiveSensingService
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * T12.6 L0 准入门禁：currentDanger / psychosisOrMania / substanceImpairment 任一为真即阻断进入。
 *
 * 抽成纯函数便于单测断言「阻断逻辑不变」；与 OnboardingScreen 按钮 enabled 条件共享同一判定。
 */
internal fun l0OnboardingBlocked(
    currentDanger: Boolean,
    psychosisOrMania: Boolean,
    substanceImpairment: Boolean
): Boolean = currentDanger || psychosisOrMania || substanceImpairment

/** Onboarding 引导步骤（多步渐进披露，PM 规格 8 步收敛为 5 步向导 + 完成）。 */
private enum class OnboardingStep { WELCOME, CONSENTS, L0, EMERGENCY, DONE }

/**
 * Onboarding 产品化（T02 / docs/15）：
 *
 * - 移除 u_demo / 内部 user_id / access token / 机构配置令牌 输入框；
 * - 真实用户只接触机构激活码 → POST /v1/onboarding/verify-code（预认证）
 *   → 服务端返回 user_id + 短时 access_token（FieldCipher 加密存储）；
 * - 七态状态机持久化到 [AppPreferences.onboardingState]：
 *   NOT_STARTED → ACTIVATING → BOUND → CONSENT_PENDING → READY_OFFLINE/READY
 *   （ACTIVATION_FAILED 失败态；restricted 由 verify-code 403 决定 → 安全支持页）；
 * - 保留：18+ / 紧急入口 / 分项 consent / 麦克风独立 consent / 可撤回。
 *
 * 文案守住「非诊断、非医疗、非紧急服务」边界。
 */
@Composable
fun OnboardingScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preferences = container.preferences

    var ageConfirmed by remember { mutableStateOf(false) }
    var boundaryConfirmed by remember { mutableStateOf(false) }
    var activationCode by remember { mutableStateOf("") }
    var activating by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }

    var psychologicalConsent by remember { mutableStateOf(false) }
    var passiveSensingConsent by remember { mutableStateOf(false) }
    var micConsent by remember { mutableStateOf(false) }

    var currentDanger by remember { mutableStateOf(false) }
    var priorAttempt by remember { mutableStateOf(false) }
    var psychosisOrMania by remember { mutableStateOf(false) }
    var substanceImpairment by remember { mutableStateOf(false) }
    var hasProfessionalSupport by remember { mutableStateOf(false) }

    var emergencyName by remember { mutableStateOf("") }
    var emergencyPhone by remember { mutableStateOf("") }
    var emergencyConsent by remember { mutableStateOf(false) }

    var showSafety by remember { mutableStateOf(false) }
    var step by remember {
        mutableStateOf(
            when (preferences.onboardingState) {
                AppPreferences.ONBOARDING_BOUND, AppPreferences.ONBOARDING_CONSENT_PENDING -> OnboardingStep.CONSENTS
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

    fun evidence(prefix: String, userId: String, granted: Boolean): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$prefix:$userId:$granted".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun verifyCode() {
        val code = activationCode.trim()
        if (code.length < 8) {
            activationError = "激活码格式不正确，请检查后重试。"
            return
        }
        activating = true
        activationError = null
        preferences.onboardingState = AppPreferences.ONBOARDING_ACTIVATING
        scope.launch {
            try {
                val res = container.repository.verifyOnboardingCode(code)
                activating = false
                activationError = null
                // verify-code 已把 userId + 加密 access_token 安全存储，并推进 BOUND
                if (res.restricted) {
                    showSafety = true
                } else {
                    step = OnboardingStep.CONSENTS
                }
            } catch (e: Exception) {
                activating = false
                preferences.onboardingState = AppPreferences.ONBOARDING_ACTIVATION_FAILED
                activationError = when ((e as? OnboardingVerifyException)?.reason) {
                    "invalid_code" -> "激活码无效，请联系机构获取正确的激活码。"
                    "restricted" -> "该激活码已受限，请联系机构。"
                    else -> "暂时无法验证激活信息，请检查网络后重试。"
                }
            }
        }
    }

    fun finishOnboarding() {
        scope.launch {
            val userId = preferences.userId
            // 分项 consent（核心必选 + 可选）
            container.repository.saveConsent(
                granted = psychologicalConsent,
                evidenceHash = evidence("path-a-consent-2026.07", userId, psychologicalConsent)
            )
            container.repository.saveL0(currentDanger, priorAttempt, psychosisOrMania, substanceImpairment, hasProfessionalSupport)
            if (passiveSensingConsent) {
                container.repository.saveConsent(
                    granted = true,
                    evidenceHash = evidence("passive-sensing-consent-2026.07", userId, true),
                    consentType = "passive_sensing",
                    version = "passive-sensing-consent-2026.07",
                    priority = 600
                )
                container.preferences.setPassiveSensingEnabled(true)
            }
            if (micConsent) {
                try {
                    container.repository.saveVoiceFeaturesConsent(true)
                } catch (_: Exception) {
                    // voice_features consent 上传失败不阻断 Onboarding（outbox 已尽力；可后续重试）
                }
                container.preferences.setMicEnabled(true)
            }
            if (emergencyName.isNotBlank() && emergencyPhone.isNotBlank() && emergencyConsent) {
                container.repository.saveConsent(
                    granted = true,
                    evidenceHash = evidence("emergency-contact-consent-2026.07", userId, true),
                    consentType = "emergency_contact",
                    version = "emergency-contact-consent-2026.07",
                    priority = 700
                )
                container.repository.saveEmergencyContact(emergencyName, emergencyPhone, "用户指定联系人")
            }
            preferences.onboardingState = AppPreferences.ONBOARDING_CONSENT_PENDING
            // 本地全部步骤完成 → READY_OFFLINE（服务端确认待网络恢复；serverActivated 由同步收敛）
            preferences.serverActivated = false
            preferences.onboardingState = AppPreferences.ONBOARDING_READY_OFFLINE
            // 02b 共享知识 1：consent granted → flag（拉取租户配置，失败 fail-closed）→ 真实启动服务
            if (passiveSensingConsent) {
                try {
                    container.repository.fetchFeatureFlags()
                } catch (_: Exception) {
                    // flag 拉取失败 fail-closed：服务启动三重门控内 flag=false 不启动
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
                Text("ECHO Mind 是心理健康记录、筛查提示和审核练习工具。它不是医生、不是诊断服务，也不是紧急服务。")
                CheckLine(ageConfirmed, { ageConfirmed = it }, "我已年满 18 周岁")
                CheckLine(boundaryConfirmed, { boundaryConfirmed = it }, "我理解专业判断和危机处置由人工承担")
                HorizontalDivider()
                Text("机构绑定", style = MaterialTheme.typography.titleMedium)
                Text("请输入机构提供的激活码（由你的机构发放）。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    activationCode,
                    { activationCode = it },
                    label = { Text("激活码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                activationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { verifyCode() },
                    enabled = ageConfirmed && boundaryConfirmed && activationCode.isNotBlank() && !activating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (activating) "正在验证机构激活信息…" else "验证并继续")
                }
                Text("存在立即危险时，请直接联系身边可信任的人、机构值班人员、110 或 120。")
            }

            OnboardingStep.CONSENTS -> {
                Text("产品边界", style = MaterialTheme.typography.titleMedium)
                Text("这是一个支持性工具：它提供记录、趋势回顾与能力练习，不做诊断、不替代专业医疗、不是紧急服务。")
                HorizontalDivider()
                Text("分项同意", style = MaterialTheme.typography.titleMedium)
                CheckLine(psychologicalConsent, { psychologicalConsent = it }, "我同意处理心理记录与量表信息（核心必选）")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = passiveSensingConsent, onCheckedChange = { passiveSensingConsent = it })
                    Column(Modifier.weight(1f)) {
                        Text("授权被动采集传感器 / 屏幕 / 通知 / App 活跃数据（可选）")
                        Text("原始数据仅在本机内存中处理，不上云不落盘。可随时关闭。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = micConsent, onCheckedChange = { micConsent = it })
                    Column(Modifier.weight(1f)) {
                        Text("授权麦克风派生特征（可选，默认关闭）")
                        Text("麦克风数据仅在本地处理，不上传录音。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = { step = OnboardingStep.L0 },
                    enabled = psychologicalConsent,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("下一步") }
            }

            OnboardingStep.L0 -> {
                Text("L0 准入确认", style = MaterialTheme.typography.titleMedium)
                Text("这不是诊断，只是判断当前是否适合使用本工具。", style = MaterialTheme.typography.bodySmall)
                CheckLine(currentDanger, { currentDanger = it }, "我当前存在立即伤害自己或他人的危险")
                CheckLine(priorAttempt, { priorAttempt = it }, "我有既往高风险事件或相关住院经历")
                CheckLine(psychosisOrMania, { psychosisOrMania = it }, "我当前有明显现实检验受损、幻觉妄想或躁狂表现")
                CheckLine(substanceImpairment, { substanceImpairment = it }, "我当前受酒精或其他物质明显影响")
                CheckLine(hasProfessionalSupport, { hasProfessionalSupport = it }, "我目前已有专业人员支持")

                if (l0OnboardingBlocked(currentDanger, psychosisOrMania, substanceImpairment)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("常规 AI 服务当前不适用。请优先联系人工或紧急服务。")
                            Button(onClick = { showSafety = true }) { Text("打开安全支持") }
                        }
                    }
                }
                Button(
                    onClick = { step = OnboardingStep.EMERGENCY },
                    enabled = !l0OnboardingBlocked(currentDanger, psychosisOrMania, substanceImpairment),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("下一步") }
            }

            OnboardingStep.EMERGENCY -> {
                Text("紧急联系人（建议填写，可跳过）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(emergencyName, { emergencyName = it }, label = { Text("姓名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(emergencyPhone, { emergencyPhone = it }, label = { Text("电话") }, modifier = Modifier.fillMaxWidth())
                CheckLine(emergencyConsent, { emergencyConsent = it }, "我单独同意在危机人工接管范围内处理该联系人信息")
                Button(
                    onClick = { step = OnboardingStep.DONE },
                    enabled = (emergencyName.isBlank() && emergencyPhone.isBlank()) ||
                        (emergencyName.isNotBlank() && emergencyPhone.isNotBlank() && emergencyConsent),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("完成设置") }
                OutlinedButton(
                    onClick = { step = OnboardingStep.DONE },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("跳过（不填）") }
            }

            OnboardingStep.DONE -> {
                Text("准备完成", style = MaterialTheme.typography.titleMedium)
                Text("已授予：${if (psychologicalConsent) "心理数据、量表信息" else ""}" +
                    "${if (passiveSensingConsent) "、被动感知" else ""}${if (micConsent) "、麦克风派生特征" else ""}。")
                Text("部分确认将在网络恢复后自动完成，你的数据仍安全保存在本机。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { finishOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("进入应用") }
                Text("存在立即危险时，请优先拨打 12356 / 110 / 120 或联系机构值班人员。")
            }
        }
    }
}

@Composable
private fun CheckLine(checked: Boolean, onChecked: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onChecked)
        Text(label, modifier = Modifier.weight(1f))
    }
}
