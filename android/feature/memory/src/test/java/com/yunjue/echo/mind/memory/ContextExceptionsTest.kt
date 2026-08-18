package com.yunjue.echo.mind.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T5-P2-3 — 上下文例外 note 自由文本 roundtrip：
 * note 含（ ）@ 等保留字符时 encode→decode 必须闭合（日期与 kind 不丢）。
 */
class ContextExceptionsTest {

    @Test
    fun plainRoundtripWithoutNote() {
        val content = contextExceptionContent("travel", "", "2026-08-01")
        val info = contextExceptionInfo(content)
        assertEquals("travel", info?.kind)
        assertEquals("", info?.note)
        assertEquals("2026-08-01", info?.date)
    }

    @Test
    fun plainRoundtripWithNoteAndNoDate() {
        val content = contextExceptionContent("travel", "上海出差", null)
        val info = contextExceptionInfo(content)
        assertEquals("travel", info?.kind)
        assertEquals("上海出差", info?.note)
        assertNull(info?.date)
    }

    @Test
    fun noteWithFullWidthParenthesesRoundtrips() {
        // 此前：note 含（ ）时 regex matchEntire 失败 → null，日期与 kind 双丢
        val content = contextExceptionContent("travel", "见客户（重要）", "2026-08-01")
        val info = contextExceptionInfo(content)
        assertEquals("travel", info?.kind)
        assertEquals("见客户（重要）", info?.note)
        assertEquals("2026-08-01", info?.date)
    }

    @Test
    fun noteWithAtSignRoundtrips() {
        val content = contextExceptionContent("work_crunch", "值班 @公司", "2026-08-02")
        val info = contextExceptionInfo(content)
        assertEquals("work_crunch", info?.kind)
        assertEquals("值班 @公司", info?.note)
        assertEquals("2026-08-02", info?.date)
    }

    @Test
    fun noteWithReservedCharsAndNoDateRoundtrips() {
        val content = contextExceptionContent("exam", "期末（高数 @教学楼 201）", null)
        val info = contextExceptionInfo(content)
        assertEquals("exam", info?.kind)
        assertEquals("期末（高数 @教学楼 201）", info?.note)
        assertNull(info?.date)
    }

    @Test
    fun noteEndingWithParenAndDateLikeSuffixRoundtrips() {
        // 歧义边界：note 以 ）@yyyy-MM-dd 结尾且真实无日期——贪婪取最长 note，不误截
        val content = contextExceptionContent("event", "补班）@2026-08-03", null)
        val info = contextExceptionInfo(content)
        assertEquals("event", info?.kind)
        assertEquals("补班）@2026-08-03", info?.note)
        assertNull(info?.date)
    }

    @Test
    fun nonContextFormatStillReturnsNull() {
        assertNull(contextExceptionInfo("普通记忆内容"))
        assertNull(contextExceptionInfo("特殊时期：travel（unclosed"))
    }
}
