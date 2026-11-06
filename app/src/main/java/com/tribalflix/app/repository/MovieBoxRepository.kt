package com.tribalflix.app.repository

import com.tribalflix.app.api.MovieBoxClient
import com.tribalflix.app.api.SearchSubject
import com.tribalflix.app.player.MovieBoxResolver
import com.tribalflix.app.player.ResolvedMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for MovieBox data operations.
 * Wraps MovieBoxClient + MovieBoxResolver and exposes suspending methods
 * for use by the ViewModel.
 */

class MovieBoxRepository(private val client: MovieBoxClient) {

    private val resolver = MovieBoxResolver(client)

    suspend fun searchMovies(query: String): List<SearchSubject> =
        withContext(Dispatchers.IO) { client.search(query) }

    suspend fun resolveMovie(subjectId: String): ResolvedMovie =
        withContext(Dispatchers.IO) { resolver.resolveMovie(subjectId) }

    suspend fun resolveEpisode(subjectId: String, season: Int, episode: Int): ResolvedMovie =
        withContext(Dispatchers.IO) { resolver.resolveEpisode(subjectId, season, episode) }
}
