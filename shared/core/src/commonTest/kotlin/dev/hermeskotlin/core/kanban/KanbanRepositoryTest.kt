package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanRepositoryTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    /** Two lanes; `t_1` is listed twice, as a gateway that repeats a row would. */
    private val boardJson = """
        {"columns":[{"name":"todo","tasks":[
                       {"id":"t_1","title":"Write it","status":"todo","priority":8,"comment_count":2,
                        "link_counts":{"parents":0,"children":1}},
                       {"id":"t_1","title":"Write it","status":"todo","priority":8,"comment_count":2}]},
                    {"name":"running","tasks":[]}],
         "tenants":["acme"],"assignees":["coder"],"latest_event_id":285,"now":1793925302}
    """

    private fun repository() = KanbanRepository(
        KanbanApi(createHttpClient(MockEngine { respond(boardJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
    )

    private fun KanbanState.column(name: String) = board.columns.first { it.name == name }

    @Test
    fun refreshReadsTheBoardAndDropsARepeatedCard() = runTest {
        val repo = repository()
        repo.bind(url, null)

        repo.refresh()

        val state = repo.state.value
        assertNull(state.error)
        assertEquals(1, state.column("todo").tasks.size)
        assertEquals("t_1", state.column("todo").tasks.single().id)
        assertEquals(285, state.board.latestEventId)
        assertEquals(listOf("acme"), state.board.tenants)
    }

    @Test
    fun aStatusEventMovesTheCardToItsLane() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()

        repo.applyEvent(KanbanEvent(id = 286, taskId = "t_1", kind = "status", payload = buildJsonObject { put("status", "running") }))

        val state = repo.state.value
        assertTrue(state.column("todo").tasks.isEmpty())
        assertEquals("running", state.column("running").tasks.single().status)
        assertEquals(286, state.board.latestEventId)
    }

    @Test
    fun aCommentEventBumpsTheCountAndAnUnknownTaskChangesNothing() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()
        val before = repo.state.value

        repo.applyEvent(KanbanEvent(id = 287, taskId = "t_1", kind = "commented"))

        assertEquals(3, repo.state.value.column("todo").tasks.single().commentCount)

        repo.applyEvent(KanbanEvent(id = 288, taskId = "t_nowhere", kind = "status", payload = buildJsonObject { put("status", "done") }))
        repo.applyEvent(KanbanEvent(id = 289, taskId = "t_1", kind = "heartbeat"))

        val after = repo.state.value
        assertEquals(
            before.column("todo").tasks.single().copy(commentCount = 3),
            after.column("todo").tasks.single(),
        )
        assertEquals(
            before.column("running").tasks,
            after.column("running").tasks,
        )
    }

    @Test
    fun aDeletedCardLeavesTheBoard() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()

        repo.applyEvent(KanbanEvent(id = 290, taskId = "t_1", kind = "deleted"))

        assertTrue(repo.state.value.board.columns.all { it.tasks.isEmpty() })
    }

    @Test
    fun bindingAnotherScopeDropsTheBoard() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()

        repo.bind(url, "other")

        assertTrue(repo.state.value.board.columns.isEmpty())
        assertEquals(null, repo.state.value.slug)
    }
}
