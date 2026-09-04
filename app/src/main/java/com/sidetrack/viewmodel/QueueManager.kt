package com.sidetrack.viewmodel

import com.sidetrack.bridge.TrackInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RepeatMode { OFF, ALL, ONE }

data class QueueState(
    val contextTracks: List<String> = emptyList(),
    val contextIndex: Int = -1,
    val userQueue: List<String> = emptyList(),
    val contextName: String = "",
    val contextUri: String = "",
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val originalContextTracks: List<String> = emptyList(),
    val trackMetadata: Map<String, TrackInfo> = emptyMap(),
    val isAutoplay: Boolean = false,
) {
    val currentTrackUri: String?
        get() = if (contextIndex in contextTracks.indices) contextTracks[contextIndex] else null

    val hasNext: Boolean
        get() = userQueue.isNotEmpty() || contextIndex < contextTracks.size - 1

    val hasPrevious: Boolean
        get() = contextIndex > 0
}

class QueueManager {

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    fun loadContext(tracks: List<String>, startIndex: Int, contextName: String = "", contextUri: String = "", isAutoplay: Boolean = false) {
        val current = _state.value
        val actualTracks = if (current.shuffleEnabled && !isAutoplay) {
            val before = tracks.take(startIndex + 1)
            val after = tracks.drop(startIndex + 1).shuffled()
            before + after
        } else {
            tracks
        }
        _state.value = QueueState(
            contextTracks = actualTracks,
            contextIndex = startIndex,
            userQueue = emptyList(),
            contextName = contextName,
            contextUri = contextUri,
            shuffleEnabled = if (isAutoplay) false else current.shuffleEnabled,
            repeatMode = current.repeatMode,
            originalContextTracks = if (current.shuffleEnabled && !isAutoplay) tracks else emptyList(),
            trackMetadata = current.trackMetadata,
            isAutoplay = isAutoplay,
        )
    }

    fun next(): String? {
        val current = _state.value

        // Repeat one: return current track
        if (current.repeatMode == RepeatMode.ONE) {
            return current.currentTrackUri
        }

        // User queue takes priority
        if (current.userQueue.isNotEmpty()) {
            val nextUri = current.userQueue.first()
            _state.update { it.copy(userQueue = it.userQueue.drop(1)) }
            return nextUri
        }

        // Advance in context
        val nextIndex = current.contextIndex + 1
        if (nextIndex < current.contextTracks.size) {
            _state.update { it.copy(contextIndex = nextIndex) }
            return current.contextTracks[nextIndex]
        }

        // End of context - check repeat all
        if (current.repeatMode == RepeatMode.ALL && current.contextTracks.isNotEmpty()) {
            _state.update { it.copy(contextIndex = 0) }
            return current.contextTracks[0]
        }

        return null // End of queue
    }

    fun previous(): String? {
        val current = _state.value
        val prevIndex = current.contextIndex - 1
        if (prevIndex >= 0) {
            _state.update { it.copy(contextIndex = prevIndex) }
            return current.contextTracks[prevIndex]
        }
        // Restart current track
        return current.currentTrackUri
    }

    fun addToQueue(uri: String) {
        _state.update { it.copy(userQueue = it.userQueue + uri) }
    }

    fun removeFromQueue(index: Int) {
        _state.update {
            val mutable = it.userQueue.toMutableList()
            if (index in mutable.indices) mutable.removeAt(index)
            it.copy(userQueue = mutable)
        }
    }

    fun playFromUserQueue(index: Int): String? {
        val current = _state.value
        if (index !in current.userQueue.indices) return null
        val uri = current.userQueue[index]
        _state.update {
            val mutable = it.userQueue.toMutableList()
            mutable.removeAt(index)
            it.copy(userQueue = mutable)
        }
        return uri
    }

    fun playFromContext(absoluteIndex: Int): String? {
        val current = _state.value
        if (absoluteIndex !in current.contextTracks.indices) return null
        _state.update { it.copy(contextIndex = absoluteIndex) }
        return current.contextTracks[absoluteIndex]
    }

    fun toggleShuffle() {
        if (_state.value.isAutoplay) return
        _state.update { current ->
            if (!current.shuffleEnabled) {
                // Shuffle remaining tracks after current position
                val before = current.contextTracks.take(current.contextIndex + 1)
                val after = current.contextTracks.drop(current.contextIndex + 1).shuffled()
                current.copy(
                    contextTracks = before + after,
                    shuffleEnabled = true,
                    originalContextTracks = current.contextTracks,
                )
            } else {
                // Restore original order, find current track position
                val currentUri = current.currentTrackUri
                val originalIndex = current.originalContextTracks.indexOf(currentUri)
                current.copy(
                    contextTracks = current.originalContextTracks,
                    contextIndex = if (originalIndex >= 0) originalIndex else current.contextIndex,
                    shuffleEnabled = false,
                    originalContextTracks = emptyList(),
                )
            }
        }
    }

    fun cycleRepeatMode() {
        _state.update { current ->
            current.copy(
                repeatMode = when (current.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                },
            )
        }
    }

    fun cacheMetadata(uri: String, info: TrackInfo) {
        _state.update { current ->
            val updated = current.trackMetadata + (uri to info)
            if (updated.size <= 200) {
                current.copy(trackMetadata = updated)
            } else {
                // Keep tracks near current position + user queue, evict the rest
                val keepUris = mutableSetOf(uri)
                keepUris.addAll(current.userQueue)
                val start = (current.contextIndex - 5).coerceAtLeast(0)
                val end = (current.contextIndex + 25).coerceAtMost(current.contextTracks.size)
                if (start < end) keepUris.addAll(current.contextTracks.subList(start, end))
                current.copy(trackMetadata = updated.filterKeys { it in keepUris })
            }
        }
    }
}
