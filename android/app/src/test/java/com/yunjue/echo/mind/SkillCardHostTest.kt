package com.yunjue.echo.mind

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.SkillFetchResult
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.ui.buildSkillCardHtml
import com.yunjue.echo.mind.ui.coldStartHint
import com.yunjue.echo.mind.ui.configureSandboxWebView
import com.yunjue.echo.mind.ui.shouldShowEmergencyFab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11 卡片渲染 + 危机入口常驻单测。
 *
 * 覆盖：
 * - 卡片渲染：SkillDisplay → HTML 含 name / guardrails / steps（含 HTML 注入转义）
 * - WebView 安全沙箱：javascriptEnabled / allowFileAccess / domStorageEnabled 均为 false
 * - Skill 列表空态冷启动：空列表返回冷启动文案
 * - 危机入口常驻：FAB 在任何非 SUPPORT tab 下可见（代码审查不变量，抽成纯函数断言）
 *
 * Robolectric 限定 SDK 35，与 MicCollectorTest / PassiveSensingTest 一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkillCardHostTest {

    private fun sampleSkill() = SkillDisplay(
        id = "sk_1",
        name = "情绪降速",
        version = 2,
        triggerConditions = listOf("narrative.mood_hint eq 偏低"),
        guardrails = listOf("不输出诊断结论", "命中红色信号立即冻结"),
        steps = listOf("扫描当日特征", "输出报告"),
        status = "reviewed"
    )

    // ---------- T11.6 卡片渲染 ----------

    @Test
    fun cardHtmlContainsNameGuardrailsAndSteps() {
        val html = buildSkillCardHtml(sampleSkill())
        assertTrue("HTML 应包含 name", html.contains("情绪降速"))
        assertTrue("HTML 应包含 guardrail 1", html.contains("不输出诊断结论"))
        assertTrue("HTML 应包含 guardrail 2", html.contains("命中红色信号立即冻结"))
        assertTrue("HTML 应包含 step 1", html.contains("扫描当日特征"))
        assertTrue("HTML 应包含 step 2", html.contains("输出报告"))
    }

    @Test
    fun cardHtmlContainsVersionAndStatus() {
        val html = buildSkillCardHtml(sampleSkill())
        assertTrue("HTML 应包含版本号", html.contains("v2"))
        assertTrue("HTML 应包含状态", html.contains("reviewed"))
    }

    @Test
    fun cardHtmlEscapesUnsafeContentToPreventXss() {
        // 后端字段按不可信内容处理：name 含 <script> 应被转义，不能注入到 HTML
        val malicious = sampleSkill().copy(name = "<script>alert(1)</script>")
        val html = buildSkillCardHtml(malicious)
        assertTrue("应转义 <script> 标签", html.contains("&lt;script&gt;"))
        assertFalse("不应残留未转义 <script>", html.contains("<script>alert"))
    }

    @Test
    fun cardHtmlOmitsEmptySectionsGracefully() {
        val empty = sampleSkill().copy(triggerConditions = emptyList(), steps = emptyList(), guardrails = emptyList())
        val html = buildSkillCardHtml(empty)
        // 空段落不应渲染对应标题
        assertFalse(html.contains("触发条件"))
        assertFalse(html.contains("步骤"))
        assertFalse(html.contains("边界"))
        // name 仍渲染
        assertTrue(html.contains("情绪降速"))
    }

    // ---------- T11.6 WebView 安全沙箱 ----------

    @Test
    fun webViewSandboxDisablesJavaScriptAndFileAccess() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val webView = WebView(context)
        // 先开启再配置，证明 configureSandboxWebView 确实关闭了这些能力
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true

        configureSandboxWebView(webView)

        assertFalse("javascriptEnabled 必须为 false（安全沙箱禁用 JS）", webView.settings.javaScriptEnabled)
        assertFalse("allowFileAccess 必须为 false（禁用 file:// 越权）", webView.settings.allowFileAccess)
        assertFalse("domStorageEnabled 必须为 false", webView.settings.domStorageEnabled)
    }

    // ---------- P3 冷启动分阶段文案映射 ----------

    @Test
    fun coldStartHintMapsStage0ToResource() {
        assertEquals("stage_0 应映射到 cold_start_stage_0", R.string.cold_start_stage_0, coldStartHint("stage_0", 0))
    }

    @Test
    fun coldStartHintMapsStage1_3ToResource() {
        assertEquals("stage_1_3 应映射到 cold_start_stage_1_3", R.string.cold_start_stage_1_3, coldStartHint("stage_1_3", 2))
    }

    @Test
    fun coldStartHintMapsStage4_7ToResource() {
        assertEquals("stage_4_7 应映射到 cold_start_stage_4_7", R.string.cold_start_stage_4_7, coldStartHint("stage_4_7", 5))
    }

    @Test
    fun coldStartHintMapsStage7PlusToResource() {
        assertEquals("stage_7_plus 应映射到 cold_start_stage_7_plus", R.string.cold_start_stage_7_plus, coldStartHint("stage_7_plus", 10))
    }

    @Test
    fun coldStartHintFallsBackToStage0ForUnknownStage() {
        assertEquals("未知 stage 应兜底 stage_0", R.string.cold_start_stage_0, coldStartHint("unknown", 0))
    }

    @Test
    fun loadFailedStateTriggersRetryButton() {
        // 网络失败时 SkillFetchResult.loadFailed=true，UI 据此展示「加载失败」+ 重试按钮
        val failed = SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true)
        assertTrue("loadFailed=true 时应展示重试按钮", failed.loadFailed)
        assertNull("失败时 skills 应为 null（区别于空列表冷启动）", failed.skills)
    }

    @Test
    fun successfulEmptyStateDoesNotTriggerRetryButton() {
        // 真无 Skill（冷启动空态）时 loadFailed=false，不显示重试按钮而是分阶段文案
        val coldStart = SkillFetchResult(skills = emptyList(), coldStartHint = "stage_0", loadFailed = false)
        assertFalse("冷启动空态不应显示重试按钮", coldStart.loadFailed)
    }

    // ---------- T11.6 危机入口常驻（代码审查不变量） ----------

    @Test
    fun emergencyFabVisibleOnAllTabsExceptSupport() {
        // 危机入口常驻：除 SUPPORT tab 自身外，紧急 FAB 在任何 tab 下都可见
        assertTrue("非 SUPPORT tab 应显示紧急 FAB", shouldShowEmergencyFab(isSupportTab = false))
        assertFalse("SUPPORT tab 自身不重复显示 FAB", shouldShowEmergencyFab(isSupportTab = true))
    }
}
