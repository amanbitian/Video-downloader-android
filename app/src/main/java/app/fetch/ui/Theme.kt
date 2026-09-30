package app.fetch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.fetch.settings.ThemeMode

val Blue = Color(0xFF2F6BFF)

private val LightColors = lightColorScheme(
    primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFFE1E6F4), onPrimaryContainer = Color(0xFF0B2A73),
    background = Color(0xFFFAFAFA), surface = Color.White, onSurface = Color(0xFF16181D), onSurfaceVariant = Color(0xFF626873),
    surfaceVariant = Color(0xFFF0F1F5), surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF7F8FA),
    outlineVariant = Color(0xFFE8E9EC), error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AA9FF), onPrimary = Color(0xFF0B2A73), primaryContainer = Color(0xFF1E3570), onPrimaryContainer = Color(0xFFDCE4FF),
    background = Color(0xFF111316), surface = Color(0xFF16181D), onSurface = Color(0xFFE6E8EC), onSurfaceVariant = Color(0xFFA3A9B4),
    surfaceVariant = Color(0xFF23262D), surfaceContainer = Color(0xFF1B1E23), surfaceContainerHigh = Color(0xFF23262D),
    outlineVariant = Color(0xFF30343C), error = Color(0xFFF2B8B5),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun FetchTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
