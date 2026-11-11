package com.tribalflix.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.tribalflix.app.api.CaptionTrack
import com.tribalflix.app.api.PlayableSource

/**
 * Media3 ExoPlayer wrapper for TribalFlix playback.
 *
 * Applies per-stream headers (User-Agent, Referer, Cookie) to every request,
 * including DASH manifest and segment requests. When [PlayableSource.subtitleUrl]
 * is present, the video source is merged with a sidecar subtitle track (SRT is
 * converted to WebVTT by [SubtitleDataSourceFactory]) so ExoPlayer renders
 * captions natively.
 *
 * The media-source factory is fixed when the player is built. Each stream's
 * headers are applied by mutating [httpDataSourceFactory] just before prepare,
 * because data sources are created lazily from that same factory instance.
 */
class MovieBoxPlayer private constructor(
    private val context: Context,
    private val subtitleDataSourceFactory: SubtitleDataSourceFactory
) {

    private val trackSelector = DefaultTrackSelector(context)

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        httpDataSourceFactory,
        DefaultExtractorsFactory()
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(),
            /* handleAudioFocus = */ true
        )
        .build()

    private var currentSource: PlayableSource? = null

    /** Subtitle tracks available for the current source, or empty list. */
    val subtitleTracks: List<CaptionTrack>
        get() = currentSource?.subtitleUrl?.takeIf { it.isNotBlank() }
            ?.let { listOf(CaptionTrack(language = "Subtitle", url = it, size = 0)) }
            ?: emptyList()

    /** Index of the selected subtitle track (0), or -1 when disabled. */
    var subtitleTrackIndex: Int = -1
        private set

    /** Binds a PlayerView for UI and controls. */
    fun bindView(playerView: PlayerView) {
        playerView.player = player
    }

    /** Releases the player. Call when the screen is destroyed. */
    fun release() {
        player.release()
        currentSource = null
        subtitleTrackIndex = -1
    }

    /** The underlying ExoPlayer, for binding to PlayerView and MediaSession. */
    val exoPlayer: ExoPlayer
        get() = player

    /** The currently loaded source, or null if nothing is loaded. */
    fun currentSource(): PlayableSource? = currentSource

    /** Whether the player is currently playing. */
    val isPlaying: Boolean
        get() = player.isPlaying

    /** Current playback position in milliseconds. */
    val currentPosition: Long
        get() = player.currentPosition

    /** Total duration in milliseconds. */
    val duration: Long
        get() = player.duration

    /** Buffered percentage (0-100). */
    val bufferedPercentage: Int
        get() = player.bufferedPercentage

    /** Adds a player listener. */
    fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    /** Removes a player listener. */
    fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }

    /**
     * Selects a subtitle track: 0 enables the sidecar track, -1 disables it.
     * Rebuilds the media source, preserving position and play/pause state.
     */
    fun selectSubtitleTrack(index: Int) {
        val enabled = index >= 0 && subtitleTracks.isNotEmpty()
        val target = if (enabled) 0 else -1
        if (target == subtitleTrackIndex) return
        subtitleTrackIndex = target
        currentSource?.let { source ->
            val position = player.currentPosition
            val wasPlaying = player.playWhenReady
            load(source, headersFor(source))
            player.seekTo(position)
            player.playWhenReady = wasPlaying
        }
    }

    /** Builds the HTTP headers needed to play a given source (UA, Referer, Cookie). */
    fun headersFor(source: PlayableSource): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = source.userAgent
        // DASH needs the anti-hotlinking referer; direct MP4 must NOT send it
        // (returns HTTP 429) — see FINDINGS.md §5.2.
        if (source.referer.isNotBlank()) headers["Referer"] = source.referer
        source.cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        return headers
    }

    /** Loads a playable source with its required headers and optional subtitle. */
    fun load(source: PlayableSource, headers: Map<String, String>) {
        currentSource = source
        val subtitleUrl = source.subtitleUrl
        // Default to subtitles ON when the source carries a track.
        subtitleTrackIndex = if (!subtitleUrl.isNullOrBlank()) 0 else -1

        // Per-stream headers must be installed BEFORE createMediaSource(): the
        // DASH/MP4 data sources are created lazily from this same factory.
        httpDataSourceFactory.setUserAgent(source.userAgent)
        httpDataSourceFactory.setDefaultRequestProperties(headers)

        val videoItem = MediaItem.fromUri(source.url)
        val videoSource = mediaSourceFactory.createMediaSource(videoItem)

        val finalSource: MediaSource = if (!subtitleUrl.isNullOrBlank()) {
            subtitleDataSourceFactory.extraHeaders = headers
            // We always serve WebVTT bytes, so pin the MIME type — otherwise
            // ExoPlayer infers it from the remote ".srt" extension and finds
            // no matching decoder.
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            // Media3 1.3.1's SingleSampleMediaSource.Factory is NOT a
            // MediaSource.Factory: its only create overload takes
            // (SubtitleConfiguration, durationUs).
            val subtitleSource = SingleSampleMediaSource.Factory(subtitleDataSourceFactory)
                .createMediaSource(subtitleConfig, C.TIME_UNSET)
            MergingMediaSource(videoSource, subtitleSource)
        } else {
            videoSource
        }

        player.setMediaSource(finalSource)
        player.prepare()
    }

    /** Loads a playable source and starts playback immediately. */
    fun play(source: PlayableSource, headers: Map<String, String>) {
        load(source, headers)
        player.playWhenReady = true
    }

    /** Starts/resumes playback. */
    fun play() {
        player.playWhenReady = true
    }

    /** Pauses playback. */
    fun pause() {
        player.playWhenReady = false
    }

    /** Seeks to a position in milliseconds. */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /** Stops playback and clears the media source. */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        currentSource = null
        subtitleTrackIndex = -1
    }

    /** Sets volume (0.0 to 1.0). */
    fun setVolume(volume: Float) {
        player.volume = volume
    }

    companion object {
        /** Creates a MovieBoxPlayer instance. */
        fun create(context: Context): MovieBoxPlayer =
            MovieBoxPlayer(context, SubtitleDataSourceFactory())
    }
}
