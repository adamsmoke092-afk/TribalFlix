package com.tribalflix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodelScope
import com.tribalflix.app.playback.MovieBoxPlayer
import com.tribalflix.app.repository.MovieBoxRepository
import com.tribalflix.app.api.MovieBoxClient
import com.tribalflix.app.ui.Screens.NavigationHost
import com.tribalflix.app.ui.theme.TribalFlixTheme
import com.tribalflix.app.viewmodel.MovieBoxViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val player by lazy { MovieBoxPlayer.create(this) }

    private val viewModel: MovieBoxViewModel by viewModels {
        MovieBoxViewModelFactory(
            repository = MovieBoxRepository(client = MovieBoxClient())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                TribalFlixTheme {
                    NavigationHost(viewModel = viewModel, player = player)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}

/** Simple ViewModel factory to inject the repository. */
class MovieBoxViewModelFactory(
    private val repository: com.tribalflix.app.repository.MovieBoxRepository
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        MovieBoxViewModel(repository) as T
}
