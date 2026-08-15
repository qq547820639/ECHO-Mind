package com.yunjue.echo.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 18 §95 — In-app Build Info（Me → About）：
 * Version / Commit / Build date 三要素非空、可验证，且不泄露敏感 CI 信息。
 */
class BuildInfoTest {

    @Test
    fun versionMatchesReleaseVersion() {
        assertEquals("0.11.0", BuildConfig.BUILD_VERSION)
    }

    @Test
    fun commitIsFortyHexOrUnknown() {
        val commit = BuildConfig.GIT_COMMIT
        assertTrue(
            "GIT_COMMIT 应为 40 位 hex 或 unknown（实际：$commit）",
            commit == "unknown" || commit.matches(Regex("[0-9a-f]{40}"))
        )
    }

    @Test
    fun buildTimestampIsPresent() {
        assertTrue(BuildConfig.BUILD_TIMESTAMP.isNotBlank())
        assertTrue(BuildConfig.BUILD_TIMESTAMP != "unknown")
    }

    @Test
    fun noSensitiveCiInfoLeaksIntoBuildInfo() {
        // §95：不泄露敏感 CI 信息（run id / secrets / token）
        val info = "${BuildConfig.GIT_COMMIT} ${BuildConfig.BUILD_TIMESTAMP} ${BuildConfig.BUILD_VERSION}"
        assertFalse(info.contains("CI_RUN_ID"))
        assertFalse(info.contains("GITHUB_RUN_ID"))
        assertFalse(info.contains("secrets", ignoreCase = true))
        assertFalse(info.contains("token", ignoreCase = true))
    }
}
