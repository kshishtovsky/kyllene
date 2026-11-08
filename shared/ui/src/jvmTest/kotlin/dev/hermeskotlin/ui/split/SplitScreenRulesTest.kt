package dev.hermeskotlin.ui.split

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The split screen's own rule, the one the button is on or off by. */
class SplitScreenRulesTest {
    @Test
    fun aPaneSplitsOnlyWhileThereIsRoomForTwo() {
        assertTrue(canSplit(count = 1, paneDp = 600f, minPaneDp = 240f))
        // Two halves of 300 dp on a 480 dp pane: 240 each, so it still fits.
        assertTrue(canSplit(count = 3, paneDp = 480f, minPaneDp = 240f))
        assertFalse(canSplit(count = 3, paneDp = 470f, minPaneDp = 240f))
        assertFalse(canSplit(count = MAX_PANES, paneDp = 1200f, minPaneDp = 240f))
        // Before the pane has measured: no room known, so no split rather than one that overflows.
        assertFalse(canSplit(count = 1, paneDp = null, minPaneDp = 240f))
    }
}
