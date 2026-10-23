package com.cassiadev.harmonysyncer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    /** The color tokens defined in the design asset, converted to sRGB. */
    @Test
    fun `light colors match the design asset`() {
        assertEquals(Color(0xFF006DEC), LightColors.primary)
        assertEquals(Color(0xFF007165), LightColors.secondary)
        assertEquals(Color(0xFFFFFFFF), LightColors.background) // the design asset paints the page in Surface
        assertEquals(Color(0xFFF8F8F8), LightColors.surfaceVariant) // subtle fill
        assertEquals(Color(0xFF182028), LightColors.onSurface)
        assertEquals(Color(0xFF5C6C7C), LightColors.onSurfaceVariant)
        assertEquals(Color(0xFFAC2724), LightColors.error)
        assertEquals(Color(0xFF0267DC), LightExtraColors.accentOnTint)
    }

    // Compares with the intended brand value, not "differs from the Material baseline":
    // light surfaceContainerLowest and onTertiary are white in both, so an inequality check fails correct code.
    @Test
    fun `component roles are derived from the brand palette`() {
        for ((name, scheme, extra, fill) in cases) {
            val expected = mapOf(
                "surfaceContainerLowest" to (scheme.surfaceContainerLowest to scheme.surface),
                "surfaceContainerLow (bottom sheet)" to (scheme.surfaceContainerLow to scheme.surface),
                "surfaceContainer" to (scheme.surfaceContainer to scheme.surface),
                "surfaceContainerHigh" to (scheme.surfaceContainerHigh to scheme.surface),
                "surfaceContainerHighest" to (scheme.surfaceContainerHighest to scheme.surface),
                "background (page)" to (scheme.background to scheme.surface),
                "surfaceVariant" to (scheme.surfaceVariant to fill),
                "primaryContainer" to (scheme.primaryContainer to scheme.primary.tintOver(scheme.surface, TINT_TILE)),
                "onPrimaryContainer" to (scheme.onPrimaryContainer to extra.accentOnTint),
                "secondaryContainer (progress, slider track)" to
                    (scheme.secondaryContainer to scheme.secondary.tintOver(scheme.surface, TINT_TILE)),
                "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondary),
                "tertiary" to (scheme.tertiary to scheme.secondary),
                "onTertiary" to (scheme.onTertiary to scheme.onSecondary),
                "tertiaryContainer" to (scheme.tertiaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer" to (scheme.onTertiaryContainer to scheme.onSecondaryContainer),
                "outline" to (scheme.outline to scheme.onSurfaceVariant),
            )
            for ((role, colors) in expected) assertEquals("$name $role", colors.second, colors.first)
        }
    }

    /**
     * WCAG (Web Content Accessibility Guidelines) level AA: normal-size text needs a contrast ratio
     * of at least 4.5:1 against its background.
     */
    @Test
    fun `text pairs meet WCAG AA contrast in both schemes`() {
        for ((name, scheme, extra) in cases) {
            val pairs = mapOf(
                "body" to (scheme.onBackground to scheme.background),
                "muted/bg" to (scheme.onSurfaceVariant to scheme.background),
                "muted/surface" to (scheme.onSurfaceVariant to scheme.surface),
                "muted/fill" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "B text/fill" to (scheme.secondary to scheme.surfaceVariant),
                "button" to (scheme.onPrimary to scheme.primary),
                "A text/card (변경)" to (extra.accentOnTint to scheme.primary.tintOver(scheme.surface, TINT_SOFT)),
                "A text/page" to (scheme.primary to scheme.background),
                "B text/card" to (scheme.secondary to scheme.secondary.tintOver(scheme.surface, TINT_SOFT)),
                "muted/A card" to (scheme.onSurfaceVariant to scheme.primary.tintOver(scheme.surface, TINT_SOFT)),
                "muted/B card" to (scheme.onSurfaceVariant to scheme.secondary.tintOver(scheme.surface, TINT_SOFT)),
                "error/box" to (scheme.error to scheme.error.tintOver(scheme.surface, DANGER_SOFT)),
                "success/bg" to (extra.success to scheme.background),
            )
            for ((pair, colors) in pairs) {
                val ratio = computeContrast(colors.first, colors.second)
                assertTrue("$name $pair contrast $ratio", ratio >= 4.5)
            }
        }
    }

    private data class Case(val name: String, val scheme: ColorScheme, val extra: HarmonyExtraColors, val fill: Color)

    private val cases = listOf(
        Case("light", LightColors, LightExtraColors, LightFill),
        Case("dark", DarkColors, DarkExtraColors, DarkFill),
    )

    private fun computeContrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
