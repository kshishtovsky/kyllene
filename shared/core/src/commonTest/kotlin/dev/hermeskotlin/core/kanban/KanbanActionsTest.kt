package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The writes of docs/kanban-api.md §2: right route, right body, 409/403 turned into the server's own text. */
class KanbanActionsTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val lanes = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done")

    private fun task(id: String, status: String, assignee: String? = null) =
        KanbanTask(id = id, title = "Card $id", status = status, assignee = assignee)

    private fun boardJson(vararg tasks: KanbanTask, latest: Long = 10): String = HermesJson.encodeToString(
        KanbanBoard.serializer(),
        KanbanBoard(columns = lanes.map { KanbanColumn(it, tasks.filter { t -> t.status == it }) }, latestEventId = latest),
    )

    private fun reply(task: KanbanTask) = buildJsonObject { put("task", HermesJson.encodeToJsonElement(KanbanTask.serializer(), task)) }.toString()

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement(bodyText()).jsonObject

    private fun HttpRequestData.isBoard() = method == HttpMethod.Get && url.encodedPath.endsWith("/board")

    private fun List<HttpRequestData>.call(method: HttpMethod, suffix: String): HttpRequestData =
        single { it.method == method && it.url.encodedPath.endsWith(suffix) }

    private fun List<HttpRequestData>.writes(): Int = count { it.method != HttpMethod.Get }

    /** A repository on a `dev` board that answers [handler]; [seen] keeps every request for the assertions. */
    private fun repository(
        seen: MutableList<HttpRequestData> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KanbanRepository = KanbanRepository(
        KanbanApi(createHttpClient(MockEngine { request -> seen += request; handler(request) })),
    ).also { it.bind(url, null) }

    /** Binds nothing yet; reads the `dev` board the handler answers. */
    private suspend fun KanbanRepository.open(): KanbanRepository {
        selectBoard("dev")
        assertEquals("dev", state.value.slug)
        return this
    }

    private fun KanbanRepository.card(id: String) = state.value.board.columns.asSequence().flatMap { it.tasks }.firstOrNull { it.id == id }

    private fun KanbanRepository.lane(id: String) = state.value.board.columns.first { column -> column.tasks.any { it.id == id } }.name

    @Test
    fun createIntoAColumnPostsThenPatchesTheStatus() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            when {
                request.isBoard() -> respond(boardJson(task("t_new", "todo")), HttpStatusCode.OK, json)
                request.method == HttpMethod.Post -> respond(reply(task("t_new", "todo")), HttpStatusCode.OK, json)
                else -> respond(reply(task("t_new", "ready")), HttpStatusCode.OK, json)
            }
        }.open()

        val result = repo.create(
            KanbanNewTask(title = "Write it", body = "the body", assignee = "coder", priority = 3),
            column = "ready",
        )

        assertIs<ApiResult.Success<KanbanTask>>(result)
        val post = seen.call(HttpMethod.Post, "/tasks")
        assertEquals("/api/plugins/kanban/tasks", post.url.encodedPath)
        assertEquals("board=dev", post.url.encodedQuery)
        val sent = post.json()
        assertEquals("Write it", sent["title"]?.jsonPrimitive?.content)
        assertEquals("the body", sent["body"]?.jsonPrimitive?.content)
        assertEquals("coder", sent["assignee"]?.jsonPrimitive?.content)
        assertEquals("3", sent["priority"]?.jsonPrimitive?.content)
        assertEquals("false", sent["triage"]?.jsonPrimitive?.content)

        val patch = seen.call(HttpMethod.Patch, "/tasks/t_new")
        assertEquals("ready", patch.json()["status"]?.jsonPrimitive?.content)
        assertFalse("assignee" in patch.json())
        assertEquals("ready", repo.lane("t_new"))
    }

    @Test
    fun triageIsACreateFlagAndTheServerLaneWins() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(), HttpStatusCode.OK, json)
            } else {
                respond(reply(task("t_new", "triage")), HttpStatusCode.OK, json)
            }
        }.open()

        repo.create(KanbanNewTask(title = "Idea", triage = true))

        assertEquals(1, seen.writes())
        assertEquals("true", seen.call(HttpMethod.Post, "/tasks").json()["triage"]?.jsonPrimitive?.content)
        assertEquals("triage", repo.lane("t_new"))
    }

    @Test
    fun aCreateTheServerRefusesShowsItsText() = runTest {
        val repo = repository { request ->
            if (request.isBoard()) {
                respond(boardJson(), HttpStatusCode.OK, json)
            } else {
                respond("""{"detail":[{"loc":["body","title"],"msg":"Field required"}]}""", HttpStatusCode.UnprocessableEntity, json)
            }
        }.open()

        val result = repo.create(KanbanNewTask(title = " "))

        val failed = assertIs<ApiResult.Failed>(result)
        assertEquals(422, failed.status)
        assertTrue(failed.message.contains("Field required"))
    }

    @Test
    fun aCardCreatedInALaneTheServerRefusesIsShownWhereItLanded() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        var reads = 0
        val repo = repository(seen) { request ->
            when {
                request.isBoard() -> {
                    reads++
                    respond(boardJson(task("t_new", "todo")), HttpStatusCode.OK, json)
                }
                request.method == HttpMethod.Post -> respond(reply(task("t_new", "todo")), HttpStatusCode.OK, json)
                else -> respond("""{"detail":"status transition to 'ready' not valid from current state"}""", HttpStatusCode.Conflict, json)
            }
        }.open()

        val result = repo.create(KanbanNewTask(title = "Write it"), column = "ready")

        assertEquals("status transition to 'ready' not valid from current state", assertIs<ApiResult.Failed>(result).message)
        assertEquals(2, reads) // the board was re-read: the card exists, only not in the asked-for lane
        assertEquals("todo", repo.lane("t_new"))
    }

    @Test
    fun moveShowsTheCardInItsNewLaneBeforeTheServerAnswersAndPutsItBackOn409() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = repository { request ->
            when {
                request.isBoard() -> respond(boardJson(task("t_1", "todo")), HttpStatusCode.OK, json)
                else -> {
                    gate.await()
                    respond("""{"detail":"status transition to 'blocked' not valid from current state"}""", HttpStatusCode.Conflict, json)
                }
            }
        }.open()

        val call = async { repo.move("t_1", "blocked") }
        runCurrent()

        assertEquals("blocked", repo.card("t_1")?.status) // optimistic, before any reply
        assertEquals("blocked", repo.lane("t_1"))

        gate.complete(Unit)

        assertEquals("status transition to 'blocked' not valid from current state", call.await())
        assertEquals("todo", repo.lane("t_1")) // rolled back
    }

    @Test
    fun aMoveIsSentAsAStatusPatchOnTheBoardRoute() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "todo")), HttpStatusCode.OK, json)
            } else {
                respond(reply(task("t_1", "done")), HttpStatusCode.OK, json)
            }
        }.open()

        assertNull(repo.move("t_1", "done"))

        val patch = seen.call(HttpMethod.Patch, "/tasks/t_1")
        assertEquals("done", patch.json()["status"]?.jsonPrimitive?.content)
        assertFalse("assignee" in patch.json())
        assertEquals("board=dev", patch.url.encodedQuery)
        assertEquals("done", repo.lane("t_1"))
    }

    @Test
    fun aRefusedMoveShowsTheServerText() = runTest {
        val repo = repository { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "todo")), HttpStatusCode.OK, json)
            } else {
                respond("""{"detail":"Cloudflare stopped the request"}""", HttpStatusCode.Forbidden, json)
            }
        }.open()

        assertEquals("Cloudflare stopped the request", repo.move("t_1", "done"))
        assertEquals("todo", repo.lane("t_1"))
    }

    @Test
    fun assignSendsTheReclaimFlagAndRollsBackWhenTheWorkerIsAlive() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "running", assignee = "old")), HttpStatusCode.OK, json)
            } else {
                respond(
                    """{"detail":"cannot reassign t_1: still running (pass reclaim_first=true to release the claim first)"}""",
                    HttpStatusCode.Conflict, json,
                )
            }
        }.open()

        val error = repo.assign("t_1", "coder", reclaimFirst = true)

        assertEquals("cannot reassign t_1: still running (pass reclaim_first=true to release the claim first)", error)
        assertEquals("old", repo.card("t_1")?.assignee) // rolled back
        val post = seen.call(HttpMethod.Post, "/tasks/t_1/reassign")
        assertEquals("coder", post.json()["profile"]?.jsonPrimitive?.content)
        assertEquals("true", post.json()["reclaim_first"]?.jsonPrimitive?.content)
        assertEquals("board=dev", post.url.encodedQuery)
    }

    @Test
    fun aReclaimOfACardThatIsNotRunningKeepsTheServerText() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "todo")), HttpStatusCode.OK, json)
            } else {
                respond(
                    """{"detail":"cannot reclaim t_1: not in a claimable state (not running, or unknown id)"}""",
                    HttpStatusCode.Conflict, json,
                )
            }
        }.open()

        assertEquals(
            "cannot reclaim t_1: not in a claimable state (not running, or unknown id)",
            repo.reclaim("t_1", "dead worker"),
        )
        assertEquals("/api/plugins/kanban/tasks/t_1/reclaim", seen.call(HttpMethod.Post, "/tasks/t_1/reclaim").url.encodedPath)
    }

    @Test
    fun aDoubleTapOnDispatchSendsOneRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "ready")), HttpStatusCode.OK, json)
            } else {
                gate.await()
                respond("""{"reclaimed":0,"promoted":1,"spawned":[["t_1","coder",""]]}""", HttpStatusCode.OK, json)
            }
        }.open()

        val first = async { repo.dispatch() }
        val second = async { repo.dispatch() }

        // The second tap answers at once instead of waiting for the first request's reply.
        assertNull(withTimeout(2_000) { second.await() })

        gate.complete(Unit)

        assertNull(first.await())
        assertEquals(1, seen.writes()) // one request for two taps
    }

    @Test
    fun completeRollsBackWhenAParentIsStillOpen() = runTest {
        val repo = repository { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "review")), HttpStatusCode.OK, json)
            } else {
                respond(
                    """{"detail":"cannot move t_1 to 'done': unsatisfied parent dependencies: t_2 (running); complete the parents first (done or archived)"}""",
                    HttpStatusCode.Conflict, json,
                )
            }
        }.open()

        assertEquals(
            "cannot move t_1 to 'done': unsatisfied parent dependencies: t_2 (running); complete the parents first (done or archived)",
            repo.complete("t_1", summary = "done"),
        )
        assertEquals("review", repo.lane("t_1"))
    }

    @Test
    fun blockAndUnblockSendTheirStatusAndReason() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val status = mutableMapOf<String, String>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "ready")), HttpStatusCode.OK, json)
            } else {
                val want = request.json()["status"]?.jsonPrimitive?.content.orEmpty()
                val reason = request.json()["block_reason"]?.jsonPrimitive?.content
                status[want] = reason ?: ""
                respond(reply(task("t_1", want)), HttpStatusCode.OK, json)
            }
        }.open()

        assertNull(repo.block("t_1", "needs a human"))
        assertEquals("needs a human", status["blocked"])
        assertEquals("blocked", repo.lane("t_1"))

        assertNull(repo.unblock("t_1"))
        assertEquals("", status["ready"])
        assertEquals("ready", repo.lane("t_1"))
    }

    @Test
    fun terminateHitsTheRunRouteAndReReadsTheBoard() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            if (request.isBoard()) {
                respond(boardJson(task("t_1", "ready")), HttpStatusCode.OK, json)
            } else {
                respond("""{"ok":true,"run_id":12,"task_id":"t_1"}""", HttpStatusCode.OK, json)
            }
        }.open()

        assertNull(repo.terminate(12, "stuck"))

        assertEquals("/api/plugins/kanban/runs/12/terminate", seen.call(HttpMethod.Post, "/runs/12/terminate").url.encodedPath)
        assertEquals(2, seen.count { it.isBoard() }) // refreshed: the card is back in a queue lane
    }

    @Test
    fun linksMoveTheCountsAndACycleLeavesThemAlone() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        var refuse = false
        val repo = repository(seen) { request ->
            when {
                request.isBoard() -> respond(
                    boardJson(
                        task("t_parent", "todo").copy(linkCounts = KanbanLinkCounts(children = 1)),
                        task("t_child", "todo").copy(linkCounts = KanbanLinkCounts(parents = 1)),
                    ),
                    HttpStatusCode.OK, json,
                )
                refuse -> respond("""{"detail":"a task cannot depend on itself"}""", HttpStatusCode.BadRequest, json)
                else -> respond("""{"ok":true,"gated":true}""", HttpStatusCode.OK, json)
            }
        }.open()

        assertNull(repo.addLink("t_parent", "t_child"))
        assertEquals(2, repo.card("t_parent")?.linkCounts?.children)
        assertEquals(2, repo.card("t_child")?.linkCounts?.parents)
        val post = seen.call(HttpMethod.Post, "/links")
        assertEquals("t_parent", post.json()["parent_id"]?.jsonPrimitive?.content)
        assertEquals("t_child", post.json()["child_id"]?.jsonPrimitive?.content)

        refuse = true
        assertEquals("a task cannot depend on itself", repo.addLink("t_parent", "t_child"))
        assertEquals(2, repo.card("t_child")?.linkCounts?.parents) // rolled back
    }

    @Test
    fun removeLinkUsesQueryParametersAndLowersTheCounts() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = repository(seen) { request ->
            when {
                request.isBoard() -> respond(
                    boardJson(
                        task("t_parent", "todo").copy(linkCounts = KanbanLinkCounts(children = 2)),
                        task("t_child", "todo").copy(linkCounts = KanbanLinkCounts(parents = 1)),
                    ),
                    HttpStatusCode.OK, json,
                )
                else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
            }
        }.open()

        assertNull(repo.removeLink("t_parent", "t_child"))

        val delete = seen.call(HttpMethod.Delete, "/links")
        assertEquals("board=dev&parent_id=t_parent&child_id=t_child", delete.url.encodedQuery)
        assertEquals(1, repo.card("t_parent")?.linkCounts?.children)
        assertEquals(0, repo.card("t_child")?.linkCounts?.parents)
    }
}
