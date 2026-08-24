package com.yunjue.echo.mind.localportrait

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.MessageDisplay
import com.yunjue.echo.mind.model.containsBlockedVocabulary
import com.yunjue.echo.mind.model.PORTRAIT_DIMENSIONS
import com.yunjue.echo.mind.model.dimensionDisplayName
import java.security.MessageDigest

/**
 * 本周节律小结（v0.7 分析消息，本地模式版本）：
 * 与后端 `app/services/messages.py::build_weekly_digest` **逐语义镜像**——
 * 本地模式（未订阅）由本模块从端侧 7 天画像确定性生成同款小结（无网络）；
 * 订阅模式由后端生成（GET /v1/me/messages）。
 *
 * - 有效画像天数 < 3 → 不生成（数据不足不硬编，产品契约 abstain）；
 * - message.id = SHA-256(weekly_digest|窗口起|窗口止|正文) 前 16 位，确定性幂等
 *   （与后端一致，客户端据此去重）；
 * - 词表安全：正文含契约 BLOCK 词则 fail-closed 不出小结。
 *
 * 纯 Kotlin 无 Android 依赖。
 */
object LocalPortraitDigest {

    const val MIN_PORTRAIT_DAYS = 3



    /** 从画像列表确定性生成小结；不足 MIN_PORTRAIT_DAYS 天返回 null。 */
    fun build(portraits: List<DailyPortraitDto>): MessageDisplay? {
        if (portraits.size < MIN_PORTRAIT_DAYS) return null

        data class Stat(var similar: Int = 0, var total: Int = 0)

        val stats = LinkedHashMap<String, Stat>()
        for (portrait in portraits) {
            for (dim in PORTRAIT_DIMENSIONS) {
                val value = portrait.dimensionValue(dim) ?: continue
                val stat = stats.getOrPut(dim) { Stat() }
                stat.total++
                if (value == "SIMILAR") stat.similar++
            }
        }
        if (stats.isEmpty()) return null

        val dates = portraits.map { it.date }.sorted()
        val windowStart = dates.first()
        val windowEnd = dates.last()

        val mostSimilar = stats.maxByOrNull { it.value.similar.toFloat() / it.value.total }!!
        val mostChanged = stats.maxByOrNull { it.value.total - it.value.similar }!!

        val body =
            "最接近：${dimensionDisplayName(mostSimilar.key)}；变化较明显：${dimensionDisplayName(mostChanged.key)}。"
        if (containsBlockedVocabulary(body)) {
            // fail-closed：命中契约 BLOCK 词则不出小结（宁可 abstain，绝不越界）
            return null
        }

        val id = MessageDigest.getInstance("SHA-256")
            .digest("weekly_digest|$windowStart|$windowEnd|$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

        return MessageDisplay(id = id, title = "本周节律小结", body = body)
    }
}
