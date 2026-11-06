package com.tribalflix.app

import android.content.Intent
import android.os.IBinder
import androidx.media3.session.MediaSessionService

/**
 * Media session service for background audio/video playback.
 * Declared in AndroidManifest.xml for foreground service support.
 */
class PlaybackService : MediaSessionService() {

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onGetSession(controller: androidx.media3.session.MediaSessionController): androidx.media3.session.MediaSession? {
        return super.onGetSession(controller)
    }
}
