package com.sidetrack.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sidetrack.MainActivity
import com.sidetrack.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "sidetrack_playback"
        private const val NOTIFICATION_ID = 1
        private const val PAUSE_TIMEOUT_MS = 10 * 60 * 1000L

        const val ACTION_UPDATE = "com.sidetrack.UPDATE"
        const val ACTION_STOP_SERVICE = "com.sidetrack.STOP_SERVICE"

        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ART_URL = "art_url"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DURATION_MS = "duration_ms"

        fun startService(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            context.startForegroundService(intent)
        }

        fun updateMetadata(
            context: Context,
            title: String,
            artist: String,
            artUrl: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
        ) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ART_URL, artUrl)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_POSITION_MS, positionMs)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private lateinit var mediaSession: MediaSession
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentArtUrl: String? = null
    private var currentArtBitmap: Bitmap? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pauseTimeoutRunnable = Runnable { onPauseTimeout() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSession(this, "sidetrack")
        mediaSession.setCallback(mediaSessionCallback)
        mediaSession.isActive = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sidetrack::playback")
            .apply { setReferenceCounted(false) }

        // Start with a minimal notification immediately
        try {
            startForeground(NOTIFICATION_ID, buildNotification("sidetrack", "", false))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.sidetrack.MEDIA_play" -> sendMediaCommand("play")
            "com.sidetrack.MEDIA_pause" -> sendMediaCommand("pause")
            "com.sidetrack.MEDIA_prev" -> sendMediaCommand("previous")
            "com.sidetrack.MEDIA_next" -> sendMediaCommand("next")
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val artUrl = intent.getStringExtra(EXTRA_ART_URL)
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0)

                updateMediaSession(title, artist, isPlaying, positionMs, durationMs)

                // Always call startForeground to satisfy the startForegroundService() contract
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(title, artist, isPlaying))
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Manage wake lock and pause timeout
                if (isPlaying) {
                    handler.removeCallbacks(pauseTimeoutRunnable)
                    wakeLock?.acquire()
                } else {
                    if (wakeLock?.isHeld != true) wakeLock?.acquire()
                    handler.removeCallbacks(pauseTimeoutRunnable)
                    handler.postDelayed(pauseTimeoutRunnable, PAUSE_TIMEOUT_MS)
                }

                // Fetch art async if URL changed
                if (artUrl != null && artUrl != currentArtUrl) {
                    currentArtUrl = artUrl
                    scope.launch {
                        val bitmap = withContext(Dispatchers.IO) { loadBitmap(artUrl) }
                        currentArtBitmap = bitmap
                        updateMediaSessionArt(bitmap)
                        updateNotification(title, artist, isPlaying)
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                releaseWakeLock()
                // Satisfy any pending startForegroundService() contract before stopping
                try {
                    startForeground(NOTIFICATION_ID, buildNotification("sidetrack", "", false))
                } catch (_: ForegroundServiceStartNotAllowedException) { }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        releaseWakeLock()
        NativeBridge.playerStop()
        NativeBridge.sessionDisconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        mediaSession.isActive = false
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun onPauseTimeout() {
        // Only stop if still paused (not playing)
        val state = mediaSession.controller?.playbackState?.state
        if (state != PlaybackState.STATE_PLAYING) {
            sendMediaCommand("stop")
        }
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(pauseTimeoutRunnable)
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Music playback controls"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun updateMediaSession(
        title: String,
        artist: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)

        currentArtBitmap?.let {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
        }

        mediaSession.setMetadata(metadata.build())

        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                positionMs,
                if (isPlaying) 1.0f else 0f,
            )
            .build()
        mediaSession.setPlaybackState(state)
    }

    private fun updateMediaSessionArt(bitmap: Bitmap?) {
        bitmap ?: return
        val current = mediaSession.controller?.metadata ?: return
        val updated = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, current.getString(MediaMetadata.METADATA_KEY_TITLE))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, current.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, current.getLong(MediaMetadata.METADATA_KEY_DURATION))
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            .build()
        mediaSession.setMetadata(updated)
    }

    private fun buildNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifEmpty { "sidetrack" })
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)

        currentArtBitmap?.let { builder.setLargeIcon(it) }

        // Add actions: previous, play/pause, next
        val prevIntent = createMediaActionIntent("prev")
        builder.addAction(
            Notification.Action.Builder(
                null, "Previous",
                prevIntent,
            ).build()
        )

        if (isPlaying) {
            val pauseIntent = createMediaActionIntent("pause")
            builder.addAction(
                Notification.Action.Builder(
                    null, "Pause",
                    pauseIntent,
                ).build()
            )
        } else {
            val playIntent = createMediaActionIntent("play")
            builder.addAction(
                Notification.Action.Builder(
                    null, "Play",
                    playIntent,
                ).build()
            )
        }

        val nextIntent = createMediaActionIntent("next")
        builder.addAction(
            Notification.Action.Builder(
                null, "Next",
                nextIntent,
            ).build()
        )

        return builder.build()
    }

    private fun createMediaActionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = "com.sidetrack.MEDIA_$action"
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(title: String, artist: String, isPlaying: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, artist, isPlaying))
    }

    private suspend fun loadBitmap(url: String): Bitmap? {
        return try {
            val loader = this@PlaybackService.imageLoader
            val request = ImageRequest.Builder(this)
                .data(url)
                .size(128)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            (result as? SuccessResult)?.drawable?.let { drawable ->
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888,
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendMediaCommand(command: String, positionMs: Long = 0) {
        MediaCommandBridge.onCommand?.invoke(command, positionMs)
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = sendMediaCommand("play")
        override fun onPause() = sendMediaCommand("pause")
        override fun onSkipToNext() = sendMediaCommand("next")
        override fun onSkipToPrevious() = sendMediaCommand("previous")
        override fun onStop() = sendMediaCommand("stop")
        override fun onSeekTo(pos: Long) = sendMediaCommand("seek", pos)
    }
}
