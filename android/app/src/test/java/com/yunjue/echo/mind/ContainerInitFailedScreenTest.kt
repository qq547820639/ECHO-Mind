package com.yunjue.echo.mind

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 32 R15（真机首启闪退修复回归）：容器初始化失败不再闪退——
 * 显示可重试错误画面（异常类名可见，供反馈定位）。
 *
 * V3 §BI：debug 保留原文（message 可见）；release 清洗——
 * 不暴露 message/路径/堆栈，仅 异常类 + 稳定错误码 + API + 版本，可导出已清洗诊断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContainerInitFailedScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun debugBuildRendersRecoverableErrorAndFiresCallbacks() {
        var retried = false
        var exited = false
        compose.setContent {
            MaterialTheme {
                ContainerInitFailedScreen(
                    errorClass = "KeyStoreException",
                    onRetry = { retried = true },
                    onExit = { exited = true },
                    isDebugBuild = true,
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

    @Test
    fun debugBuildKeepsRawDetail() {
        compose.setContent {
            MaterialTheme {
                ContainerInitFailedScreen(
                    errorClass = "KeyStoreException",
                    errorDetail = "keystore broken",
                    onRetry = {},
                    onExit = {},
                    isDebugBuild = true,
                )
            }
        }
        compose.onNodeWithText("keystore broken", substring = true).assertExists()
    }

    @Test
    fun releaseBuildSanitizesDetailAndExportsDiagnostics() {
        compose.setContent {
            MaterialTheme {
                ContainerInitFailedScreen(
                    errorClass = "KeyStoreException",
                    errorDetail = "secret message /data/user/0/x",
                    onRetry = {},
                    onExit = {},
                    isDebugBuild = false,
                )
            }
        }
        compose.onNodeWithText("ECHO 没能安全地启动").assertExists()
        compose.onNodeWithText("secret message /data/user/0/x", substring = true).assertDoesNotExist()
        compose.onNodeWithText("代码 ", substring = true).assertExists()
        compose.onNodeWithText("环境：Android API ", substring = true).assertExists()
        val file = File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "echo_init_diagnostics.txt",
        )
        compose.onNodeWithText("导出诊断报告").performClick()
        assertTrue("导出应写入诊断文件", file.exists())
        val content = file.readText()
        assertTrue(content.contains("class=KeyStoreException"))
        assertTrue(content.contains("code="))
        assertTrue(content.contains("api="))
        assertTrue(content.contains("version="))
        assertFalse("诊断文件不得包含 message", content.contains("secret"))
    }
}
