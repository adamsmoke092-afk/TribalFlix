package com.tribalflix.app.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.tribalflix.app.api.PlayableSource

/**
 * Media3 ExoPlayer wrapper for TribalFlix playback.
 * Applies per-stream headers (User-Agent, Referer, Cookie) to every request,
 * including DASH manifest and segment requests.
 *
 * The media-source factory is fixed when the player is built. Each stream's
 * headers are applied by mutating [httpDataSourceFactory] just before prepare,
 * because data sources are created lazily from that same factory instance.
 */
class MovieBoxPlayer private constructor(private val context: Context) {

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

    /** Binds a PlayerView for UI and controls. */
    fun bindView(playerView: PlayerView) {
        playerView.player = player
    }

    /** Releases the player. Call when the screen is destroyed. */
    fun release() {
        player.release()
        currentSource = null
    }

    /** The underlying ExoPlayer, for binding to PlayerView. */
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

    /** Loads a playable source with its required headers. */
    fun load(source: PlayableSource, headers: Map<String, String>) {
        currentSource = source
        // Explicit User-Agent wins over the data source's default UA.
        httpDataSourceFactory.setUserAgent(source.userAgent)
        httpDataSourceFactory.setDefaultRequestProperties(headers)
        player.setMediaItem(MediaItem.fromUri(source.url))
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
    }

    /** Sets volume (0.0 to 1.0). */
    fun setVolume(volume: Float) {
        player.volume = volume
    }

    companion object {
        /** Creates a MovieBoxPlayer instance. */
        fun create(context: Context): MovieBoxPlayer = MovieBoxPlayer(context)
    }
}