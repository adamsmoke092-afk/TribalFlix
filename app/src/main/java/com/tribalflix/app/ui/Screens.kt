package com.tribalflix.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    player: MovieBoxPlayer,
    isInPip: Boolean,
    onEnterPip: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    var query by remember { mutableStateOf("") }
    var playingSource by remember { mutableStateOf<PlayableSource?>(null) }
    var playingHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var playingTitle by remember { mutableStateOf<String?>(null) }
    var playingArtwork by remember { mutableStateOf<String?>(null) }
    val resolver = remember { MovieBoxResolver(MovieBoxClient()) }

    val activeSource = playingSource
    if (activeSource != null) {
        PlayerScreen(
            player = player,
            source = activeSource,
            headers = playingHeaders,
            title = playingTitle,
            artworkUri = playingArtwork,
            isInPip = isInPip,
            onEnterPip = onEnterPip,
            onBack = {
                player.stop()
                playingSource = null
            }
        )
        return
    }

    val play: (PlayableSource, Map<String, String>, String?, String?) -> Unit =
        { source, headers, title, artwork ->
            playingSource = source
            playingHeaders = headers
            playingTitle = title
            playingArtwork = artwork
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
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
    onPlay: (PlayableSource, Map<String, String>, String?, String?) -> Unit,
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
                val options = remember(movie) { resolver.qualityOptions(movie) }
                // Default to the best DIRECT MP4 rather than "Auto"/DASH:
                // DASH playback is not yet confirmed on-device, so the proven
                // progressive path is the safer default for the Play button.
                var selectedLabel by remember(movie) {
                    mutableStateOf(
                        options.firstOrNull { it.source.format == "direct" }?.label
                            ?: options.firstOrNull()?.label
                    )
                }
                val selected = options.firstOrNull { it.label == selectedLabel } ?: options.firstOrNull()

                if (selected != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Quality", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option.label == selected.label,
                                onClick = { selectedLabel = option.label },
                                label = { Text(text = option.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onPlay(
                                selected.source,
                                resolver.headersFor(selected.source),
                                movie.title,
                                movie.poster
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Play")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    // Only a direct MP4 is a real file; a DASH manifest is not
                    // downloadable (saving it would produce an unusable .mpd).
                    if (selected.source.format == "direct") {
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = downloader.startDownload(
                                        source = selected.source,
                                        title = movie.title,
                                        headers = resolver.headersFor(selected.source),
                                        subjectId = movie.subjectId
                                    )
                                    val message = result.fold(
                                        onSuccess = { file -> "Saved ${file.name}" },
                                        onFailure = { error ->
                                            // Include the type: some Android exceptions
                                            // (e.g. NetworkOnMainThreadException) have a
                                            // null message.
                                            "Download failed: ${error.javaClass.simpleName}: ${error.message}"
                                        }
                                    )
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Download")
                        }
                    } else {
                        Text(
                            text = "Auto (DASH) — pick a resolution to download a file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    title: String?,
    artworkUri: String?,
    isInPip: Boolean,
    onEnterPip: () -> Unit,
    onBack: () -> Unit
) {
    var errorText by remember { mutableStateOf<String?>(null) }
    var subtitlesOn by remember { mutableStateOf(!source.subtitleUrl.isNullOrBlank()) }
    val hasSubtitles = !source.subtitleUrl.isNullOrBlank()

    // Surface playback failures instead of dying silently on a black screen.
    DisposableEffect(player.exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = "${error.errorCodeName}: ${error.message}"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(source.url) {
        errorText = null
        try {
            // Title/artwork feed the lock-screen + notification metadata.
            player.play(source, headers, title, artworkUri)
        } catch (e: Exception) {
            // A missing media module or unsupported manifest surfaces here.
            errorText = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).also { it.useController = true } },
            update = { view ->
                view.player = player.exoPlayer
                // In PiP the system draws its own minimal chrome; hide the
                // in-app controller so it doesn't overlap the small window.
                view.useController = !isInPip
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .align(Alignment.Center)
        )

        if (!isInPip) {
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

            // Enter Picture-in-Picture (only meaningful while playing).
            TextButton(
                onClick = onEnterPip,
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Text(text = "PiP", color = Color.White)
            }
        }

        if (hasSubtitles && !isInPip) {
            // The sidecar track is merged into the source; toggling rebuilds
            // the media source with the track enabled or disabled.
            TextButton(
                onClick = {
                    subtitlesOn = !subtitlesOn
                    player.selectSubtitleTrack(if (subtitlesOn) 0 else -1)
                },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = if (subtitlesOn) "CC On" else "CC Off",
                    color = Color.White
                )
            }
        }

        errorText?.let { message ->
            Text(
                text = "Playback failed\n$message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}