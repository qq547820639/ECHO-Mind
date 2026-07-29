package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.security.QuestionnaireScorer
import com.yunjue.echo.mind.security.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineTest {
    @Test fun redSignalFreezesGeneration() {
        val result = SafetyEngine.evaluate("我已经准备好工具，今晚结束生命")
        assertEquals(Severity.RED, result.severity)
        assertTrue(result.freezeGeneration)
    }

    @Test fun negatedRiskStillRequestsHumanReview() {
        val result = SafetyEngine.evaluate("我没有想死，但最近很难受")
        assertEquals(Severity.YELLOW, result.severity)
        assertFalse(result.freezeGeneration)
    }

    @Test fun exitIsDeterministic() {
        val result = SafetyEngine.evaluate("退出")
        assertEquals(Severity.EXIT, result.severity)
        assertTrue(result.freezeGeneration)
    }

    @Test fun phq9UrgentItem() {
        val result = QuestionnaireScorer.phq9(listOf(0, 0, 0, 0, 0, 0, 0, 0, 1))
        assertTrue(result.urgentItem)
        assertEquals(1, result.score)
    }

    @Test fun gad7ScoreIsDeterministic() {
        val result = QuestionnaireScorer.gad7(listOf(1, 1, 1, 1, 1, 1, 1))
        assertEquals(7, result.score)
        assertFalse(result.urgentItem)
    }
}
