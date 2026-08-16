package com.yunjue.echo.mind.presence

/**
 * ERA 74 §64/§65 — Presence 表面功耗策略（纯函数，可 JVM 锚定）。
 *
 * 用户视觉偏好（Me → Presence 设置）必须真正进入 Wallpaper/Dream 渲染：
 * - 减少动画（无障碍）→ [SurfaceMode.REDUCED_MOTION]（flowSpeed 归零，§64 animation strength）；
 * - 动态程度 QUIET/DEFAULT/LIVELY → [PresenceMotionLevel] 系数；
 * - 增强夜间模式 → nightFactor 额外降暗减速。
 *
 * 此前 Wallpaper/Dream 调用 computeVisualParameters 时未传任何偏好（默认 DEFAULT/无夜间），
 * 用户设置对功耗最高的两个 ambient surface 无效——§64/§65 真值缺口。
 */

/** Wallpaper/Dream 渲染所用的表面配置（surface + 动态程度 + 夜间模式）。 */
data class PresenceSurfaceConfig(
    val surface: SurfaceMode,
    val motionLevel: PresenceMotionLevel,
    val nightMode: Boolean,
)

/** 用户偏好 → 表面配置（未知动态程度名 fail-closed 到 DEFAULT）。 */
fun resolveSurfaceConfig(
    baseSurface: SurfaceMode,
    reduceMotion: Boolean,
    motionLevelName: String,
    nightMode: Boolean,
): PresenceSurfaceConfig {
    val surface = if (reduceMotion) SurfaceMode.REDUCED_MOTION else baseSurface
    val motionLevel = when (motionLevelName) {
        PresenceMotionLevel.QUIET.name -> PresenceMotionLevel.QUIET
        PresenceMotionLevel.LIVELY.name -> PresenceMotionLevel.LIVELY
        else -> PresenceMotionLevel.DEFAULT
    }
    return PresenceSurfaceConfig(
        surface = surface,
        motionLevel = motionLevel,
        nightMode = nightMode,
    )
}

/**
 * §65 — 快照重读节流：渲染循环不再每帧 JSON 解码（40 字段），
 * 至多每 [intervalMs] 重读一次（Presence 快照更新节奏为分钟级，1s 节流零感知损失）。
 */
fun shouldRefreshSnapshot(lastReadMs: Long, nowMs: Long, intervalMs: Long = SNAPSHOT_REFRESH_INTERVAL_MS): Boolean =
    nowMs - lastReadMs >= intervalMs

/** 快照重读最小间隔（1s：与 15 分钟更新节奏相比极保守）。 */
const val SNAPSHOT_REFRESH_INTERVAL_MS = 1000L


