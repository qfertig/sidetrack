package com.sidetrack.history

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PlayHistoryEntry(
    val contextUri: String,
    val contextName: String,
    val contextType: String, // "album", "playlist", "show"
    val artistName: String = "",
    val imageUrl: String? = null,
    val playedAtMs: Long,
)

class PlayHistoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "sidetrack_history"
        private const val KEY_ENTRIES = "play_history"
        private const val MAX_ENTRIES = 200
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun recordPlay(entry: PlayHistoryEntry) {
        val entries = loadEntries().toMutableList()
        entries.removeAll { it.contextUri == entry.contextUri }
        entries.add(0, entry)
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).clear()
        }
        val serialized = json.encodeToString(entries)
        prefs.edit().putString(KEY_ENTRIES, serialized).commit()
    }

    @Synchronized
    fun loadEntries(): List<PlayHistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PlayHistoryEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
