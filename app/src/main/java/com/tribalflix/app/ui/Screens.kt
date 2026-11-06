package com.tribalflix.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tribalflix.app.api.CaptionTrack
import com.tribalflix.app.api.MovieBoxClient
import com.tribalflix.app.api.PlayableSource
import com.tribalflix.app.api.SearchSubject
import com.tribalflix.app.player.MovieBoxResolver
import com.tribalflix.app.player.ResolvedMovie
import com.tribalflix.app.repository.MovieBoxRepository
import com.tribalflix.app.playback.MovieBoxPlayer
import com.tribalflix.app.ui.theme.TribalFlixTheme
import com.tribalflix.app.viewmodel.MovieBoxViewModel
import com.tribalflix.app.viewmodel.UiState
import androidx.media3.ui.PlayerView

/**
 * Navigation host that switches between screens based on UI state.
 *
 * @param viewModel the ViewModel (owned by the Activity)
 * @param player the MovieBoxPlayer (owned by the Activity)
 */
@Composable
fun NavigationHost(
    viewModel: MovieBoxViewModel,
    player: MovieBoxPlayer
) {
    var showPlayer by remember { mutableStateOf(false) }
    var currentSource by remember { mutableStateOf<PlayableSource?>(null) }
    var currentHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val state by viewModel.state.collectAsState()

    TribalFlixTheme {
        Scaffold(
            topBar = {
                if (!showPlayer) {
                    TopAppBar(
                        title = { Text(text = "TribalFlix") },
                        colors = TopAppBarDefaults.mediumTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            },
            floatingActionButton = if (showPlayer) null else {
                FloatingActionButton(
                    onClick = { /* search handled by UI state; no-op here */ },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search") }
                )
            }
        ) { _ ->
            if (showPlayer) {
                PlayerScreen(
                    player = player,
                    source = currentSource ?: error("Player shown without source"),
                    headers = currentHeaders,
                    onBack = { showPlayer = false }
                )
            } else {
                when (val s = state) {
                    is UiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is UiState.Error -> {
                        ErrorScreen(message = s.message, onRetry = { /* no-op */ })
                    }
                    is UiState.SearchResults -> {
                        SearchScreen(
                            viewModel = viewModel,
                            query = s.query,
                            results = s.results,
                            onMovieSelected = { subjectId ->
                                viewModel.resolveMovie(subjectId)
                            },
                            onSourceSelected = { source, headers ->
                                currentSource = source
                                currentHeaders = headers
                                showPlayer = true
                            }
                        )
                    }
                    is UiState.MovieResolved -> {
                        DetailsScreen(
                            viewModel = viewModel,
                            movie = s.movie,
                            resolver = MovieBoxResolver(client = MovieBoxClient()),
                            onPlay = { source, headers ->
                                currentSource = source
                                currentHeaders = headers
                                showPlayer = true
                            }
                        )
                    }
                    is UiState.SourceSelected -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/** Simple error screen with retry. */
@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Error", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text(text = "Retry")
        }
    }
}

/** Search results screen. */
@Composable
private fun SearchScreen(
    viewModel: MovieBoxViewModel,
    query: String,
    results: List<SearchSubject>,
    onMovieSelected: (String) -> Unit,
    onSourceSelected: (PlayableSource, Map<String, String>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Search results for '$query'",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        if (results.isEmpty()) {
            Text(text = "No results found.", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(results) { subject ->
                    Card(
                        modifier = Modifier.fillMaxWidth().paddingBottom(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clickable { onMovieSelected(subject.subjectId) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            subject.poster?.let {
                                AsyncImage(
                                    model = it,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedShape(4.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = subject.title ?: "Unknown",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2
                                )
                                if (subject.year != null) {
                                    Text(
                                        text = "${subject.year}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Movie/TV details screen with play button and source selection. */
@Composable
private fun DetailsScreen(
    viewModel: MovieBoxViewModel,
    movie: ResolvedMovie,
    resolver: MovieBoxResolver,
    onPlay: (PlayableSource, Map<String, String>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = movie.title ?: "Details") },
            navigationIcon = {
                IconButton(onClick = { /* handled by parent */ }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    text = movie.title ?: "Unknown Title",
                    style = MaterialTheme.typography.headlineMedium
                )
                if (movie.year != null) {
                    Text(
                        text = "${movie.year} • Rating: ${movie.rating?.toString() ?: "N/A"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                movie.synopsis?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            item {
                if (movie.directSources.isNotEmpty()) {
                    Text(
                        text = "Direct MP4 Sources",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    val bestDirect = resolver.selectBestDirect(movie.directSources)
                    bestDirect?.let { source ->
                        val headers = resolver.headersFor(source)
                        Button(
                            onClick = { onPlay(source, headers) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Play Direct MP4 (${source.resolutions.firstOrNull() ?: "?"})")
                        }
                    }
                }
            }

            item {
                if (movie.dashSources.isNotEmpty()) {
                    Text(
                        text = "DASH Streams",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    val bestDash = resolver.selectBestDash(movie.dashSources)
                    bestDash?.let { source ->
                        val headers = resolver.headersFor(source)
                        Button(
                            onClick = { onPlay(source, headers) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Play DASH (${source.format})")
                        }
                    }
                }
            }

            item {
                if (movie.subtitles.isNotEmpty()) {
                    Text(
                        text = "Subtitles (${movie.subtitles.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    items(movie.subtitles) { sub ->
                        Text(
                            text = "${sub.language} (${sub.size} bytes)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Fullscreen player screen. */
@Composable
private fun PlayerScreen(
    player: MovieBoxPlayer,
    source: PlayableSource,
    headers: Map<String, String>,
    onBack: () -> Unit
) {
    var playerStarted by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Capture the ExoPlayer reference before the PlayerView apply block,
        // since 'player' inside apply refers to PlayerView.player.
        val exoPlayerRef = player.exoPlayer

        AndroidView(
            factory = { context -> PlayerView(context) },
            update = { view -> view.player = exoPlayerRef },
            modifier = Modifier.fillMaxSize()
        )

        if (!playerStarted) {
            LaunchedEffect(Unit) {
                player.play(source, headers)
                playerStarted = true
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = source.format,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { player.pause() }) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
            }
        }
    }
}
