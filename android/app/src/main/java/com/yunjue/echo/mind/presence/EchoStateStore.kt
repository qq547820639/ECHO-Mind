package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoPresenceState

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ERA 13.5 — EchoStateStore 留在 :app（进程级快照存储，Application scoped 基础设施；
 * 实现 ports.PresenceSnapshotStore；Wallpaper/Dream 共享同一 ECHO）。
 *
 * ERA 1：内存态 + publish/clear；ERA 2：挂接 AmbientEngine 写入，
 * 并落盘最近一版快照（进程死亡后 Wallpaper 恢复用）。
 */
class EchoStateStore : com.yunjue.echo.mind.ports.PresenceSnapshotStore {
    private val _state = MutableStateFlow<EchoPresenceState?>(null)

    /** 当前状态流；null = 尚未发布过任何状态（消费者显示中性占位，禁止编造）。 */
    override val state: StateFlow<EchoPresenceState?> = _state

    override fun publish(state: EchoPresenceState) {
        _state.value = state
    }

    override fun clear() {
        _state.value = null
    }
}

