package dev.hermeskotlin.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The inbound frame bound. It cannot live in the Ktor WebSockets config: the OkHttp engine throws
 * "Max frame size switch is not supported" on every upgrade, so the bound is checked per frame here.
 */
class FrameBoundTest {

    @Test
    fun aWholeTranscriptPageFits() {
        assertFalse(exceedsFrameBound(512 * 1024))
    }

    @Test
    fun theLimitItselfIsStillAllowed() {
        assertFalse(exceedsFrameBound(MAX_FRAME_BYTES))
    }

    @Test
    fun oneByteOverIsRejected() {
        assertTrue(exceedsFrameBound(MAX_FRAME_BYTES + 1))
    }

    @Test
    fun tooBigClosesWith1009() {
        assertEquals(1009.toShort(), CLOSE_TOO_BIG)
    }
}
