package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanEventsTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    /** The frame from §8 of docs/kanban-api.md. */
    private val frameJson = """
        {"events":[
           {"id":307,"task_id":"t_1","run_id":null,"kind":"commented",
            "payload":{"author":"dashboard","len":13},"created_at":1793925272},
           {"id":308,"task_id":"t_1","run_id":null,"kind":"reprioritized",
            "payload":{"priority":3},"created_at":1793925273}],
         "cursor":308}
    """

    @Test
    fun aFrameCarriesItsRowsAndCursor() {
        val frame = parseKanbanFrame(frameJson)!!

        assertEquals(308L, frame.cursor)
        assertEquals(listOf(307L, 308L), frame.events.map { it.id })
        assertEquals("commented", frame.events.first().kind)
    }

    @Test
    fun aCursorIsNeverBehindTheLastRowAndJunkIsNotAFrame() {
        assertEquals(12L, parseKanbanFrame("""{"events":[{"id":12,"task_id":"t_1","kind":"status"}],"cursor":9}""")?.cursor)
        assertNull(parseKanbanFrame("ping"))
        assertNull(parseKanbanFrame("""{"events":[]}"""))
    }

    @Test
    fun theFirstConnectOpensAtTheBoardsTailAndNeverReplays() = runTest {
        val stream = FakeStream()
        val source = FakeSource { stream }
        val socket = KanbanEventsSocket(source, firstDelayMs = 1, maxDelayMs = 8, silenceMs = 1_000)

        // The board was read at event 270; the socket starts right there and the server replays 271 up.
        stream.send("""{"events":[{"id":271,"task_id":"t_1","kind":"status"},{"id":272,"task_id":"t_2","kind":"status"}],"cursor":272}""")
        stream.send("""{"events":[{"id":272,"task_id":"t_2","kind":"status"},{"id":273,"task_id":"t_3","kind":"status"}],"cursor":273}""")

        val got = async { socket.events(url, "dev", tail = 270).take(2).toList() }.await()

        val frames = got.map { (it as KanbanSocketEvent.Frame).frame }
        assertEquals(listOf<Long?>(270L), source.asked)
        assertEquals(listOf(271L, 272L), frames[0].events.map { it.id })
        // 272 already went past: a replay must not hand it to the board a second time.
        assertEquals(listOf(273L), frames[1].events.map { it.id })
        assertEquals(273L, frames[1].cursor)
    }

    @Test
    fun aReconnectResumesAfterTheLastCursor() = runTest {
        val first = FakeStream()
        val second = FakeStream()
        var attempt = 0
        val source = FakeSource { if (attempt++ == 0) first else second }
        val socket = KanbanEventsSocket(source, firstDelayMs = 1, maxDelayMs = 8)

        first.send("""{"events":[{"id":5,"task_id":"t_1","kind":"status"},{"id":6,"task_id":"t_1","kind":"status"}],"cursor":6}""")
        first.end()
        second.send("""{"events":[{"id":7,"task_id":"t_1","kind":"status"}],"cursor":7}""")

        val events = async { socket.events(url, "dev", tail = 0).take(3).toList() }.await()

        assertTrue(events[0] is KanbanSocketEvent.Frame)
        assertTrue(events[1] is KanbanSocketEvent.Retrying)
        // No board snapshot yet, so the first connect carries no `since`; the reconnect carries the cursor.
        assertEquals(listOf(null, 6L), source.asked)
        assertEquals(listOf(7L), (events[2] as KanbanSocketEvent.Frame).frame.events.map { it.id })
    }

    @Test
    fun theDelayDoublesCapsAt15SecondsAndStartsOverAfterAFrame() = runTest {
        val stream = FakeStream()
        stream.send("""{"events":[{"id":1,"task_id":"t_1","kind":"status"}],"cursor":1}""")
        stream.end()
        var attempt = 0
        // Two refusals, a good connection that then drops, then refusals again.
        val source = FakeSource { if (attempt++ == 2) stream else null }
        val socket = KanbanEventsSocket(source, firstDelayMs = 1, maxDelayMs = 15)

        val events = async { socket.events(url, "dev", 0).take(8).toList() }.await()
        val delays = events.filterIsInstance<KanbanSocketEvent.Retrying>().map { it.delayMs }

        assertEquals(listOf(1L, 2L, 1L, 2L, 4L, 8L, 15L), delays)
    }

    @Test
    fun aSilentSocketIsGivenUpOn() = runTest {
        val socket = KanbanEventsSocket(FakeSource { FakeStream() }, firstDelayMs = 1, silenceMs = 50)

        val event = async { socket.events(url, "dev", 0).take(1).toList() }.await().single()

        assertTrue(event is KanbanSocketEvent.Retrying)
    }

    @Test
    fun eachBoardKeepsItsOwnCursor() = runTest {
        val a = FakeStream()
        val b = FakeStream()
        var attempt = 0
        val source = FakeSource { if (attempt++ == 0) a else b }
        val socket = KanbanEventsSocket(source, firstDelayMs = 1, maxDelayMs = 8)

        a.send("""{"events":[{"id":900,"task_id":"t_1","kind":"status"}],"cursor":900}""")
        b.send("""{"events":[{"id":3,"task_id":"t_2","kind":"status"}],"cursor":3}""")

        async { socket.events(url, "dev", 0).take(1).toList() }.await()
        val got = async { socket.events(url, "ops", 0).take(1).toList() }.await()

        // 900 belongs to `dev`; `ops` opens without a cursor rather than skipping its own first 900 rows.
        assertEquals(listOf<Long?>(null, null), source.asked)
        assertEquals(listOf(3L), (got.single() as KanbanSocketEvent.Frame).frame.events.map { it.id })
    }

    /** Hands out one scripted stream per call; records the `since` each attempt asked for. */
    private class FakeSource(private val next: () -> KanbanEventStream?) : KanbanEventSource {
        val asked = mutableListOf<Long?>()

        override suspend fun open(url: GatewayUrl, slug: String, since: Long?): KanbanEventStream? {
            asked += since
            return next()
        }
    }

    private class FakeStream : KanbanEventStream {
        private val frames = Channel<String>(Channel.UNLIMITED)

        fun send(text: String) {
            frames.trySend(text)
        }

        fun end() {
            frames.close()
        }

        override suspend fun next(): String? = frames.receiveCatching().getOrNull()

        override suspend fun close() {
            frames.close()
        }
    }
}
