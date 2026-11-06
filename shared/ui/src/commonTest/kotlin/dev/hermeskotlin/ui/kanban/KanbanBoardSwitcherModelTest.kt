package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanBoardSwitcherModelTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    /** What the gateway holds; a DELETE really drops the board here, so a reload can't resurrect it. */
    private val gatewayBoards = mutableListOf(
        """{"slug":"dev","name":"Dev","counts":{"running":2,"todo":1},"total":3}""",
        """{"slug":"other","name":"Other","counts":{},"total":0}""",
    )

    private fun boardsJson() = """{"boards":[${gatewayBoards.joinToString(",")}],"current":"dev"}"""

    private val calls = mutableListOf<String>()

    /** One card per board, named after the board's slug: a card of the wrong board is visible at once. */
    private fun client() = createHttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/api/plugins/kanban")
            val board = request.url.parameters["board"]
            calls += "${request.method.value} $path" + (board?.let { "?board=$it" } ?: "")
            val json = when {
                path == "/boards" && request.method == HttpMethod.Get -> boardsJson()
                path == "/board" && request.method == HttpMethod.Get ->
                    """{"columns":[{"name":"todo","tasks":[{"id":"t_$board","title":"$board","status":"todo"}]}],"latest_event_id":7}"""
                path == "/boards" && request.method == HttpMethod.Post -> """{"board":{"slug":"new","name":"New","total":0}}"""
                path.startsWith("/boards/") && request.method == HttpMethod.Patch -> """{"board":{"slug":"dev","name":"Renamed"}}"""
                path.startsWith("/boards/") && request.method == HttpMethod.Delete -> {
                    gatewayBoards.removeAll { it.contains("\"slug\":\"${path.removePrefix("/boards/")}\"") }
                    """{"result":{"slug":"other","action":"archived"}}"""
                }
                else -> error("unexpected ${request.method.value} $path")
            }
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private class Subject(val model: KanbanBoardSwitcherModel, val repository: KanbanRepository) {
        /** The cards the board cache holds, whatever lane they're in. */
        val cards get() = repository.state.value.board.columns.flatMap { it.tasks }.map { it.id }
    }

    /** The model with the board cache it feeds, and the slugs its stream was restarted on. */
    private fun TestScope.subject(store: InMemoryKeyValueStore = InMemoryKeyValueStore()): Pair<Subject, List<String>> {
        val reopened = mutableListOf<String>()
        val api = KanbanApi(client())
        val repository = KanbanRepository(api)
        val model = KanbanBoardSwitcherModel(api, repository, store, this, reopenEvents = { reopened += it })
        return Subject(model, repository) to reopened
    }

    // The engine answers on its own dispatcher, so these wait for real calls; virtual time never runs them.
    private suspend fun KanbanBoardSwitcherModel.loaded() = withContext(Dispatchers.Default) {
        withTimeout(5_000) { state.first { !it.loading && it.current != null } }
    }

    private suspend fun KanbanBoardSwitcherModel.idle() = withContext(Dispatchers.Default) {
        withTimeout(5_000) { state.first { !it.saving && !it.loading } }
    }

    private suspend fun KanbanRepository.onBoard(slug: String) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { state.first { it.slug == slug && !it.loading && it.board.columns.isNotEmpty() } }
    }

    @Test
    fun theListIsReadAndTheBoardLastLeftOnOpensAgain() = runTest {
        val store = InMemoryKeyValueStore()
        val (first, reopened) = subject(store)
        first.model.load(url, "work")
        first.model.loaded()

        assertEquals(listOf("dev", "other"), first.model.state.value.boards.map { it.slug })
        assertEquals("dev", first.model.state.value.current)
        first.repository.onBoard("dev")
        assertEquals(listOf("t_dev"), first.cards)

        first.model.select("other")
        first.repository.onBoard("other")
        assertEquals("other", first.model.state.value.current)
        assertEquals("other", reopened.last())

        // A new session on the same gateway and profile opens the board the user left on, not the gateway's own.
        val (second, reopenedAgain) = subject(store)
        second.model.load(url, "work")
        second.model.loaded()
        second.repository.onBoard("other")

        assertEquals("other", second.model.state.value.current)
        assertEquals(listOf("other"), reopenedAgain)
        assertEquals(listOf("t_other"), second.cards)
    }

    @Test
    fun pickingABoardDropsTheOldCardsAndRestartsTheStream() = runTest {
        val (subject, reopened) = subject()
        subject.model.load(url, null)
        subject.model.loaded()
        subject.repository.onBoard("dev")

        subject.model.select("other")
        subject.repository.onBoard("other")

        assertEquals(listOf("t_other"), subject.cards)
        assertEquals("other", reopened.last())
        assertTrue(calls.contains("GET /board?board=other"))

        subject.model.select("dev")
        subject.repository.onBoard("dev")

        assertEquals(listOf("t_dev"), subject.cards)
        assertEquals("dev", reopened.last())
    }

    @Test
    fun deletingAsksBeforeAnythingIsSent() = runTest {
        val (subject, _) = subject()
        subject.model.load(url, null)
        subject.model.loaded()
        val other = subject.model.state.value.boards.first { it.slug == "other" }

        subject.model.askDelete(other)
        assertEquals(other, subject.model.state.value.deleting)
        assertTrue(calls.none { it.startsWith("DELETE") })

        subject.model.confirmDelete()
        subject.model.idle()

        assertTrue(calls.contains("DELETE /boards/other"))
        assertNull(subject.model.state.value.deleting)
        assertEquals(listOf("dev"), subject.model.state.value.boards.map { it.slug })
        assertEquals("dev", subject.model.state.value.current)
    }

    @Test
    fun aNewBoardBecomesTheOneShown() = runTest {
        val (subject, reopened) = subject()
        subject.model.load(url, null)
        subject.model.loaded()

        subject.model.create("New", "New")
        subject.model.idle()

        assertTrue(calls.contains("POST /boards"))
        assertEquals("new", subject.model.state.value.current)
        assertEquals("new", subject.repository.state.value.slug)
        assertEquals("new", reopened.last())
    }

    @Test
    fun aMalformedSlugNeverReachesTheGateway() = runTest {
        val (subject, _) = subject()
        subject.model.load(url, null)
        subject.model.loaded()

        subject.model.create("Bad Slug!!")

        assertTrue(calls.none { it.startsWith("POST") })
        assertTrue(subject.model.state.value.error?.contains("slug") == true)
    }
}
