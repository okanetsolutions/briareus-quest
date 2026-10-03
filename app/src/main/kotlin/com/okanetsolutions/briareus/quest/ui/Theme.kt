package com.okanetsolutions.briareus.quest.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The dashboard's palette: its dark `@theme` block verbatim. */
@Immutable
data class Palette(
    val canvas: Color, val sidebar: Color, val raise: Color, val field: Color, val sunken: Color,
    val line: Color, val lineStrong: Color, val ink: Color, val muted: Color,
    val accent: Color, val accentDim: Color, val ok: Color, val warn: Color, val danger: Color, val onAccent: Color,
)

val DarkPalette = Palette(
    canvas = Color(0xFF262624), sidebar = Color(0xFF1F1E1D), raise = Color(0xFF30302E), field = Color(0xFF3D3D3A),
    sunken = Color(0xFF1C1C1A), line = Color(0xFF3E3E3A), lineStrong = Color(0xFF5A5850), ink = Color(0xFFE8E6E1),
    muted = Color(0xFFA29E93), accent = Color(0xFFD97757), accentDim = Color(0xFFB35C3E), ok = Color(0xFF7FBF7F),
    warn = Color(0xFFE0AF68), danger = Color(0xFFE06C75), onAccent = Color(0xFF1B1B19),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Mono = FontFamily.Monospace

@Composable
fun BriareusTheme(content: @Composable () -> Unit) {
    // Always dark, as the dashboard is: in a headset a bright panel glares beside a video.
    val p = DarkPalette
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accentDim, background = p.canvas, onBackground = p.ink,
        surface = p.raise, onSurface = p.ink, surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.line,
        outlineVariant = p.line, error = p.danger, surfaceContainer = p.raise, surfaceContainerHigh = p.field,
        surfaceContainerHighest = p.field, surfaceContainerLow = p.sidebar, surfaceContainerLowest = p.sunken,
    )
    // Larger than a desktop's: panels sit a metre or more away.
    val type = Typography(
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
