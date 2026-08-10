package com.yunjue.echo.mind.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yunjue.echo.mind.model.SyncState

/**
 * 同步状态映射（PRD v0.6 契约点 9）。
 *
 * 内部 HTTP status 只用于推导用户可见的 [SyncState]，**严禁**把 status 直接暴露给普通用户。
 */
fun mapSyncState(
    pendingCount: Int,
    lastHttpCode: Int?,
    networkAvailable: Boolean,
    deadLetterCount: Int
): SyncState = when {
    lastHttpCode == 401 || lastHttpCode == 403 -> SyncState.BLOCKED_BY_AUTH
    lastHttpCode == 412 || lastHttpCode == 422 -> SyncState.BLOCKED_BY_CONSENT
    deadLetterCount > 0 -> SyncState.FAILED_TERMINAL
    pendingCount == 0 -> SyncState.SYNCED
    !networkAvailable -> SyncState.OFFLINE
    lastHttpCode != null && (lastHttpCode >= 500 || lastHttpCode == 429) -> SyncState.RETRYING
    else -> SyncState.PENDING
}

/**
 * [SyncState] → 用户文案映射契约（PRD 契约点 9 文案表）。
 * 文案不得包含 HTTP status / 401 / 412 / 500 等内部码。
 */
fun syncStateText(state: SyncState, pendingCount: Int): String = when (state) {
    SyncState.SYNCED -> "已同步"
    SyncState.PENDING -> "$pendingCount 项待上传 · 数据仍安全保存在本机"
    SyncState.SYNCING -> "正在同步"
    SyncState.OFFLINE -> "等待网络恢复 · 数据仍安全保存在本机"
    SyncState.RETRYING -> "同步遇到临时问题，将自动重试"
    SyncState.BLOCKED_BY_AUTH -> "登录已失效，请重新登录"
    SyncState.BLOCKED_BY_CONSENT -> "被动感知已关闭，相关数据不再上传"
    SyncState.FAILED_TERMINAL -> "部分数据无法上传（旧版数据无需再上传；如需帮助请联系机构支持）"
}

/** 当前是否有可用网络（用于离线状态展示）。 */
fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return try {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }
}
