package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A token stream must not reprint the reply per token, and the final text must still arrive. */
@OptIn(ExperimentalTestApi::class)
class HeldForReadTest {

    @Test
    fun repaintsOnceForManyTokensAndSettlesOnTheLastText() = runComposeUiTest {
        mainClock.autoAdvance = false
        var text by mutableStateOf("a")
        var streaming by mutableStateOf(true)
        val painted = mutableListOf<String>()
        setContent {
            val shown = heldForRead(text, streaming)
            SideEffect { if (painted.lastOrNull() != shown) painted += shown }
        }
        assertEquals(listOf("a"), painted)

        // A burst of tokens inside one held window: the reply is repainted at the end of it, not once per token.
        repeat(30) { text = "a".repeat(it + 2) }
        mainClock.advanceTimeBy(200)
        assertEquals(listOf("a", "a".repeat(31)), painted)

        // The turn ends: whatever arrived last is shown at once, with no window left to wait for.
        streaming = false
        text = "settled"
        mainClock.advanceTimeByFrame()
        assertEquals("settled", painted.last())
        mainClock.autoAdvance = true
    }

    @Test
    fun settledTextIsNeverHeldBack() = runComposeUiTest {
        var text by mutableStateOf("first")
        val painted = mutableListOf<String>()
        setContent {
            val shown = heldForRead(text, streaming = false)
            SideEffect { if (painted.lastOrNull() != shown) painted += shown }
        }
        text = "second"
        waitForIdle()
        assertEquals(listOf("first", "second"), painted)
    }
}
