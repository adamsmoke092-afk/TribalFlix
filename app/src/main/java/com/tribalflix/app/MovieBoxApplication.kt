package com.tribalflix.app

import android.app.Application
import com.tribalflix.app.api.SessionCache

class MovieBoxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Restores the cached visitor JWT, matching client.py's session file so
        // the app does not re-run visitor-login on every launch.
        SessionCache.init(this)
    }
}