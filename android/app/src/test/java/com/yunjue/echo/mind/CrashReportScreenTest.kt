package com.yunjue.echo.mind

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 32 R18（无 adb 崩溃反馈闭环）：上次未捕获异常写入本地日志后，
 * 下次打开显示完整堆栈，供设备持有者复制反馈；清除后重试、退出。
 *
 * V3 §BJ：release 不再显示/复制原文——只渲染清洗摘要（异常类 + 首帧，
 * 无消息体/绝对路径），「复制全文」隐藏，改为导出已清洗诊断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun debugBuildRendersRawTextAndFiresCallbacks() {
        val raw = "time=1\ndevice=nubia NX809J\njava.lang.RuntimeException: boom"
        var cleared = false
        var exited = false
        compose.setContent {
            MaterialTheme {
                CrashReportScreen(
                    text = raw,
                    onClearAndRetry = { cleared = true },
                    onExit = { exited = true },
                    isDebugBuild = true,
                )
            }
        }
        compose.onNodeWithText("上次打开时出现了问题").assertExists()
        compose.onNodeWithText(raw).assertExists()
        compose.onNodeWithText("复制全文").assertExists()
        compose.onNodeWithText("清除并重试").performClick()
        compose.onNodeWithText("退出").performClick()
        assertTrue("清除并重试回调应触发", cleared)
        assertTrue("退出回调应触发", exited)
    }

    @Test
    fun releaseBuildShowsSanitizedSummaryOnly() {
        val raw = "time=1\ndevice=nubia NX809J\n" +
            "java.lang.RuntimeException: /data/user/0/boom message\n" +
            "  at com.yunjue.echo.mind.Foo(Foo.kt:42)\n" +
            "  at java.lang.Bar(Bar.kt:7)"
        compose.setContent {
            MaterialTheme {
                CrashReportScreen(
                    text = raw,
                    onClearAndRetry = {},
                    onExit = {},
                    isDebugBuild = false,
                )
            }
        }
        compose.onNodeWithText("上次打开时出现了问题").assertExists()
        compose.onNodeWithText("java.lang.RuntimeException", substring = true).assertExists()
        compose.onNodeWithText("复制全文").assertDoesNotExist()
        compose.onNodeWithText("/data/user/0", substring = true).assertDoesNotExist()
        compose.onNodeWithText("boom message", substring = true).assertDoesNotExist()
        compose.onNodeWithText("导出已清洗诊断").assertExists()
        compose.onNodeWithText("清除并重试").assertExists()
        compose.onNodeWithText("退出").assertExists()
    }

    @Test
    fun sanitizerKeepsExceptionClassAndFirstFrameOnly() {
        val raw = "time=123\n" +
            "device=nubia NX809J · Android 15 (API 35)\n" +
            "thread=Thread[main,5,main]\n" +
            "java.lang.RuntimeException: secret /data/user/0/app boom\n" +
            "  at com.yunjue.echo.mind.Foo(Foo.kt:42)\n" +
            "  at java.lang.Bar.bar(Bar.kt:7)"
        assertEquals(
            "java.lang.RuntimeException\nat com.yunjue.echo.mind.Foo(Foo.kt:42)",
            CrashReportSanitizer.sanitize(raw),
        )
    }

    @Test
    fun sanitizerStripsAbsolutePathsFromFrames() {
        val raw = "java.lang.Exception: x\n" +
            "  at /data/app/com.yunjue.echo.mind/files/Foo.bar(Foo.kt:1)"
        val sanitized = CrashReportSanitizer.sanitize(raw)
        assertFalse(sanitized.contains("/data/app"))
        assertTrue(sanitized.contains("Foo.bar(Foo.kt:1)"))
    }

    @Test
    fun sanitizerCapsLength() {
        val raw = (1..500).joinToString("\n") { "java.lang.RuntimeException: msg $it" }
        val sanitized = CrashReportSanitizer.sanitize(raw)
        assertTrue(sanitized.length < raw.length)
        assertTrue(sanitized.contains("已截断"))
    }
}
