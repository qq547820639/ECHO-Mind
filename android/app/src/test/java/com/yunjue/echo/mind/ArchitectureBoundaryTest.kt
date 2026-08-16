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

    /**
     * ERA 32 R10：production 模块源码根自动发现自 settings.gradle.kts（与 Source Reality 生成器同源纪律），
     * 禁止手写模块清单；:feature:qa 不属于 Production Runtime，不进入扫描根。
     */
    private val moduleRoots: List<File> = run {
        val settings = File("../settings.gradle.kts").readText()
        Regex("""include\("?([\w:]+)"?\)""").findAll(settings)
            .map { it.groupValues[1].substringAfter(':') }
            .filter { it != "feature:qa" && it != "qa" }
            .map { name ->
                if (name == "app") File("src/main/java/com/yunjue/echo/mind")
                else File("..", name.replace(':', '/') + "/src/main/java/com/yunjue/echo/mind")
            }
            .filter { it.isDirectory }
            .toList()
    }

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
            "= JourneyRepository(", "= JourneyMemoryRepository(",
        )
        assertTrue(
            "AppContainer 不得直接构造领域对象（构造职责归子容器）：\n" +
                offendingLines(t, forbiddenConstructors),
            forbiddenConstructors.none { it in t }
        )
    }

    @Test
    fun productionModulesNeverDependOnQaPackage() {
        // ERA 32 R10（§35/§6）：QA 不属于 Production Runtime——任何 production 模块不得引用
        // com.yunjue.echo.mind.qa（QA 代码不得进入正式 APK；dependency + 包引用双保险）。
        for (root in moduleRoots) {
            for (f in root.walkTopDown()) {
                if (!f.isFile || f.extension != "kt") continue
                assertTrue(
                    "production/${f.name} 不得引用 com.yunjue.echo.mind.qa",
                    "com.yunjue.echo.mind.qa" !in f.readText(),
                )
            }
        }
    }

    @Test
    fun wearableDomainDependsOnlyOnCoreBoundaries() {
        // ERA 33：:feature:wearable 只依赖 :core:model + :core:ports。
        // 禁止：:app 数据实现（Room/EchoDatabase/data 包）、intelligence、actions、UI、
        // 以及任何 vendor SDK 实现（vendor 适配属 :app adapter 层）。
        for (f in filesUnder("wearable")) {
            val t = textOf(f)
            val forbidden = listOf(
                "com.yunjue.echo.mind.data",
                "androidx.room",
                "EchoDatabase",
                "com.yunjue.echo.mind.intelligence",
                "com.yunjue.echo.mind.actions",
                "com.yunjue.echo.mind.ui",
                "com.xiaomi",
                "xiaomi.wearable",
            )
            assertTrue(
                "wearable/${f.name} 不得依赖 app/Room/intelligence/actions/UI/vendor SDK：\n" +
                    offendingLines(t, forbidden),
                forbidden.none { it in t },
            )
        }
    }

    @Test
    fun wearableProtocolNeverSerializesPrivateState() {
        // ERA 33 隐私边界（结构保证）：WearPresenceEnvelope 投影器不得引用
        // privateNarrative / affectiveState / EchoMemory（手环 payload 的 PUBLIC_SAFE 白名单）。
        // 只扫描代码行（剥离 KDoc/注释，禁令说明本身会提及这些词）。
        for (f in filesUnder("wearable")) {
            val t = textOf(f)
            if (!f.name.contains("Envelope") && !f.name.contains("Projector") &&
                !f.name.contains("Runtime") && !f.name.contains("Codec") &&
                !f.name.contains("Policy")
            ) {
                continue
            }
            val codeLines = t.lines().filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("*") && !trimmed.startsWith("//") && !trimmed.startsWith("/*")
            }.joinToString("\n")
            assertTrue(
                "wearable/${f.name} 协议层不得序列化 privateNarrative/affectiveState/Memory",
                listOf("privateNarrative", "affectiveState", "EchoMemory(").none { it in codeLines },
            )
        }
    }

    private fun offendingLines(text: String, needles: List<String>): String =
        text.lines().filter { line -> needles.any { it in line } }.take(3).joinToString("\n")
}
