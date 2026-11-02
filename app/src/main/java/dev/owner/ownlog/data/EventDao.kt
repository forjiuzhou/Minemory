package dev.owner.ownlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query(
        """
        SELECT * FROM events
        WHERE uploaded = 0 AND abandoned = 0
        ORDER BY occurredAt ASC
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int): List<EventEntity>

    @Query("UPDATE events SET uploaded = 1, lastError = NULL WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("UPDATE events SET abandoned = 1, lastError = :error WHERE id = :id")
    suspend fun markAbandoned(id: String, error: String)

    @Query("UPDATE events SET uploadAttempts = uploadAttempts + 1, lastError = :error WHERE id IN (:ids)")
    suspend fun markAttemptFailed(ids: List<String>, error: String)

    @Query("DELETE FROM events WHERE uploaded = 1 AND occurredAt < :before")
    suspend fun pruneUploadedBefore(before: Long)

    @Query("SELECT COUNT(*) FROM events WHERE occurredAt >= :start")
    fun countSince(start: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM events WHERE type = 'screen_ocr' AND occurredAt >= :start")
    fun countOcrSince(start: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM events WHERE uploaded = 0 AND abandoned = 0")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM events ORDER BY occurredAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EventEntity>>

    @Query(
        """
        SELECT * FROM events
        WHERE type IN ('note', 'photo', 'voice', 'share')
        ORDER BY occurredAt DESC
        LIMIT :limit
        """,
    )
    fun recentInbox(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun find(id: String): EventEntity?

    @Query("UPDATE events SET title = :title, content = :content, extrasJson = :extras WHERE id = :id")
    suspend fun updatePayload(id: String, title: String?, content: String?, extras: String?)
}
