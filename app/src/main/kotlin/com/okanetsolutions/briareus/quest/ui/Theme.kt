package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import com.okanetsolutions.briareus.tokens.BriareusHeadsetType
import com.okanetsolutions.briareus.tokens.BriareusPalette

val LocalPalette = staticCompositionLocalOf { BriareusPalette.Dark }

val Mono = FontFamily.Monospace

/** The shared Briareus tokens, light or dark as the system is set, with the headset's larger type. */
@Composable
fun BriareusTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) BriareusPalette.Dark else BriareusPalette.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accentDim, background = p.canvas, onBackground = p.ink,
            surface = p.raise, onSurface = p.ink, surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.line,
            outlineVariant = p.line, error = p.danger, surfaceContainer = p.raise, surfaceContainerHigh = p.field,
            surfaceContainerHighest = p.field, surfaceContainerLow = p.sidebar, surfaceContainerLowest = p.sunken,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accentDim, background = p.canvas, onBackground = p.ink,
            surface = p.raise, onSurface = p.ink, surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.line,
            outlineVariant = p.line, error = p.danger, surfaceContainer = p.raise, surfaceContainerHigh = p.field,
            surfaceContainerHighest = p.field, surfaceContainerLow = p.sidebar, surfaceContainerLowest = p.sunken,
        )
    }
    val type = Typography(
        bodyLarge = BriareusHeadsetType.bodyLarge, bodyMedium = BriareusHeadsetType.bodyMedium, bodySmall = BriareusHeadsetType.bodySmall,
        titleLarge = BriareusHeadsetType.titleLarge, titleMedium = BriareusHeadsetType.titleMedium, titleSmall = BriareusHeadsetType.titleSmall,
        labelLarge = BriareusHeadsetType.labelLarge, labelMedium = BriareusHeadsetType.labelMedium, labelSmall = BriareusHeadsetType.labelSmall,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
