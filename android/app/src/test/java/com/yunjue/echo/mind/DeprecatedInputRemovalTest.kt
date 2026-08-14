package com.yunjue.echo.mind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.1 §119：旧主动输入入口移除不变量回归（source-scan 版）。
 *
 * 旧时代入口（QuestionnaireScreen / RecordScreen / PracticeScreen）已删除，
 * 其停用文案常量（LegacyScreens.kt）随 ERA 12 一并删除。
 * 本测试改为**源代码事实断言**：
 * 1. 主源码中不存在主动录入写路径（saveJournal / saveQuestionnaire / recordPractice）；
 * 2. 主源码用户可见文案中不存在 legacy 产品定位词（心理健康记录 / 筛查提示）。
 * （用户可见文案的全面词表门禁另见 Phase6PsychologyReviewTest。）
 */
class DeprecatedInputRemovalTest {

    private val srcRoot = File("src/main/java/com/yunjue/echo/mind")

    private fun allKotlinFiles(): Sequence<File> = srcRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }

    @Test
    fun noActiveInputWritePathsRemain() {
        // 主动录入写路径必须已全部移除（被动感知范式唯一）
        val banned = listOf("fun saveJournal", "fun saveQuestionnaire", "fun recordPractice")
        for (f in allKotlinFiles()) {
            val text = f.readText()
            for (needle in banned) {
                assertFalse("${f.name} 不得包含主动录入写路径：$needle", needle in text)
            }
        }
    }

    @Test
    fun noLegacyProductPositioningInCopy() {
        // legacy 产品定位词不得出现在任何主源码（用户可见文案层面）
        val legacy = listOf("心理健康记录", "筛查提示", "心理记录与量表信息")
        for (f in allKotlinFiles()) {
            val text = f.readText()
            for (needle in legacy) {
                assertFalse("${f.name} 不得包含 legacy 定位词：$needle", needle in text)
            }
        }
    }

    @Test
    fun passiveSensingIsTheOnlyInputParadigm() {
        // 反向锚点：被动感知范式文案仍然存在（产品主线）
        val witness = File(srcRoot, "ui/OnboardingScreen.kt")
        assertTrue(witness.exists())
        val text = witness.readText()
        assertTrue(text.contains("安静地生活"))
        assertTrue(text.contains("它不会判断你的情绪"))
    }
}
