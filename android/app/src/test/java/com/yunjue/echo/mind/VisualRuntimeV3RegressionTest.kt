package com.yunjue.echo.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V3 §84–§87 — Visual Runtime V3 回归门（源码结构锚点 + 纯函数锚点）。
 *
 * Compose 行为级契约由各 smoke suite 承担（EchoSceneContentSmokeTest 等）；
 * 本测试锁定结构性回归（旧 presentation 复活 / 图表回流第一视觉 / 旧管线复活）。
 */
class VisualRuntimeV3RegressionTest {

    private fun read(path: String): String = File(path).readText()

    private val scene = "src/main/java/com/yunjue/echo/mind/ui/EchoSceneScreen.kt"
    private val journey = "src/main/java/com/yunjue/echo/mind/ui/journey/JourneyScreen.kt"
    private val onboarding = "src/main/java/com/yunjue/echo/mind/ui/OnboardingScreen.kt"
    private val me = "src/main/java/com/yunjue/echo/mind/ui/me/MeScreen.kt"
    private val shell = "src/main/java/com/yunjue/echo/mind/ui/EchoMindApp.kt"

    @Test
    fun echoSceneIsNotAFeed() {
        val s = read(scene)
        assertFalse("Scene 不得回到 LazyColumn feed", s.contains("LazyColumn"))
        assertFalse("LOADING 不得用 spinner 主视觉", s.contains("CircularProgressIndicator"))
        assertTrue("Why 入口", s.contains("echo_scene_why"))
        assertTrue("Ask 入口", s.contains("echo_scene_ask"))
        assertTrue("视觉 testTag", s.contains("echo_scene_visual"))
        assertTrue("narrative testTag", s.contains("echo_scene_narrative"))
    }

    @Test
    fun journeyRootHasNoFilterChipsAndChartsStayBehindEvidence() {
        val s = read(journey)
        assertFalse("Journey root 不得有 FilterChip", s.contains("FilterChip("))
        assertTrue("scale selector testTag", s.contains("journey_scale_selector"))
        assertTrue("memory visual testTag", s.contains("journey_memory_visual"))
        // §61：chart/证据只在「查看依据」之后
        assertTrue(s.contains("if (state.showEvidence)"))
    }

    @Test
    fun meHasIntelligenceMapAndSunkControls() {
        val s = read(me)
        assertTrue("me_intelligence_map", s.contains("me_intelligence_map") || s.contains("MeIntelligenceMap"))
        assertTrue("更多控制下沉", s.contains("更多控制"))
    }

    @Test
    fun onboardingHasNoDoneAndNoLegacyPage() {
        val s = read(onboarding)
        // DONE 作为枚举值不得回归（注释中的历史说明允许存在）
        val enumBody = s.substringAfter("enum class OnboardingStep").substringBefore("}")
        assertFalse("Onboarding 不得重新加入 DONE", Regex("\\bDONE\\b").containsMatchIn(enumBody))
        assertFalse("不得回到 generic legacy Page wrapper", s.contains("Page("))
        assertTrue("2200ms 不变", s.contains("AWAKENING_DURATION_MS = 2200L"))
    }

    @Test
    fun shellKeepsThreeWorldsAndCrisisFab() {
        val s = read(shell)
        assertTrue(s.contains("ECHO(\"ECHO\"") || s.contains("Tab(val label"))
        assertTrue("危机 FAB 常驻逻辑", s.contains("shouldShowEmergencyFab"))
        assertFalse("不得增加主 Tab", s.contains("Tab.ME") && s.contains("Tab.SUPPORT"))
    }

    @Test
    fun sceneVisualRatioFunctionWithinSpec() {
        val f1 = com.yunjue.echo.mind.ui.visualFractionFor(1.0f)
        val f115 = com.yunjue.echo.mind.ui.visualFractionFor(1.15f)
        val f13 = com.yunjue.echo.mind.ui.visualFractionFor(1.30f)
        val f15 = com.yunjue.echo.mind.ui.visualFractionFor(1.50f)
        assertTrue("fontScale 1.0 目标 61.5%", f1 in 0.56f..0.64f)
        assertTrue(f115 in 0.56f..0.64f)
        assertTrue("fontScale ≥1.3 → ~52%", f13 >= 0.52f - 1e-4f && f13 < 0.56f)
        assertTrue(f15 >= 0.52f - 1e-4f && f15 < 0.56f)
    }

    @Test
    fun singleProductionVisualPipelineNotForked() {
        // §96：single production visual pipeline 未分叉——旧渲染器文件已删除，
        // 唯一帧求值器 = OrganismFrameComputer（core:visual）
        assertFalse(
            "旧 EchoSceneRenderers 不得复活",
            File("../feature/presence/src/main/java/com/yunjue/echo/mind/presence/EchoSceneRenderers.kt").exists(),
        )
        val visualRoot = File("../core/visual/src/main/java/com/yunjue/echo/mind/visual/render")
        val computers = visualRoot.listFiles()?.filter { it.name.contains("FrameComputer") } ?: emptyList()
        assertEquals("唯一帧求值器", 1, computers.size)

        // V3 §H：唯一 Presence → Visual 语义路径——GenomeDeriver 已删除，
        // production main 源码任何位置不得复活它或第二套 derive(state) 解释器。
        val androidRoot = File("..")
        val mainSources = androidRoot.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    it.absolutePath.contains("/src/main/") && !it.absolutePath.contains("/build/")
            }
            .toList()
        assertTrue("应能扫描到 production main 源码", mainSources.isNotEmpty())
        val deriverRefs = mainSources.filter { it.readText().contains("GenomeDeriver") }
        assertTrue(
            "GenomeDeriver 不得存在于任何 src/main（实际：${deriverRefs.map { it.name }}）",
            deriverRefs.isEmpty(),
        )
        val secondMappers = mainSources.filter { f ->
            Regex("""fun\s+derive\s*\(\s*state\s*:\s*EchoPresenceState""").containsMatchIn(f.readText())
        }
        assertTrue(
            "EchoVisualMapper 必须是唯一 Presence→Visual 映射（不得出现第二个 derive(state)）：${
                secondMappers.map { it.name }
            }",
            secondMappers.isEmpty(),
        )
    }

    @Test
    fun oldRenderPipelineStaysDeleted() {
        // 删除纪律：旧单环管线不得复活（§93）
        val journeyVisuals = File("../feature/journey/src/main/java/com/yunjue/echo/mind/journey/JourneyVisuals.kt")
        if (journeyVisuals.exists()) {
            assertFalse(
                "旧 computeEchoSceneFrame 不得复活于 JourneyVisuals",
                journeyVisuals.readText().contains("computeEchoSceneFrame"),
            )
        }
        assertFalse(
            "Scene 不得直接调用旧帧计算",
            read(scene).contains("computeEchoSceneFrame"),
        )
    }
}
