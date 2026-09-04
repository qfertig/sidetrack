package com.sidetrack.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DPAD_NAV_CODES = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

private val DPAD_BACK_CODES = setOf(
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_ESCAPE,
)

/** Global toggle: true after any D-pad key, false after any touch. Default true for keypad devices. */
private val dpadActive = mutableStateOf(true)

fun Modifier.focusHighlight(
    color: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
    onEnterKey: (() -> Unit)? = null,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 8.dp,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val showHighlight by dpadActive
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val fillColor = if (hasFocus && showHighlight) resolvedColor.copy(alpha = 0.5f) else Color.Transparent

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    dpadActive.value = false
                }
            }
        }
        .onFocusChanged { hasFocus = it.hasFocus }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == KeyEvent.ACTION_DOWN) {
                dpadActive.value = true
            }
            if (onEnterKey != null && hasFocus) {
                val kc = native.keyCode
                when {
                    native.action == KeyEvent.ACTION_DOWN && (
                        kc == KeyEvent.KEYCODE_ENTER ||
                        kc == KeyEvent.KEYCODE_MENU ||
                        kc == KeyEvent.KEYCODE_SOFT_RIGHT ||
                        (kc == KeyEvent.KEYCODE_DPAD_CENTER && native.isLongPress)
                    ) -> {
                        onEnterKey()
                        return@onPreviewKeyEvent true
                    }
                    kc == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            onEnterKey()
                        }
                        return@onPreviewKeyEvent true
                    }
                }
            }
            false
        }
        .focusable()
        .background(fillColor, shape)
        .padding(horizontal = horizontalPadding, vertical = verticalPadding)
}

/** Draws a white border on a filled button when focused via D-pad. */
fun Modifier.focusDarken(): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val showHighlight by dpadActive

    this
        .onFocusChanged { hasFocus = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (hasFocus && showHighlight) {
                val hInset = 0.dp.toPx()
                val vInset = 2.dp.toPx()
                drawRoundRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(hInset, vInset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - hInset * 2,
                        size.height - vInset * 2,
                    ),
                    style = Stroke(width = 3.dp.toPx()),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
        }
}

/** Draws a filled circle behind an IconButton when focused via D-pad. */
fun Modifier.focusCircle(
    color: Color = Color.Unspecified,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val showHighlight by dpadActive
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color

    this
        .onFocusChanged { hasFocus = it.hasFocus }
        .drawWithContent {
            if (hasFocus && showHighlight) {
                drawCircle(resolvedColor.copy(alpha = 0.5f))
            }
            drawContent()
        }
}

/** Intercept DPAD_LEFT/RIGHT inside bottom sheet popups and dismiss.
 *  Also consume MEDIA_PLAY_PAUSE so it doesn't leak to the Activity. */
fun Modifier.dismissOnDpad(onDismiss: () -> Unit): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode in DPAD_BACK_CODES
        ) {
            onDismiss()
            true
        } else false
    }
    .onKeyEvent { event ->
        // Bubble-phase safety net: consume any MEDIA_PLAY_PAUSE not caught by
        // a focused child (e.g. ACTION_UP arriving after the action row recomposes).
        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }
