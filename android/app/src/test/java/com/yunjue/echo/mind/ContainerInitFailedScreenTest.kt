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
 * ERA 32 R15（真机首启闪退修复回归）：容器初始化失败不再闪退——
 * 显示可重试错误画面（异常类名可见，供反馈定位；不泄漏消息/路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContainerInitFailedScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersRecoverableErrorAndFiresCallbacks() {
        var retried = false
        var exited = false
        compose.setContent {
            MaterialTheme {
                ContainerInitFailedScreen(
                    errorClass = "KeyStoreException",
                    onRetry = { retried = true },
                    onExit = { exited = true },
                )
            }
        }
        compose.onNodeWithText("ECHO 没能安全地启动").assertExists()
        compose.onNodeWithText("本机安全存储初始化失败（KeyStoreException）。你的数据没有丢失，也没有被降级处理。\n请把这一行完整信息反馈给开发团队；重试可能无法解决，需按设备针对性修复。").assertExists()
        compose.onNodeWithText("设备：", substring = true).assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("退出").performClick()
        assertTrue("重试回调应触发", retried)
        assertTrue("退出回调应触发", exited)
    }
}
