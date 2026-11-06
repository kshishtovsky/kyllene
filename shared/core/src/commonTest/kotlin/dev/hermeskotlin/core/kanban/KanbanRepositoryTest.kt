package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
                        "link_counts":{"parents":0,"children":1},"progress":{"done":1,"total":3}},
                       {"id":"t_1","title":"Write it","status":"todo","priority":8,"comment_count":2}]},
                     {"name":"running","tasks":[]},{"name":"done","tasks":[]}],
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

    @Test
    fun aFramesRowsLandTogetherAndOnlyTheOpenCardsAreInvalidated() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()
        val invalidated = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { repo.invalidated.toList(invalidated) }
        val seen = mutableListOf<KanbanState>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { repo.state.toList(seen) }

        // One frame: the card moves, gains a comment, and a row for a card this board doesn't hold rides along.
        repo.applyEvents(
            listOf(
                KanbanEvent(id = 291, taskId = "t_1", kind = "status", payload = buildJsonObject { put("status", "running") }),
                KanbanEvent(id = 292, taskId = "t_1", kind = "commented"),
                KanbanEvent(id = 293, taskId = "t_gone", kind = "status", payload = buildJsonObject { put("status", "done") }),
            ),
        )

        val card = repo.state.value.column("running").tasks.single()
        assertEquals("t_1", card.id)
        assertEquals(3, card.commentCount)
        // The unknown card's row is ignored, so the board's cursor stops at the last row it applied.
        assertEquals(292L, repo.state.value.board.latestEventId)
        assertEquals(293L, repo.state.value.lastEvent?.id)
        // The board moved once for three rows, and the sheet heard about the one card that changed.
        assertEquals(2, seen.size)
        assertEquals(listOf("t_1"), invalidated.distinct())
        watcher.cancel()
        collector.cancel()
    }

    @Test
    fun aStatusRowWithoutStatusFallsBackToTheRequestedOneAndCompletedLandsInDone() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()

        // The plugin's own `status` payload: `status` is where it is now, `requested_status` where it was asked.
        repo.applyEvent(
            KanbanEvent(id = 286, taskId = "t_1", kind = "status", payload = buildJsonObject { put("requested_status", "running") }),
        )
        assertEquals("running", repo.state.value.column("running").tasks.single().status)

        repo.applyEvent(KanbanEvent(id = 287, taskId = "t_1", kind = "completed"))
        assertEquals("done", repo.state.value.column("done").tasks.single().status)
        assertTrue(repo.state.value.column("running").tasks.isEmpty())
    }

    @Test
    fun aPatchKeepsTheCountersAndRollupsOnlyTheBoardReadKnows() = runTest {
        val repo = repository()
        repo.bind(url, null)
        repo.refresh()

        // A frame's payload never carries progress, link counts or a title: the patch must not wipe them.
        repo.applyEvent(
            KanbanEvent(id = 286, taskId = "t_1", kind = "reprioritized", payload = buildJsonObject { put("priority", 3) }),
        )

        val card = repo.state.value.column("todo").tasks.single()
        assertEquals(3, card.priority)
        assertEquals(2, card.commentCount)
        assertEquals(1, card.progress?.done)
        assertEquals(3, card.progress?.total)
        assertEquals(1, card.linkCounts.children)
        assertEquals("Write it", card.title)
    }
}
