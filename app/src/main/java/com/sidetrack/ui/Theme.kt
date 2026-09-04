package com.sidetrack.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Spotify-inspired dark color scheme
private val SidetrackColorScheme = darkColorScheme(
    primary = Color(0xFF1DB954),          // Spotify green
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1AA34A),
    secondary = Color(0xFFB3B3B3),        // Light gray for secondary text
    onSecondary = Color.Black,
    background = Color(0xFF121212),        // Spotify dark background
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),           // Slightly lighter surface
    onSurface = Color.White,
    surfaceVariant = Color(0xFF282828),    // Card/elevated surface
    onSurfaceVariant = Color(0xFFB3B3B3),
    error = Color(0xFFCF6679),
    onError = Color.Black,
)

// Typography optimized for 480x640 (2.8") display
internal val SidetrackTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidetrackTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        MaterialTheme(
            colorScheme = SidetrackColorScheme,
            typography = SidetrackTypography,
            content = content,
        )
    }
}
