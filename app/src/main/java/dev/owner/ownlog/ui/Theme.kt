package dev.owner.ownlog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1C1915)
private val Paper = Color(0xFFF3EEE4)
private val Card = Color(0xFFFFFBF5)
private val Amber = Color(0xFF8C4A12)
private val Moss = Color(0xFF2F6F4E)
private val Clay = Color(0xFF9C3B2E)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color(0xFFFFF8EF),
    secondary = Moss,
    onSecondary = Color(0xFFF4FFF8),
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E0D4),
    onSurfaceVariant = Color(0xFF534C43),
    error = Clay,
    outline = Color(0xFFD4CBBE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE2A15A),
    onPrimary = Ink,
    secondary = Color(0xFF8FBF9F),
    onSecondary = Ink,
    background = Color(0xFF12100E),
    onBackground = Paper,
    surface = Color(0xFF1E1A16),
    onSurface = Paper,
    surfaceVariant = Color(0xFF2A251F),
    onSurfaceVariant = Color(0xFFD4CBBE),
    error = Color(0xFFF0A598),
    outline = Color(0xFF3A342C),
)

@Composable
fun OwnLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
