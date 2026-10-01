package com.geminilive.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Gemini-inspired color palette ────────────────────────────────────────────
val GeminiBlue = Color(0xFF1A73E8)
val GeminiPurple = Color(0xFF9C27B0)
val GeminiTeal = Color(0xFF00ACC1)
val GeminiGreen = Color(0xFF0F9D58)
val GeminiAmber = Color(0xFFF4B400)
val GeminiRed = Color(0xFFDB4437)

// Dark palette (primary UI)
val DarkBackground = Color(0xFF0A0A0F)
val DarkSurface = Color(0xFF111118)
val DarkSurfaceVariant = Color(0xFF1A1A24)
val DarkCard = Color(0xFF161622)
val DarkBorder = Color(0xFF2A2A3A)
val DarkText = Color(0xFFF0F0FF)
val DarkSubtext = Color(0xFF8888AA)

// Accent gradient colors
val AccentStart = Color(0xFF4285F4)   // Google Blue
val AccentMid = Color(0xFF9C4DCC)     // Purple
val AccentEnd = Color(0xFF00BCD4)     // Teal

private val DarkColorScheme = darkColorScheme(
    primary = AccentStart,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C3A6E),
    secondary = AccentMid,
    onSecondary = Color.White,
    tertiary = AccentEnd,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkSubtext,
    outline = DarkBorder,
    error = GeminiRed
)

@Composable
fun GeminiLiveTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
