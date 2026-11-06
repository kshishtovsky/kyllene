package dev.hermeskotlin.ui.kanban

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanBoard
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanEventSource
import dev.hermeskotlin.core.kanban.KanbanEventStream
import dev.hermeskotlin.core.kanban.KanbanEventsSocket
import dev.hermeskotlin.core.kanban.KanbanLive
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.kanban.KanbanState
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.designsystem.HermesTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the board does while the app is running: a lane with nothing in it, a failed read, and a socket event that
 * carries a card from one lane to another without the screen being reloaded.
 */
@OptIn(ExperimentalTestApi::class)
class KanbanBoardLiveTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    private val card = KanbanTask(id = "t_1", title = "Fix the driveway", status = "todo")

    private fun board(vararg tasks: KanbanTask) = KanbanBoard(
        columns = kanbanLanes.map { lane -> KanbanColumn(lane.first, tasks.filter { it.status == lane.first }) },
    )

    /** One card, in whichever lane the gateway says it is now. */
    private fun boardJson(status: String): String {
        val task = """{"id":"t_1","title":"Fix the driveway","status":"$status"}"""
        val lanes = listOf("todo", "running").joinToString(",") { lane ->
            """{"name":"$lane","tasks":[${if (lane == status) task else ""}]}"""
        }
        return """{"columns":[$lanes],"latest_event_id":285,"now":1793925302}"""
    }

    /**
     * The lane a card is drawn in: the eight headers sit above their lanes in order, so the one nearest the card
     * across the row is that card's own.
     */
    private fun ComposeUiTest.laneOf(title: String): String {
        val card = onNodeWithText(title).fetchSemanticsNode().boundsInRoot.center.x
        return kanbanLanes.map { it.second }
            .minBy { lane -> kotlin.math.abs(onNodeWithText(lane).fetchSemanticsNode().boundsInRoot.center.x - card) }
    }

    @Test
    fun aLaneWithNothingInItSaysSo() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                KanbanBoardContent(KanbanState(board = board(card)), "Dev", desktop = true)
            }
        }
        // The card is in To Do, so the other seven lanes say they are empty and don't disappear.
        assertEquals(7, onAllNodesWithText("Empty").fetchSemanticsNodes().size)
    }

    @Test
    fun aRefusedReadKeepsTheCacheAndOffersARetry() = runComposeUiTest {
        var retried = 0
        val state = KanbanState(slug = "dev", board = board(card), error = "Connection refused")
        setContent {
            HermesTheme(ColorScheme.Dark) {
                KanbanBoardContent(state, "Dev", desktop = true, onRetry = { retried++ })
            }
        }
        onNodeWithText("Connection refused").assertExists()
        // The cards read before the failure stay: the banner goes over the cache, not instead of it.
        onNodeWithText("Fix the driveway").assertExists()
        onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun anEventCarriesTheCardToItsNewLane() = runComposeUiTest {
        var movedToRunning = false
        val engine = MockEngine {
            respond(
                boardJson(if (movedToRunning) "running" else "todo"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repository = KanbanRepository(KanbanApi(createHttpClient(engine)))
        val stream = FakeStream()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            repository.bind(url, null)
            scope.launch { repository.selectBoard("dev") }
            setContent {
                val state by repository.state.collectAsState()
                HermesTheme(ColorScheme.Dark) { KanbanBoardContent(state, "Dev", desktop = true) }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Fix the driveway").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("To Do", laneOf("Fix the driveway"))

            // The worker claims the card: the socket says where it went, and the send is the app's own.
            val live = KanbanLive(repository, KanbanEventsSocket(FakeSource(stream)), scope, pollMs = 60_000)
            live.start()
            movedToRunning = true
            scope.launch {
                stream.send(
                    """{"events":[{"id":286,"task_id":"t_1","kind":"status","payload":{"status":"running"}}],"cursor":286}""",
                )
            }

            waitUntil(timeoutMillis = 10_000) { laneOf("Fix the driveway") == "Running" }
            onNodeWithText("Fix the driveway").assertExists()
            live.stop()
        } finally {
            scope.cancel()
        }
    }

    private class FakeStream : KanbanEventStream {
        private val frames = Channel<String>(Channel.UNLIMITED)
        suspend fun send(text: String) {
            frames.send(text)
        }

        override suspend fun next(): String? = frames.receiveCatching().getOrNull()

        override suspend fun close() {}
    }

    private class FakeSource(private val stream: KanbanEventStream) : KanbanEventSource {
        override suspend fun open(url: GatewayUrl, slug: String, since: Long?): KanbanEventStream = stream
    }
}
