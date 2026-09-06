package com.sidetrack.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidetrack.bridge.NativeBridge
import com.sidetrack.bridge.PlaylistInfo
import com.sidetrack.bridge.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

data class DiscoverUiState(
    val trackUris: List<String> = emptyList(),
    val tracks: List<TrackInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Sidetrack doesn't keep a per-track play history (History only tracks albums and
 * playlists that were opened, not individual plays), so there's no direct "what you've
 * been listening to" log to seed this from. Liked Songs is the closest available
 * stand-in for taste. A handful of liked tracks are used as radio seeds through the
 * same metadataGetAutoplayTracks endpoint autoplay already relies on, results across
 * seeds are merged and deduped, and anything already liked is filtered back out so the
 * list stays songs the user doesn't already have.
 */
class DiscoverViewModel : ViewModel() {

    private companion object {
        const val SEED_COUNT = 5
        const val TRACK_FETCH_CONCURRENCY = 8
    }

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var loaded = false

    fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val likedJson = NativeBridge.metadataGetLikedSongs()
            val liked = likedJson?.let { PlaylistInfo.fromJson(it) }
            if (liked == null || liked.trackUris.isEmpty()) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Like a few songs first to seed Discover")
                }
                return@launch
            }

            val likedSet = liked.trackUris.toSet()
            val seeds = liked.trackUris.shuffled().take(SEED_COUNT)

            val discovered = linkedSetOf<String>()
            for (seed in seeds) {
                val recentJson = JSONArray(listOf(seed)).toString()
                val resultJson = try {
                    NativeBridge.metadataGetAutoplayTracks(seed, recentJson)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (resultJson.contains("\"error\"")) continue
                try {
                    val arr = JSONArray(resultJson)
                    for (i in 0 until arr.length()) {
                        val uri = arr.getString(i)
                        if (uri !in likedSet) discovered.add(uri)
                    }
                } catch (_: Exception) {
                    // Malformed response for this seed - skip it, keep the rest.
                }
            }

            val trackUris = discovered.toList()
            if (trackUris.isEmpty()) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Nothing new to discover right now")
                }
                return@launch
            }

            val tracks = trackUris.chunked(TRACK_FETCH_CONCURRENCY).flatMap { chunk ->
                coroutineScope {
                    chunk.map { uri ->
                        async {
                            NativeBridge.metadataGetTrack(uri)?.let { TrackInfo.fromJson(it) }
                        }
                    }.awaitAll()
                }
            }.filterNotNull()

            _uiState.update {
                it.copy(trackUris = tracks.map { t -> t.uri }, tracks = tracks, isLoading = false)
            }
        }
    }
}
