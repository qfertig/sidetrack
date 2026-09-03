package com.sidespot.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidespot.auth.AuthState
import com.sidespot.auth.ZeroconfState

@Composable
fun LoginScreen(
    authState: AuthState,
    zeroconfState: ZeroconfState,
    onSignIn: () -> Unit,
    onPairWithSpotifyApp: () -> Unit,
    onCancelPairing: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            authState.isAuthenticated -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    color = Color(0xFF1DB954),
                )
            }

            zeroconfState != ZeroconfState.Idle -> {
                PairingScreen(
                    zeroconfState = zeroconfState,
                    onCancel = onCancelPairing,
                )
            }

            else -> {
                val primaryFocusRequester = remember { FocusRequester() }

                LaunchedEffect(Unit) {
                    try {
                        primaryFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                }

                // Scrollable so the buttons survive tiny screens, but centered so
                // short content fills the middle of the display instead of leaving
                // a huge black band at the bottom.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "sidespot",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Button 1: Sign in on Phone
                        DpadButton(
                            onClick = onSignIn,
                            enabled = !authState.isLoading,
                            isPrimary = true,
                            focusRequester = primaryFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) { isFocused ->
                            if (authState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                            } else {
                                Text(
                                    text = "Sign in on Phone",
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Button 2: Pair with the Spotify app on another phone (Zeroconf,
                        // same network — no typing on this device's keypad required)
                        DpadButton(
                            onClick = onPairWithSpotifyApp,
                            enabled = !authState.isLoading,
                            isPrimary = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) { isFocused ->
                            Text(
                                text = "Pair with Spotify App",
                                color = if (isFocused) Color.Black else Color(0xFF1DB954),
                                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                        }

                        if (authState.error != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = authState.error,
                                color = Color(0xFFCF6679),
                                textAlign = TextAlign.Center,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DpadButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null,
    content: @Composable RowScope.(isFocused: Boolean) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)

    val baseModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else modifier

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> Color.White
                isPrimary -> Color(0xFF1DB954)
                else -> Color(0xFF242424)
            },
        ),
        border = if (isFocused) {
            BorderStroke(3.dp, Color(0xFF1DB954))
        } else if (!isPrimary) {
            BorderStroke(1.dp, Color(0xFF444444))
        } else null,
        modifier = baseModifier
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            },
    ) {
        content(isFocused)
    }
}

@Composable
private fun PairingScreen(
    zeroconfState: ZeroconfState,
    onCancel: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            cancelFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Same scrollable-but-centered pattern as the main login screen.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Pair with Spotify App",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1DB954),
            )

            Spacer(modifier = Modifier.height(10.dp))

            when (zeroconfState) {
                is ZeroconfState.Error -> {
                    Text(
                        text = zeroconfState.message,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                        color = Color(0xFFCF6679),
                    )
                }
                else -> {
                    Text(
                        text = "Open Spotify on your phone, tap the Devices icon, " +
                            "and pick this device from the list.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                        color = Color(0xFFDDDDDD),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF1DB954),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            DpadButton(
                onClick = onCancel,
                isPrimary = false,
                focusRequester = cancelFocusRequester,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
            ) { isFocused ->
                Text(
                    text = "Cancel",
                    color = if (isFocused) Color.Black else Color.White,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
