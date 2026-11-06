package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every route the app sends, against docs/kanban-api.md §2: the path, the query and the reply it decodes.
 * One call per test, so a renamed route or parameter names the endpoint that broke instead of failing a batch.
 */
class KanbanApiRoutesTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private var seen: HttpRequestData? = null
    private var sent: String? = null

    /** Runs [call] against [reply], then checks the route it took; returns the decoded value. */
    private suspend fun <T> route(
        reply: String,
        path: String,
        query: String = "",
        call: suspend KanbanApi.() -> ApiResult<T>,
    ): T {
        val api = KanbanApi(
            createHttpClient(
                MockEngine { request ->
                    seen = request
                    sent = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                    respond(reply, HttpStatusCode.OK, json)
                },
            ),
        )
        val result = call(api)
        assertEquals(path, seen?.url?.encodedPath)
        assertEquals(query, seen?.url?.encodedQuery)
        return assertIs<ApiResult.Success<T>>(result, "expected a decoded reply, got $result").value
    }

    private fun body() = Json.parseToJsonElement(sent ?: error("no body was sent")).jsonObject

    private val task = """{"id":"t_1","title":"Write it","status":"todo","assignee":"coder","priority":8,
        "comment_count":2,"progress":{"done":1,"total":3},"link_counts":{"parents":1,"children":0}}"""

    @Test
    fun theBoardCarriesTheTenantToo() = runTest {
        val board = route(
            """{"columns":[{"name":"todo","tasks":[$task]}],"latest_event_id":285,"now":1}""",
            "/api/plugins/kanban/board",
            "board=dev&tenant=acme",
        ) { board(url, board = "dev", tenant = "acme") }

        assertEquals(1, board.progressOfFirstCard()?.done)
        assertEquals(2, board.columns.single().tasks.single().commentCount)
    }

    @Test
    fun aTaskDetailUnwrapsEveryContainer() = runTest {
        val detail = route(
            """{"task":$task,
                "comments":[{"id":1,"task_id":"t_1","author":"user","body":"hi","created_at":5}],
                "events":[{"id":9,"task_id":"t_1","kind":"commented","created_at":6}],
                "attachments":[{"id":3,"task_id":"t_1","filename":"a.png","content_type":null,"size":7}],
                "links":{"parents":["t_0"],"children":[]},
                "link_tasks":[{"id":"t_0","title":"Parent","status":"done"}],
                "child_results":[{"id":"t_2","title":"Kid","status":"done","result":"ok"}],
                "runs":[{"id":14,"task_id":"t_1","profile":"coder","status":"succeeded","outcome":"done",
                         "metadata":{"files":2}}]}""",
            "/api/plugins/kanban/tasks/t_1",
            "board=dev",
        ) { task(url, "t_1", board = "dev") }

        assertEquals("hi", detail.comments.single().body)
        assertEquals("commented", detail.events.single().kind)
        assertNull(detail.attachments.single().contentType)
        assertEquals(listOf("t_0"), detail.links.parents)
        assertEquals("Parent", detail.linkTasks.single().title)
        assertEquals("ok", detail.childResults.single().result)
        assertEquals(2, detail.runs.single().metadata?.jsonObject?.get("files")?.jsonPrimitive?.content?.toInt())
        assertNull(detail.estimate)
    }

    @Test
    fun theRunFilterIsSentAsTwoParams() = runTest {
        val runs = route("""{"task":$task,"runs":[{"id":14,"task_id":"t_1","status":"succeeded"}]}""",
            "/api/plugins/kanban/tasks/t_1", "board=dev&run_state_type=status&run_state_name=succeeded") {
            runs(url, "t_1", board = "dev", stateType = "status", stateName = "succeeded")
        }

        assertEquals(14, runs.single().id)
    }

    @Test
    fun aHalfRunFilterIsNotSent() = runTest {
        val runs = route("""{"task":$task,"runs":[]}""", "/api/plugins/kanban/tasks/t_1", "board=dev") {
            runs(url, "t_1", board = "dev", stateType = "status")
        }

        assertTrue(runs.isEmpty())
    }

    @Test
    fun creatingATaskSendsTheNewCardAndUnwrapsIt() = runTest {
        val created = route("""{"task":$task}""", "/api/plugins/kanban/tasks", "board=dev") {
            createTask(url, KanbanNewTask(title = "Write it", assignee = "coder", parents = listOf("t_0")), board = "dev")
        }

        assertEquals("t_1", created.id)
        val sent = body()
        assertEquals("Write it", sent["title"]?.jsonPrimitive?.content)
        assertEquals("coder", sent["assignee"]?.jsonPrimitive?.content)
        assertEquals("[\"t_0\"]", sent["parents"].toString())
    }

    @Test
    fun patchingATaskSendsOnlyWhatTheCallMeans() = runTest {
        val patched = route("""{"task":$task}""", "/api/plugins/kanban/tasks/t_1", "board=dev") {
            updateTask(url, "t_1", KanbanTaskPatch(priority = 3), board = "dev")
        }

        assertEquals("t_1", patched.id)
        assertEquals("3", body()["priority"]?.jsonPrimitive?.content)
        // A null field is "leave it": the card's title, assignee and summary must not ride along.
        assertFalse("title" in body())
        assertFalse("assignee" in body())
        assertFalse("summary" in body())
    }

    @Test
    fun blockAndUnblockPatchTheStatus() = runTest {
        route("""{"task":$task}""", "/api/plugins/kanban/tasks/t_1", "board=dev") {
            block(url, "t_1", reason = "needs input", board = "dev")
        }
        assertEquals("blocked", body()["status"]?.jsonPrimitive?.content)
        assertEquals("needs input", body()["block_reason"]?.jsonPrimitive?.content)

        route("""{"task":$task}""", "/api/plugins/kanban/tasks/t_1", "board=dev") { unblock(url, "t_1", "dev") }
        assertEquals("ready", body()["status"]?.jsonPrimitive?.content)
        assertFalse("block_reason" in body())
        assertFalse("assignee" in body())
    }

    @Test
    fun aCommentCarriesItsAuthor() = runTest {
        route("""{"ok":true}""", "/api/plugins/kanban/tasks/t_1/comments", "board=dev") {
            addComment(url, "t_1", body = "looks good", author = "tester", board = "dev")
        }

        assertEquals("looks good", body()["body"]?.jsonPrimitive?.content)
        assertEquals("tester", body()["author"]?.jsonPrimitive?.content)
    }

    @Test
    fun reassignNamesTheProfileAndTheReclaimFlag() = runTest {
        route("""{"ok":true,"task_id":"t_1","assignee":"reviewer"}""", "/api/plugins/kanban/tasks/t_1/reassign", "board=dev") {
            reassign(url, "t_1", "reviewer", reclaimFirst = true, board = "dev")
        }

        assertEquals("reviewer", body()["profile"]?.jsonPrimitive?.content)
        assertEquals("true", body()["reclaim_first"]?.jsonPrimitive?.content)
    }

    @Test
    fun terminateSendsTheReason() = runTest {
        route("""{"ok":true,"run_id":14,"task_id":"t_1"}""", "/api/plugins/kanban/runs/14/terminate", "board=dev") {
            terminateRun(url, 14, reason = "stuck", board = "dev")
        }

        assertEquals("stuck", body()["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun dispatchAsksForADryRunAndAMax() = runTest {
        val dispatch = route(
            """{"reclaimed":1,"promoted":2,"spawned":[["t_1","coder",null]]}""",
            "/api/plugins/kanban/dispatch", "board=dev&dry_run=true&max=3",
        ) { dispatch(url, board = "dev", dryRun = true, max = 3) }

        assertEquals(2, dispatch.promoted)
        assertNull(dispatch.spawned.single()[2])
    }

    @Test
    fun linksAreAPostBodyOnTheWayInAndQueryOnTheWayOut() = runTest {
        route("""{"ok":true,"gated":true}""", "/api/plugins/kanban/links", "board=dev") {
            addLink(url, "t_1", "t_2", board = "dev")
        }
        assertEquals("t_1", body()["parent_id"]?.jsonPrimitive?.content)
        assertEquals("t_2", body()["child_id"]?.jsonPrimitive?.content)

        // A missing edge answers 200 {ok:false}: not an error, and not worth a retry.
        route("""{"ok":false}""", "/api/plugins/kanban/links", "board=dev&parent_id=t_1&child_id=t_2") {
            removeLink(url, "t_1", "t_2", board = "dev")
        }
    }

    @Test
    fun theSwitcherReadsBoardsCountsAndTheCurrentSlug() = runTest {
        val boards = route(
            """{"boards":[{"slug":"dev","name":"Dev","icon":null,"counts":{"todo":3,"done":7},"total":10,
                          "is_current":true,"db_path":"/srv/dev.db","archived":false}],"current":"dev"}""",
            "/api/plugins/kanban/boards",
        ) { boards(url) }

        assertEquals("dev", boards.current)
        val dev = boards.boards.single()
        assertEquals(10, dev.total)
        assertEquals(7, dev.counts["done"])
        assertTrue(dev.isCurrent)
        assertNull(dev.icon)
    }

    @Test
    fun creatingABoardUnwrapsTheBoardItMade() = runTest {
        val created = route("""{"board":{"slug":"probe","name":"Probe"},"current":"probe"}""", "/api/plugins/kanban/boards") {
            createBoard(url, "probe", name = "Probe")
        }

        assertEquals("probe", created.slug)
        assertEquals("probe", body()["slug"]?.jsonPrimitive?.content)
        assertEquals("Probe", body()["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun renamingABoardSendsOnlyTheNewName() = runTest {
        val renamed = route("""{"board":{"slug":"dev","name":"Dev 2"}}""", "/api/plugins/kanban/boards/dev") {
            renameBoard(url, "dev", "Dev 2")
        }

        assertEquals("Dev 2", renamed.name)
        assertEquals(setOf("name"), body().keys)
    }

    @Test
    fun deletingABoardAsksForTheArchiveUnlessTold() = runTest {
        val archived = route(
            """{"result":{"slug":"dev","action":"archived","new_path":null}}""",
            "/api/plugins/kanban/boards/dev",
        ) { deleteBoard(url, "dev") }
        assertEquals("archived", archived.action)
        assertNull(archived.newPath)

        val deleted = route(
            """{"result":{"slug":"dev","action":"deleted","new_path":null}}""",
            "/api/plugins/kanban/boards/dev", "delete=true",
        ) { deleteBoard(url, "dev", delete = true) }
        assertEquals("deleted", deleted.action)
    }

    @Test
    fun exportAndImportGoThroughTheBoardRoutes() = runTest {
        val export = route(
            """{"board":"dev","archive":"/srv/board-exports/dev.zip","size":4096,"counts":{"tasks":23}}""",
            "/api/plugins/kanban/boards/dev/export",
        ) { exportBoard(url, "dev", attachments = false) }

        assertEquals(4096, export.size)
        assertEquals("false", body()["attachments"]?.jsonPrimitive?.content)
        assertEquals("false", body()["logs"]?.jsonPrimitive?.content)

        val import = route(
            """{"board":"dev-2","requested_board":"dev","renamed":true,"warnings":["attachment 1 missing"]}""",
            "/api/plugins/kanban/boards/import",
        ) { importBoard(url, "/srv/dev.zip", slug = "dev", activate = true) }

        assertTrue(import.renamed)
        assertEquals("/srv/dev.zip", body()["archive"]?.jsonPrimitive?.content)
        assertEquals("true", body()["switch"]?.jsonPrimitive?.content)
    }

    @Test
    fun profilesAndAssigneesUnwrapTheirLists() = runTest {
        val profiles = route(
            """{"profiles":[{"name":"coder","is_default":true,"model":"m","provider":"p","description":null,
                            "description_auto":true,"skill_count":4}]}""",
            "/api/plugins/kanban/profiles",
        ) { profiles(url) }

        assertEquals(4, profiles.single().skillCount)
        assertTrue(profiles.single().isDefault)
        assertNull(profiles.single().description)

        val assignees = route(
            """{"assignees":[{"name":"coder","on_disk":true,"counts":{"ready":2}}]}""",
            "/api/plugins/kanban/assignees", "board=dev",
        ) { assignees(url, board = "dev") }

        assertEquals(2, assignees.single().counts["ready"])
        assertTrue(assignees.single().onDisk)
    }

    @Test
    fun statsConfigAndWorkflowParse() = runTest {
        val stats = route(
            """{"by_status":{"todo":2},"by_assignee":{"coder":{"todo":2}},"oldest_ready_age_seconds":12,"now":99}""",
            "/api/plugins/kanban/stats", "board=dev",
        ) { stats(url, board = "dev") }

        assertEquals(2, stats.byAssignee["coder"]?.get("todo"))
        assertEquals(12, stats.oldestReadyAgeSeconds)

        val config = route(
            """{"default_tenant":"acme","lane_by_profile":true,"include_archived_by_default":false,"render_markdown":true}""",
            "/api/plugins/kanban/config",
        ) { config(url) }

        assertEquals("acme", config.defaultTenant)
        assertTrue(config.renderMarkdown)
        assertFalse(config.includeArchivedByDefault)

        val workflow = route(
            """{"columns":[{"key":"todo","label":"To do","icon":"i","drag_target":true}],
                "manual":{"todo":["ready","triage"]},"archived":"archived"}""",
            "/api/plugins/kanban/workflow",
        ) { workflow(url) }

        assertEquals("todo", workflow.columns.single().key)
        assertEquals(listOf("ready", "triage"), workflow.manual["todo"])
    }

    @Test
    fun attachmentsLogAndASingleRunParse() = runTest {
        val attachments = route(
            """{"attachments":[{"id":3,"task_id":"t_1","filename":"a.png","size":7,"stored_path":"/srv/a.png"}]}""",
            "/api/plugins/kanban/tasks/t_1/attachments", "board=dev",
        ) { attachments(url, "t_1", board = "dev") }

        assertEquals("/srv/a.png", attachments.single().storedPath)

        val log = route(
            """{"task_id":"t_1","path":"/srv/t_1.log","exists":true,"size_bytes":3,"content":"hi\n","truncated":false}""",
            "/api/plugins/kanban/tasks/t_1/log", "board=dev&tail=10",
        ) { log(url, "t_1", tail = 10, board = "dev") }

        assertEquals("hi\n", log.content)
        assertTrue(log.exists)

        val run = route(
            """{"run":{"id":14,"task_id":"t_1","profile":"coder","status":"failed","outcome":"crashed",
                      "error":"exit 1","ended_at":1793925000}}""",
            "/api/plugins/kanban/runs/14", "board=dev",
        ) { run(url, 14, board = "dev") }

        assertEquals("crashed", run.outcome)
        assertEquals("exit 1", run.error)
    }

    @Test
    fun aReplyWithoutTheWrappedRowIsAnErrorNotAnEmptyCard() = runTest {
        val api = KanbanApi(createHttpClient(MockEngine { respond("""{"task":null}""", HttpStatusCode.OK, json) }))

        val result = api.updateTask(url, "t_1", KanbanTaskPatch(status = "done"))

        assertIs<ApiResult.Failed>(result)
    }

    private fun KanbanBoard.progressOfFirstCard() = columns.single().tasks.single().progress
}
