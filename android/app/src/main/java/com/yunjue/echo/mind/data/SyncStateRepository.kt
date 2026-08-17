package com.yunjue.echo.mind.data

import kotlinx.coroutines.flow.Flow

/**
 * 同步状态观测仓库：几乎全是 preferences / DAO 的零逻辑透传。
 *
 * 供「趋势页」「支持页」读取最近同步/采集时间、错误类别、认证暂停态、待上传事件数等，
 * 同步状态观测的单一职责仓库（从旧聚合门面中剥离）。
 */
class SyncStateRepository(
    private val db: EchoDatabase,
    private val preferences: AppPreferences,
) {
    /** 待上传 outbox 事件数（Flow，支持页徽标）。 */
    fun observePendingCount(): Flow<Int> = db.dao().observePendingCount()

    fun passiveSensingConsentFlow(): Flow<Boolean> = preferences.passiveSensingEnabledFlow()

    /** "最近同步"一律指**最近成功同步**（成功条件才更新）。 */
    fun lastSyncTimestamp(): Long = preferences.lastSuccessfulSyncAt

    fun lastCollectionTimestamp(): Long = preferences.lastCollectionTimestamp

    fun lastSyncHttpCode(): Int? = preferences.lastSyncHttpCode

    fun deadLetterCount(): Int = preferences.deadLetterCount()

    /** 是否处于认证暂停态（批内 401/403 后为 true；重新认证成功后清除）。 */
    fun isAuthBlocked(): Boolean = preferences.authRequired

    /** 最近一次同步错误类别（auth / consent / retryable / terminal；无错误为 null）。 */
    fun lastSyncErrorClass(): String? = preferences.lastSyncErrorClass

    /** 最近一次窗口持久化失败时间（epoch ms；无失败为 null）。 */
    fun lastPersistenceFailure(): Long? = preferences.lastPersistenceFailure

    /** 连续窗口持久化失败计数（成功后清零）。 */
    fun consecutivePersistenceFailures(): Int = preferences.consecutivePersistenceFailures

    /** 待上传事件数（outbox pending；趋势页"等待上传"原因用）。异步查询，调用方在协程中调用。 */
    suspend fun pendingUploadCount(): Int = runCatching {
        db.dao().pendingOutbox().size
    }.getOrDefault(0)
}
