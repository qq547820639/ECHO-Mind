package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitFactDto
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Portrait DTO ↔ Room 实体转换（顶层 internal 扩展，纯映射无 Android 依赖）。
 *
 * - [DailyPortraitEntity.toDto]：缓存行 → 展示 DTO（缓存未存 baseline 字段，重建默认值）。
 * - [DailyPortraitDto.toEntity]：展示 DTO → 缓存行（identity = localDate + userId）。
 */
internal fun DailyPortraitEntity.toDto(): DailyPortraitDto = DailyPortraitDto(
    date = localDate,
    status = status,
    confidence = confidence,
    baselineDays = 0,
    headline = runCatching { JSONArray(headlineJson).toStringList() }.getOrDefault(emptyList()),
    summary = summary,
    dimensions = runCatching {
        val o = JSONObject(dimensionsJson)
        val m = mutableMapOf<String, PortraitDimensionDto>()
        o.keys().forEach { k ->
            o.optJSONObject(k)?.let { entry ->
                PortraitDimensionDto.parse(entry)?.let { m[k] = it }
            }
        }
        m
    }.getOrDefault(emptyMap()),
    coverage = coverageJson?.let { runCatching { JSONObject(it).toAnyMap() }.getOrNull() },
    facts = runCatching {
        val arr = JSONArray(factsJson)
        (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            PortraitFactDto(
                label = f.optString("label"),
                todayText = f.optString("today_text"),
                baselineText = f.optString("baseline_text"),
                deltaText = f.optString("delta_text")
            )
        }
    }.getOrDefault(emptyList()),
    timezoneUsed = timezoneUsed
)

internal fun DailyPortraitDto.toEntity(userId: String, localDate: String, nowMs: Long): DailyPortraitEntity =
    DailyPortraitEntity(
        id = "${localDate}_$userId",
        localDate = localDate,
        userId = userId,
        status = status,
        confidence = confidence,
        headlineJson = JSONArray(headline).toString(),
        summary = summary,
        dimensionsJson = JSONObject().apply {
            dimensions.forEach { (k, v) ->
                put(k, JSONObject().apply {
                    put("value", v.value)
                    v.metric?.let { put("metric", it) }
                    v.z?.let { put("z", it) }
                })
            }
        }.toString(),
        factsJson = JSONArray().apply {
            facts.forEach { fact ->
                put(
                    JSONObject().apply {
                        put("label", fact.label)
                        put("today_text", fact.todayText)
                        put("baseline_text", fact.baselineText)
                        put("delta_text", fact.deltaText)
                    }
                )
            }
        }.toString(),
        coverageJson = coverage?.let { toJsonObject(it).toString() },
        timezoneUsed = timezoneUsed,
        fetchedAt = nowMs
    )

/** Map<String, Any> → JSONObject（Number/Boolean 原样，其余转字符串）。 */
internal fun toJsonObject(map: Map<String, Any>): JSONObject = JSONObject().apply {
    map.forEach { (k, v) ->
        when (v) {
            is Number, is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

/**
 * JSONObject → Map<String, Any>（Boolean 原样；Number 原样，其中 BigDecimal 归一为 Double；
 * 其余转字符串；JSON null 跳过）。
 */
internal fun JSONObject.toAnyMap(): Map<String, Any> {
    val out = mutableMapOf<String, Any>()
    keys().forEach { k ->
        val v = opt(k)
        when (v) {
            null, JSONObject.NULL -> Unit
            is BigDecimal -> out[k] = v.toDouble()
            is Boolean, is Number -> out[k] = v
            else -> out[k] = v.toString()
        }
    }
    return out
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
