package com.yunjue.echo.mind

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * v3.3 §6/§93 — Source Integrity Test（自动发现，不靠手工维护路径清单）。
 *
 * 1. Manifest 注册的每个组件（activity/service/receiver/provider）都有对应源类；
 * 2. Application 注册的每个 Worker 类都存在；
 * 3. 每个 Kotlin 文件的 package 声明与目录路径一致；
 * 4. project-local 引用（import + 全限定）目标存在（符号声明索引 + 豁免规则）；
 * 5. Room DAO 抽象函数（abstract fun xxxDao）有对应接口/类；
 * 6. 必需领域包存在；runtime 五态健康模型完整。
 */
class SourceIntegrityTest {

    private val srcRoot = File("src/main/java/com/yunjue/echo/mind")
    private val manifest = File("src/main/AndroidManifest.xml")

    /** ERA 13.5：物理模块源码根（app + feature/core modules；新增模块在此登记）。 */
    private val moduleRoots = listOf(
        srcRoot,
        File("../feature/actions/src/main/java/com/yunjue/echo/mind"),
        File("../core/security/src/main/java/com/yunjue/echo/mind"),
        File("../core/model/src/main/java/com/yunjue/echo/mind"),
        File("../feature/memory/src/main/java/com/yunjue/echo/mind"),
        File("../feature/observation/src/main/java/com/yunjue/echo/mind"),
        File("../feature/presence/src/main/java/com/yunjue/echo/mind"),
        File("../feature/presencevisual/src/main/java/com/yunjue/echo/mind"),
        File("../core/visual/src/main/java/com/yunjue/echo/mind"),
        File("../core/ports/src/main/java/com/yunjue/echo/mind"),
        File("../feature/intelligence/src/main/java/com/yunjue/echo/mind"),
        File("../feature/journey/src/main/java/com/yunjue/echo/mind"),
        File("../feature/wearable/src/main/java/com/yunjue/echo/mind"),
    )

    private fun allKotlinFiles(): List<File> =
        moduleRoots.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    private fun readOrFail(f: File): String {
        assertTrue("文件应存在：${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun manifestComponentsHaveSourceClasses() {
        val text = readOrFail(manifest)
        val matcher = Pattern.compile(
            "<(activity|service|receiver|provider)[^>]*android:name=\"\\.([A-Za-z0-9_.]+)\""
        ).matcher(text)
        var count = 0
        while (matcher.find()) {
            count++
            val path = matcher.group(2).replace('.', '/') + ".kt"
            val found = moduleRoots.any { File(it, path).exists() }
            assertTrue("Manifest 组件缺少源类：.${matcher.group(2)}", found)
        }
        assertTrue("Manifest 应至少注册若干组件", count >= 3)
    }

    @Test
    fun registeredWorkersExist() {
        val appFile = File(srcRoot, "EchoMindApplication.kt")
        val text = readOrFail(appFile)
        val workers = Pattern.compile("([A-Za-z]+Worker)\\b").matcher(text)
            .let { m ->
                val set = mutableSetOf<String>()
                while (m.find()) set.add(m.group(1))
                set
            }
        assertTrue(workers.isNotEmpty())
        for (worker in workers) {
            val found = allKotlinFiles().any { it.name == "$worker.kt" }
            assertTrue("Worker 已注册但缺少实现：$worker", found)
        }
    }

    @Test
    fun packageDeclarationsMatchDirectories() {
        var checked = 0
        for (f in allKotlinFiles()) {
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
    fun projectLocalReferencesResolve() {
        // 符号声明索引：class/interface/object/fun/val/typealias/enum class（含修饰符与扩展属性）
        val declarations = mutableMapOf<String, File>()
        val declPattern = Pattern.compile(
            "\\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|value|suspend)\\s+)*" +
                "(?:const\\s+)?(?:data\\s+)?(?:fun\\s+interface|class|interface|object|fun|val|var|typealias|enum class)\\s+" +
                "(?:<[^>]+>\\s+)?" + // 泛型函数（fun <A, B, ...> combine7）
                "(?:[\\w.]+\\.)?([A-Za-z_][\\w]*)"
        )
        for (f in allKotlinFiles()) {
            for (line in f.readLines()) {
                val m = declPattern.matcher(line)
                if (m.find()) declarations.putIfAbsent(m.group(1), f)
            }
        }
        val refPattern = Pattern.compile(
            "(?:import\\s+)?com\\.yunjue\\.echo\\.mind\\.([\\w.]+)\\.([A-Za-z_][\\w]*)"
        )
        val unresolved = mutableListOf<String>()
        for (f in allKotlinFiles()) {
            val text = f.readText()
            val m = refPattern.matcher(text)
            while (m.find()) {
                if (m.group(1) == "BuildConfig") continue                    // 生成字段
                if (text.substring(0, m.start()).count { it == '"' } % 2 == 1) continue // 字符串字面量内
                val name = m.group(2)
                if (name == name.uppercase()) continue                        // 枚举条目/常量
                val line = text.substring(0, m.start()).substringAfterLast('\n')
                if (line.trim().startsWith("package")) continue
                if (line.trim().startsWith("import") && text.startsWith(".*", m.end())) continue
                val tail = text.substring(m.end()).trimStart()
                if (tail.startsWith("(")) continue                           // 已知对象方法调用
                if (name !in declarations) {
                    unresolved.add("${f.name} → $name（来自 ${m.group(1)}）")
                }
            }
        }
        assertTrue("存在无法解析的 project-local 引用：\n" + unresolved.joinToString("\n"), unresolved.isEmpty())
    }

    @Test
    fun roomDaoAbstractionsExist() {
        val db = readOrFail(File(srcRoot, "data/EchoDatabase.kt"))
        val matcher = Pattern.compile("abstract\\s+fun\\s+([A-Za-z]+Dao)\\(\\)").matcher(db)
        var count = 0
        while (matcher.find()) {
            count++
            val name = matcher.group(1)
            val found = allKotlinFiles().any { f ->
                val text = f.readText()
                text.contains("interface $name", ignoreCase = true) ||
                    text.contains("abstract class $name", ignoreCase = true) ||
                    text.contains("interface ${name.capitalize()}")
            }
            assertTrue("Room 抽象 DAO 缺少定义：$name", found)
        }
        assertTrue("应至少存在一个 Room DAO 抽象函数", count >= 2)
    }

    @Test
    fun requiredDomainPackagesExist() {
        for (domain in listOf(
            "sensing", "localportrait", "presence", "intelligence", "memory",
            "actions", "journey", "runtime", "visual", "presencevisual", "wearable",
        )) {
            val dirs = moduleRoots.map { File(it, domain) }.filter { it.isDirectory }
            assertTrue("领域包缺失：$domain", dirs.isNotEmpty())
            assertTrue("领域包为空：$domain", dirs.any { dir -> dir.walkTopDown().any { it.isFile && it.extension == "kt" } })
        }
    }

    @Test
    fun runtimeHealthModelIsFiveState() {
        val runtime = readOrFail(File(srcRoot, "runtime/EchoRuntimeCoordinator.kt"))
        for (state in listOf("READY", "STARTING", "DEGRADED", "PAUSED", "UNAVAILABLE")) {
            assertTrue("RuntimeComponentStatus 缺少 $state", "RuntimeComponentStatus.$state" in runtime)
        }
        assertTrue("不得取代 SensingRuntimeStatus（六态独立保留）", "SensingRuntimeStatus.STARTING" in runtime)
    }

    /** 提取方法体文本（从签名行到 companion object 边界的宽松截取）。 */
    private fun methodBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("应找到方法：$signature", start >= 0)
        val rest = text.substring(start)
        val companion = rest.indexOf("\n    companion object")
        return if (companion > 0) rest.substring(0, companion) else rest
    }

    @Test
    fun rebuildTodayPortraitNetworkSectionRunsOnIoDispatcher() {
        // P1-5 回归：订阅模式「重新生成画像」的阻塞网络调用（apiClient.rebuildPortrait，
        // HttpURLConnection）必须在 withContext(Dispatchers.IO) 内执行——主线程调用会抛
        // NetworkOnMainThreadException 且被 catch 吞掉（静默降级本地重算）。
        // Robolectric 不模拟 NetworkOnMainThreadException，故以源码结构断言锁定调度契约。
        val text = readOrFail(File(srcRoot, "data/PortraitRepository.kt"))
        val body = methodBody(text, "suspend fun rebuildTodayPortrait")
        val io = body.indexOf("withContext(Dispatchers.IO)")
        val call = body.indexOf("apiClient.rebuildPortrait()")
        assertTrue("rebuildTodayPortrait 应包含 withContext(Dispatchers.IO)", io >= 0)
        assertTrue("apiClient.rebuildPortrait() 应在 withContext(Dispatchers.IO) 块内执行", call > io)
    }

    @Test
    fun eveningReminderSchedulesNextAfterReminderNotBefore() {
        // P1-7 回归：scheduleNext(REPLACE) 若位于 doWork 首行会取消正在运行的自身（挂起点抛
        // CancellationException → 21:00 提醒静默丢失）；必须位于 finally——完成本次提醒
        //（含所有提前 return 路径）后再排明晚。
        val text = readOrFail(File(srcRoot, "EveningReminderWorker.kt"))
        val body = methodBody(text, "override suspend fun doWork")
        val notify = body.indexOf("nm.notify(")
        val schedule = body.indexOf("scheduleNext(")
        val finally = body.indexOf("finally")
        assertTrue("doWork 应有 finally 块承载自续期", finally >= 0)
        assertTrue("scheduleNext 应位于 finally 块内", schedule > finally)
        assertTrue("排下一次应在发送提醒之后（scheduleNext 位于 notify 之后）", schedule > notify)
    }
}
