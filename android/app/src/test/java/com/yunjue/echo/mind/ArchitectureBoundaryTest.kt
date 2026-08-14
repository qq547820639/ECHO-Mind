package com.yunjue.echo.mind

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3 §95 — 架构依赖方向测试（source-scan）。
 *
 * 永久边界：
 * - presence 渲染层不依赖 Room/数据库（渲染器只画 EchoVisualParameters）；
 * - observation（sensing/localportrait）不依赖 intelligence/affective（Ground Truth 独立）；
 * - memory 不依赖具体 Provider（Memory 是 ECHO 的，不是任何 LLM 的）；
 * - intelligence 不依赖 UI。
 */
class ArchitectureBoundaryTest {

    private val srcRoot = File("src/main/java/com/yunjue/echo/mind")

    private fun filesUnder(relativeDir: String): List<File> =
        File(srcRoot, relativeDir).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun textOf(f: File): String = f.readText()

    @Test
    fun presenceRendererDoesNotDependOnRoomOrDatabase() {
        for (f in filesUnder("presence")) {
            val t = textOf(f)
            assertTrue(
                "presence/${f.name} 不得依赖 Room：\n" + offendingLines(t, listOf("androidx.room", "EchoDatabase", "MemoryDao")),
                listOf("androidx.room", "EchoDatabase", "MemoryDao").none { it in t }
            )
        }
    }

    @Test
    fun observationDoesNotDependOnIntelligence() {
        for (f in filesUnder("sensing") + filesUnder("localportrait")) {
            val t = textOf(f)
            assertTrue(
                "observation/${f.name} 不得依赖 intelligence/affective",
                listOf("com.yunjue.echo.mind.intelligence", "affective").none { it in t }
            )
        }
    }

    @Test
    fun memoryDoesNotDependOnProviderOrIntelligence() {
        for (f in filesUnder("memory")) {
            val t = textOf(f)
            assertTrue(
                "memory/${f.name} 不得依赖 intelligence",
                "com.yunjue.echo.mind.intelligence" !in t
            )
        }
    }

    @Test
    fun intelligenceDoesNotDependOnUi() {
        for (f in filesUnder("intelligence")) {
            val t = textOf(f)
            assertTrue(
                "intelligence/${f.name} 不得依赖 ui",
                "com.yunjue.echo.mind.ui" !in t
            )
        }
    }

    @Test
    fun presenceServicesDoNotDependOnUi() {
        // v3 §48：Wallpaper/Dream 只消费状态；渲染适配器属 presence 自身
        for (f in filesUnder("presence")) {
            val t = textOf(f)
            assertTrue(
                "presence/${f.name} 不得依赖 ui 包",
                "com.yunjue.echo.mind.ui" !in t
            )
        }
    }

    @Test
    fun journeyApplicationLayerDoesNotDependOnUi() {
        // ERA 13 §27：journey 应用层（JourneyRepository/JourneyPort/装配器）不得依赖 UI；
        // 方向恒为 ui → journey（Screen/ViewModel 消费应用层状态）。
        for (f in filesUnder("journey")) {
            val t = textOf(f)
            assertTrue(
                "journey/${f.name} 不得依赖 ui 包",
                "com.yunjue.echo.mind.ui" !in t
            )
        }
    }

    private fun offendingLines(text: String, needles: List<String>): String =
        text.lines().filter { line -> needles.any { it in line } }.take(3).joinToString("\n")
}
