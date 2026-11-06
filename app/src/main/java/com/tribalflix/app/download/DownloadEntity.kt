package com.tribalflix.app.download

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val subjectId: String,
    val title: String?,
    val url: String,
    val resolution: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String, // "pending", "in_progress", "completed", "failed", "paused"
    val updatedAt: Long
)
