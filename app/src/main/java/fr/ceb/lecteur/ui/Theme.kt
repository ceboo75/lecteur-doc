package fr.ceb.lecteur.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Petrol = Color(0xFF1F4E5F)
val Amber = Color(0xFFF2B134)
val PdfColor = Color(0xFFC8553D)
val WordColor = Color(0xFF2F6FB2)

private val LightColors = lightColorScheme(
    primary = Petrol,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6EC),
    onPrimaryContainer = Color(0xFF0B2A35),
    secondary = Color(0xFF8A6A1F),
    background = Color(0xFFF7F6F3),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFF7F6F3),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFE9E7E2),
    onSurfaceVariant = Color(0xFF5E6166),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF2F0EC),
    surfaceContainerHigh = Color(0xFFECEAE5),
    surfaceContainerHighest = Color(0xFFE6E3DE),
    outlineVariant = Color(0xFFDDDAD3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCBDD),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF1F4E5F),
    onPrimaryContainer = Color(0xFFD3E6EC),
    secondary = Amber,
    background = Color(0xFF121416),
    onBackground = Color(0xFFE3E2E0),
    surface = Color(0xFF121416),
    onSurface = Color(0xFFE3E2E0),
    surfaceVariant = Color(0xFF2A2D30),
    onSurfaceVariant = Color(0xFFB4B8BC),
    surfaceContainerLowest = Color(0xFF0D0F11),
    surfaceContainerLow = Color(0xFF1B1E21),
    surfaceContainer = Color(0xFF1F2225),
    surfaceContainerHigh = Color(0xFF26292C),
    surfaceContainerHighest = Color(0xFF2E3134),
    outlineVariant = Color(0xFF3A3D41),
)

@Composable
fun LecteurTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
