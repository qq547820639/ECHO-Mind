package com.yunjue.echo.mind.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "checkins")
data class CheckinEntity(
    @PrimaryKey val eventId: String,
    val mood: Int,
    val stress: Int,
    val energy: Int,
    val sleepRecovery: Int,
    val eventFlag: Boolean,
    val helpRequested: Boolean,
    val noteCiphertext: String?,
    val clientTimeEpochMs: Long,
    val syncState: String = "pending"
)

@Entity(tableName = "journal_entries")
data class JournalEntity(
    @PrimaryKey val eventId: String,
    val logicalId: String,
    val revision: Int,
    val bodyCiphertext: String?,
    val tagsJson: String,
    val clientTimeEpochMs: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "questionnaire_results")
data class QuestionnaireEntity(
    @PrimaryKey val eventId: String,
    val instrument: String,
    val version: String,
    val answersJson: String,
    val score: Int,
    val urgentItem: Boolean,
    val clientTimeEpochMs: Long
)

@Entity(tableName = "practice_completions")
data class PracticeCompletionEntity(
    @PrimaryKey val eventId: String,
    val practiceId: String,
    val contentVersion: String,
    val status: String,
    val durationSeconds: Int,
    val clientTimeEpochMs: Long
)

@Entity(tableName = "outbox_events")
data class OutboxEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val payloadCiphertext: String,
    val priority: Int,
    val createdAtEpochMs: Long,
    val attempts: Int = 0
)

@Dao
interface EchoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCheckin(value: CheckinEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertJournal(value: JournalEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertQuestionnaire(value: QuestionnaireEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPracticeCompletion(value: PracticeCompletionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOutbox(value: OutboxEventEntity)

    @Query("SELECT * FROM checkins ORDER BY clientTimeEpochMs DESC") fun observeCheckins(): Flow<List<CheckinEntity>>
    @Query("SELECT * FROM journal_entries WHERE deleted = 0 ORDER BY clientTimeEpochMs DESC") fun observeJournals(): Flow<List<JournalEntity>>
    @Query("SELECT * FROM questionnaire_results ORDER BY clientTimeEpochMs DESC") fun observeQuestionnaires(): Flow<List<QuestionnaireEntity>>
    @Query("SELECT * FROM practice_completions ORDER BY clientTimeEpochMs DESC") fun observePractices(): Flow<List<PracticeCompletionEntity>>
    @Query("SELECT * FROM outbox_events ORDER BY priority DESC, createdAtEpochMs ASC") suspend fun pendingOutbox(): List<OutboxEventEntity>
    @Query("DELETE FROM outbox_events WHERE eventId = :eventId") suspend fun deleteOutbox(eventId: String)
    @Query("UPDATE outbox_events SET attempts = attempts + 1 WHERE eventId = :eventId") suspend fun incrementAttempts(eventId: String)
    @Query("SELECT COUNT(*) FROM outbox_events") fun observePendingCount(): Flow<Int>
}

@Database(
    entities = [CheckinEntity::class, JournalEntity::class, QuestionnaireEntity::class, PracticeCompletionEntity::class, OutboxEventEntity::class],
    version = 2,
    exportSchema = true
)
abstract class EchoDatabase : RoomDatabase() { abstract fun dao(): EchoDao }
