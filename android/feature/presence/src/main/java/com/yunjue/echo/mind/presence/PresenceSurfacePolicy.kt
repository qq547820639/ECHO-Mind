package com.yunjue.echo.mind.presence

import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy

/**
 * ERA 74 §64/§65 + V3 §L/§M — Presence 渲染策略（纯函数，可 JVM 锚定）。
 *
 * 用户视觉偏好（Me → Presence 设置）进入 Wallpaper/Dream 渲染：
 * - 减少动画（无障碍）→ [MotionPolicy.REDUCED]（mapper 内 flowSpeed 归零 + 编译期低动效）；
 * - 动态程度 QUIET/DEFAULT/LIVELY → [PresenceMotionLevel] 系数（mapper 承载）
 *   并映射 [MotionPolicy.QUIET]（渲染层幅度缩放）；
 * - 增强夜间模式 → nightFactor 降暗减速。
 *
 * V3 §L：Surface（privacy/文字/亮度）与 MotionPolicy（velocity/amplitude/parallax）
 * 与 RenderQuality（数量/光晕）三个正交概念分离，不再混入 SurfaceMode。
 */

/** Wallpaper/Dream/App 渲染所用的策略（surface + 动效策略 + 动态程度 + 夜间模式）。 */
data class PresenceRenderPolicy(
    val surface: EchoSurface,
    val motion: MotionPolicy,
    val motionLevel: PresenceMotionLevel,
    val nightMode: Boolean,
    val reduceMotion: Boolean,
)

/** 用户偏好 → 渲染策略（未知动态程度名 fail-closed 到 DEFAULT）。 */
fun resolveRenderPolicy(
    baseSurface: EchoSurface,
    reduceMotion: Boolean,
    motionLevelName: String,
    nightMode: Boolean,
): PresenceRenderPolicy {
    val motionLevel = when (motionLevelName) {
        PresenceMotionLevel.QUIET.name -> PresenceMotionLevel.QUIET
        PresenceMotionLevel.LIVELY.name -> PresenceMotionLevel.LIVELY
        else -> PresenceMotionLevel.DEFAULT
    }
    val motion = when {
        reduceMotion -> MotionPolicy.REDUCED
        motionLevel == PresenceMotionLevel.QUIET -> MotionPolicy.QUIET
        else -> MotionPolicy.NORMAL
    }
    return PresenceRenderPolicy(
        surface = baseSurface,
        motion = motion,
        motionLevel = motionLevel,
        nightMode = nightMode,
        reduceMotion = reduceMotion,
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
