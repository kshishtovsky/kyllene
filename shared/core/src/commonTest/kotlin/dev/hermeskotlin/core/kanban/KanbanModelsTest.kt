package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The replies in docs/kanban-api.md, decoded with the app's own parser. */
class KanbanModelsTest {

    private val card = """
        {"id":"t_f9bdee25","title":"<title>","body":"# Body\n\nwith `markdown`","assignee":"coder","status":"todo",
         "priority":8,"created_by":"user","created_at":1793923467,"started_at":null,"completed_at":null,
         "workspace_kind":"dir","workspace_path":"E:/PET/<project>","claim_lock":null,"claim_expires":null,
         "tenant":null,"branch_name":null,"project_id":null,"result":null,"idempotency_key":null,
         "consecutive_failures":0,"worker_pid":null,"last_failure_error":null,"max_runtime_seconds":null,
         "last_heartbeat_at":null,"current_run_id":null,"workflow_template_id":null,"current_step_key":null,
         "skills":null,"model_override":null,"provider_override":null,"reasoning_effort":null,"max_retries":null,
         "goal_mode":false,"goal_max_turns":null,"session_id":null,"block_kind":null,"block_recurrences":0,
         "completion_contract":"local-only",
         "age":{"created_age_seconds":1857,"started_age_seconds":null,"time_to_complete_seconds":null},
         "latest_summary":null,"current_run_started_at":null,"link_counts":{"parents":2,"children":7},
         "comment_count":0,"progress":{"done":0,"total":7},"unknown_future_key":{"a":1}}
    """.trimIndent()

    @Test
    fun boardParsesEveryLaneAndAFullCard() {
        val board = HermesJson.decodeFromString(
            KanbanBoard.serializer(),
            """{"columns":[
                 {"name":"triage","tasks":[]},
                 {"name":"todo","tasks":[$card]},
                 {"name":"scheduled","tasks":[]},
                 {"name":"ready","tasks":[]},
                 {"name":"running","tasks":[]},
                 {"name":"blocked","tasks":[]},
                 {"name":"review","tasks":[]},
                 {"name":"done","tasks":[]}],
               "tenants":[],"assignees":["boss","coder","default","researcher","reviewer","tester"],
               "latest_event_id":285,"now":1793925302}""",
        )

        assertEquals(
            listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done"),
            board.columns.map { it.name },
        )
        assertEquals(285, board.latestEventId)
        assertEquals(1793925302, board.now)
        assertTrue(board.tenants.isEmpty())
        assertEquals("coder", board.assignees[1])

        val task = board.columns[1].tasks.single()
        assertEquals("t_f9bdee25", task.id)
        assertEquals("todo", task.status)
        assertEquals(8, task.priority)
        assertEquals("user", task.createdBy)
        assertEquals(KanbanLinkCounts(parents = 2, children = 7), task.linkCounts)
        assertEquals(KanbanProgress(done = 0, total = 7), task.progress)
        assertEquals("E:/PET/<project>", task.workspacePath)
        assertNull(task.warnings)
        assertNull(task.latestSummary)
        assertNull(task.blockKind)
        assertEquals(0, task.blockRecurrences)
    }

    @Test
    fun aFlaggedRunningCardCarriesWarningsAndLiveness() {
        val task = HermesJson.decodeFromString(
            KanbanTask.serializer(),
            """{"id":"t_1","title":"Run","body":null,"status":"running","assignee":"tester","priority":3,
               "tenant":"acme","created_at":1793923467,"started_at":1793924000,"current_run_started_at":1793924100,
               "worker_pid":25636,"last_heartbeat_at":1793925357,"block_kind":"transient","block_recurrences":2,
               "progress":null,
               "warnings":{"count":2,"kinds":{"stale_running":2},"latest_at":1793925000,"highest_severity":"error"}}""",
        )

        assertEquals("running", task.status)
        assertEquals("acme", task.tenant)
        assertEquals(25636, task.workerPid)
        assertEquals(1793924100, task.currentRunStartedAt)
        assertEquals("transient", task.blockKind)
        assertEquals(2, task.blockRecurrences)
        assertNull(task.progress)
        assertNull(task.body)
        val warnings = assertNotNull(task.warnings)
        assertEquals(2, warnings.count)
        assertEquals(mapOf("stale_running" to 2), warnings.kinds)
        assertEquals("error", warnings.highestSeverity)
    }

    @Test
    fun taskDetailParsesEveryContainer() {
        val detail = HermesJson.decodeFromString(
            KanbanTaskDetail.serializer(),
            """{"task":{"id":"t_b96dd588","title":"Docs","status":"done","latest_summary":"Full summary, not cut"},
               "comments":[{"id":4,"task_id":"t_b96dd588","author":"dashboard","body":"done","created_at":1793925470}],
               "events":[{"id":307,"task_id":"t_b96dd588","run_id":null,"kind":"commented",
                          "payload":{"author":"dashboard","len":13},"created_at":1793925272},
                         {"id":308,"task_id":"t_b96dd588","run_id":13,"kind":"edited","payload":null,"created_at":1793925273}],
               "attachments":[{"id":1,"task_id":"t_b96dd588","filename":"att.txt","content_type":"text/plain",
                               "size":17,"uploaded_by":"probe","stored_path":"C:\\tmp\\att.txt","created_at":1793925473}],
               "links":{"parents":["t_a"],"children":["t_b"]},
               "link_tasks":[{"id":"t_a","title":"Parent","status":"done"},{"id":"t_b","title":"Child","status":"todo"}],
               "child_results":[{"id":"t_b","title":"Child","status":"todo","latest_summary":null,"result":null}],
               "runs":[{"id":13,"task_id":"t_b96dd588","profile":"coder","step_key":null,"status":"done",
                        "claim_lock":"<host>:<pid>","claim_expires":1793926257,"worker_pid":25636,
                        "max_runtime_seconds":null,"last_heartbeat_at":1793925357,"started_at":1793924827,
                        "ended_at":1793925450,"outcome":"completed","summary":"did it",
                        "metadata":{"note":{"nested":true}},"error":null}]}""",
        )

        assertEquals("t_b96dd588", detail.task.id)
        assertEquals("Full summary, not cut", detail.task.latestSummary)
        assertEquals("dashboard", detail.comments.single().author)
        assertEquals(listOf("commented", "edited"), detail.events.map { it.kind })
        assertNull(detail.events[1].payload)
        assertEquals(13, detail.events[1].runId)
        assertEquals("text/plain", detail.attachments.single().contentType)
        assertEquals(listOf("t_a"), detail.links.parents)
        assertEquals(listOf("t_a", "t_b"), detail.linkTasks.map { it.id })
        assertEquals("Child", detail.childResults.single().title)
        val run = detail.runs.single()
        assertEquals(13, run.id)
        assertEquals("completed", run.outcome)
        assertEquals(true, run.metadata?.jsonObject?.get("note")?.jsonObject?.get("nested")?.jsonPrimitive?.content?.toBoolean())
        assertNull(detail.estimate)
    }

    @Test
    fun aDetailWithOnlyATaskKeepsItsCollectionsEmpty() {
        val detail = HermesJson.decodeFromString(
            KanbanTaskDetail.serializer(),
            """{"task":{"id":"t_1","title":"x"},"events":[]}""",
        )

        assertEquals("t_1", detail.task.id)
        assertTrue(detail.comments.isEmpty())
        assertTrue(detail.attachments.isEmpty())
        assertTrue(detail.links.parents.isEmpty())
        assertTrue(detail.runs.isEmpty())
    }

    @Test
    fun boardsListParsesCountsAndTheCurrentSlug() {
        val boards = HermesJson.decodeFromString(
            KanbanBoards.serializer(),
            """{"boards":[{"slug":"default","name":"Default","description":"","icon":"","color":"",
                          "default_workdir":null,"project_id":null,"created_at":null,"archived":false,
                          "db_path":"C:\\kanban.db","is_current":false,"counts":{},"total":0,
                          "default_workspace_kind":"scratch","project_name":null},
                         {"slug":"dev","name":"<board name>","is_current":true,
                          "counts":{"archived":4,"done":7,"running":4,"todo":12},"total":23}],
               "current":"dev"}""",
        )

        assertEquals("dev", boards.current)
        val dev = boards.boards[1]
        assertEquals(7, dev.counts["done"])
        assertEquals(23, dev.total)
        assertTrue(dev.isCurrent)
        assertFalse(boards.boards[0].archived)
        assertEquals("scratch", boards.boards[0].defaultWorkspaceKind)
    }

    @Test
    fun workflowParsesColumnOrderAndManualMoves() {
        val workflow = HermesJson.decodeFromString(
            KanbanWorkflow.serializer(),
            """{"columns":[{"key":"triage","label":"Triage","icon":"◇","drag_target":true},
                           {"key":"scheduled","label":"Scheduled","icon":"⏱","drag_target":false}],
               "manual":{"ready":["blocked","done","review","scheduled","todo","triage"]},
               "archived":"archived"}""",
        )

        assertEquals(listOf("triage", "scheduled"), workflow.columns.map { it.key })
        assertTrue(workflow.columns[0].dragTarget)
        assertFalse(workflow.columns[1].dragTarget)
        assertEquals(6, workflow.manual["ready"]?.size)
        assertEquals("archived", workflow.archived)
    }

    @Test
    fun statsAssigneesProfilesAndConfigParse() {
        val stats = HermesJson.decodeFromString(
            KanbanStats.serializer(),
            """{"by_status":{"done":7,"running":4,"todo":12},
               "by_assignee":{"coder":{"done":3,"running":1,"todo":8}},"oldest_ready_age_seconds":null,"now":1793925418}""",
        )
        assertEquals(12, stats.byStatus["todo"])
        assertEquals(8, stats.byAssignee["coder"]?.get("todo"))
        assertNull(stats.oldestReadyAgeSeconds)

        val assignees = HermesJson.decodeFromString(
            AssigneesReplyFixture.serializer(),
            """{"assignees":[{"name":"coder","on_disk":true,"counts":{"done":3,"running":1,"todo":8}},
                             {"name":"default","on_disk":true,"counts":{}}]}""",
        )
        assertEquals("default", assignees.assignees[1].name)
        assertTrue(assignees.assignees[1].counts.isEmpty())

        val profiles = HermesJson.decodeFromString(
            ProfilesReplyFixture.serializer(),
            """{"profiles":[{"name":"default","is_default":true,"model":"<model>","provider":"<provider>",
                            "description":"","description_auto":false,"skill_count":140},
                           {"name":"coder","is_default":false}]}""",
        )
        assertTrue(profiles.profiles[0].isDefault)
        assertEquals(140, profiles.profiles[0].skillCount)
        assertEquals(0, profiles.profiles[1].skillCount)

        val config = HermesJson.decodeFromString(
            KanbanConfig.serializer(),
            """{"default_tenant":"","lane_by_profile":true,"include_archived_by_default":false,"render_markdown":true}""",
        )
        assertTrue(config.laneByProfile)
        assertTrue(config.renderMarkdown)
        assertFalse(config.includeArchivedByDefault)
    }

    @Test
    fun aMissingLogIsAReplyNotAnError() {
        val log = HermesJson.decodeFromString(
            KanbanLog.serializer(),
            """{"task_id":"t_1","path":"C:\\logs\\t_1.log","exists":false,"size_bytes":0,"content":"","truncated":false}""",
        )

        assertFalse(log.exists)
        assertEquals("", log.content)
        assertEquals(0, log.sizeBytes)
    }

    @Test
    fun boardRemovalAndEstimateParse() {
        val removal = HermesJson.decodeFromString(
            KanbanBoardRemoval.serializer(),
            """{"slug":"probe-api","action":"archived","new_path":"C:\\boards\\_archived\\probe-api-1"}""",
        )
        assertEquals("archived", removal.action)

        val estimate = HermesJson.decodeFromString(
            KanbanEstimate.serializer(),
            """{"ok":false,"reason":"a title is required to estimate"}""",
        )
        assertFalse(estimate.ok)
        assertEquals("a title is required to estimate", estimate.reason)
        assertNull(estimate.estTokens)
    }

    @Test
    fun dispatchExportAndImportParse() {
        val dispatch = HermesJson.decodeFromString(
            KanbanDispatchResult.serializer(),
            """{"reclaimed":0,"promoted":2,"spawned":[["t_1","coder","E:\\worktrees\\t_1"],["t_2","tester",null]]}""",
        )
        assertEquals(2, dispatch.promoted)
        assertEquals("coder", dispatch.spawned[0][1])
        assertEquals(listOf("t_2", "tester", null), dispatch.spawned[1])

        val export = HermesJson.decodeFromString(
            KanbanExport.serializer(),
            """{"board":"dev","archive":"C:\\boards\\board-exports\\dev-20261106.kanban.zip","size":40960,
               "counts":{"comments":18,"tasks":23}}""",
        )
        assertEquals(23, export.counts["tasks"])
        assertEquals(40960, export.size)

        val import = HermesJson.decodeFromString(
            KanbanImport.serializer(),
            """{"board":"dev-2","requested_board":"dev","renamed":true,"name":"Dev",
               "counts":{"tasks":23},"attachments_restored":1,"tasks_parked":0,
               "warnings":["attachment 1 missing on disk"],"activated":false}""",
        )
        assertTrue(import.renamed)
        assertEquals("dev-2", import.board)
        assertEquals(1, import.attachmentsRestored)
        assertEquals(listOf("attachment 1 missing on disk"), import.warnings)
    }

    @Test
    fun anEmptyReplyStillParses() {
        assertEquals(KanbanBoard(), HermesJson.decodeFromString(KanbanBoard.serializer(), "{}"))
        assertEquals(KanbanBoards(), HermesJson.decodeFromString(KanbanBoards.serializer(), "{}"))
    }

    /** `GET /assignees` and `GET /profiles` answer under a key; the API unwraps it, the fixture mirrors the reply. */
    @kotlinx.serialization.Serializable
    private data class AssigneesReplyFixture(val assignees: List<KanbanAssignee> = emptyList())

    @kotlinx.serialization.Serializable
    private data class ProfilesReplyFixture(val profiles: List<KanbanProfile> = emptyList())
}
