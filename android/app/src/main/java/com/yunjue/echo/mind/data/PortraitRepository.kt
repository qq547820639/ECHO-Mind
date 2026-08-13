package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.model.BaselineStatusDto
import com.yunjue.echo.mind.model.PortraitStateInputs
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.mapServerStatus
import com.yunjue.echo.mind.model.resolveTodayPortraitState
import com.yunjue.echo.mind.model.todayLocalDateString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 每日画像仓库（Today Portrait / Timeline / Baseline / Feedback）。
 *
 * - 缓存优先 + 后台刷新 + 平滑替换（九态见 [PortraitUiState]）；
 * - 缓存 identity 使用**服务器返回的 local_date**（Phase 6.4，不覆盖为端侧日期）；
 * - 反馈（recordPortraitFeedback）同时本地记录 + 入 Outbox 可靠同步。
 * - 端侧画像引擎（v0.7 本地优先）：本地模式（未订阅）直接走 [LocalPortraitDataSource]；
 *   已订阅时服务端失败/无网络回退端侧本地重算（数据源为本地 feature_vectors，
 *   与服务端同算法镜像），本地画像不写入 Room 缓存（缓存保持"服务端来源"语义）。
 */
class PortraitRepository(
    private val db: EchoDatabase,
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
    private val outbox: Outbox,
    private val localDataSource: LocalPortraitDataSource
) {
    private val _todayPortraitState = MutableStateFlow(
        PortraitUiState(status = PortraitStatus.LOADING, portrait = null, offline = false)
    )

    fun observeTodayPortrait(): StateFlow<PortraitUiState> = _todayPortraitState

    private val _timelineState = MutableStateFlow(PortraitTimelineUiState(days = 7))

    fun observePortraits(days: Int): StateFlow<PortraitTimelineUiState> = _timelineState

    /** 端侧本地重算 Today 画像（本地模式 / 离线回退共用）。 */
    private suspend fun emitLocalTodayPortrait() {
        val userId = preferences.userId
        if (userId.isBlank()) {
            _todayPortraitState.value = PortraitUiState(status = PortraitStatus.ERROR)
            return
        }
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        val dto = runCatching { localDataSource.computeToday(userId, today, zone) }.getOrNull()
        if (dto == null) {
            _todayPortraitState.value = PortraitUiState(status = PortraitStatus.ERROR)
            return
        }
        val status = mapServerStatus(dto.status) ?: PortraitStatus.ERROR
        _todayPortraitState.value = PortraitUiState(
            status = status,
            portrait = dto,
            offline = false,
            localComputed = true
        )
    }

    suspend fun refreshTodayPortrait(networkAvailable: Boolean = true) {
        val sensingEnabled = runCatching {
            preferences.passiveSensingPrefs.passiveSensingEnabled.first()
        }.getOrDefault(false)
        val userId = preferences.userId
        val localToday = todayLocalDateString(Instant.now(), ZoneId.systemDefault())
        val cached = runCatching { db.portraitDao().queryLatest(userId) }.getOrNull()
            ?.takeIf { it.localDate == localToday && it.userId == userId }

        if (!sensingEnabled) {
            _todayPortraitState.value = PortraitUiState(PortraitStatus.SENSING_DISABLED)
            return
        }

        // 本地模式（未绑定机构）：跳过服务端，直接用端侧引擎（数据只在本机）
        if (preferences.localMode) {
            emitLocalTodayPortrait()
            return
        }

        if (cached != null) {
            _todayPortraitState.value =
                PortraitUiState(PortraitStatus.OFFLINE_CACHED, cached.toDto(), offline = false)
        } else {
            _todayPortraitState.value = PortraitUiState(PortraitStatus.LOADING)
        }

        withContext(Dispatchers.IO) {
            if (!networkAvailable) {
                // 无网络：先尝试端侧本地重算（比陈旧服务端缓存更新鲜）；无本地数据再走缓存/错误
                runCatching { localDataSource.computeToday(userId, localTodayDate(), ZoneId.systemDefault()) }
                    .getOrNull()?.let { localDto ->
                        _todayPortraitState.value = PortraitUiState(
                            status = mapServerStatus(localDto.status) ?: PortraitStatus.ERROR,
                            portrait = localDto,
                            offline = false,
                            localComputed = true
                        )
                        return@withContext
                    }
                _todayPortraitState.value = PortraitUiState(
                    status = resolveTodayPortraitState(
                        PortraitStateInputs(
                            sensingEnabled = true, loading = false, hasCache = cached != null,
                            networkAvailable = false, fetchFailed = false, serverStatus = null
                        )
                    ),
                    portrait = cached?.toDto(),
                    offline = true
                )
                return@withContext
            }
            val fetch = try {
                apiClient.getTodayPortrait()
            } catch (e: Exception) {
                null
            }
            if (fetch != null && fetch.first in 200..299 && !fetch.second.isNullOrBlank()) {
                val dto = PortraitParsers.parseDailyPortrait(fetch.second!!)
                if (dto != null) {
                    runCatching {
                        db.portraitDao().insert(dto.toEntity(userId, dto.date, System.currentTimeMillis()))
                    }
                    _todayPortraitState.value = PortraitUiState(
                        status = resolveTodayPortraitState(
                            PortraitStateInputs(
                                sensingEnabled = true, loading = false, hasCache = cached != null,
                                networkAvailable = true, fetchFailed = false, serverStatus = dto.status
                            )
                        ),
                        portrait = dto,
                        offline = false
                    )
                    return@withContext
                }
            }
            // 服务端失败：端侧本地重算优先于陈旧缓存
            runCatching { localDataSource.computeToday(userId, localTodayDate(), ZoneId.systemDefault()) }
                .getOrNull()?.let { localDto ->
                    _todayPortraitState.value = PortraitUiState(
                        status = mapServerStatus(localDto.status) ?: PortraitStatus.ERROR,
                        portrait = localDto,
                        offline = false,
                        localComputed = true
                    )
                    return@withContext
                }
            _todayPortraitState.value = PortraitUiState(
                status = resolveTodayPortraitState(
                    PortraitStateInputs(
                        sensingEnabled = true, loading = false, hasCache = cached != null,
                        networkAvailable = networkAvailable, fetchFailed = true, serverStatus = null
                    )
                ),
                portrait = cached?.toDto(),
                offline = true
            )
        }
    }

    /** 端侧本地时间线（演示模式 / 离线回退共用）。 */
    private suspend fun localTimelineState(days: Int): PortraitTimelineUiState {
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        val list = runCatching {
            localDataSource.computeTimeline(preferences.userId, days, today, zone)
        }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            return PortraitTimelineUiState(days = days, loading = false, loadFailed = true)
        }
        val expected = (0 until days).map { today.minusDays((days - 1 - it).toLong()).toString() }
        val present = list.map { it.date }.toSet()
        val missing = expected.filter { it !in present }
        return PortraitTimelineUiState(
            days = days,
            portraits = list,
            loading = false,
            loadFailed = false,
            fromCache = false,
            isPartial = missing.isNotEmpty(),
            missingDates = missing
        )
    }

    suspend fun refreshPortraits(days: Int) {
        val sensingEnabled = runCatching {
            preferences.passiveSensingPrefs.passiveSensingEnabled.first()
        }.getOrDefault(false)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val todayDate = now.atZone(zone).toLocalDate()
        val today = todayDate.toString()
        val from = todayDate.minusDays((days - 1).toLong()).toString()

        if (!sensingEnabled) {
            _timelineState.value = PortraitTimelineUiState(days = days, loading = false, portraits = emptyList())
            return
        }
        // 本地模式：本地时间线
        if (preferences.localMode) {
            _timelineState.value = localTimelineState(days)
            return
        }
        _timelineState.value = PortraitTimelineUiState(days = days, loading = true)

        withContext(Dispatchers.IO) {
            val fetch = try {
                apiClient.getPortraits(days)
            } catch (e: Exception) {
                null
            }
            if (fetch != null && fetch.first in 200..299 && !fetch.second.isNullOrBlank()) {
                val list = PortraitParsers.parsePortraitList(fetch.second!!)
                runCatching {
                    db.portraitDao().insertAll(
                        list.map { it.toEntity(preferences.userId, it.date, System.currentTimeMillis()) }
                    )
                }
                val expected = (0 until days).map { todayDate.minusDays((days - 1 - it).toLong()).toString() }
                val present = list.map { it.date }.toSet()
                val missing = expected.filter { it !in present }
                _timelineState.value = PortraitTimelineUiState(
                    days = days,
                    portraits = list,
                    loading = false,
                    loadFailed = false,
                    fromCache = false,
                    isPartial = missing.isNotEmpty() && list.isNotEmpty(),
                    missingDates = missing
                )
            } else {
                // 服务端失败：端侧本地时间线优先；无本地数据再走 Room 缓存
                val localAttempt = runCatching {
                    localDataSource.computeTimeline(preferences.userId, days, todayDate, zone)
                }.getOrDefault(emptyList())
                if (localAttempt.isNotEmpty()) {
                    val expected = (0 until days).map { todayDate.minusDays((days - 1 - it).toLong()).toString() }
                    val present = localAttempt.map { it.date }.toSet()
                    val missing = expected.filter { it !in present }
                    _timelineState.value = PortraitTimelineUiState(
                        days = days,
                        portraits = localAttempt,
                        loading = false,
                        loadFailed = false,
                        fromCache = false,
                        isPartial = missing.isNotEmpty(),
                        missingDates = missing
                    )
                    return@withContext
                }
                val cached = runCatching {
                    db.portraitDao().queryByDateRange(preferences.userId, from, today).map { it.toDto() }
                }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    val expected = (0 until days).map { todayDate.minusDays((days - 1 - it).toLong()).toString() }
                    val present = cached.map { it.date }.toSet()
                    val missing = expected.filter { it !in present }
                    _timelineState.value = PortraitTimelineUiState(
                        days = days,
                        portraits = cached,
                        loading = false,
                        loadFailed = false,
                        fromCache = true,
                        isPartial = missing.isNotEmpty(),
                        missingDates = missing
                    )
                } else {
                    _timelineState.value = PortraitTimelineUiState(
                        days = days, portraits = emptyList(), loading = false, loadFailed = true
                    )
                }
            }
        }
    }

    suspend fun fetchBaselineStatus(): BaselineStatusDto? {
        if (preferences.localMode) {
            val zone = ZoneId.systemDefault()
            val today = Instant.now().atZone(zone).toLocalDate()
            return runCatching { localDataSource.baselineStatus(preferences.userId, today, zone) }.getOrNull()
        }
        return withContext(Dispatchers.IO) {
            val fetch = try {
                apiClient.getBaselineStatus()
            } catch (e: Exception) {
                null
            }
            if (fetch != null && fetch.first in 200..299 && !fetch.second.isNullOrBlank()) {
                PortraitParsers.parseBaselineStatus(fetch.second!!)
            } else {
                // 离线回退：端侧本地基线状态
                val zone = ZoneId.systemDefault()
                val today = Instant.now().atZone(zone).toLocalDate()
                runCatching { localDataSource.baselineStatus(preferences.userId, today, zone) }.getOrNull()
            }
        }
    }

    private fun localTodayDate(): java.time.LocalDate {
        val zone = ZoneId.systemDefault()
        return Instant.now().atZone(zone).toLocalDate()
    }

    /** 记录画像反馈（本地即时记录 + 入 Outbox 可靠同步）。 */
    suspend fun recordPortraitFeedback(date: String, helpful: Boolean) {
        preferences.recordPortraitFeedback(date, helpful)
        val eventId = "pf_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("event_id", eventId)
            put("user_id", preferences.userId)
            put("portrait_id", date)
            put("feedback", if (helpful) "LIKE" else "NOT_LIKE")
            put("portrait_schema_version", PORTRAIT_SCHEMA_VERSION_LOCAL)
            put("created_at", Instant.now().toString())
        }
        outbox.enqueue(eventId, "portrait_feedback", payload, 40)
    }

    /** 某日画像反馈（true=挺像 / false=不太像 / null=未反馈）。 */
    fun portraitFeedback(date: String): Boolean? = preferences.portraitFeedback(date)

    /**
     * 重新生成今日画像（v0.7 反馈闭环）：
     * - 订阅模式：POST /v1/me/portraits/rebuild（服务端重算），失败回退本地重算；
     * - 本地模式：端侧引擎直接重算（晚到窗口会进入聚合，结果随之更新）。
     */
    suspend fun rebuildTodayPortrait() {
        if (preferences.localMode) {
            emitLocalTodayPortrait()
            return
        }
        val fetch = try {
            apiClient.rebuildPortrait()
        } catch (e: Exception) {
            null
        }
        if (fetch != null && fetch.first in 200..299 && !fetch.second.isNullOrBlank()) {
            val dto = PortraitParsers.parseDailyPortrait(fetch.second!!)
            if (dto != null) {
                runCatching {
                    db.portraitDao().insert(dto.toEntity(preferences.userId, dto.date, System.currentTimeMillis()))
                }
                _todayPortraitState.value = PortraitUiState(
                    status = mapServerStatus(dto.status) ?: PortraitStatus.ERROR,
                    portrait = dto
                )
                return
            }
        }
        // 服务端重建失败 → 本地重算回退
        emitLocalTodayPortrait()
    }

    companion object {
        /** 画像 schema 版本（对齐后端 portrait-v1；feedback 上报口径）。 */
        private const val PORTRAIT_SCHEMA_VERSION_LOCAL = "portrait-v1"
    }
}
