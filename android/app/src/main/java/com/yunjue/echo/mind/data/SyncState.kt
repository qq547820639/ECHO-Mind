package com.yunjue.echo.mind.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yunjue.echo.mind.model.SyncState

/**
 * 同步状态映射（PRD v0.6 契约点 9）。
 *
 * 内部 HTTP status 只用于推导用户可见的 [SyncState]，**严禁**把 status 直接暴露给普通用户。
 *
 * v0.6.2（Batch A）签名调整：不再以单一 lastHttpCode 覆盖式代表整批，
 * 改为批次级分类聚合结果：
 * - [authBlocked]：批内存在 401/403（认证暂停，UI 提示重新登录）
 * - [consentBlocked]：批内存在 412/422（consent 撤回/坏 payload）
 * - [retrying]：批内存在 5xx/429/网络类可重试失败
 * lastSyncHttpCode 仍保留，但降级为诊断字段，不再参与 UI 状态映射。
 */
fun mapSyncState(
    pendingCount: Int,
    networkAvailable: Boolean,
    deadLetterCount: Int,
    authBlocked: Boolean,
    consentBlocked: Boolean,
    retrying: Boolean
): SyncState = when {
    authBlocked -> SyncState.BLOCKED_BY_AUTH
    consentBlocked -> SyncState.BLOCKED_BY_CONSENT
    deadLetterCount > 0 -> SyncState.FAILED_TERMINAL
    pendingCount == 0 -> SyncState.SYNCED
    !networkAvailable -> SyncState.OFFLINE
    retrying -> SyncState.RETRYING
    else -> SyncState.PENDING
}

/**
 * [SyncState] → 用户文案映射契约（PRD 契约点 9 文案表；v0.6.1 P1-6 语义更新）。
 * 文案不得包含 HTTP status / 401 / 412 / 500 等内部码；
 * 敏感错误不显示原始服务端 exception（只显示分类文案）。
 */
fun syncStateText(state: SyncState, pendingCount: Int): String = when (state) {
    SyncState.SYNCED -> "最近成功同步"
    SyncState.PENDING -> "有 $pendingCount 项待同步 · 数据仍安全保存在本机"
    SyncState.SYNCING -> "正在同步"
    SyncState.OFFLINE -> "正在等待网络 · 数据仍安全保存在本机"
    SyncState.RETRYING -> "同步失败，可重试 · 数据仍安全保存在本机"
    SyncState.BLOCKED_BY_AUTH -> "需要重新登录"
    SyncState.BLOCKED_BY_CONSENT -> "需要重新授权 · 相关数据暂不上传"
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
