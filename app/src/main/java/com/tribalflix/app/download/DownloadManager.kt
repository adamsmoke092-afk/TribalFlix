package com.tribalflix.app.download

import android.content.Context
import android.os.Environment
import com.tribalflix.app.api.PlayableSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Manages resumable direct MP4 downloads.
 * Uses OkHttp with Range requests for pause/resume support.
 * Persists progress to Room so downloads survive process death
 * (but not uninstall-reinstall, per the user's install habit).
 */
class DownloadManager private constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val db: AppDatabase
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val downloadDir: File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TribalFlix")

    init {
        downloadDir.mkdirs()
    }

    constructor(context: Context) : this(
        context = context.applicationContext,
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build(),
        db = AppDatabase.getInstance(context.applicationContext)
    )

    /**
     * Starts or resumes a download. Returns the destination file.
     *
     * @param subjectId Room key; defaults to the source id.
     */
    suspend fun startDownload(
        source: PlayableSource,
        title: String?,
        headers: Map<String, String>,
        subjectId: String = source.id
    ): Result<File> = coroutineScope {
        val destFile = destFileFor(source, title)
        val existing = db.downloadDao().getBySubjectId(subjectId)

        if (existing?.status == STATUS_COMPLETED && destFile.exists()) {
            return@coroutineScope Result.success(destFile)
        }

        val startByte = if (existing != null) existing.downloadedBytes else 0L
        val headerBuilder = Headers.Builder()
        headers.forEach { (name, value) -> headerBuilder.add(name, value) }
        if (startByte > 0) headerBuilder.add("Range", "bytes=$startByte-")
        val okHeaders = headerBuilder.build()

        db.downloadDao().insert(
            DownloadEntity(
                subjectId = subjectId,
                title = title,
                url = source.url,
                resolution = source.resolutions.firstOrNull(),
                totalBytes = existing?.totalBytes ?: 0L,
                downloadedBytes = startByte,
                status = STATUS_IN_PROGRESS,
                updatedAt = System.currentTimeMillis()
            )
        )

        try {
            val request = Request.Builder()
                .url(source.url)
                .headers(okHeaders)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    db.downloadDao().updateProgress(
                        subjectId, startByte, STATUS_FAILED, System.currentTimeMillis()
                    )
                    return@use Result.failure(Exception("HTTP ${response.code}"))
                }

                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                val total = if (startByte > 0) contentLength + startByte else contentLength
                val body = response.body ?: return@use Result.failure(Exception("Empty body"))

                val tempFile = File(destFile.absolutePath + ".part")
                val append = startByte > 0 && tempFile.exists()

                FileOutputStream(tempFile, append).buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = startByte
                        var lastPersisted = startByte
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastPersisted >= PERSIST_INTERVAL_BYTES) {
                                db.downloadDao().updateProgress(
                                    subjectId, downloaded, STATUS_IN_PROGRESS,
                                    System.currentTimeMillis()
                                )
                                lastPersisted = downloaded
                            }
                        }
                        output.flush()
                        lastPersisted = downloaded
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    db.downloadDao().updateProgress(
                        subjectId, startByte, STATUS_FAILED, System.currentTimeMillis()
                    )
                    return@use Result.failure(Exception("Could not finalise ${destFile.name}"))
                }

                db.downloadDao().updateProgress(
                    subjectId, if (total > 0) total else destFile.length(),
                    STATUS_COMPLETED, System.currentTimeMillis()
                )
                Result.success(destFile)
            }
        } catch (e: Exception) {
            runCatching {
                db.downloadDao().updateProgress(
                    subjectId, startByte, STATUS_FAILED, System.currentTimeMillis()
                )
            }
            Result.failure(e)
        }
    }

    private fun destFileFor(source: PlayableSource, title: String?): File {
        val cleanTitle = cleanTitle(title)
        val res = source.resolutions.firstOrNull() ?: "unknown"
        return File(downloadDir, "${cleanTitle}_$res.mp4")
    }

    private fun cleanTitle(title: String?): String =
        title?.replace("[^a-zA-Z0-9._-]".toRegex(), "_")?.take(80) ?: "unknown"

    /** Lists all downloads from Room. */
    fun listDownloads(): Flow<List<DownloadEntity>> = db.downloadDao().getAll()

    /** Cancels all work and releases resources. */
    fun shutdown() {
        job.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        private const val PERSIST_INTERVAL_BYTES = 256L * 1024L
    }
}