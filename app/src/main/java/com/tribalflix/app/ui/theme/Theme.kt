package com.tribalflix.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = 0xFFBB86FC,
    secondary = 0xFF03DAC6,
    background = 0xFF121212,
    surface = 0xFF1E1E1E,
    error = 0xFFCF6679
)

private val LightColorScheme = lightColorScheme(
    primary = 0xFF6200EE,
    secondary = 0xFF018786,
    background = 0xFFFFFFFF,
    surface = 0xFFF5F5F5,
    error = 0xFFB3261E
)

@Composable
fun TribalFlixTheme(
    darkTheme: Boolean = androidx.compose.material3.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
