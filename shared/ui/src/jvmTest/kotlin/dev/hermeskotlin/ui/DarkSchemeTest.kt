package dev.hermeskotlin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.DarkStyle
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each dark style is drawn in its own palette: the scheme the settings pick is the one the screen paints, not the
 * graphite one with a different name.
 */
@OptIn(ExperimentalTestApi::class)
class DarkSchemeTest {

    @Test
    fun everyDarkStylePaintsItsOwnGround() {
        val grounds = mapOf(
            DarkStyle.Graphite to Color(0xFF0A0A0C),
            DarkStyle.Black to Color.Black,
            DarkStyle.Ayu to Color(0xFF0B0E14),
            DarkStyle.Gruvbox to Color(0xFF282828),
            DarkStyle.Kanagawa to Color(0xFF1F1F28),
            DarkStyle.Vesper to Color(0xFF101010),
            DarkStyle.Hyperliquid to Color(0xFF0F121D),
        )
        for ((style, expected) in grounds) {
            runComposeUiTest {
                setContent {
                    HermesTheme(darkScheme(style)) {
                        Box(Modifier.size(8.dp).testTag("ground").background(Theme[colors][background]))
                    }
                }
                val painted = onNodeWithTag("ground").captureToImage().toPixelMap()[2, 2]
                assertEquals(expected, painted, "$style paints $painted")
            }
        }
    }
}
