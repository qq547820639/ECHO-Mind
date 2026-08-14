package com.yunjue.echo.mind.journey

import kotlinx.coroutines.flow.Flow

/**
 * ERA 16 §83 — Journey 长期记忆端口（Canonical Daily State）。
 *
 * Journey 应用层只依赖本端口；数据层（Room Adapter）是唯一实现。
 * 测试以 fake 替换。所有查询按 userId 隔离（数据权利删除按用户执行）。
 */
interface JourneyMemoryPort {
    /** 某用户全部 Canonical Daily State（按日期升序；解析失败的存量行跳过）。 */
    val canonicalDays: Flow<List<JourneyCanonicalDay>>

    /** 落盘/覆盖某一天的 Canonical Daily State（幂等 upsert，绝不保存 bitmap）。 */
    suspend fun snapshot(day: JourneyCanonicalDay)

    /** 按日期读取单日快照；缺失/解析失败 → null。 */
    suspend fun byDate(date: String): JourneyCanonicalDay?

    /** 日期区间读取（yyyy-MM-dd 字典序 = 时间序；含两端）。 */
    suspend fun range(from: String, to: String): List<JourneyCanonicalDay>
}
