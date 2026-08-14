package com.yunjue.echo.mind

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * v3.2 §4/§92 — Source Integrity Test（source = manifest = workers = package 一致性）。
 *
 * 不依赖 Gradle 编译成功才发现打包缺文件：
 * 1. Manifest 注册的每个组件（activity/service/receiver/provider）都有对应源类；
 * 2. Application 注册的每个 Worker 类都存在；
 * 3. 每个 Kotlin 文件的 package 声明与目录路径一致；
 * 4. runtime/intelligence/presence 关键类存在（防止快照缺失）。
 */
class SourceIntegrityTest {

    private val moduleRoot = File(".")
    private val srcRoot = File(moduleRoot, "src/main/java/com/yunjue/echo/mind")
    private val manifest = File(moduleRoot, "src/main/AndroidManifest.xml")

    private fun readOrFail(f: File): String {
        assertTrue("文件应存在：${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun manifestComponentsHaveSourceClasses() {
        val text = readOrFail(manifest)
        val componentPattern = Pattern.compile(
            "<(activity|service|receiver|provider)[^>]*android:name=\"\\.([A-Za-z0-9_.]+)\""
        )
        val matcher = componentPattern.matcher(text)
        var count = 0
        while (matcher.find()) {
            count++
            val className = matcher.group(2)
            val path = className.replace('.', '/') + ".kt"
            val f = File(srcRoot, path)
            assertTrue("Manifest 组件缺少源类：.$className（期望 $path）", f.exists())
        }
        assertTrue("Manifest 应至少注册若干组件", count >= 3)
    }

    @Test
    fun registeredWorkersExist() {
        // EchoMindApplication 注册的周期/一次性 Worker 必须有实现类
        val appFile = File(srcRoot, "EchoMindApplication.kt")
        val text = readOrFail(appFile)
        val workerPattern = Pattern.compile("([A-Za-z]+Worker)\\b")
        val matcher = workerPattern.matcher(text)
        val workers = mutableSetOf<String>()
        while (matcher.find()) workers.add(matcher.group(1))
        assertTrue(workers.isNotEmpty())
        for (worker in workers) {
            val found = srcRoot.walkTopDown().any { it.isFile && it.name == "$worker.kt" }
            assertTrue("Worker 已注册但缺少实现：$worker", found)
        }
    }

    @Test
    fun packageDeclarationsMatchDirectories() {
        var checked = 0
        for (f in srcRoot.walkTopDown()) {
            if (!f.isFile || f.extension != "kt") continue
            val firstLine = f.readLines().firstOrNull { it.startsWith("package ") } ?: continue
            val declared = firstLine.removePrefix("package ").trim()
            val expected = "com.yunjue.echo.mind" + f.parentFile.path
                .substringAfter("com/yunjue/echo/mind").replace('/', '.')
                .let { if (it.endsWith(".")) it.dropLast(1) else it }
            assertTrue("${f.path} package 声明与目录不一致：$declared != $expected", declared == expected)
            checked++
        }
        assertTrue("应检查到 Kotlin 文件", checked > 10)
    }

    @Test
    fun runtimeAndCoreClassesExist() {
        // v3.2 §2：runtime 快照完整性（防提示词所述缺失场景）
        for (relative in listOf(
            "runtime/EchoRuntimeCoordinator.kt",
            "presence/EchoPresenceState.kt",
            "intelligence/EchoContextCompiler.kt",
            "intelligence/EchoContextRetriever.kt",
            "memory/EchoMemory.kt",
        )) {
            assertTrue("关键类缺失：$relative", File(srcRoot, relative).exists())
        }
    }

    @Test
    fun runtimeHealthModelIsFiveState() {
        // v3.2 §3：READY/STARTING/DEGRADED/PAUSED/UNAVAILABLE 五态聚合模型
        val runtime = readOrFail(File(srcRoot, "runtime/EchoRuntimeCoordinator.kt"))
        for (state in listOf("READY", "STARTING", "DEGRADED", "PAUSED", "UNAVAILABLE")) {
            assertTrue("RuntimeComponentStatus 缺少 $state", "RuntimeComponentStatus.$state" in runtime)
        }
        assertTrue("不得取代 SensingRuntimeStatus（六态独立保留）", "SensingRuntimeStatus.STARTING" in runtime)
    }
}
