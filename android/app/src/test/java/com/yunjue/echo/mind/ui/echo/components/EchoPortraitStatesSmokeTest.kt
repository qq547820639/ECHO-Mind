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
 * ERA 35 — EchoPortraitStates 子组件 smoke test：
 * PortraitSummaryOnly / BaselineProgress / CoverageRow / UnlockBanner（一次性仪式语义）。
 * 纯状态渲染（PortraitUiState 注入；SeedPortraitBlock 依赖 AppPreferences 不在本层覆盖）。
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

    @Test
    fun summaryOnlyRendersPortraitSummary() {
        compose.setContent {
            MaterialTheme { PortraitSummaryOnly(portraitState(summary = "最近的作息更稳定。")) }
        }
        compose.onNodeWithText("最近的作息更稳定。").assertExists()
    }

    @Test
    fun summaryOnlyRendersNothingWhenNoPortrait() {
        compose.setContent {
            MaterialTheme {
                PortraitSummaryOnly(PortraitUiState(status = PortraitStatus.LOADING, portrait = null))
            }
        }
        compose.onNodeWithText("今天和平时很接近。").assertDoesNotExist()
    }

    @Test
    fun baselineProgressShowsAccumulatedDays() {
        compose.setContent {
            MaterialTheme { BaselineProgress(baselineDays = 3) }
        }
        compose.onNodeWithText("已积累 3/7 天，基线即将成型").assertExists()
    }

    @Test
    fun coverageRowRendersPercentage() {
        compose.setContent {
            MaterialTheme { CoverageRow(coverage = mapOf("coverage_score" to 0.42)) }
        }
        compose.onNodeWithText("今日已学习").assertExists()
        compose.onNodeWithText("42%").assertExists()
    }

    @Test
    fun coverageRowHiddenWhenNull() {
        compose.setContent {
            MaterialTheme { CoverageRow(coverage = null) }
        }
        compose.onNodeWithText("今日已学习").assertDoesNotExist()
    }

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
