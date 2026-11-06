package com.tribalflix.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tribalflix.app.api.SearchSubject
import com.tribalflix.app.player.PlayableSource
import com.tribalflix.app.player.ResolvedMovie
import com.tribalflix.app.repository.MovieBoxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading : UiState()
    data class Error(val message: String) : UiState()
    data class SearchResults(val query: String, val results: List<SearchSubject>) : UiState()
    data class MovieResolved(val movie: ResolvedMovie) : UiState()
    data class SourceSelected(val source: PlayableSource, val headers: Map<String, String>) : UiState()
}

class MovieBoxViewModel(private val repository: MovieBoxRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)

    // Expose as StateFlow for Compose
    val state: StateFlow<UiState> = _uiState.asStateFlow()

    fun search(query: String) {
        viewModelScope.launch {
            _uiState = UiState.Loading
            try {
                val results = repository.searchMovies(query)
                _uiState = UiState.SearchResults(query = query, results = results)
            } catch (e: Exception) {
                _uiState = UiState.Error(message = e.message ?: "Search failed")
            }
        }
    }

    fun resolveMovie(subjectId: String) {
        viewModelScope.launch {
            _uiState = UiState.Loading
            try {
                val movie = repository.resolveMovie(subjectId)
                _uiState = UiState.MovieResolved(movie = movie)
            } catch (e: Exception) {
                _uiState = UiState.Error(message = e.message ?: "Failed to resolve movie")
            }
        }
    }

    fun resolveEpisode(subjectId: String, season: Int, episode: Int) {
        viewModelScope.launch {
            _uiState = UiState.Loading
            try {
                val movie = repository.resolveEpisode(subjectId, season, episode)
                _uiState = UiState.MovieResolved(movie = movie)
            } catch (e: Exception) {
                _uiState = UiState.Error(message = e.message ?: "Failed to resolve episode")
            }
        }
    }

    fun selectSource(source: PlayableSource, headers: Map<String, String>) {
        _uiState = UiState.SourceSelected(source = source, headers = headers)
    }
}
