package com.yunjue.echo.mind.ports

import com.yunjue.echo.mind.model.EchoPresenceState
import kotlinx.coroutines.flow.StateFlow

/**
 * ERA 13.2 §40 — Presence Ports。
 *
 * PresenceRepository（组装方）与 EchoStateStore（进程级快照）分别实现 Source/Writer/Store；
 * 消费方（runtime 协调器 / Wallpaper / Dream）只依赖端口。
 */

/** Presence 状态源（当前 ECHO 状态流）。 */
interface PresenceStateSource {
    val state: StateFlow<EchoPresenceState?>
}

/** Presence 状态写入（触发组装刷新）。 */
interface PresenceStateWriter {
    suspend fun refresh()
}

/** Presence 快照存储（进程共享 + 落盘恢复，Wallpaper/Dream 共享同一 ECHO）。 */
interface PresenceSnapshotStore {
    val state: StateFlow<EchoPresenceState?>
    fun publish(state: EchoPresenceState)
    fun clear()
}
