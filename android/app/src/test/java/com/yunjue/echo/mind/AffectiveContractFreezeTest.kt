package com.yunjue.echo.mind

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AFFECTIVE_CONTRACT §11 冻结执行：在 §8（临床/安全评审定稿）+ §9（PIPIA 审计）+
 * §10（错误恢复前置）全部完成前，系统内 `affectiveState` 必须恒为 null，
 * 任何 AffectiveState 的**非空构造**或**非 null 赋值**都是发布阻断项。
 *
 * 本测试扫描全部 main 源码，属「冻结契约的编译器外强制」：
 * - AffectiveState( 只允许出现在 EchoPresenceState.kt 的 data class 定义处；
 * - 任何 `affectiveState =` 赋值只允许是 `affectiveState = null`。
 */
class AffectiveContractFreezeTest {

    private val srcRoots = listOf(
        File("src/main/java/com/yunjue/echo/mind"),
        File("../feature/actions/src/main/java/com/yunjue/echo/mind"),
        File("../core/security/src/main/java/com/yunjue/echo/mind"),
        File("../core/model/src/main/java/com/yunjue/echo/mind"),
        File("../feature/memory/src/main/java/com/yunjue/echo/mind"),
        File("../feature/observation/src/main/java/com/yunjue/echo/mind"),
        File("../feature/presence/src/main/java/com/yunjue/echo/mind"),
        File("../core/ports/src/main/java/com/yunjue/echo/mind"),
        File("../feature/intelligence/src/main/java/com/yunjue/echo/mind"),
        File("../feature/journey/src/main/java/com/yunjue/echo/mind"),
    )

    private fun allMainSources(): List<File> =
        srcRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    @Test
    fun affectiveStateStaysNullEverywhere() {
        val violations = mutableListOf<String>()
        for (file in allMainSources()) {
            val text = file.readText()
            text.lineSequence().forEachIndexed { index, line ->
                val no = index + 1
                if ("AffectiveState(" in line && !line.contains("data class AffectiveState")) {
                    violations.add("${file.path}:$no 非空构造 AffectiveState（§11 冻结）")
                }
                if ("affectiveState =" in line && "= null" !in line) {
                    violations.add("${file.path}:$no 非 null 赋值 affectiveState（§11 冻结）")
                }
            }
        }
        assertTrue(
            "AFFECTIVE_CONTRACT §11 冻结被违反（§8/§9/§10 评审门槛完成前 affectiveState 恒 null）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun contractGateCommentRemainsInPlace() {
        val presence = File("src/main/java/com/yunjue/echo/mind/data/PresenceRepository.kt")
        assertTrue("PresenceRepository 必须保留契约冻结注释", presence.readText().contains("affectiveState = null"))
    }
}
