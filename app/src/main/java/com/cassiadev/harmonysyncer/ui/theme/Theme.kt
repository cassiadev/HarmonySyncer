package com.cassiadev.harmonysyncer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Tint fractions match the color-mix() ratios in design/css/app.css.
const val TINT_SOFT = 0.07f
const val TINT_TILE = 0.11f
const val TINT_BORDER = 0.16f
const val DANGER_SOFT = 0.06f
const val DANGER_BORDER = 0.18f

/** Mixes [fraction] of this color into [base], like CSS `color-mix(this fraction, base)`. */
fun Color.tintOver(base: Color, fraction: Float): Color = lerp(base, this, fraction)

/** Brand colors with no Material 3 role. */
@Immutable
data class HarmonyExtraColors(val success: Color, val accentOnTint: Color)

internal val LightExtraColors = HarmonyExtraColors(success = LightSuccess, accentOnTint = LightAccentOnTint)
internal val DarkExtraColors = HarmonyExtraColors(success = DarkSuccess, accentOnTint = DarkAccent)

val LocalHarmonyExtraColors = staticCompositionLocalOf { LightExtraColors }

// Every role a component reads is set, so no Material baseline (purple) color can show through.
private fun ColorScheme.withBrand(
    fill: Color,
    surface: Color,
    foreground: Color,
    muted: Color,
    border: Color,
    accent: Color,
    voice: Color,
    danger: Color,
    onAccent: Color,
    accentOnTint: Color,
): ColorScheme {
    val voiceContainer = voice.tintOver(surface, TINT_TILE)
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.tintOver(surface, TINT_TILE),
        onPrimaryContainer = accentOnTint,
        secondary = voice,
        onSecondary = onAccent,
        secondaryContainer = voiceContainer,
        onSecondaryContainer = voice,
        // Unused by the app; mapped to Audio B so the baseline pink never appears.
        tertiary = voice,
        onTertiary = onAccent,
        tertiaryContainer = voiceContainer,
        onTertiaryContainer = voice,
        background = surface,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        surfaceVariant = fill,
        onSurfaceVariant = muted,
        surfaceTint = accent,
        error = danger,
        onError = onAccent,
        errorContainer = danger.tintOver(surface, DANGER_SOFT),
        onErrorContainer = danger,
        outline = muted,
        outlineVariant = border,
        surfaceBright = surface,
        surfaceDim = surface,
        // The design uses one flat surface; ModalBottomSheet reads surfaceContainerLow.
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
    )
}

internal val LightColors = lightColorScheme().withBrand(
    fill = LightFill,
    surface = LightSurface,
    foreground = LightForeground,
    muted = LightMuted,
    border = LightBorder,
    accent = LightAccent,
    voice = LightVoice,
    danger = LightDanger,
    onAccent = LightOnAccent,
    accentOnTint = LightAccentOnTint,
)

internal val DarkColors = darkColorScheme().withBrand(
    fill = DarkFill,
    surface = DarkSurface,
    foreground = DarkForeground,
    muted = DarkMuted,
    border = DarkBorder,
    accent = DarkAccent,
    voice = DarkVoice,
    danger = DarkDanger,
    onAccent = DarkOnAccent,
    accentOnTint = DarkAccent,
)

@Composable
fun HarmonySyncerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHarmonyExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            shapes = HarmonyShapes,
            content = content,
        )
    }
}
