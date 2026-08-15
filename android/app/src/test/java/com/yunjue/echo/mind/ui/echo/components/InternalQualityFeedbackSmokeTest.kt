package com.yunjue.echo.mind.ui.echo.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 29 §64 — 内部质量反馈 smoke（仅 DEBUG 构建渲染；记录本地翻转）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InternalQualityFeedbackSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs(): AppPreferences = AppPreferences(context, JvmTestFieldCipher())

    @Test
    fun rendersThreeQuestionsAndTogglesLocalRecord() {
        val p = prefs()
        compose.setContent {
            MaterialTheme {
                InternalQualityFeedback(preferences = p, headline = "初见。")
            }
        }
        compose.onNodeWithText("内部质量反馈（仅调试构建）").assertExists()
        compose.onNodeWithText("这个 ECHO 今天真实吗？").assertExists()
        compose.onNodeWithText("这条解释有用吗？").assertExists()
        compose.onNodeWithText("这个变化明显吗？").assertExists()

        val today = java.time.LocalDate.now().toString()
        assertTrue("初始为空", p.internalFeedbackFor(today).isEmpty())

        // 三个「是」各点一次（每次点击后文案变为「✓ 是」，索引动态取 0）→ 三个字段记录
        repeat(3) { compose.onAllNodes(androidx.compose.ui.test.hasText("是"))[0].performClick() }
        val recorded = p.internalFeedbackFor(today)
        assertTrue("记录 3 个字段（实际 $recorded）", recorded.size == 3)
    }
}
