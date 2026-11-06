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

    private class FakeSource(private val next: () -> KanbanEventStream?) : KanbanEventSource {
        override suspend fun open(url: GatewayUrl, slug: String, since: Long?): KanbanEventStream? = next()
    }

    private class FakeStream : KanbanEventStream {
        private val frames = Channel<String>(Channel.UNLIMITED)

        fun send(text: String) {
            frames.trySend(text)
        }

        override suspend fun next(): String? = frames.receiveCatching().getOrNull()

        override suspend fun close() {
            frames.close()
        }
    }
}
