package com.tribalflix.app

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tribalflix.app.playback.PlayerHolder

/**
 * Foreground media-session service: background playback, lock-screen /
 * notification controls, and Bluetooth/media-button handling.
 *
 * It wraps the SAME ExoPlayer instance the UI renders (via [PlayerHolder]), so
 * the notification and the on-screen player never diverge. Media3 presents the
 * default notification + channel automatically; the service is promoted to the
 * foreground while playback is ongoing.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.get(this)
        session = MediaSession.Builder(this, player.exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // Release the session but NOT the player: the Activity may still hold
        // the shared instance (PlayerHolder). Releasing it here would leave the
        // UI with a released ExoPlayer and crash on the next play. The player
        // lives for the process lifetime.
        session?.release()
        session = null
        super.onDestroy()
    }
}