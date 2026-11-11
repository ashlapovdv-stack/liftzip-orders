package ru.liftzip.orders.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF174C3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E9DF),
    onPrimaryContainer = Color(0xFF092D23),
    secondary = Color(0xFF48635A),
    secondaryContainer = Color(0xFFE1EAE5),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFF7F9F8),
    surfaceVariant = Color(0xFFEDF1EF),
    onSurface = Color(0xFF17211D),
    onSurfaceVariant = Color(0xFF4B5953),
    error = Color(0xFF9E2F2F),
    errorContainer = Color(0xFFFFE4E1),
)

@Composable
fun OrdersTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
