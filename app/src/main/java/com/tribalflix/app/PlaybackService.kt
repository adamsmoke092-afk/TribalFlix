package com.tribalflix.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.media3.session.MediaSessionService

/**
 * Media session service for background audio/video playback.
 * Declared in AndroidManifest.xml for foreground service support.
 * Currently minimal — playback is controlled from the Activity via MovieBoxPlayer.
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }
}
