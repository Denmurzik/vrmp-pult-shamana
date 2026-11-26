package ru.nsu.vrmp.shaman.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ShamanDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD8B9FF),
    onPrimary = Color(0xFF3E1E6B),
    primaryContainer = Color(0xFF4B3274),
    onPrimaryContainer = Color(0xFFEFDBFF),
    secondary = Color(0xFFCBBFD9),
    onSecondary = Color(0xFF332A3E),
    secondaryContainer = Color(0xFF3E354B),
    onSecondaryContainer = Color(0xFFE7DBF6),
    tertiary = Color(0xFFF3C26B),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF4A3520),
    onTertiaryContainer = Color(0xFFFFDFB0),
    background = Color(0xFF141118),
    onBackground = Color(0xFFE9E2EE),
    surface = Color(0xFF141118),
    onSurface = Color(0xFFE9E2EE),
    surfaceVariant = Color(0xFF282231),
    onSurfaceVariant = Color(0xFFC9C1D2),
    surfaceContainer = Color(0xFF1E1926),
    surfaceContainerHigh = Color(0xFF272130),
    outline = Color(0xFF6A5F78),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C181D),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val ShamanLightColorScheme = expressiveLightColorScheme().copy(
    primary = Color(0xFF5D3B8C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFDBFF),
    onPrimaryContainer = Color(0xFF23004E),
    tertiary = Color(0xFF8B5D00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDFB0),
    onTertiaryContainer = Color(0xFF2E1D00),
    surface = Color(0xFFFAF6FF),
    surfaceContainer = Color(0xFFF1EBF8),
    surfaceContainerHigh = Color(0xFFE8DFF2),
)

private val ShamanShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Project-wide Material 3 Expressive theme matching the Figma design system. */
@Composable
fun ShamanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) ShamanDarkColorScheme else ShamanLightColorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ShamanShapes,
        content = content,
    )
}
