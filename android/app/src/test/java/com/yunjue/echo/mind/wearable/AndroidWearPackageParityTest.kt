package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ERA 33 —— Android ↔ Vela 包名一致性门（Phase 7）。
 *
 * interconnect 官方身份要求：Quick App manifest.json 的 `package` 必须与
 * 手机端 Android applicationId **完全一致**，否则系统 interconnect 无法
 * 在两端建立身份关联（XIAOMI_BAND10_CAPABILITY_MATRIX §1.3）。
 *
 * 本测试读取真实 Android applicationId（BuildConfig，由 android/app/build.gradle.kts 生成）
 * 与 Vela manifest package（wearable/xiaomi-vela/src/manifest.json），
 * 不一致即 FAIL——防止两端漂移后真机 interconnect 静默失效。
 */
class AndroidWearPackageParityTest {

    /** 单测工作目录 = android/app；manfiest 相对仓库根 = wearable/xiaomi-vela/src/manifest.json。 */
    private val velaManifest = File("../../wearable/xiaomi-vela/src/manifest.json")

    @Test
    fun velaManifestPackage_equalsAndroidApplicationId() {
        assertTrue("Vela manifest 缺失：${velaManifest.canonicalFile}", velaManifest.isFile)
        val text = velaManifest.readText()
        val packageRegex = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")
        val match = packageRegex.find(text)
        assertTrue("manifest.json 必须声明 package 字段", match != null)
        val velaPackage = match!!.groupValues[1]

        assertEquals(
            "Vela manifest package 必须与 Android applicationId 完全一致（interconnect 身份要求）",
            BuildConfig.APPLICATION_ID,
            velaPackage,
        )
    }

    @Test
    fun velaManifest_declaresNoDuplicatePackage_andValidShape() {
        val text = velaManifest.readText()
        val packages = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals("manifest 中 package 字段应只出现一次", 1, packages.size)
        assertTrue(
            "包名必须形如 com.yunjue.echo.mind（无空格/斜杠）",
            packages[0].matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")),
        )
        assertFalse("manifest 不得包含占位符", packages[0].contains("\${"))
    }
}
