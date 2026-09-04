package com.sidetrack.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Player events deserialized from JSON produced by the native layer.
 * Mirrors the PlayerEventInfo enum in native/src/player.rs.
 */
@Serializable
sealed class PlayerEvent {

    @Serializable
    @SerialName("playing")
    data class Playing(
        @SerialName("track_id") val trackId: String,
        @SerialName("position_ms") val positionMs: UInt,
    ) : PlayerEvent()

    @Serializable
    @SerialName("paused")
    data class Paused(
        @SerialName("track_id") val trackId: String,
        @SerialName("position_ms") val positionMs: UInt,
    ) : PlayerEvent()

    @Serializable
    @SerialName("stopped")
    data class Stopped(
        @SerialName("track_id") val trackId: String,
    ) : PlayerEvent()

    @Serializable
    @SerialName("loading")
    data class Loading(
        @SerialName("track_id") val trackId: String,
        @SerialName("position_ms") val positionMs: UInt,
    ) : PlayerEvent()

    @Serializable
    @SerialName("end_of_track")
    data class EndOfTrack(
        @SerialName("track_id") val trackId: String,
    ) : PlayerEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
    ) : PlayerEvent()

    @Serializable
    @SerialName("timeout")
    data class Timeout(
        val message: String,
    ) : PlayerEvent()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonString: String): PlayerEvent? {
            return try {
                json.decodeFromString<PlayerEvent>(jsonString)
            } catch (e: Exception) {
                null
            }
        }
    }
}
