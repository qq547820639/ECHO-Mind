package com.yunjue.echo.mind.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 16 §78/§86 — 上下文例外内容格式：
 * 组装（含日期）/ 解析（kind/note/date）/ 无日期仍合法 / 解析失败 fail-closed。
 */
class ContextExceptionsTest {

    @Test
    fun contentWithDateIsStructured() {
        val content = contextExceptionContent("travel", "出差", "2026-08-01")
        assertEquals("特殊时期：travel（出差）@2026-08-01", content)
        val info = contextExceptionInfo(content)
        assertEquals("travel", info?.kind)
        assertEquals("出差", info?.note)
        assertEquals("2026-08-01", info?.date)
    }

    @Test
    fun contentWithoutDateRemainsValid() {
        val content = contextExceptionContent("exam", "考试周", null)
        assertEquals("特殊时期：exam（考试周）", content)
        val info = contextExceptionInfo(content)
        assertEquals("exam", info?.kind)
        assertNull(info?.date)
    }

    @Test
    fun contentWithoutNoteAndDate() {
        val content = contextExceptionContent("travel", "", null)
        assertEquals("特殊时期：travel", content)
        assertEquals("travel", contextExceptionInfo(content)?.kind)
    }

    @Test
    fun blankKindFallsBackToOther() {
        val content = contextExceptionContent("", "", null)
        assertEquals("特殊时期：其他", content)
        assertEquals("其他", contextExceptionInfo(content)?.kind)
    }

    @Test
    fun invalidDateIsDropped() {
        val content = contextExceptionContent("travel", "", "08/01")
        assertEquals("特殊时期：travel", content)
        assertNull(contextExceptionInfo(content)?.date)
    }

    @Test
    fun parsingNonExceptionContentFailsClosed() {
        assertNull(contextExceptionInfo("今天天气不错。"))
        assertNull(contextExceptionInfo("反复出现的模式：…"))
        assertNull(contextExceptionInfo("画像反馈：不太像"))
    }

    @Test
    fun parsingIsDeterministic() {
        val content = "特殊时期：work_crunch（赶项目）@2026-09-01"
        assertEquals(contextExceptionInfo(content), contextExceptionInfo(content))
    }

    @Test
    fun kindLabelsAreNeutralChinese() {
        assertEquals("旅行", contextExceptionKindLabel("travel"))
        assertEquals("工作紧张期", contextExceptionKindLabel("work_crunch"))
        assertEquals("生病", contextExceptionKindLabel("illness"))
        assertEquals("未知", contextExceptionKindLabel("未知"))
        assertTrue(contextExceptionKindLabel("travel").isNotBlank())
    }
}
