package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.model.BaselineStatusDto
import com.yunjue.echo.mind.model.PortraitStateInputs
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.resolveTodayPortraitState
import com.yunjue.echo.mind.model.todayLocalDateString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 每日画像仓库（Today Portrait / Timeline / Baseline / Feedback）。
 *
 * - 缓存优先 + 后台刷新 + 平滑替换（九态见 [PortraitUiState]）；
 * - 缓存 identity 使用**服务器返回的 local_date**（Phase 6.4，不覆盖为端侧日期）；
 * - 反馈（recordPortraitFeedback）同时本地记录 + 入 Outbox 可靠同步。
 */
class PortraitRepository(
    private val db: EchoDatabase,
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
    private val outbox: Outbox,
) {
    private val _todayPortraitState = MutableStateFlow(
        PortraitUiState(status = PortraitStatus.LOADING, portrait = null, offline = false)
    )

    fun observeTodayPortrait(): StateFlow<PortraitUiState> = _todayPortraitState

    private val _timelineState = MutableStateFlow(PortraitTimelineUiState(days = 7))

    fun observePortraits(days: Int): StateFlow<PortraitTimelineUiState> = _timelineState

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

        if (cached != null) {
            _todayPortraitState.value =
                PortraitUiState(PortraitStatus.OFFLINE_CACHED, cached.toDto(), offline = false)
        } else {
            _todayPortraitState.value = PortraitUiState(PortraitStatus.LOADING)
        }

        withContext(Dispatchers.IO) {
            if (!networkAvailable) {
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

    suspend fun refreshPortraits(days: Int) {
        val sensingEnabled = runCatching {
            preferences.passiveSensingPrefs.passiveSensingEnabled.first()
        }.getOrDefault(false)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val todayDate = LocalDate.ofInstant(now, zone)
        val today = todayDate.toString()
        val from = todayDate.minusDays((days - 1).toLong()).toString()

        if (!sensingEnabled) {
            _timelineState.value = PortraitTimelineUiState(days = days, loading = false, portraits = emptyList())
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

    suspend fun fetchBaselineStatus(): BaselineStatusDto? = withContext(Dispatchers.IO) {
        val fetch = try {
            apiClient.getBaselineStatus()
        } catch (e: Exception) {
            return@withContext null
        }
        if (fetch.first in 200..299 && !fetch.second.isNullOrBlank()) {
            PortraitParsers.parseBaselineStatus(fetch.second!!)
        } else {
            null
        }
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

    companion object {
        /** 画像 schema 版本（对齐后端 portrait-v1；feedback 上报口径）。 */
        private const val PORTRAIT_SCHEMA_VERSION_LOCAL = "portrait-v1"
    }
}
