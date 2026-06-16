package net.micapum.neopen.live.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 (Material You). En Android 12+ los colores se derivan del fondo de
 * pantalla del usuario (color dinámico); en versiones anteriores se usa la paleta
 * de marca azul/verde. Sigue el modo claro/oscuro del sistema.
 *
 * El verde se reserva como "conectado" y el ámbar como "conectando" vía los roles
 * secondary/tertiary, de modo que el color dinámico también respete esa semántica.
 */

// --- Paleta de marca (fallback < Android 12), generada como esquema tonal M3 ---

private val BrandLight = lightColorScheme(
    primary = Color(0xFF4655B6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF386A3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBAF0BC),
    onSecondaryContainer = Color(0xFF00210A),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF96),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF132684),
    primaryContainer = Color(0xFF2D3F9D),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFF9FD4A1),
    onSecondary = Color(0xFF05390F),
    secondaryContainer = Color(0xFF205129),
    onSecondaryContainer = Color(0xFFBAF0BC),
    tertiary = Color(0xFFF5BD48),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF96),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
)

@Composable
fun NeopenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Color dinámico (Material You) disponible en Android 12+.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = NeopenShapes,
        content = content,
    )
}
