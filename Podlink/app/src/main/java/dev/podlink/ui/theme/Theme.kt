package dev.podlink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val Teal = Color(0xFF3DDCC4)
val TealDim = Color(0xFF1B6F66)
val Coral = Color(0xFFFF6B6B)
val Amber = Color(0xFFFFB84D)
val Mint = Color(0xFF5CE0B8)
val Ink = Color(0xFF0B0E11)
val Surface1 = Color(0xFF14181C)
val Surface2 = Color(0xFF1C2228)
val TextDim = Color(0xFF8A96A3)

private val Dark = darkColorScheme(
    primary = Teal, onPrimary = Ink,
    primaryContainer = TealDim, onPrimaryContainer = Color(0xFFCFFFF6),
    secondary = Mint, onSecondary = Ink,
    background = Ink, onBackground = Color(0xFFE8EDF2),
    surface = Ink, onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Surface2, onSurfaceVariant = TextDim,
    surfaceContainer = Surface1, surfaceContainerHigh = Surface2, surfaceContainerLow = Color(0xFF101418),
    error = Coral, errorContainer = Color(0xFF4A1F22), onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF2A323A), outlineVariant = Color(0xFF222930),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7C86), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFF2EC), onPrimaryContainer = Color(0xFF00363A),
    secondary = Color(0xFF1E9E7C), onSecondary = Color.White,
    background = Color(0xFFF4F6F8), onBackground = Color(0xFF14181C),
    surface = Color(0xFFF4F6F8), onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFE9EDF1), onSurfaceVariant = Color(0xFF5B6873),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEF1F4), surfaceContainerLow = Color(0xFFFAFBFC),
    error = Color(0xFFD64545), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD5DBE1), outlineVariant = Color(0xFFE3E8ED),
)

private val PodlinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * @param theme "system" | "dark" | "light"
 * @param dynamic Material You colours on Android 12+ (falls back to the Podlink palette)
 */
@Composable
fun PodlinkTheme(theme: String = "system", dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    PodlinkTheme(dark = dark, dynamic = dynamic, content = content)
}

@Composable
fun PodlinkTheme(dark: Boolean, dynamic: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, shapes = PodlinkShapes, content = content)
}

fun batteryColor(level: Int?): Color = when {
    level == null -> TextDim
    level <= 20 -> Coral
    level <= 40 -> Amber
    else -> Mint
}
