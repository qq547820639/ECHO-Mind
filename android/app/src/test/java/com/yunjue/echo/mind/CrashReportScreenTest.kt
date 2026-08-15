package com.yunjue.echo.mind

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 32 R18（无 adb 崩溃反馈闭环）：上次未捕获异常写入本地日志后，
 * 下次打开显示完整堆栈，供设备持有者复制反馈；清除后重试、退出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersCrashTextAndFiresCallbacks() {
        var cleared = false
        var exited = false
        compose.setContent {
            MaterialTheme {
                CrashReportScreen(
                    text = "time=1\ndevice=nubia NX809J\njava.lang.RuntimeException: boom",
                    onClearAndRetry = { cleared = true },
                    onExit = { exited = true },
                )
            }
        }
        compose.onNodeWithText("上次打开时出现了问题").assertExists()
        compose.onNodeWithText("time=1\ndevice=nubia NX809J\njava.lang.RuntimeException: boom").assertExists()
        compose.onNodeWithText("复制全文").assertExists()
        compose.onNodeWithText("清除并重试").performClick()
        compose.onNodeWithText("退出").performClick()
        assertTrue("清除并重试回调应触发", cleared)
        assertTrue("退出回调应触发", exited)
    }
}
