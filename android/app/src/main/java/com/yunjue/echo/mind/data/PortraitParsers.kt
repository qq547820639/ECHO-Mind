package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.model.BaselineStatusDto
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitFactDto
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Portrait JSON 解析纯函数（Phase 1.2 跨端契约测试锚点）。
 *
 * 无实例状态、无 Android 依赖，纯 JVM 可测；[PortraitContractParseTest] 直接以
 * `PortraitParsers.parseXxx` 调用。生产调用方 [PortraitRepository] 同源复用，行为不变。
 */
object PortraitParsers {
    /** 解析 /v1/portraits/today 或 /v1/portraits 单元素为 [DailyPortraitDto]；失败返回 null。 */
    internal fun parseDailyPortrait(json: String): DailyPortraitDto? = runCatching {
        val o = JSONObject(json)
        val dimensions = mutableMapOf<String, PortraitDimensionDto>()
        o.optJSONObject("dimensions")?.let { d ->
            d.keys().forEach { k ->
                d.optJSONObject(k)?.let { entry ->
                    PortraitDimensionDto.parse(entry)?.let { dimensions[k] = it }
                }
            }
        }
        DailyPortraitDto(
            date = o.optString("date"),
            status = o.optString("status"),
            confidence = o.optString("confidence"),
            baselineDays = o.optInt("baseline_days", 0),
            baselineVersion = o.optString("baseline_version").takeIf { it.isNotBlank() && it != "null" },
            headline = o.optJSONArray("headline")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            summary = o.optString("summary"),
            dimensions = dimensions,
            coverage = o.optJSONObject("coverage")?.let { co ->
                val out = mutableMapOf<String, Any>()
                co.keys().forEach { k ->
                    when (val v = co.opt(k)) {
                        null, JSONObject.NULL -> Unit
                        is BigDecimal -> out[k] = v.toDouble()
                        is Boolean, is Number -> out[k] = v
                        else -> out[k] = v.toString()
                    }
                }
                out
            },
            facts = o.optJSONArray("facts")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val f = arr.optJSONObject(i) ?: return@mapNotNull null
                    PortraitFactDto(
                        label = f.optString("label"),
                        todayText = f.optString("today_text"),
                        baselineText = f.optString("baseline_text"),
                        deltaText = f.optString("delta_text")
                    )
                }
            } ?: emptyList(),
            timezoneUsed = o.optString("timezone_used").takeIf { it.isNotBlank() && it != "null" }
        )
    }.getOrNull()

    /** 解析 /v1/portraits 批量响应为 [DailyPortraitDto] 列表（按 date 升序）。 */
    internal fun parsePortraitList(body: String): List<DailyPortraitDto> = runCatching {
        val root = JSONObject(body)
        val arr = root.optJSONArray("portraits") ?: return@runCatching emptyList<DailyPortraitDto>()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseDailyPortrait(it.toString()) }
        }.sortedBy { it.date }
    }.getOrDefault(emptyList())

    /** 解析 /v1/baseline/status 为 [BaselineStatusDto]；失败返回 null。 */
    internal fun parseBaselineStatus(body: String): BaselineStatusDto? = runCatching {
        val o = JSONObject(body)
        BaselineStatusDto(
            status = o.optString("status"),
            baselineDays = o.optInt("baseline_days", 0),
            baselineVersion = o.optString("baseline_version").takeIf { it.isNotBlank() && it != "null" },
            windowStart = o.optString("window_start").takeIf { it.isNotBlank() && it != "null" },
            windowEnd = o.optString("window_end").takeIf { it.isNotBlank() && it != "null" },
            bucketUsage = o.optString("bucket_usage").takeIf { it.isNotBlank() && it != "null" },
            todayCoverage = o.optDouble("today_coverage", 0.0)
        )
    }.getOrNull()
}
