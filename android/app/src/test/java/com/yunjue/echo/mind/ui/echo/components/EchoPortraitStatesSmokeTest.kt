package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_COPY_BASELINE_UNLOCKED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 35 — EchoPortraitStates 子组件 smoke test：UnlockBanner（一次性仪式语义）。
 * 纯状态渲染（PortraitUiState 注入）。PortraitSummaryOnly / SeedPortraitBlock
 * 已随生产死代码删除（Scene 九态收敛后无调用），对应用例同步移除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoPortraitStatesSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun portraitState(
        summary: String = "今天和平时很接近。",
        baselineDays: Int = 7,
        coverage: Map<String, Any>? = null,
        status: PortraitStatus = PortraitStatus.READY,
    ) = PortraitUiState(
        status = status,
        portrait = DailyPortraitDto(
            date = "2026-08-15",
            status = "READY",
            confidence = "HIGH",
            baselineDays = baselineDays,
            headline = listOf("接近"),
            summary = summary,
            dimensions = emptyMap(),
            coverage = coverage,
        ),
    )

    // ERA 31 R28：BaselineProgress / CoverageRow 已从 Scene 移除（§9/§13 禁进度条与
    // 覆盖率仪表盘），对应 smoke 用例随组件删除——「它在记录」由当天事实句承担。

    @Test
    fun unlockBannerShowsOnceWhenBaselineReadyAndNotConsumed() {
        compose.setContent {
            MaterialTheme {
                UnlockBanner(
                    consumeUnlocked = { true },
                    state = portraitState(baselineDays = 7, status = PortraitStatus.READY),
                )
            }
        }
        compose.onNodeWithText(PORTRAIT_COPY_BASELINE_UNLOCKED).assertExists()
    }

    @Test
    fun unlockBannerHiddenWhenAlreadyConsumed() {
        compose.setContent {
            MaterialTheme {
                UnlockBanner(
                    consumeUnlocked = { false },
                    state = portraitState(baselineDays = 7, status = PortraitStatus.READY),
                )
            }
        }
        compose.onNodeWithText(PORTRAIT_COPY_BASELINE_UNLOCKED).assertDoesNotExist()
    }

    @Test
    fun unlockBannerHiddenBeforeSevenDays() {
        compose.setContent {
            MaterialTheme {
                UnlockBanner(
                    consumeUnlocked = { true },
                    state = portraitState(baselineDays = 5, status = PortraitStatus.READY),
                )
            }
        }
        compose.onNodeWithText(PORTRAIT_COPY_BASELINE_UNLOCKED).assertDoesNotExist()
    }

    @Test
    fun unlockBannerConsumeCalledWhenReady() {
        var consumed = false
        compose.setContent {
            MaterialTheme {
                UnlockBanner(
                    consumeUnlocked = { consumed = true; true },
                    state = portraitState(baselineDays = 7, status = PortraitStatus.READY),
                )
            }
        }
        compose.waitForIdle()
        org.junit.Assert.assertTrue(consumed)
    }
}
