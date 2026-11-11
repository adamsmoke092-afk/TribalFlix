package com.tribalflix.app.playback

import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tribalflix.app.subtitles.SubtitleConverter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DataSource.Factory that serves sidecar subtitle tracks as WebVTT.
 *
 * Downloads the remote SRT (or VTT) file, converts SRT to WebVTT in memory,
 * and serves the resulting bytes to ExoPlayer via [BaseDataSource]. Subtitle
 * tracks are small (tens of KB), so eager in-memory conversion keeps the
 * DataSource contract simple.
 *
 * [extraHeaders] are applied to every download; set them to the parent
 * stream's headers before preparing the media source, because subtitle CDNs
 * often require the same User-Agent / Referer / Cookie as the video.
 */
class SubtitleDataSourceFactory(
    private val httpClient: OkHttpClient = defaultClient()
) : DataSource.Factory {

    /** Headers applied to subtitle downloads; set before the source is prepared. */
    @Volatile
    var extraHeaders: Map<String, String> = emptyMap()

    override fun createDataSource(): DataSource = BytesDataSource()

    /**
     * Downloads [url] and converts SRT→WebVTT. If the content already begins
     * with "WEBVTT" it is returned unchanged.
     */
    fun fetchAndConvert(url: String): ByteArray {
        val builder = Request.Builder().url(url)
        extraHeaders.forEach { (name, value) -> builder.addHeader(name, value) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Subtitle HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty subtitle body for $url")
            val text = String(body.bytes(), StandardCharsets.UTF_8)
            val vtt = if (text.trimStart().startsWith("WEBVTT")) text
            else SubtitleConverter.toWebVtt(text)
            return vtt.toByteArray(StandardCharsets.UTF_8)
        }
    }

    /** In-memory DataSource over the converted WebVTT bytes. */
    private inner class BytesDataSource : BaseDataSource(/* isNetwork = */ false) {

        private var stream: InputStream? = null
        private var ended = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val uri = dataSpec.uri ?: throw IOException("No subtitle URI")
            val bytes = fetchAndConvert(uri.toString())
            stream = ByteArrayInputStream(bytes)
            transferStarted(dataSpec)
            return bytes.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = stream?.read(buffer, offset, length) ?: -1
            if (read == -1) {
                if (!ended) {
                    ended = true
                    transferEnded()
                }
            } else {
                bytesTransferred(read)
            }
            return read
        }

        override fun close() {
            try {
                stream?.close()
            } finally {
                stream = null
                if (!ended) {
                    ended = true
                    transferEnded()
                }
            }
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
