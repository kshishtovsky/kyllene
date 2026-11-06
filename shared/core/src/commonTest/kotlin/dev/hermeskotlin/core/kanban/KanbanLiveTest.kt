package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanbanLiveTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    private val boardJson = """
        {"columns":[{"name":"ready","tasks":[{"id":"t_1","title":"Write it","status":"ready"}]},
                    {"name":"running","tasks":[]}],
         "latest_event_id":285,"now":1793925302}
    """

    @Test
    fun aFrameResetsTheSilenceClock() = runTest {
        val stream = FakeStream()
        var clock = 1_000L
        val socket = KanbanEventsSocket(FakeSource { stream }, silenceMs = 30_000, now = { clock })
        val job = CoroutineScope(coroutineContext).launch { socket.events(url, "dev", 0).collect {} }

        stream.send("""{"events":[{"id":286,"task_id":"t_1","kind":"status"}],"cursor":286}""")
        delay(100)

        assertFalse(socket.isSilent(1_000 + 29_000))
        assertTrue(socket.isSilent(1_000 + 31_000))
        clock = 1_000 + 40_000
        // The socket gives up on that silence by itself and comes back with a fresh connection.
        assertTrue(socket.isSilent())
        job.cancel()
    }

    @Test
    fun aDeadSocketLetsThePollerReadTheBoardAgain() = runTest {
        var reads = 0
        val engine = MockEngine {
            reads++
            respond(boardJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repository = KanbanRepository(KanbanApi(createHttpClient(engine)))
        repository.bind(url, null)
        repository.selectBoard("dev")
        assertEquals(1, reads)

        // No socket at all: every attempt is refused, so the poller is the only thing keeping the board fresh.
        val socket = KanbanEventsSocket(FakeSource { null }, firstDelayMs = 1, maxDelayMs = 8)
        val live = KanbanLive(repository, socket, this, pollMs = 100)
        live.start()
        delay(5_000)

        assertTrue(reads >= 3, "the board is reread while the socket is down, got $reads reads")
        assertFalse(repository.state.value.live)
        live.stop()
    }

    @Test
    fun aLiveSocketKeepsThePollerQuietUntilItGoesSilent() = runTest {
        val engine = MockEngine { respond(boardJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val repository = KanbanRepository(KanbanApi(createHttpClient(engine)))
        repository.bind(url, null)
        repository.selectBoard("dev")

        val stream = FakeStream()
        var opened = 0
        // The socket's own clock, so the 30 s mark can be walked up to exactly.
        var clock = 0L
        val socket = KanbanEventsSocket(
            FakeSource { if (opened++ == 0) stream else null },
            firstDelayMs = 1_000,
            silenceMs = 30_000,
            now = { clock },
        )
        var reloads = 0
        val live = KanbanLive(repository, socket, this, pollMs = 4_000)
        live.openTask("t_1") { reloads++ }
        live.start()

        // An empty frame asks for nothing, but it is the socket being heard — and that is what keeps the
        // fallback off. (Read counts are not asserted here: the board read resumes on the HTTP client's own
        // threads, so a virtual-time window would race it.)
        stream.send("""{"events":[],"cursor":285}""")
        advanceTimeBy(12_000)
        runCurrent()
        assertEquals(0, reloads, "the poller ran while the socket was heard")

        // 29 s since the frame: the mark is not past yet.
        clock = 29_000
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(0, reloads, "the poller ran at 29 s of silence")

        // 31 s: past the mark, the poller takes over.
        clock = 31_000
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(reloads > 0, "the poller never took over after 30 s of silence")
        live.stop()
    }

    @Test
    fun leavingTheScreenClosesTheSocketAndStopsThePolling() = runTest {
        val engine = MockEngine { respond(boardJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val repository = KanbanRepository(KanbanApi(createHttpClient(engine)))
        repository.bind(url, null)
        repository.selectBoard("dev")

        var clock = 0L
        val stream = FakeStream()
        val socket = KanbanEventsSocket(
            FakeSource { stream },
            firstDelayMs = 1_000,
            silenceMs = 30_000,
            now = { clock },
        )
        var reloads = 0
        val live = KanbanLive(repository, socket, this, pollMs = 4_000)
        live.openTask("t_1") { reloads++ }
        live.start()

        // A row for a card the board doesn't hold: the socket is heard, the sheet has nothing to reload.
        stream.send("""{"events":[{"id":286,"task_id":"t_2","kind":"commented"}],"cursor":286}""")
        advanceTimeBy(100)
        runCurrent()
        assertTrue(repository.state.value.live)

        live.stop()
        runCurrent()

        assertTrue(stream.closed, "the socket stays open after the screen is left")
        assertFalse(repository.state.value.live)
        clock = 100_000
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, reloads, "the poller keeps running in the background")
    }

    @Test
    fun theOpenCardIsReloadedByItsOwnRowsAndNotByAnotherCards() = runTest {
        val engine = MockEngine { respond(boardJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val repository = KanbanRepository(KanbanApi(createHttpClient(engine)))
        repository.bind(url, null)
        repository.selectBoard("dev")

        val stream = FakeStream()
        val socket = KanbanEventsSocket(
            FakeSource { stream },
            firstDelayMs = 1_000,
            silenceMs = 30_000,
            now = { testScheduler.currentTime },
        )
        val live = KanbanLive(repository, socket, this, pollMs = 4_000)
        val reloaded = mutableListOf<String>()
        live.openTask("t_1") { reloaded += it }
        live.start()

        // t_2 is not on this board, so nothing the sheet shows has changed.
        stream.send("""{"events":[{"id":286,"task_id":"t_2","kind":"commented"}],"cursor":286}""")
        advanceTimeBy(100)
        runCurrent()
        assertTrue(reloaded.isEmpty())

        stream.send("""{"events":[{"id":287,"task_id":"t_1","kind":"commented"}],"cursor":287}""")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("t_1"), reloaded)
        live.stop()
    }

    private class FakeSource(private val next: () -> KanbanEventStream?) : KanbanEventSource {
        override suspend fun open(url: GatewayUrl, slug: String, since: Long?): KanbanEventStream? = next()
    }

    private class FakeStream : KanbanEventStream {
        private val frames = Channel<String>(Channel.UNLIMITED)

        /** True once the flow closed the socket, as it must when the screen goes away. */
        var closed = false
            private set

        fun send(text: String) {
            frames.trySend(text)
        }

        override suspend fun next(): String? = frames.receiveCatching().getOrNull()

        override suspend fun close() {
            closed = true
            frames.close()
        }
    }
}
