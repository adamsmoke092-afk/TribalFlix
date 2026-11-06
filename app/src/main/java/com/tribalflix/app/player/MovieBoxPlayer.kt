package com.tribalflix.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioAttributes
import androidx.media3.common.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.trackselection.DefaultTrackSelector

/**
 * Media3 ExoPlayer wrapper for TribalFlix playback.
 * Applies per-stream headers (User-Agent, Referer, Cookie) to every request.
 *
 * Usage:
 *   val player = MovieBoxPlayer(context)
 *   player.bindView(playerView)
 *   player.load(source, headers)
 *   player.play()
 */

class MovieBoxPlayer private constructor(
    private val context: Context,
    private val player: ExoPlayer,
    private val trackSelector: DefaultTrackSelector
) {

    private var currentSource: PlayableSource? = null

    /** Creates a player with default ExoPlayer configuration. */
    constructor(context: Context) : this(
        context = context,
        player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            .build(),
        trackSelector = DefaultTrackSelector(context)
    ) {
        player.trackSelector = trackSelector
    }

    /** Binds a PlayerView for UI and controls. */
    fun bindView(playerView: androidx.media3.ui.PlayerView) {
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
    val currentSource: PlayableSource?
        get() = this.currentSource

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
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        player.setMediaSourceFactory(DefaultMediaSourceFactory(context, dataSourceFactory))
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
        currentSource = null
    }

    /** Sets volume (0.0 to 1.0). */
    fun setVolume(volume: Float) {
        player.setVolume(volume)
    }

    companion object {
        /** Creates a MovieBoxPlayer instance. */
        fun create(context: Context): MovieBoxPlayer = MovieBoxPlayer(context)
    }
}
