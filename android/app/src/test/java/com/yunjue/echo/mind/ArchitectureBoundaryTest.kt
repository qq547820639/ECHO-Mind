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

    /** ERA 13.5：物理模块源码根（app + :feature:actions + :core:security；新增模块在此登记）。 */
    private val moduleRoots = listOf(
        srcRoot,
        File("../feature/actions/src/main/java/com/yunjue/echo/mind"),
        File("../core/security/src/main/java/com/yunjue/echo/mind"),
        File("../core/model/src/main/java/com/yunjue/echo/mind"),
    )

    private fun filesUnder(relativeDir: String): List<File> =
        moduleRoots.map { File(it, relativeDir) }.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

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
    fun observationDoesNotDependOnDataImplementations() {
        // ERA 13.2 §42：Ground Truth 不得 import data 实现（Domain interfaces ↑ data implementations）。
        for (f in filesUnder("sensing") + filesUnder("localportrait") + filesUnder("model")) {
            val t = textOf(f)
            assertTrue(
                "observation/${f.name} 不得依赖 data 实现：\n" + offendingLines(t, listOf("com.yunjue.echo.mind.data")),
                "com.yunjue.echo.mind.data" !in t
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
    fun intelligenceDoesNotDependOnDataImplementations() {
        // ERA 13.2 §37/§42：intelligence 只依赖 ports（ObservationEvidenceSource/EchoMemoryReader），
        // 不得 import data 实现类（LocalPortraitDataSource/MemoryRepository/...）。
        for (f in filesUnder("intelligence")) {
            val t = textOf(f)
            assertTrue(
                "intelligence/${f.name} 不得依赖 data 实现：\n" + offendingLines(t, listOf("com.yunjue.echo.mind.data")),
                "com.yunjue.echo.mind.data" !in t
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

    @Test
    fun appContainerIsCompositionRootOnly() {
        // ERA 13.3 §43/§44：Root 只组合六容器 + 跨域编排；领域对象构造归子容器。
        // 防回归：AppContainer 类体内不得直接构造数据/智能/画像实现对象。
        val t = textOf(File(srcRoot, "AppContainer.kt"))
        val forbiddenConstructors = listOf(
            "= MemoryRepository(", "= AiNarrativeService(", "= PortraitRepository(",
            "= PresenceRepository(", "= LocalPortraitDataSource(", "= EchoStateStore(",
            "= ProviderCredentialStore(", "= AiProviderManager(", "= EchoContextRetriever(",
            "= SyncStateRepository(", "= FeatureFlagRepository(", "= ConsentRepository(",
            "= SensingRepository(", "= SkillRepository(", "= EscalationRepository(",
            "= OnboardingRepository(", "= LocalDataRights(", "= MessageRepository(",
        )
        assertTrue(
            "AppContainer 不得直接构造领域对象（构造职责归子容器）：\n" +
                offendingLines(t, forbiddenConstructors),
            forbiddenConstructors.none { it in t }
        )
    }

    private fun offendingLines(text: String, needles: List<String>): String =
        text.lines().filter { line -> needles.any { it in line } }.take(3).joinToString("\n")
}
