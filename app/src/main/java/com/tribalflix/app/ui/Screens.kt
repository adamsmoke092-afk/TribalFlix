package com.tribalflix.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tribalflix.app.api.MovieBoxClient
import com.tribalflix.app.api.PlayableSource
import com.tribalflix.app.api.SearchSubject
import com.tribalflix.app.download.DownloadManager
import com.tribalflix.app.playback.MovieBoxPlayer
import com.tribalflix.app.player.MovieBoxResolver
import com.tribalflix.app.player.ResolvedMovie
import com.tribalflix.app.viewmodel.MovieBoxViewModel
import com.tribalflix.app.viewmodel.UiState
import kotlinx.coroutines.launch

/**
 * Root navigation between search, details and playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationHost(
    viewModel: MovieBoxViewModel,
    player: MovieBoxPlayer
) {
    val state by viewModel.state.collectAsState()

    var query by remember { mutableStateOf("") }
    var playingSource by remember { mutableStateOf<PlayableSource?>(null) }
    var playingHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val resolver = remember { MovieBoxResolver(MovieBoxClient()) }

    val activeSource = playingSource
    if (activeSource != null) {
        PlayerScreen(
            player = player,
            source = activeSource,
            headers = playingHeaders,
            onBack = {
                player.stop()
                playingSource = null
            }
        )
        return
    }

    val play: (PlayableSource, Map<String, String>) -> Unit = { source, headers ->
        playingSource = source
        playingHeaders = headers
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "TribalFlix") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Idle -> SearchScreen(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { viewModel.search(query) },
                    results = emptyList(),
                    showHint = true,
                    onMovieSelected = viewModel::resolveMovie
                )

                is UiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                is UiState.Error -> ErrorScreen(
                    message = s.message,
                    onRetry = viewModel::reset
                )

                is UiState.SearchResults -> SearchScreen(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { viewModel.search(query) },
                    results = s.results,
                    showHint = false,
                    onMovieSelected = viewModel::resolveMovie
                )

                is UiState.MovieResolved -> DetailsScreen(
                    movie = s.movie,
                    resolver = resolver,
                    onPlay = play,
                    onLoadEpisode = viewModel::resolveEpisode,
                    onBack = viewModel::back
                )

                is UiState.SourceSelected -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) { Text(text = "Back to search") }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<SearchSubject>,
    showHint: Boolean,
    onMovieSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            label = { Text(text = "Search movies and TV") },
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )

        when {
            showHint -> Text(
                text = "Type a title and press search.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            results.isEmpty() -> Text(
                text = "No results found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(results, key = { it.subjectId }) { subject ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onMovieSelected(subject.subjectId) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!subject.poster.isNullOrBlank()) {
                                AsyncImage(
                                    model = subject.poster,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = subject.title ?: "Unknown",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = listOfNotNull(
                                    subject.year?.toString(),
                                    subject.rating?.let { rating -> "%.1f".format(rating) }
                                ).joinToString(" • ")
                                if (meta.isNotEmpty()) {
                                    Text(
                                        text = meta,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsScreen(
    movie: ResolvedMovie,
    resolver: MovieBoxResolver,
    onPlay: (PlayableSource, Map<String, String>) -> Unit,
    onLoadEpisode: (String, Int, Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { DownloadManager(context) }

    var season by remember { mutableStateOf("1") }
    var episode by remember { mutableStateOf("1") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = movie.title ?: "Details") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                if (!movie.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = movie.poster,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = movie.title ?: "Unknown Title",
                    style = MaterialTheme.typography.headlineSmall
                )
                val meta = listOfNotNull(
                    movie.year?.toString(),
                    movie.rating?.let { rating -> "%.1f".format(rating) }
                ).joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                movie.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = synopsis, style = MaterialTheme.typography.bodyMedium)
                }
            }

            item {
                val bestDash = resolver.selectBestDash(movie.dashSources)
                val bestDirect = resolver.selectBestDirect(movie.directSources)
                val best = bestDash ?: bestDirect

                if (best != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onPlay(best, resolver.headersFor(best)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Play")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (bestDirect != null) {
                            Button(
                                onClick = { onPlay(bestDirect, resolver.headersFor(bestDirect)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "Direct MP4")
                            }
                        }
                        if (bestDash != null) {
                            Button(
                                onClick = { onPlay(bestDash, resolver.headersFor(bestDash)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "DASH")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val downloadSource = bestDirect ?: best
                            scope.launch {
                                val result = downloader.startDownload(
                                    source = downloadSource,
                                    title = movie.title,
                                    headers = resolver.headersFor(downloadSource),
                                    subjectId = movie.subjectId
                                )
                                val message = result.fold(
                                    onSuccess = { file -> "Saved ${file.name}" },
                                    onFailure = { error -> "Download failed: ${error.message}" }
                                )
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Download")
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No playable sources found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = "TV episode", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = season,
                        onValueChange = { season = it.filter(Char::isDigit) },
                        label = { Text(text = "Season") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = episode,
                        onValueChange = { episode = it.filter(Char::isDigit) },
                        label = { Text(text = "Episode") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val selectedSeason = season.toIntOrNull() ?: 1
                        val selectedEpisode = episode.toIntOrNull() ?: 1
                        onLoadEpisode(movie.subjectId, selectedSeason, selectedEpisode)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Load episode")
                }
            }

            if (movie.subtitles.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Subtitles (${movie.subtitles.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(movie.subtitles) { subtitle ->
                    Text(
                        text = "${subtitle.language} (${subtitle.size} bytes)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    player: MovieBoxPlayer,
    source: PlayableSource,
    headers: Map<String, String>,
    onBack: () -> Unit
) {
    LaunchedEffect(source.url) {
        player.play(source, headers)
    }

    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).also { it.useController = true } },
            update = { view -> view.player = player.exoPlayer },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .align(Alignment.Center)
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}