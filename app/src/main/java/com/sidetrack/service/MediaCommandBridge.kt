package com.sidetrack.service

/**
 * Direct in-process bridge for media commands between PlaybackService and PlayerViewModel.
 * Replaces the unreliable broadcast-based approach.
 */
object MediaCommandBridge {
    var onCommand: ((command: String, positionMs: Long) -> Unit)? = null
}
