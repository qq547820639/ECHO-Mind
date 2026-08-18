package com.yunjue.echo.mind.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.presence.EchoStateStore
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T8-P2-3 补缺：echoMaturity「日历钟」装配半边测试。
 *
 * 阶梯纯函数（echoMaturity 边界/单调）已有三处锁定；但 epoch ms → 时区 → LocalDate →
 * ChronoUnit.DAYS 的钟装配（PresenceRepository.maturityCalendarDays，ERA 21）此前零测试：
 * 跨午夜 / 跨时区 / DST 边界全裸奔。本文件经真实 PresenceRepository 实例
 * （反射调用其私有纯函数——只读 java.time，不触 Android 运行时）锁定装配语义：
 * - 锚点缺失（<=0）= 0（SEED 语义保持）；
 * - 日历天数按**给定 zone 的本地日期**计，同一 epoch 在不同时区可差一天；
 * - DST 折损日（23h）仍按日历日推进；
 * - ≥28 天可到 MATURE（baseline.validDays 的 28 天分桶窗口永远到不了——那是另一把钟）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresenceRepositoryMaturityClockTest {

    private lateinit var context: Context
    private lateinit var db: EchoDatabase
    private lateinit var repository: PresenceRepository

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PresenceRepository(
            dataSource = LocalPortraitDataSource(db),
            preferences = AppPreferences(context, JvmTestFieldCipher()),
            passiveSensingPrefs = PassiveSensingPrefs(context),
            appContext = context,
            store = EchoStateStore(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 反射调用生产私有纯函数（生产代码零改动；签名变更时本测试同步失败——故意脆）。 */
    private fun calendarDays(awakenedAtEpochMs: Long, today: LocalDate, zone: ZoneId): Int {
        val method = PresenceRepository::class.java.getDeclaredMethod(
            "maturityCalendarDays",
            Long::class.javaPrimitiveType,
            LocalDate::class.java,
            ZoneId::class.java,
        )
        method.isAccessible = true
        return method.invoke(repository, awakenedAtEpochMs, today, zone) as Int
    }

    private fun epochMs(date: String, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun missingAnchorYieldsZeroDaysAndSeedMaturity() {
        assertEquals(0, calendarDays(0L, LocalDate.of(2026, 8, 18), shanghai))
        assertEquals(0, calendarDays(-5L, LocalDate.of(2026, 8, 18), shanghai))
        assertEquals(EchoMaturity.SEED, echoMaturity(0))
    }

    @Test
    fun sameLocalDayYieldsZeroEvenHoursLater() {
        // 苏醒当天 09:00 → 同日 23:59 仍是 Day 0（SEED：苏醒当天不伪造观察）
        val anchor = epochMs("2026-08-18", 9, 0, shanghai)
        assertEquals(0, calendarDays(anchor, LocalDate.of(2026, 8, 18), shanghai))
    }

    @Test
    fun crossingMidnightAdvancesExactlyOneDay() {
        // 23:30 苏醒 → 次日即 Day 1（跨午夜边界：30 分钟也进位一个日历日）
        val anchor = epochMs("2026-08-17", 23, 30, shanghai)
        assertEquals(0, calendarDays(anchor, LocalDate.of(2026, 8, 17), shanghai))
        assertEquals(1, calendarDays(anchor, LocalDate.of(2026, 8, 18), shanghai))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(1))
    }

    @Test
    fun sameInstantDifferentZoneCanDifferByOneDay() {
        // 2026-08-18T02:00+08:00 == 2026-08-17T14:00-04:00：上海已是 18 日，纽约还是 17 日
        val instant = epochMs("2026-08-18", 2, 0, shanghai)
        val today = LocalDate.of(2026, 9, 1)
        val shanghaiDays = calendarDays(instant, today, shanghai)
        val newYorkDays = calendarDays(instant, today, newYork)
        assertEquals(14, shanghaiDays)
        assertEquals(15, newYorkDays)
        assertTrue(
            "同一 epoch 在两时区的日历天数应相差 1（装配必须走给定 zone 的本地日期）",
            kotlin.math.abs(shanghaiDays - newYorkDays) == 1,
        )
    }

    @Test
    fun dstShortenedDayStillCountsAsOneCalendarDay() {
        // America/New_York 2026-03-08 春令时（02:00→03:00，当天仅 23h）：
        // 日历天数按 LocalDate 推进，不按 24h 物理时长
        val anchor = epochMs("2026-03-07", 12, 0, newYork)
        assertEquals(2, calendarDays(anchor, LocalDate.of(2026, 3, 9), newYork))
    }

    @Test
    fun twentyEightCalendarDaysReachesMature() {
        // 日历钟可越过 28（对照：baseline.validDays 28 天窗口分桶 ≤20，永远到不了 MATURE）
        val anchor = epochMs("2026-07-21", 12, 0, shanghai)
        val days = calendarDays(anchor, LocalDate.of(2026, 8, 18), shanghai)
        assertEquals(28, days)
        assertEquals(EchoMaturity.MATURE, echoMaturity(days))
    }

    @Test
    fun futureAnchorProducesNegativeRawDaysAndCallerCoercesToSeed() {
        // 时钟回拨（锚点在未来）时私有函数返回负原始值；生产调用方 coerceAtLeast(0) 兜底
        val futureAnchor = epochMs("2026-08-20", 12, 0, shanghai)
        val raw = calendarDays(futureAnchor, LocalDate.of(2026, 8, 18), shanghai)
        assertTrue("未来锚点原始值应为负（调用方 coerce 兜底）", raw < 0)
        assertEquals(EchoMaturity.SEED, echoMaturity(raw.coerceAtLeast(0)))
    }
}
