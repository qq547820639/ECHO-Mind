package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.PresenceMotionLevel

/**
 * Product Quality Era（ERA 19 §3）— 七个长期用户 fixture。
 *
 * 目标：让开发者真正看到「一个用户用了半年以后产品是什么样」，
 * 而不是只测 crash。七个 profile 覆盖稳定 / 夜猫 / 不规律 / 出差 /
 * 项目冲刺 / 低数据 / 周末分化的真实人群形状。
 *
 * 所有 profile 使用同一安装日（2026-01-05，周一），便于横向比较。
 */
object QaProfiles {

    /** 所有 fixture 的安装日（周一）。 */
    const val EPOCH_DATE: String = "2026-01-05"

    /** Day 快照锚点（ERA 19 §3）。 */
    val SNAPSHOT_DAYS: List<Int> = listOf(0, 3, 7, 28, 90, 180)

    val A_STABLE = QaProfileSpec(
        id = "PROFILE_A_STABLE",
        displayName = "A · 稳定通勤者",
        description = "工作日朝九晚五、节奏高度规律、周末略晚起。ECHO 应在 Day 7-28 快速形成高置信基线。",
        identitySeed = 7710L,
        motionPreference = PresenceMotionLevel.DEFAULT,
        timezone = "Asia/Shanghai",
        wakeMinute = 530, endMinute = 1390,
        wakeNoiseMinutes = 6.0, endNoiseMinutes = 10.0,
        movementIndex = 0.9, movementNoise = 0.08,
        screenOnMinutes = 252.0, screenNoiseMinutes = 18.0,
        appSwitchCount = 260, switchNoise = 30.0,
        notificationCount = 90,
        coverage = 0.90, coverageNoise = 0.03,
        weekendWakeShiftMinutes = 25, weekendEndShiftMinutes = 30,
        irregularity = 0.05,
    )

    val B_NIGHT_OWL = QaProfileSpec(
        id = "PROFILE_B_NIGHT_OWL",
        displayName = "B · 夜猫创作者",
        description = "活跃起点 ~11:40、凌晨 02:20 才结束；且半年内节律持续缓慢后移。ECHO 应看到『later』长期漂移。",
        identitySeed = 11291L,
        motionPreference = PresenceMotionLevel.DEFAULT,
        timezone = "Asia/Shanghai",
        wakeMinute = 700, endMinute = 140,
        wakeNoiseMinutes = 20.0, endNoiseMinutes = 30.0,
        movementIndex = 0.8, movementNoise = 0.12,
        screenOnMinutes = 300.0, screenNoiseMinutes = 35.0,
        appSwitchCount = 220, switchNoise = 35.0,
        notificationCount = 70,
        coverage = 0.85, coverageNoise = 0.06,
        weekendWakeShiftMinutes = 40, weekendEndShiftMinutes = 60,
        wakeDriftPer30DaysMinutes = 5.0, endDriftPer30DaysMinutes = 6.0,
        screenDriftPer30DaysMinutes = 10.0,
        irregularity = 0.20,
    )

    val C_IRREGULAR = QaProfileSpec(
        id = "PROFILE_C_IRREGULAR",
        displayName = "C · 不规律自由职业",
        description = "活跃起点噪声 ±2.5 小时、覆盖率波动大。ECHO 应保持低置信、少判断，不硬造规律。",
        identitySeed = 17551L,
        motionPreference = PresenceMotionLevel.QUIET,
        timezone = "Asia/Shanghai",
        wakeMinute = 560, endMinute = 1380,
        wakeNoiseMinutes = 150.0, endNoiseMinutes = 180.0,
        movementIndex = 1.0, movementNoise = 0.5,
        screenOnMinutes = 240.0, screenNoiseMinutes = 90.0,
        appSwitchCount = 240, switchNoise = 120.0,
        notificationCount = 110,
        coverage = 0.50, coverageNoise = 0.22,
        weekendWakeShiftMinutes = 60, weekendEndShiftMinutes = 60,
        irregularity = 0.70,
    )

    val D_TRAVEL = QaProfileSpec(
        id = "PROFILE_D_TRAVEL",
        displayName = "D · 高频出差",
        description = "平时与 A 一样稳定；三段出差窗口（Day 20-27 / 130-140 / 165-172）节奏明显前移。ECHO 应把出差视为上下文而非异常。",
        identitySeed = 24123L,
        motionPreference = PresenceMotionLevel.DEFAULT,
        timezone = "Asia/Shanghai",
        wakeMinute = 535, endMinute = 1395,
        wakeNoiseMinutes = 7.0, endNoiseMinutes = 12.0,
        movementIndex = 0.9, movementNoise = 0.09,
        screenOnMinutes = 248.0, screenNoiseMinutes = 20.0,
        appSwitchCount = 255, switchNoise = 32.0,
        notificationCount = 95,
        coverage = 0.88, coverageNoise = 0.04,
        weekendWakeShiftMinutes = 20, weekendEndShiftMinutes = 30,
        irregularity = 0.10,
        specialWindows = listOf(
            QaSpecialWindow(20, 27, "出差", wakeShiftMinutes = -90, endShiftMinutes = 60, movementScale = 1.6, screenScale = 0.8, switchScale = 1.3),
            QaSpecialWindow(130, 140, "出差", wakeShiftMinutes = -95, endShiftMinutes = 55, movementScale = 1.5, screenScale = 0.85, switchScale = 1.2),
            QaSpecialWindow(165, 172, "出差", wakeShiftMinutes = -85, endShiftMinutes = 65, movementScale = 1.7, screenScale = 0.75, switchScale = 1.4),
        ),
    )

    val E_PROJECT_CRUNCH = QaProfileSpec(
        id = "PROFILE_E_PROJECT_CRUNCH",
        displayName = "E · 项目冲刺期",
        description = "平时稳定；Day 60-95 项目冲刺：晚起、晚睡、屏幕翻倍、久坐。Day 96 后自然恢复。ECHO 应看到阶段而非报警。",
        identitySeed = 30871L,
        motionPreference = PresenceMotionLevel.DEFAULT,
        timezone = "Asia/Shanghai",
        wakeMinute = 540, endMinute = 1380,
        wakeNoiseMinutes = 8.0, endNoiseMinutes = 12.0,
        movementIndex = 0.95, movementNoise = 0.09,
        screenOnMinutes = 250.0, screenNoiseMinutes = 18.0,
        appSwitchCount = 250, switchNoise = 30.0,
        notificationCount = 85,
        coverage = 0.90, coverageNoise = 0.03,
        weekendWakeShiftMinutes = 35, weekendEndShiftMinutes = 40,
        irregularity = 0.08,
        specialWindows = listOf(
            QaSpecialWindow(60, 95, "项目冲刺", wakeShiftMinutes = 40, endShiftMinutes = 150, movementScale = 0.35, screenScale = 1.8, switchScale = 1.8),
        ),
    )

    val F_LOW_DATA = QaProfileSpec(
        id = "PROFILE_F_LOW_DATA",
        displayName = "F · 低感知数据用户",
        description = "只开了基础感知，覆盖率 ~0.3，很多天不足有效日阈值。ECHO 必须诚实：不编造、缓慢积累，而不是展示『数据不足』错误页。",
        identitySeed = 36103L,
        motionPreference = PresenceMotionLevel.QUIET,
        timezone = "Asia/Shanghai",
        wakeMinute = 540, endMinute = 1360,
        wakeNoiseMinutes = 15.0, endNoiseMinutes = 25.0,
        movementIndex = 0.8, movementNoise = 0.15,
        screenOnMinutes = 220.0, screenNoiseMinutes = 40.0,
        appSwitchCount = 180, switchNoise = 50.0,
        notificationCount = 60,
        coverage = 0.32, coverageNoise = 0.16,
        weekendWakeShiftMinutes = 50, weekendEndShiftMinutes = 50,
        irregularity = 0.15,
    )

    val G_WEEKEND_DIFFERENT = QaProfileSpec(
        id = "PROFILE_G_WEEKEND_DIFFERENT",
        displayName = "G · 周末完全不同",
        description = "工作日 07:30 起床，周末 11:00 才醒、活动量 1.6 倍。ECHO 必须区分工作日/周末两个世界，而不是把周末当异常。",
        identitySeed = 42757L,
        motionPreference = PresenceMotionLevel.LIVELY,
        timezone = "Asia/Shanghai",
        wakeMinute = 450, endMinute = 1380,
        wakeNoiseMinutes = 10.0, endNoiseMinutes = 15.0,
        movementIndex = 0.85, movementNoise = 0.10,
        screenOnMinutes = 240.0, screenNoiseMinutes = 20.0,
        appSwitchCount = 240, switchNoise = 28.0,
        notificationCount = 80,
        coverage = 0.90, coverageNoise = 0.03,
        weekendWakeShiftMinutes = 210, weekendEndShiftMinutes = 60,
        weekendMovementScale = 1.6, weekendScreenScale = 1.4,
        irregularity = 0.06,
    )

    val ALL: List<QaProfileSpec> = listOf(
        A_STABLE, B_NIGHT_OWL, C_IRREGULAR, D_TRAVEL, E_PROJECT_CRUNCH, F_LOW_DATA, G_WEEKEND_DIFFERENT,
    )

    fun byId(id: String): QaProfileSpec = ALL.first { it.id == id }
}
