package com.tribalflix.app.player

import com.tribalflix.app.api.MovieBoxClient
import com.tribalflix.app.api.PlayableSource
import com.tribalflix.app.api.ResolvedSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Higher-level stream resolution with quality selection and source prioritization.
 * Wraps [MovieBoxClient] and exposes a clean API for the UI layer.
 */

data class ResolvedMovie(
    val subjectId: String,
    val title: String?,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val synopsis: String?,
    val dashSources: List<PlayableSource>,
    val directSources: List<PlayableSource>,
    val subtitles: List<com.tribalflix.app.api.CaptionTrack>
)

/** Preferred resolution order for direct MP4 selection. */
val RESOLUTION_PRIORITY = listOf("1080p", "720p", "480p", "360p")

class MovieBoxResolver(private val client: MovieBoxClient) {

    /** Resolves all playable sources for a movie/subject. */
    suspend fun resolveMovie(subjectId: String): ResolvedMovie =
        withContext(Dispatchers.IO) {
            val sources = client.getAllPlayableSources(subjectId)
            val details = client.getDetails(subjectId)
            val subject = details.optJSONObject("subject") ?: details

            ResolvedMovie(
                subjectId = subjectId,
                title = subject.optString("title", null),
                poster = subject.optString("poster", subject.optString("posterUrl", null)),
                year = subject.optInt("year", -1).takeIf { it > 0 },
                rating = subject.optDouble("rating", -1.0).takeIf { it >= 0 },
                synopsis = subject.optString("synopsis", subject.optString("description", null)),
                dashSources = sources.dashStreams,
                directSources = sources.directStreams,
                subtitles = sources.subtitles
            )
        }

    /** Resolves all playable sources for a TV episode. */
    suspend fun resolveEpisode(subjectId: String, season: Int, episode: Int): ResolvedMovie =
        withContext(Dispatchers.IO) {
            val sources = client.getAllPlayableSources(subjectId, season, episode)
            val details = client.getDetails(subjectId)
            val subject = details.optJSONObject("subject") ?: details

            ResolvedMovie(
                subjectId = subjectId,
                title = subject.optString("title", null),
                poster = subject.optString("poster", subject.optString("posterUrl", null)),
                year = subject.optInt("year", -1).takeIf { it > 0 },
                rating = subject.optDouble("rating", -1.0).takeIf { it >= 0 },
                synopsis = subject.optString("synopsis", subject.optString("description", null)),
                dashSources = sources.dashStreams,
                directSources = sources.directStreams,
                subtitles = sources.subtitles
            )
        }

    /** Selects the best DASH source (first available, typically highest quality). */
    fun selectBestDash(sources: List<PlayableSource>): PlayableSource? =
        sources.firstOrNull()

    /** Selects the best direct MP4 source by resolution priority. */
    fun selectBestDirect(sources: List<PlayableSource>): PlayableSource? {
        for (res in RESOLUTION_PRIORITY) {
            val match = sources.firstOrNull { it.resolutions.any { it.equals(res, ignoreCase = true) } }
            if (match != null) return match
        }
        return sources.firstOrNull()
    }

    /** Builds the HTTP headers needed to play a given source (DASH or direct). */
    fun headersFor(source: PlayableSource): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = source.userAgent
        headers["Referer"] = source.referer
        source.cookie?.let { headers["Cookie"] = it }
        return headers
    }
}
