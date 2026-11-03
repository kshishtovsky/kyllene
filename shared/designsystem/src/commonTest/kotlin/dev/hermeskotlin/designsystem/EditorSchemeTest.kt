package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editor schemes bring their own colours, so nothing in Settings checks them: each one is held to the same
 * readability the app's own palettes are, or the text in it is unreadable on a real screen.
 */
class EditorSchemeTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private val schemes = mapOf(
        "gruvbox" to gruvboxColors(),
        "kanagawa" to kanagawaColors(),
        "vesper" to vesperColors(),
        "hyperliquid" to hyperliquidColors(),
    )

    /**
     * Body text and the secondary step at AA on every ground they sit on; the tertiary step at 4:1, which is where the
     * app's own palette keeps its muted steps, the densest ground being the selected row's.
     */
    @Test
    fun everyStepOfTextReadsOnEveryGround() {
        for ((name, colors) in schemes) {
            val grounds = listOf(background, surface, surface2, surface3, surfaceElevated, well).map { colors.getValue(it) }
            for ((role, least) in listOf(text to 4.5f, textSecondary to 4.5f, textTertiary to 4f)) {
                for (ground in grounds) {
                    val ratio = contrast(colors.getValue(role), ground)
                    assertTrue(ratio >= least, "$name: $role on $ground is $ratio:1")
                }
            }
        }
    }

    /** A message's own bubble carries the accent under it, and a button's label the same. */
    @Test
    fun theAccentCarriesReadableText() {
        for ((name, colors) in schemes) {
            val ratio = contrast(colors.getValue(onUserBubble), colors.getValue(userBubble))
            assertTrue(ratio >= 4.5f, "$name: text on the accent is $ratio:1")
        }
    }

    /** Warnings and errors are read, not just seen: they must not sink into the ground. */
    @Test
    fun statusColoursReadOnTheBackground() {
        for ((name, colors) in schemes) {
            for (role in listOf(danger, success, warning)) {
                val ratio = contrast(colors.getValue(role), colors.getValue(background))
                assertTrue(ratio >= 3f, "$name: $role on the background is $ratio:1")
            }
        }
    }
}
