package com.tribalflix.app.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE subjectId = :subjectId")
    suspend fun getBySubjectId(subjectId: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, status = :status, updatedAt = :updatedAt WHERE subjectId = :subjectId")
    suspend fun updateProgress(
        subjectId: String,
        downloadedBytes: Long,
        status: String,
        updatedAt: Long
    )
}
