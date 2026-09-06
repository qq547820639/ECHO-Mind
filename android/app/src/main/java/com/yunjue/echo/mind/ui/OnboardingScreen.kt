package com.yunjue.echo.mind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.ui.echo.components.EchoGradientButton
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.SyncWorker
import com.yunjue.echo.mind.PassiveSensingService
import com.yunjue.echo.mind.ui.artwork.drawPrivacyLineIcon
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

/**
 * V3 §H：Onboarding 的 Seed ECHO genome（唯一语义链：EchoVisualMapper → VisualGenomeCompiler）。
 * UX-B2：reduceMotion 与 EchoVisualSurface 同源（AppPreferences.presenceReduceMotion）——
 * 开启时走 REDUCED 语义（mapper flowSpeed 归零 + options.reducedMotion）。
 */
internal fun seedGenomeOf(
    seed: com.yunjue.echo.mind.model.EchoPresenceState,
    hourOfDay: Float,
    reduceMotion: Boolean,
): com.yunjue.echo.mind.visual.model.EchoVisualGenome =
    com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
        com.yunjue.echo.mind.presence.EchoVisualMapper.map(seed, hourOfDay, reduceMotion = reduceMotion),
        seed.identityGenome,
    )

@Composable
private fun rememberSeedGenome(
    seed: com.yunjue.echo.mind.model.EchoPresenceState,
    reduceMotion: Boolean,
): com.yunjue.echo.mind.visual.model.EchoVisualGenome = remember(seed, reduceMotion) {
    val hourOfDay = java.time.LocalTime.now().let { it.hour + it.minute / 60f }
    seedGenomeOf(seed, hourOfDay, reduceMotion)
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

    // 设计稿 1/2/3 流程（唯一真相）：WELCOME（零门禁）→ PRIVACY_PLEDGE（四承诺卡 +
    // 页脚披露行 + CTA 即整包同意）→ 苏醒。历史实现自创的「5 勾选 + 感知能力页」已按
    // 设计稿移除；18+ 与「专业判断由人工承担」两个合规门保留为隐私页页脚一行确认
    // （随 CTA 一并记录，不设勾选框）。撤回能力不變（Me / 数据与感知）。
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
            deliveryState = SAFETY_DELIVERY_UNCONFIRMED,
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
            // 设计稿 2 流程：CTA 即整包同意（隐私页四卡 + 页脚已完整披露）。
            // 同意证据 = CTA 时刻的 granted 记录；撤回入口保留在「数据与感知」。
            if (sensingOn) {
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
            // V3 §51/§53：Seed ECHO 视觉（与 Awakening/Home 同一 identitySeed）
            seedPresence = remember { com.yunjue.echo.mind.presence.dayZeroSeedPresence(preferences.identitySeed) },
            // UX-B2：Onboarding 视觉尊重「减少动画」（与 EchoVisualSurface 同源偏好）
            reduceMotion = preferences.presenceReduceMotion,
        ),
        actions = OnboardingStepActions(
            onBackToWelcome = {
                preferences.onboardingState = ""
                step = OnboardingStep.WELCOME
            },
            onContinueToPrivacy = {
                // ERA 32 R26：进入隐私承诺页即持久化进度（进程死亡后从这里恢复，
                // 不再回到 WELCOME 重来）
                preferences.onboardingState = AppPreferences.ONBOARDING_CONSENT_PENDING
                step = OnboardingStep.PRIVACY_PLEDGE
            },
            onAwaken = {
                // 苏醒瞬间锚点（Day-0 SEED 的「已观察 N 分钟」起点）；
                // 隐私页 CTA = 整包同意（页脚 18+ / 人工边界随 CTA 一并记录）。
                preferences.awakenedAtEpochMs = System.currentTimeMillis()
                awakening = true
            },
            onOpenSafety = { showSafety = true },
            // 设计稿 1/2：已有账号登录入口（可选；未登录时全功能本地可用）
            onOpenLogin = { /* 登录为可选：保留锚点；未登录时全功能本地可用 */ },
        ),
    )
}

/** 设计稿 1/2/3 — Onboarding 两步（欢迎 → 隐私承诺）；苏醒为过渡而非步骤。 */
enum class OnboardingStep { WELCOME, PRIVACY_PLEDGE }

/** ERA 38 — Onboarding 步骤纯状态（渲染输入；编排留在 OnboardingScreen）。 */
data class OnboardingStepState(
    val step: OnboardingStep,
    /** V3 §51/§53：Seed ECHO（真实 identitySeed 派生；null = 不渲染视觉，测试友好）。 */
    val seedPresence: com.yunjue.echo.mind.model.EchoPresenceState? = null,
    /** UX-B2：减少动画（与 EchoVisualSurface 同源偏好；开启时 seed genome 走 REDUCED 语义）。 */
    val reduceMotion: Boolean = false,
)

/** ERA 38 — Onboarding 步骤回调（state-in / event-out）。 */
data class OnboardingStepActions(
    val onBackToWelcome: () -> Unit = {},
    val onContinueToPrivacy: () -> Unit = {},
    /** 隐私页 CTA（设计稿 2）：= 整包同意 + 苏醒。 */
    val onAwaken: () -> Unit = {},
    val onOpenSafety: () -> Unit = {},
    /** 设计稿 1/2：已有账号登录入口（可选；未登录时全功能本地可用）。 */
    val onOpenLogin: () -> Unit = {},
)

/**
 * 设计稿 1/2 — Onboarding 纯步骤内容（state-in / event-out）。
 * WELCOME：字标 + 大标题 + 副句 + 生命体 + CTA + 登录（零勾选）；
 * PRIVACY_PLEDGE：返回 + 标题「你的数据，只属于你」+ 四承诺卡（可展开细则）+
 * 页脚披露（含 18+ / 人工边界一行）+ CTA（= 整包同意 + 苏醒）。
 */
@Composable
fun OnboardingStepContent(state: OnboardingStepState, actions: OnboardingStepActions) {
    // V3 §50–§53：quiet 全屏场景（无 legacy Page wrapper / 无大 Card）
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        when (state.step) {
            OnboardingStep.WELCOME -> {
                Spacer(Modifier.height(28.dp))
                // 设计稿 1：字标
                Wordmark()
                Spacer(Modifier.height(48.dp))
                // 设计稿 1：大标题两行
                Text(
                    "每个人都值得，",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "被充分理解。",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "你的个人 AI 伙伴，常驻设备，理解你的日常节律，在恰当的时机，给你恰到好处的支持。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(16.dp))
                // Seed ECHO 主视觉 + 星空 + 涟漪（设计稿 1）
                state.seedPresence?.let { seed ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .testTag("onboarding_visual"),
                    ) {
                        com.yunjue.echo.mind.ui.artwork.StarfieldCanvas(
                            modifier = Modifier.fillMaxSize(),
                            maxYFraction = 0.9f,
                        )
                        com.yunjue.echo.mind.ui.artwork.RippleRings(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .align(androidx.compose.ui.Alignment.BottomCenter),
                        )
                        com.yunjue.echo.mind.presencevisual.EchoOrganism(
                            genome = rememberSeedGenome(seed, state.reduceMotion),
                            modifier = Modifier.fillMaxSize(),
                            maturityName = seed.maturity.name,
                            options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                                maturityName = seed.maturity.name,
                                reducedMotion = state.reduceMotion,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                // ERA 1 定位句（契约锚点保留：「不会判断情绪/不做心理诊断」，单测锁定）。
                Text(
                    ONBOARDING_WELCOME_CORE_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(16.dp))
                EchoGradientButton(
                    onClick = actions.onContinueToPrivacy,
                    text = "开启 ECHO",
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = "开启 ECHO",
                )
                // 设计稿 1：登录入口（居中）
                TextButton(
                    onClick = actions.onOpenLogin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = "已有账号？登录 ›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    )
                }
                OnboardingEmergencyEntry(onOpenSafety = actions.onOpenSafety, copy = EMERGENCY_HINT_COPY)
            }

            OnboardingStep.PRIVACY_PLEDGE -> {
                Spacer(Modifier.height(20.dp))
                // 设计稿 2：返回箭头
                IconButton(onClick = actions.onBackToWelcome, modifier = Modifier.size(40.dp)) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(4.dp))
                Wordmark()
                Spacer(Modifier.height(20.dp))
                Text(
                    "你的数据，只属于你",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "ECHO 默认在设备内理解你",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(20.dp))
                // 设计稿 2：四承诺卡（细线图标 + 标题 + 描述 + chevron；点按展开细则，无死链）
                PrivacyPledgeCard(
                    icon = com.yunjue.echo.mind.ui.artwork.PrivacyLineIconType.HOME,
                    tint = Color(0xFF34D399),
                    title = "本地优先",
                    description = "数据主要在你的设备本地处理",
                    detail = "原始传感器数据只在本机处理成行为摘要（如移动量、屏幕使用时长、应用切换次数），不保存、不上传。",
                    tag = "privacy_card_local",
                )
                PrivacyPledgeCard(
                    icon = com.yunjue.echo.mind.ui.artwork.PrivacyLineIconType.LOCK,
                    tint = Color(0xFF38BDF8),
                    title = "最小化记录",
                    description = "只保留必要的派生特征，不保存原始流",
                    detail = "画像只依据派生特征（节律/分布/次数）生成；原始传感器流不落盘。",
                    tag = "privacy_card_minimal",
                )
                PrivacyPledgeCard(
                    icon = com.yunjue.echo.mind.ui.artwork.PrivacyLineIconType.PERSON,
                    tint = Color(0xFF818CF8),
                    title = "你完全掌控",
                    description = "可随时暂停、导出、删除",
                    detail = "你随时可以暂停、撤回同意、申请导出或删除数据；撤回后 ECHO 停止学习。",
                    tag = "privacy_card_control",
                )
                PrivacyPledgeCard(
                    icon = com.yunjue.echo.mind.ui.artwork.PrivacyLineIconType.HISTORY,
                    tint = Color(0xFFA855F7),
                    title = "随时可撤回",
                    description = "权限可以稍后再开",
                    detail = "通知等权限可以稍后再开；全部授权入口都在「Me / 数据与感知」。",
                    tag = "privacy_card_revoke",
                )
                Spacer(Modifier.height(24.dp))
                // 设计稿 2：页脚披露行（CTA 即同意）
                Text(
                    "继续即表示你同意《隐私政策》与《用户协议》",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                // 合规保留：18+ 与「专业判断/危机处置由人工承担」随 CTA 一并确认（无勾选框）
                Text(
                    "继续即确认你已年满 18 周岁，并理解专业判断和危机处置由人工承担。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                EchoGradientButton(
                    onClick = actions.onAwaken,
                    text = "我理解了，继续",
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = "我理解了，继续",
                )
                OnboardingEmergencyEntry(onOpenSafety = actions.onOpenSafety)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 设计稿 1/2：ECHO Mind 字标（ECHO 加粗字距 + Mind 细体）。 */
@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "ECHO",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
        )
        Text(
            " Mind",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Light,
        )
    }
}

/**
 * 设计稿 2 — 隐私承诺卡：圆形细线图标徽章 + 标题 + 描述 + chevron；
 * 点按展开实施细则（真实数据处理披露，不设死链）。
 */
@Composable
private fun PrivacyPledgeCard(
    icon: com.yunjue.echo.mind.ui.artwork.PrivacyLineIconType,
    tint: Color,
    title: String,
    description: String,
    detail: String,
    tag: String,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1426))
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
                    drawPrivacyLineIcon(type = icon, color = tint)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (expanded) "⌃" else "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
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
    // §N：苏醒时间线的会话起点取自 boot-global EchoVisualClock（ticker 只请求帧）；
    // 时间线语义 = 进入苏醒后经过的毫秒（2200ms 固定脚本），非 organism 视觉相位。
    val awakeningStartNanos = remember { com.yunjue.echo.mind.presencevisual.EchoVisualClock.nowNanos() }
    LaunchedEffect(Unit) {
        // §AX：有界帧循环——elapsed 到达 2200ms 即退出（不再无限自旋占帧）；
        // 条件为 elapsed < TOTAL：最后一帧仍经 withFrameNanos 更新交付，timeline.finished 随后触发。
        while (elapsedMs < AWAKENING_DURATION_MS) {
            withFrameNanos {
                elapsedMs = (com.yunjue.echo.mind.presencevisual.EchoVisualClock.nowNanos() - awakeningStartNanos) /
                    1_000_000L
            }
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
    // UX-B2：苏醒过渡同样尊重「减少动画」（同源偏好，REDUCED 语义）
    val reduceMotion = preferences.presenceReduceMotion

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
                    genome = rememberSeedGenome(seedPresence, reduceMotion),
                    modifier = Modifier.fillMaxSize(),
                    maturityName = seedPresence.maturity.name,
                    options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                        maturityName = "SEED",
                        reducedMotion = reduceMotion,
                        haloScale = timeline.haloScale,
                        detailScale = timeline.detailScale,
                        ringAlphaScale = timeline.ringAlphaScale,
                        breathScaleOverride = timeline.breathScale,
                    ),
                )
            }
            Text(
                "ECHO 正在苏醒",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFE8ECF5).copy(alpha = timeline.headlineAlpha),
            )
            // V3 §54 / 设计稿图3：图形化进度条 + 百分比 + 阶段文案 + 前台提示（真实时间驱动，非假数值）
            val progressPct = (elapsedMs.toFloat() / AWAKENING_DURATION_MS.toFloat() * 100f).toInt().coerceIn(0, 100)
            Box(
                Modifier
                    .fillMaxWidth(0.66f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF232C44)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progressPct / 100f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFF38BDF8)))
                        ),
                )
            }
            Text(
                "$progressPct%  ·  正在生成你的第一份数字生命",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB9C0D4).copy(alpha = timeline.headlineAlpha),
            )
            // 阶段文案（真实阶段描述，不编造）
            Text(
                "正在为你生成专属的生命节律…",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E8EA8).copy(alpha = timeline.headlineAlpha),
            )
            // 前台运行提示（设计稿图3 底部提示）
            Text(
                "请保持应用在前台运行",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6E6E8C).copy(alpha = timeline.headlineAlpha),
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

/** 定位核心句（ERA 1 文案；单测锚点：必须含「不会判断情绪/不做心理诊断」契约句）。 */
internal const val ONBOARDING_WELCOME_CORE_COPY =
    "ECHO 会安静地生活在你的手机里，慢慢认识属于你的生活节律。它会告诉你今天和平常的自己有什么不同。它不会判断你的情绪，也不会做心理诊断。"

/** 紧急入口常驻文案（单测锚点）。 */
internal const val EMERGENCY_HINT_COPY = "存在立即危险时，请直接联系身边可信任的人、110 或 120。"
