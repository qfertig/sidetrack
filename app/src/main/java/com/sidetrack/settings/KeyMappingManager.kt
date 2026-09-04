package com.sidetrack.settings

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Soft-key functions whose physical key varies by OEM. D-pad directions,
 * center, volume, and media keys are standardized across Android and never
 * need remapping — these are the ones that aren't.
 */
enum class RemappableAction(val label: String, val defaultKeyCodes: Set<Int>) {
    TOGGLE_NOW_PLAYING(
        "Show/Hide Now Playing",
        setOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_POUND),
    ),
    CYCLE_TABS(
        "Cycle Tabs (Queue/Library/Search)",
        setOf(KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_STAR),
    ),
    ROW_ACTIONS(
        "Row Actions Menu",
        setOf(KeyEvent.KEYCODE_MENU),
    ),
}

/**
 * Lets a device whose soft keys send different keycodes than the reference
 * TCL layout still use those functions: each [RemappableAction] keeps its
 * built-in default keycodes always working, plus at most one user-recorded
 * override keycode layered on top — recording never breaks the defaults, it
 * only adds a key that also triggers the action.
 */
class KeyMappingManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "sidetrack_keymap"
        private fun prefKey(action: RemappableAction) = "override_${action.name}"

        @Volatile
        private var instance: KeyMappingManager? = null

        fun getInstance(context: Context): KeyMappingManager =
            instance ?: synchronized(this) {
                instance ?: KeyMappingManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _overrides = MutableStateFlow(loadOverrides())
    val overrides: StateFlow<Map<RemappableAction, Int?>> = _overrides.asStateFlow()

    /** Non-null while the Key Mapping screen is waiting for the next physical key press. */
    private val _recordingAction = MutableStateFlow<RemappableAction?>(null)
    val recordingAction: StateFlow<RemappableAction?> = _recordingAction.asStateFlow()

    private fun loadOverrides(): Map<RemappableAction, Int?> =
        RemappableAction.entries.associateWith { action ->
            val stored = prefs.getInt(prefKey(action), -1)
            if (stored == -1) null else stored
        }

    /** True if [keyCode] should trigger [action] — either a recorded override or a built-in default. */
    fun matches(action: RemappableAction, keyCode: Int): Boolean =
        keyCode == _overrides.value[action] || keyCode in action.defaultKeyCodes

    /** Record [keyCode] as the override for [action] and persist it immediately. */
    fun setOverride(action: RemappableAction, keyCode: Int) {
        prefs.edit().putInt(prefKey(action), keyCode).apply()
        _overrides.value = _overrides.value.toMutableMap().apply { put(action, keyCode) }
    }

    /** Remove the recorded override for [action], reverting to just the built-in defaults. */
    fun clearOverride(action: RemappableAction) {
        prefs.edit().remove(prefKey(action)).apply()
        _overrides.value = _overrides.value.toMutableMap().apply { put(action, null) }
    }

    /** Start listening for the next physical key press to bind to [action]. */
    fun startRecording(action: RemappableAction) {
        _recordingAction.value = action
    }

    /** Stop listening without recording anything. */
    fun cancelRecording() {
        _recordingAction.value = null
    }

    /**
     * Called from MainActivity.dispatchKeyEvent before any other key handling.
     * If a recording is in progress, binds [keyCode] to it and consumes the
     * event; otherwise does nothing and lets normal key handling proceed.
     */
    fun consumeIfRecording(keyCode: Int): Boolean {
        val action = _recordingAction.value ?: return false
        setOverride(action, keyCode)
        _recordingAction.value = null
        return true
    }
}
