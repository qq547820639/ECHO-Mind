package com.yunjue.echo.mind.qa
import com.yunjue.echo.mind.journey.buildJourneyDays
import org.junit.Test
class DebugJourneyTest {
    @Test fun debug() {
        val days = buildJourneyDays(QaTimeline(QaProfiles.E_PROJECT_CRUNCH).portraitsUpTo(95))
        println("total days: ${days.size}")
        // 每个窗口的非 SIMILAR 占比
        for (i in 0 until days.size - 28 step 7) {
            val b = days.subList(i, i + 14)
            val a = days.subList(i + 14, i + 28)
            fun frac(list: List<com.yunjue.echo.mind.journey.JourneyDay>): Double {
                val n = list.count { d -> d.dimensionValues.any { (k, v) -> k != "STABILITY" && v !in setOf("SIMILAR", "VERY_SIMILAR", "") } }
                return n.toDouble() / list.size
            }
            println("win $i (${b.first().date}..${a.last().date}): before=${frac(b)} after=${frac(a)}")
        }
        // 每天 RHYTHM 值
        days.forEach { d -> if (d.date >= "2026-02-28" && d.date <= "2026-03-15") println("${d.date} RHYTHM=${d.dimensionValues["RHYTHM"]} SCREEN=${d.dimensionValues["SCREEN_AMOUNT"]}") }
    }
}
