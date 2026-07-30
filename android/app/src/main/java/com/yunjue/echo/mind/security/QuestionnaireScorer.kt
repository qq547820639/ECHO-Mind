package com.yunjue.echo.mind.security

import com.yunjue.echo.mind.model.QuestionnaireScore

object QuestionnaireScorer {
    fun phq9(answers: List<Int>): QuestionnaireScore {
        require(answers.size == 9 && answers.all { it in 0..3 })
        val score = answers.sum()
        val message = when (score) {
            in 0..4 -> "当前筛查分数较低"
            in 5..9 -> "存在一些相关困扰，建议持续观察"
            in 10..14 -> "建议联系专业人员进一步了解"
            else -> "建议尽快联系专业人员进一步了解"
        }
        return QuestionnaireScore(score, message, urgentItem = answers[8] > 0)
    }

    fun gad7(answers: List<Int>): QuestionnaireScore {
        require(answers.size == 7 && answers.all { it in 0..3 })
        val score = answers.sum()
        val message = when (score) {
            in 0..4 -> "当前筛查分数较低"
            in 5..9 -> "存在一些相关困扰，建议持续观察"
            in 10..14 -> "建议联系专业人员进一步了解"
            else -> "建议尽快联系专业人员进一步了解"
        }
        return QuestionnaireScore(score, message)
    }
}
