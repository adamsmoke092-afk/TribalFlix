package com.tribalflix.app.download

import android.content.Context
import android.os.Environment
import androidx.lifecycle.LifecycleService
import com.tribalflix.app.api.PlayableSource
import com.squareup.okhttp3.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages resumable direct MP4 downloads.
 * Uses OkHttp with Range requests for pause/resume support.
 * Persists progress to Room so downloads survive process death
 * (but not uninstall-reinstall, per user habit).
 */

class DownloadManager private constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val db: AppDatabase
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    private val downloadDir: File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TribalFlix")

    init {
        downloadDir.mkdirs()
    }

    constructor(context: Context) : this(
        context = context,
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build(),
        db = AppDatabase.getInstance(context.applicationContext)
    )

    /** Starts or resumes a download. Returns the destination file. */
    suspend fun startDownload(
        source: PlayableSource,
        title: String?,
        headers: Map<String, String>
    ): Result<File> {
        return coroutineScope {
            val existing = db.downloadDao().getBySubjectId(source.id)
            val destFile = destFileFor(source, title)
            val startByte = if (existing != null && existing.status != "completed") {
                existing.downloadedBytes
            } else 0L

            if (existing != null && existing.status == "completed" && destFile.exists()) {
                return@coroutineScope Result.Success(destFile)
            }

            val total = existing?.totalBytes ?: 0L
            val requestHeaders = headers.toMutableMap()
            if (startByte > 0) {
                requestHeaders["Range"] = "bytes=$startByte-"
            }

            try {
                val request = Request.Builder()
                    .url(source.url)
                    .headers(Headers.of(requestHeaders))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Result.Failure(Exception("HTTP ${response.code}"))
                } else {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    val actualTotal = if (startByte > 0) contentLength + startByte else contentLength

                    val tempFile = File(destFile.absolutePath + ".part")
                    val output = tempFile.outputStream().buffered()
                    try {
                        response.body()?.source()?.use { stream ->
                            var downloaded = startByte
                            val buffer = Buffer()
                            while (true) {
                                val read = stream.read(buffer, 8192)
                                if (read == -1) break
                                output.write(buffer.readByteArray(read))
                                downloaded += read

                                // Update progress every ~1 second worth of data
                                if (downloaded - (existing?.downloadedBytes ?: 0) >= 8192) {
                                    db.downloadDao().updateProgress(
                                        source.id, downloaded,
                                        "in_progress", System.currentTimeMillis()
                                    )
                                }
                            }
                        }
                        output.flush()
                    } finally {
                        output.close()
                    }

                    // Rename .part to final file
                    if (destFile.exists()) destFile.delete()
                    tempFile.renameTo(destFile)

                    db.downloadDao().updateProgress(
                        source.id, actualTotal,
                        "completed", System.currentTimeMillis()
                    )

                    Result.Success(destFile)
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }

    /** Gets the destination file for a download. */
    private fun destFileFor(source: PlayableSource, title: String?): File {
        val cleanTitle = title?.replace("[^a-zA-Z0-9._-]".toRegex(), "_") ?: source.id
        val res = source.resolutions.firstOrNull() ?: "unknown"
        return File(downloadDir, "$cleanTitle_$res.mp4")
    }

    /** Lists all downloads from Room. */
    fun listDownloads(): Flow<List<DownloadEntity>> = db.downloadDao().getAll()

    /** Cancels all work and releases resources. */
    fun shutdown() {
        job.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }
}
