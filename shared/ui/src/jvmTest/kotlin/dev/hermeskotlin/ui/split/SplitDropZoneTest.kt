package dev.hermeskotlin.ui.split

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a chat let go on a pane lands: the quarter along an edge splits the pane that way, the middle takes its
 * chat's place.
 */
class SplitDropZoneTest {

    /** A 400 by 200 pane: the quarters end at 100 across and 50 down. */
    private fun zoneAt(x: Float, y: Float) = dropZoneAt(x, y, width = 400f, height = 200f)

    @Test
    fun theMiddleOfThePaneIsItsMiddle() {
        assertEquals(SplitDropZone.Center, zoneAt(200f, 100f))
        assertEquals(SplitDropZone.Center, zoneAt(150f, 80f))
    }

    @Test
    fun eachEdgeTakesItsQuarter() {
        assertEquals(SplitDropZone.Left, zoneAt(0f, 100f))
        assertEquals(SplitDropZone.Right, zoneAt(399f, 100f))
        assertEquals(SplitDropZone.Top, zoneAt(200f, 1f))
        assertEquals(SplitDropZone.Bottom, zoneAt(200f, 199f))
    }

    @Test
    fun aQuarterEndsWhereTheMiddleBegins() {
        assertEquals(SplitDropZone.Left, zoneAt(99f, 100f))
        assertEquals(SplitDropZone.Center, zoneAt(100f, 100f))
        assertEquals(SplitDropZone.Center, zoneAt(300f, 100f))
        assertEquals(SplitDropZone.Right, zoneAt(301f, 100f))
        assertEquals(SplitDropZone.Top, zoneAt(200f, 49f))
        assertEquals(SplitDropZone.Center, zoneAt(200f, 50f))
        assertEquals(SplitDropZone.Center, zoneAt(200f, 150f))
        assertEquals(SplitDropZone.Bottom, zoneAt(200f, 151f))
    }

    @Test
    fun aCornerGoesToTheEdgeItIsNearerAlong() {
        assertEquals(SplitDropZone.Left, zoneAt(10f, 10f))
        assertEquals(SplitDropZone.Right, zoneAt(390f, 195f))
    }

    @Test
    fun offThePaneThereIsNoZone() {
        assertNull(dropZoneAt(-1f, 100f, 400f, 200f))
        assertNull(dropZoneAt(200f, 201f, 400f, 200f))
        assertNull(dropZoneAt(0f, 0f, 0f, 0f))
    }

    @Test
    fun anEdgeSplitsThePaneThatWayAndTheMiddleReplacesIt() {
        val pane = SplitLayout.single("p1", PaneChat("a", "A", null))
        val chat = PaneChat("b", "B", null)

        val left = pane.dropChat("p1", chat, SplitDropZone.Left, "p2", paneDp = 800f, minPaneDp = 320f)
        assertEquals(listOf("p2", "p1"), left.leaves.map { it.id })
        assertEquals(SplitAxis.Row, (left.root as SplitNode.Branch).axis)
        assertEquals("b", left.leaves.first().chat.storedSessionId)
        assertEquals("p2", left.focused)

        val right = pane.dropChat("p1", chat, SplitDropZone.Right, "p2", paneDp = 800f, minPaneDp = 320f)
        assertEquals(listOf("p1", "p2"), right.leaves.map { it.id })

        val top = pane.dropChat("p1", chat, SplitDropZone.Top, "p2", paneDp = 600f, minPaneDp = 240f)
        assertEquals(listOf("p2", "p1"), top.leaves.map { it.id })
        assertEquals(SplitAxis.Column, (top.root as SplitNode.Branch).axis)

        val bottom = pane.dropChat("p1", chat, SplitDropZone.Bottom, "p2", paneDp = 600f, minPaneDp = 240f)
        assertEquals(listOf("p1", "p2"), bottom.leaves.map { it.id })

        val centre = pane.dropChat("p1", chat, SplitDropZone.Center, "p2", paneDp = 800f, minPaneDp = 320f)
        assertEquals(1, centre.count)
        assertEquals("b", centre.leaves.single().chat.storedSessionId)
        assertEquals("p1", centre.focused)
    }

    @Test
    fun aPaneWithNoRoomToSplitTakesTheChatItself() {
        val replaced = SplitLayout.single("p1", PaneChat("a", "A", null))
            .dropChat("p1", PaneChat("b", "B", null), SplitDropZone.Left, "p2", paneDp = 400f, minPaneDp = 320f)
        assertEquals(1, replaced.count)
        assertEquals("b", replaced.leaves.single().chat.storedSessionId)
    }
}
