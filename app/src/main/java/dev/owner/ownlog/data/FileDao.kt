package dev.owner.ownlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: FileEntity): Long

    @Query(
        """
        SELECT * FROM files
        WHERE uploaded = 0 AND abandoned = 0
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int): List<FileEntity>

    @Query("UPDATE files SET uploaded = 1, lastError = NULL WHERE id = :id")
    suspend fun markUploaded(id: String)

    @Query("UPDATE files SET objectPath = :path WHERE id = :id")
    suspend fun updatePath(id: String, path: String)

    @Query("UPDATE files SET abandoned = 1, lastError = :error WHERE id = :id")
    suspend fun markAbandoned(id: String, error: String)

    @Query("UPDATE files SET uploadAttempts = uploadAttempts + 1, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    @Query("SELECT * FROM files WHERE eventId IN (:eventIds)")
    suspend fun forEvents(eventIds: List<String>): List<FileEntity>

    @Query("SELECT * FROM files")
    fun observeAll(): Flow<List<FileEntity>>
}
