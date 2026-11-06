package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The socket under the reconnecting flow: text frames in, a close on the way out. */
class KanbanStreamTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    @Test
    fun onlyTextFramesAreHandedOnAndAClosedSocketEndsTheStream() = runTest {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        var closed = 0
        val stream = KtorKanbanEventStream(frames) { closed++ }

        frames.trySend(Frame.Binary(true, byteArrayOf(1, 2)))
        frames.trySend(Frame.Text("""{"events":[],"cursor":1}"""))

        assertEquals("""{"events":[],"cursor":1}""", stream.next())
        frames.close()
        assertNull(stream.next())

        stream.close()
        assertEquals(1, closed)
    }

    @Test
    fun aCloseTheSocketAlreadyRefusedIsSwallowed() = runTest {
        // The gateway may have closed it first; that is not a reason to fail the caller leaving the screen.
        KtorKanbanEventStream(Channel()) { error("the socket is already gone") }.close()
    }

    @Test
    fun aRefusedTicketOpensNoStream() = runTest {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { respond("no session", HttpStatusCode.Forbidden) }, cookies)
        val events = GatewayKanbanEvents(client, AuthApi(client, cookies))

        assertNull(events.open(url, "dev", since = 7))
    }
}
