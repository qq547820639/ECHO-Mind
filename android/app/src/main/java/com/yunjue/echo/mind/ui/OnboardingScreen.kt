package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.SyncWorker
import kotlinx.coroutines.launch
import java.security.MessageDigest

@Composable
fun OnboardingScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var ageConfirmed by remember { mutableStateOf(false) }
    var boundaryConfirmed by remember { mutableStateOf(false) }
    var consentConfirmed by remember { mutableStateOf(false) }
    var currentDanger by remember { mutableStateOf(false) }
    var priorAttempt by remember { mutableStateOf(false) }
    var psychosisOrMania by remember { mutableStateOf(false) }
    var substanceImpairment by remember { mutableStateOf(false) }
    var hasProfessionalSupport by remember { mutableStateOf(false) }
    var institutionCode by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("u_demo") }
    var accessToken by remember { mutableStateOf("") }
    var emergencyName by remember { mutableStateOf("") }
    var emergencyPhone by remember { mutableStateOf("") }
    var emergencyConsent by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }

    if (showSafety) {
        SafetyScreen(
            deliveryState = "尚未确认送达，请优先使用电话入口。",
            onBack = { showSafety = false }
        )
        return
    }

    Page("开始使用") {
        Text("ECHO Mind 是心理健康记录、筛查提示和审核练习工具。它不是医生、不是诊断服务，也不是紧急服务。")
        CheckLine(ageConfirmed, { ageConfirmed = it }, "我已年满 18 周岁")
        CheckLine(boundaryConfirmed, { boundaryConfirmed = it }, "我理解专业判断和危机处置由人工承担")
        OutlinedTextField(institutionCode, { institutionCode = it }, label = { Text("机构邀请码") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(userId, { userId = it }, label = { Text("试点用户 ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(accessToken, { accessToken = it }, label = { Text("机构配置令牌（试点环境）") }, modifier = Modifier.fillMaxWidth())
        CheckLine(consentConfirmed, { consentConfirmed = it }, "我已阅读并单独同意处理心理记录与量表信息")

        HorizontalDivider()
        Text("L0 准入确认", style = MaterialTheme.typography.titleMedium)
        CheckLine(currentDanger, { currentDanger = it }, "我当前存在立即伤害自己或他人的危险")
        CheckLine(priorAttempt, { priorAttempt = it }, "我有既往高风险事件或相关住院经历")
        CheckLine(psychosisOrMania, { psychosisOrMania = it }, "我当前有明显现实检验受损、幻觉妄想或躁狂表现")
        CheckLine(substanceImpairment, { substanceImpairment = it }, "我当前受酒精或其他物质明显影响")
        CheckLine(hasProfessionalSupport, { hasProfessionalSupport = it }, "我目前已有专业人员支持")

        HorizontalDivider()
        Text("紧急联系人（建议填写）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(emergencyName, { emergencyName = it }, label = { Text("姓名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(emergencyPhone, { emergencyPhone = it }, label = { Text("电话") }, modifier = Modifier.fillMaxWidth())
        CheckLine(emergencyConsent, { emergencyConsent = it }, "我单独同意在危机人工接管范围内处理该联系人信息")

        if (currentDanger || psychosisOrMania || substanceImpairment) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("常规 AI 服务当前不适用。请优先联系人工或紧急服务。")
                    Button(onClick = { showSafety = true }) { Text("打开安全支持") }
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    container.preferences.institutionCode = institutionCode
                    container.preferences.userId = userId
                    container.preferences.accessToken = accessToken.takeIf { it.isNotBlank() }
                    val evidence = MessageDigest.getInstance("SHA-256")
                        .digest("path-a-consent-2026.07:$userId:$consentConfirmed".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    container.repository.saveConsent(true, evidence)
                    container.repository.saveL0(currentDanger, priorAttempt, psychosisOrMania, substanceImpairment, hasProfessionalSupport)
                    if (emergencyName.isNotBlank() && emergencyPhone.isNotBlank() && emergencyConsent) {
                        val contactEvidence = MessageDigest.getInstance("SHA-256")
                            .digest("emergency-contact-consent-2026.07:$userId:true".toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        container.repository.saveConsent(
                            granted = true,
                            evidenceHash = contactEvidence,
                            consentType = "emergency_contact",
                            version = "emergency-contact-consent-2026.07",
                            priority = 700
                        )
                        container.repository.saveEmergencyContact(emergencyName, emergencyPhone, "用户指定联系人")
                    }
                    container.preferences.onboardingCompleted = true
                    SyncWorker.enqueue(context)
                    onComplete()
                }
            },
            enabled = ageConfirmed && boundaryConfirmed && consentConfirmed && institutionCode.isNotBlank() && userId.isNotBlank() &&
                !currentDanger && !psychosisOrMania && !substanceImpairment &&
                ((emergencyName.isBlank() && emergencyPhone.isBlank()) || (emergencyName.isNotBlank() && emergencyPhone.isNotBlank() && emergencyConsent)),
            modifier = Modifier.fillMaxWidth()
        ) { Text("进入应用") }
        Text("存在立即危险时，请直接联系身边可信任的人、机构值班人员、110 或 120。")
    }
}

@Composable
private fun CheckLine(checked: Boolean, onChecked: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onChecked)
        Text(label, modifier = Modifier.weight(1f))
    }
}
