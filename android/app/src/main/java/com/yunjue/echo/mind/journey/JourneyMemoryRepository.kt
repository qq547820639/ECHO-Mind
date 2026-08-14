package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.data.EchoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * ERA 16 §83 — JourneyMemoryPort 的 Room Adapter（data 层实现）。
 *
 * Canonical Daily State 持久化：
 * - payload = [JourneyCanonicalCodec] v1 编码（只存参数/身份参考/证据 id，绝不存 bitmap）；
 * - 解析失败的存量行一律跳过（fail-closed：渲染弥散占位，不编造）；
 * - 所有读写按 userId 隔离（数据权利删除按用户执行）。
 */
class JourneyMemoryRepository(
    private val db: EchoDatabase,
    private val userId: () -> String,
) : JourneyMemoryPort {

    private fun dao() = db.journeyCanonicalDao()

    override val canonicalDays: Flow<List<JourneyCanonicalDay>> =
        dao().observeByUser(userId()).map { rows -> rows.mapNotNull { JourneyCanonicalCodec.decode(it.payload) } }

    override suspend fun snapshot(day: JourneyCanonicalDay) = withContext(Dispatchers.IO) {
        val uid = userId()
        dao().upsert(
            com.yunjue.echo.mind.data.JourneyCanonicalDayEntity(
                id = "${uid}_${day.date}",
                userId = uid,
                localDate = day.date,
                payload = JourneyCanonicalCodec.encode(day),
                createdAtEpochMs = day.createdAtEpochMs,
            )
        )
    }

    override suspend fun byDate(date: String): JourneyCanonicalDay? = withContext(Dispatchers.IO) {
        dao().byId("${userId()}_$date")?.let { JourneyCanonicalCodec.decode(it.payload) }
    }

    override suspend fun range(from: String, to: String): List<JourneyCanonicalDay> = withContext(Dispatchers.IO) {
        dao().range(userId(), from, to).mapNotNull { JourneyCanonicalCodec.decode(it.payload) }
    }
}
