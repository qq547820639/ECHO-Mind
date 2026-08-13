package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.NarrativeFetchResult
import com.yunjue.echo.mind.model.ProfileDisplay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 叙事 + 画像 Profile 遗留仓库（v0.8 清理目标，阻塞网络 + 缓存 + JSON 解析）。
 */
class NarrativeProfileRepository(
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
) {
    /**
     * 批量拉取近 [days] 天每日叙事（GET /v1/narratives?user_id=&from=&to=）。
     * 失败语义：无缓存 → loadFailed=true；有缓存 → fromCache=true（OFFLINE_CACHED 态）。
     */
    fun fetchNarratives(days: Int = 7): NarrativeFetchResult {
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays((days - 1).toLong())
        val to = today
        return try {
            val (code, body) = apiClient.get(
                "/v1/narratives?user_id=${preferences.userId}&from=$from&to=$to"
            )
            if (code in 200..299 && !body.isNullOrBlank()) {
                val result = parseNarrativeRange(body, from, to)
                if (!result.loadFailed) {
                    preferences.setNarrativeCache(body)
                }
                result
            } else {
                fromNarrativeCacheOrFail(from, to)
            }
        } catch (e: Exception) {
            fromNarrativeCacheOrFail(from, to)
        }
    }

    private fun fromNarrativeCacheOrFail(from: LocalDate, to: LocalDate): NarrativeFetchResult {
        val cached = preferences.getNarrativeCacheJson()
        if (cached != null) {
            return parseNarrativeRange(cached, from, to).copy(fromCache = true, loadFailed = false)
        }
        return NarrativeFetchResult(
            narratives = emptyList(),
            loadFailed = true,
            fromCache = false,
            dataCoverage = 0f,
            missingDates = emptyList(),
            isPartial = false
        )
    }

    /**
     * 拉取用户画像（GET /v1/profile/{user_id}），失败返回 loadFailed=true 的缺省画像。
     */
    fun fetchProfile(): ProfileDisplay {
        val (code, body) = try {
            apiClient.get("/v1/profile/${preferences.userId}")
        } catch (e: Exception) {
            return defaultProfile(loadFailed = true)
        }
        if (code !in 200..299 || body.isNullOrBlank()) return defaultProfile(loadFailed = true)
        return runCatching {
            val o = JSONObject(body)
            val traits = o.optJSONObject("traits") ?: JSONObject()
            val sourcesUnion = traits.optJSONArray("sources_present_union")?.toStringList() ?: emptyList()
            ProfileDisplay(
                observationDays = traits.optInt("observation_days", 0),
                narrativeDaysLast7 = traits.optInt("narrative_days_last_7", 0),
                version = o.optInt("version", 1),
                sourcesPresentUnion = sourcesUnion,
                updatedAt = o.optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
                loadFailed = false
            )
        }.getOrDefault(defaultProfile(loadFailed = true))
    }

    private fun defaultProfile(loadFailed: Boolean = false) = ProfileDisplay(
        observationDays = 0,
        narrativeDaysLast7 = 0,
        version = 0,
        sourcesPresentUnion = emptyList(),
        loadFailed = loadFailed
    )

    /** 解析 /v1/narratives 单日对象为 [NarrativeDisplay]；失败返回 null。 */
    private fun parseNarrative(json: String): NarrativeDisplay? = runCatching {
        val o = JSONObject(json)
        val events = o.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                NarrativeEventDisplay(
                    source = e.optString("source"),
                    summary = e.optString("summary"),
                    sourcesPresent = e.optJSONArray("sources_present")?.toStringList() ?: emptyList()
                )
            }
        } ?: emptyList()
        NarrativeDisplay(
            date = o.optString("date"),
            events = events,
            gaps = o.optJSONArray("gaps")?.toStringList() ?: emptyList()
        )
    }.getOrNull()

    /** 解析批量叙事响应为 [NarrativeFetchResult]（含 data coverage / missing dates）。 */
    private fun parseNarrativeRange(json: String, from: LocalDate, to: LocalDate): NarrativeFetchResult =
        runCatching {
            val root = JSONObject(json)
            val narratives = mutableListOf<NarrativeDisplay>()
            val arr = when {
                root.has("narratives") -> root.optJSONArray("narratives")
                root.has("items") -> root.optJSONArray("items")
                root.has("results") -> root.optJSONArray("results")
                else -> null
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseNarrative(item.toString())?.let { narratives.add(it) }
                }
            } else {
                parseNarrative(json)?.let { narratives.add(it) }
            }
            val sorted = narratives.sortedBy { it.date }
            val presentDates = sorted.map { it.date }.toSet()
            val missingDates = generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .map { it.toString() }
                .filter { it !in presentDates }
                .toList()
            val totalDays = ChronoUnit.DAYS.between(from, to) + 1
            val coverage = if (totalDays > 0) {
                (sorted.size.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f)
            } else 0f
            NarrativeFetchResult(
                narratives = sorted,
                loadFailed = false,
                fromCache = false,
                dataCoverage = coverage,
                missingDates = missingDates,
                isPartial = missingDates.isNotEmpty() && sorted.isNotEmpty()
            )
        }.getOrElse {
            NarrativeFetchResult(
                narratives = emptyList(),
                loadFailed = true,
                fromCache = false,
                dataCoverage = 0f,
                missingDates = emptyList(),
                isPartial = false
            )
        }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}
