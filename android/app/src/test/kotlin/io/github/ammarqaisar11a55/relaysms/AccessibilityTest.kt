package io.github.ammarqaisar11a55.relaysms

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.ammarqaisar11a55.relaysms.ui.theme.DarkColors
import io.github.ammarqaisar11a55.relaysms.ui.theme.DarkStatus
import io.github.ammarqaisar11a55.relaysms.ui.theme.LightColors
import io.github.ammarqaisar11a55.relaysms.ui.theme.LightStatus
import io.github.ammarqaisar11a55.relaysms.ui.theme.StatusColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.1 contrast of every text/background pair the app uses, in both themes. */
class AccessibilityTest {
    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun pairs(c: ColorScheme, s: StatusColors) = mapOf(
        "onPrimary/primary" to (c.onPrimary to c.primary),
        "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
        "onBackground/background" to (c.onBackground to c.background),
        "onSurface/surface" to (c.onSurface to c.surface),
        "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
        "onSurfaceVariant/surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
        "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
        "error/surface" to (c.error to c.surface),
        "primary/surface (section headers, links)" to (c.primary to c.surface),
        "status ok" to (s.onOk to s.ok),
        "status warn" to (s.onWarn to s.warn),
    )

    @Test fun `all text colours meet WCAG AA contrast in light and dark themes`() {
        val failures = mutableListOf<String>()
        for ((theme, set) in listOf("light" to pairs(LightColors, LightStatus), "dark" to pairs(DarkColors, DarkStatus))) {
            for ((name, p) in set) {
                val ratio = contrast(p.first, p.second)
                if (ratio < 4.5) failures += "$theme $name = ${"%.2f".format(ratio)}"
            }
        }
        assertTrue("below 4.5:1 -> $failures", failures.isEmpty())
    }
}
