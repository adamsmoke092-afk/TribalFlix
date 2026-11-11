package com.tribalflix.app.playback

import android.content.Context

/**
 * Process-wide single owner of the [MovieBoxPlayer].
 *
 * Both [MainActivity] (UI / PlayerView) and PlaybackService (MediaSession) must
 * wrap the SAME ExoPlayer instance, otherwise the lock-screen session would
 * control a different player than the one the screen renders. Keeping the
 * player out of Activity lifecycle also lets playback continue when the
 * Activity is stopped or the user leaves the app (background playback).
 *
 * All calls happen on the main thread (Activity + Service callbacks), so a
 * simple synchronized lazy init is sufficient.
 */
object PlayerHolder {

    @Volatile
    private var instance: MovieBoxPlayer? = null

    /** Returns the shared player, creating it on first use. */
    fun get(context: Context): MovieBoxPlayer =
        instance ?: synchronized(this) {
            instance ?: MovieBoxPlayer.create(context.applicationContext).also { instance = it }
        }

    /** The shared player if it already exists, else null. */
    fun peek(): MovieBoxPlayer? = instance

    /** Releases and forgets the shared player. Called when the service dies. */
    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}