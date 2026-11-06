package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.kanban.KanbanDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The board over the chat: a link opens it at its card, and back gives the chat back sheet first. */
class KanbanRouteStateTest {

    @Test
    fun aLinkOpensItsBoardAtItsCard() {
        val route = KanbanRouteState()
        route.show(KanbanDeepLink("dev", "t_d2efca37"))
        assertTrue(route.open)
        assertEquals("dev", route.board)
        assertEquals("t_d2efca37", route.task)
    }

    @Test
    fun theTabOpensTheProfilesOwnBoard() {
        val route = KanbanRouteState()
        route.show()
        assertTrue(route.open)
        assertNull(route.board)
        assertNull(route.task)
    }

    @Test
    fun backClosesTheSheetBeforeTheBoard() {
        val route = KanbanRouteState()
        route.show(KanbanDeepLink("dev", "t_1"))
        assertTrue(route.back())
        assertNull(route.task)
        assertTrue(route.open)
        assertTrue(route.back())
        assertFalse(route.open)
        assertNull(route.board)
        // With nothing left to close, back belongs to the chat under it.
        assertFalse(route.back())
    }

    @Test
    fun aTappedCardOpensItsSheetOnTheOpenBoard() {
        val route = KanbanRouteState()
        route.show()
        route.openTask("t_2")
        assertEquals("t_2", route.task)
        assertTrue(route.back())
        assertTrue(route.open)
    }
}
