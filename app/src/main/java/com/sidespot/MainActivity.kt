package com.sidespot

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.settings.SettingsManager
import com.sidespot.ui.SidespotNavigation
import com.sidespot.ui.SidespotTheme
import com.sidespot.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private var playerViewModel: PlayerViewModel? = null
    private lateinit var authManager: AuthManager
    private lateinit var settingsManager: SettingsManager

    /** Now Playing is a full-screen overlay, not a NavHost destination, so its
     *  visibility can't be derived from the nav back stack — the UI syncs it here. */
    var isNowPlayingVisible: Boolean = false

    var onNowPlayingToggleRequested: (() -> Unit)? = null
    var onTabCycleRequested: (() -> Unit)? = null

    // Center button long-press tracking
    private var centerDownTime = 0L
    private var centerLongPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyImmersiveMode()

        authManager = AuthManager.getInstance(this)
        settingsManager = SettingsManager(this)

        // Push initial config to native before any connect
        settingsManager.pushConfigToNative()

        setContent {
            SidespotTheme {
                val vm: PlayerViewModel = viewModel()
                playerViewModel = vm
                vm.initPlatform(this@MainActivity)

                SidespotNavigation(
                    playerViewModel = vm,
                    authManager = authManager,
                    settingsManager = settingsManager,
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
        }

        // Toggle Now Playing overlay: Tab (Sundial), Soft Right or # (Flip phones)
        if (event.keyCode == KeyEvent.KEYCODE_TAB ||
            event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_POUND
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onNowPlayingToggleRequested?.invoke()
            }
            return true
        }

        // Cycle bottom nav tabs: Soft Left or * (Flip phones)
        if (event.keyCode == KeyEvent.KEYCODE_SOFT_LEFT || event.keyCode == KeyEvent.KEYCODE_STAR) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onTabCycleRequested?.invoke()
            }
            return true
        }

        // Menu key — open row actions sheet
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
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

            // D-pad up/down — volume on Now Playing, focus traversal elsewhere
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isNowPlayingVisible) adjustVolume(up = true)
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isNowPlayingVisible) adjustVolume(up = false)
                else super.onKeyDown(keyCode, event)
            }

            // D-pad left/right — skip track on Now Playing, focus traversal elsewhere
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isNowPlayingVisible) {
                    playerViewModel?.previous(); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isNowPlayingVisible) {
                    playerViewModel?.next(); true
                } else super.onKeyDown(keyCode, event)
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
