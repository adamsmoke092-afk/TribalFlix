package com.tribalflix.app

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Media session service for background playback.
 * Declared in AndroidManifest.xml for foreground-service support.
 */
class PlaybackService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
}