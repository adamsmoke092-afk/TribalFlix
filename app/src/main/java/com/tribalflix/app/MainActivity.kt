package com.tribalflix.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tribalflix.app.api.MovieBoxClient
import com.tribalflix.app.playback.MovieBoxPlayer
import com.tribalflix.app.playback.PlayerHolder
import com.tribalflix.app.repository.MovieBoxRepository
import com.tribalflix.app.ui.NavigationHost
import com.tribalflix.app.ui.theme.TribalFlixTheme
import com.tribalflix.app.viewmodel.MovieBoxViewModel

class MainActivity : ComponentActivity() {

    // Shared with PlaybackService via PlayerHolder, so the on-screen player and
    // the media-session (notification / lock screen) are the same instance.
    private val player: MovieBoxPlayer by lazy { PlayerHolder.get(this) }

    private var isInPip by mutableStateOf(false)

    private val viewModel: MovieBoxViewModel by viewModels {
        MovieBoxViewModelFactory(
            repository = MovieBoxRepository(client = MovieBoxClient())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TribalFlixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavigationHost(
                        viewModel = viewModel,
                        player = player,
                        isInPip = isInPip,
                        onEnterPip = ::enterPipIfPlaying
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // (Re)start the media-session service whenever the app is visible, so a
        // session exists for the next play even if the system tore the service
        // down while the app was backgrounded. Media3 promotes it to a
        // foreground service while playback is ongoing.
        startService(Intent(this, PlaybackService::class.java))
    }

    /** Enters PiP only while actually playing (PiP of a paused/blank player is useless). */
    private fun enterPipIfPlaying() {
        if (player.exoPlayer.isPlaying) enterPip()
    }

    private fun enterPip() {
        val params = PictureInPictureParams.Builder()
            // Must be within 2.39:1 .. 1:2.39; 16:9 is safe.
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pressing Home during playback drops straight into PiP.
        if (player.exoPlayer.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    // Intentionally does NOT release the player: background playback must
    // outlive the Activity. PlaybackService owns the player lifetime.
}

/** Simple ViewModel factory to inject the repository. */
class MovieBoxViewModelFactory(
    private val repository: MovieBoxRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MovieBoxViewModel(repository) as T
}