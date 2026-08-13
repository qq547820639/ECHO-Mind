package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.security.FieldCipher
import kotlinx.coroutines.flow.Flow

/**
 * 主动输入遗留透传仓库（v0.8 删除目标）。
 *
 * 只读透传旧的 checkin / journal / questionnaire / practice 表与日志解密。
 * 主动输入范式已停用（后端 410 存根），本仓库仅承载历史只读展示，待 v0.8 删除。
 */
class LegacyInputRepository(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
) {
    fun observeCheckins(): Flow<List<CheckinEntity>> = db.dao().observeCheckins()

    fun observeJournals(): Flow<List<JournalEntity>> = db.dao().observeJournals()

    fun observeQuestionnaires(): Flow<List<QuestionnaireEntity>> = db.dao().observeQuestionnaires()

    fun observePractices(): Flow<List<PracticeCompletionEntity>> = db.dao().observePractices()

    fun decryptJournal(value: JournalEntity): String = value.bodyCiphertext?.let(cipher::decrypt).orEmpty()
}
