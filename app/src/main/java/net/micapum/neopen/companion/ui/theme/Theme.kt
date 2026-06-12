package net.micapum.neopen.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF5B8CFF)
private val Green = Color(0xFF3AD29F)
private val Bg = Color(0xFF0E1116)
private val Surface = Color(0xFF161A20)
private val SurfaceVariant = Color(0xFF1F242C)
private val OnSurface = Color(0xFFE6E9EF)
private val Muted = Color(0xFF8A93A2)

private val Colors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Green,
    background = Bg,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Muted,
    error = Color(0xFFFF6B6B),
)

@Composable
fun NeopenTheme(content: @Composable () -> Unit) {
    // El companion es de tema oscuro, como la ventana del boli en la web.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = Colors, content = content)
}
