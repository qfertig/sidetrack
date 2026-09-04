package com.sidetrack

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidetrack.auth.AuthManager
import com.sidetrack.bridge.NativeBridge
import com.sidetrack.settings.KeyMappingManager
import com.sidetrack.settings.RemappableAction
import com.sidetrack.settings.SettingsManager
import com.sidetrack.ui.SidetrackNavigation
import com.sidetrack.ui.SidetrackTheme
import com.sidetrack.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private var playerViewModel: PlayerViewModel? = null
    private lateinit var authManager: AuthManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var keyMappingManager: KeyMappingManager

    /** Now Playing is a full-screen overlay, not a NavHost destination, so its
     *  visibility can't be derived from the nav back stack — the UI syncs it here. */
    var isNowPlayingVisible: Boolean = false

    var onNowPlayingToggleRequested: (() -> Unit)? = null
    var onTabCycleRequested: (() -> Unit)? = null

    // Whether the current D-pad left/right hold has already fired its long-press
    // action (shuffle/repeat) — if so, the matching key-up must NOT also skip.
    private var leftLongPressed = false
    private var rightLongPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyImmersiveMode()

        authManager = AuthManager.getInstance(this)
        settingsManager = SettingsManager(this)
        keyMappingManager = KeyMappingManager.getInstance(this)

        // Push initial config to native before any connect
        settingsManager.pushConfigToNative()

        setContent {
            SidetrackTheme {
                val vm: PlayerViewModel = viewModel()
                playerViewModel = vm
                vm.initPlatform(this@MainActivity)

                SidetrackNavigation(
                    playerViewModel = vm,
                    authManager = authManager,
                    settingsManager = settingsManager,
                    keyMappingManager = keyMappingManager,
                    mainActivity = this@MainActivity,
                )
            }
        }

        // Keep the window out of touch mode so sundial hardware key events
        // reach dispatchKeyEvent immediately (Android consumes the first
        // navigation-key press to exit touch mode, swallowing it).
        window.decorView.apply {
            post {
                try { requestFocusFromTouch() } catch (_: IllegalStateException) {}
            }
            viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
                if (inTouchMode) post {
                    try { requestFocusFromTouch() } catch (_: IllegalStateException) {}
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    /** Hide status + nav bars — flip phone has no on-screen nav buttons. */
    private fun applyImmersiveMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun adjustVolume(up: Boolean): Boolean {
        val step = 65535 / 20
        val current = NativeBridge.playerGetVolume()
        val newVol = if (up) (current + step).coerceAtMost(65535)
        else (current - step).coerceAtLeast(0)
        NativeBridge.playerSetVolume(newVol)
        playerViewModel?.onVolumeChanged(newVol)
        return true
    }

    private fun dispatchSyntheticKey(keyCode: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        super.dispatchKeyEvent(down)
        super.dispatchKeyEvent(up)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i("Sundial", "keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")

            // Settings > Key Mapping is waiting for a key press to record — capture
            // this one raw, before it can trigger whatever it currently does.
            if (keyMappingManager.consumeIfRecording(event.keyCode)) {
                return true
            }
        }

        // Toggle Now Playing overlay. Default keys: Tab, Soft Right, # — plus
        // whatever this device's own soft key was recorded as in Settings > Key
        // Mapping, since different OEMs send different codes for the same button.
        if (keyMappingManager.matches(RemappableAction.TOGGLE_NOW_PLAYING, event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onNowPlayingToggleRequested?.invoke()
            }
            return true
        }

        // Cycle bottom nav tabs. Default keys: Soft Left, * — see above.
        if (keyMappingManager.matches(RemappableAction.CYCLE_TABS, event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onTabCycleRequested?.invoke()
            }
            return true
        }

        // Row actions sheet. Default key: Menu — see above.
        if (keyMappingManager.matches(RemappableAction.ROW_ACTIONS, event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                dispatchSyntheticKey(KeyEvent.KEYCODE_ENTER)
            }
            return true
        }

        // Center button / OK: Play/Pause on Now Playing
        if (isNowPlayingVisible && (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val vm = playerViewModel ?: return true
                if (vm.uiState.value.isPlaying) vm.pause() else vm.play()
            }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val vm = playerViewModel ?: return true
                if (vm.uiState.value.isPlaying) vm.pause() else vm.play()
            }
            return true
        }

        // D-pad up/down/left/right on Now Playing. Handled here — not in
        // onKeyDown/onKeyUp — because Compose's own default arrow-key focus
        // navigation can otherwise swallow the event first if anything is
        // focused in that direction, so onKeyDown never even sees it.
        //
        // Up enters seek mode (this device has hardware volume keys, so up/down
        // were free); down exits back to normal controls. In seek mode, left/right
        // scrub the track (every repeat while held, for a continuous-scrub feel).
        // Otherwise, left/right short-press skips; since all four directions are
        // claimed here, long-press is the only way left to reach shuffle (left)
        // and repeat (right). The skip itself fires on key-up, not down — firing
        // it on down would skip on every hold, before a long press can register.
        if (isNowPlayingVisible && event.keyCode in NOW_PLAYING_SEEK_KEYS) {
            val seekMode = playerViewModel?.uiState?.value?.isSeekMode == true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        playerViewModel?.enterSeekMode()
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        playerViewModel?.exitSeekMode()
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> when {
                        seekMode -> playerViewModel?.seekRelative(-SEEK_STEP_MS)
                        event.isLongPress && !leftLongPressed -> {
                            leftLongPressed = true
                            playerViewModel?.toggleShuffle()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!seekMode && !leftLongPressed) playerViewModel?.previous()
                        leftLongPressed = false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> when {
                        seekMode -> playerViewModel?.seekRelative(SEEK_STEP_MS)
                        event.isLongPress && !rightLongPressed -> {
                            rightLongPressed = true
                            playerViewModel?.cycleRepeatMode()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!seekMode && !rightLongPressed) playerViewModel?.next()
                        rightLongPressed = false
                    }
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // Hardware volume keys — always adjust volume
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(up = true)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(up = false)

            // Media skip keys
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playerViewModel?.next(); true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playerViewModel?.previous(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playerViewModel?.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playerViewModel?.pause(); true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 5000L
        private val NOW_PLAYING_SEEK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
