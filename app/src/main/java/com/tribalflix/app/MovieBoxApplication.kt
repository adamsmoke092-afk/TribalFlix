package com.tribalflix.app

import android.app.Application

class MovieBoxApplication : Application() {
    // Application-level singletons can be initialized here if needed.
    // Currently stateless: MovieBoxClient and MovieBoxRepository are created per-ViewModel.
}
