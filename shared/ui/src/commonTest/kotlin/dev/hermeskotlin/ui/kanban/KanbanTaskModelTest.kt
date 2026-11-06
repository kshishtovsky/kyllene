package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sheet's writes, without Compose: what the model sends, what it shows while it flies, what it asks first.
 * The gateway is a [MockEngine], so every assertion is about the request the app would really make.
 *
 * Real dispatchers on purpose: the engine answers on its own thread, so a virtual-time `advanceUntilIdle` returns
 * before the reply lands and the state would still read "loading".
 */
class KanbanTaskModelTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val tasksPath = "/api/plugins/kanban/tasks/t_1"

    private val detail = """
        {"task":{"id":"t_1","title":"Work","status":"running","priority":1,"assignee":"coder","body":"old"},
         "comments":[{"id":4,"task_id":"t_1","author":"dashboard","body":"go","created_at":1793925470}],
         "events":[{"id":307,"task_id":"t_1","run_id":null,"kind":"blocked","payload":{"reason":"needs a decision"},"created_at":1793925272}],
         "attachments":[{"id":9,"task_id":"t_1","filename":"log.txt","content_type":"text/plain","size":17,"uploaded_by":"probe","stored_path":null,"created_at":1793925473}],
         "links":{"parents":[],"children":[]},
         "link_tasks":[],
         "child_results":[],
         "runs":[{"id":7,"task_id":"t_1","profile":"coder","status":"running"}]}
    """.trimIndent()

    /** Requests seen by the mock, updated from the engine's own thread. */
    private class Recorder {
        private val _sent = MutableStateFlow<List<Pair<String, String>>>(emptyList())

        fun record(method: String, path: String) = _sent.update { it + (method to path) }
        fun count(method: HttpMethod, path: String) = _sent.value.count { it.first == method.value && it.second == path }
    }

    /** A model on real dispatchers, its scope torn down with the test. */
    private fun sheet(
        recorder: Recorder,
        onComment: MockRequestHandleScope.() -> HttpResponseData = { respond("""{"ok":true}""", HttpStatusCode.OK, json) },
        onPatch: MockRequestHandleScope.() -> HttpResponseData = { respond("""{"ok":true}""", HttpStatusCode.OK, json) },
        block: suspend (KanbanTaskModel) -> Unit,
    ) = runBlocking {
        val api = KanbanApi(
            createHttpClient(
                MockEngine { request ->
                    recorder.record(request.method.value, request.url.encodedPath)
                    when {
                        request.method == HttpMethod.Patch -> onPatch()
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/comments") -> onComment()
                        request.method == HttpMethod.Post || request.method == HttpMethod.Delete ->
                            respond("""{"ok":true}""", HttpStatusCode.OK, json)
                        // The detail read the sheet follows every write with.
                        else -> respond(detail, HttpStatusCode.OK, json)
                    }
                },
            ),
        )
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            block(KanbanTaskModel(api, url, board = "dev", scope = scope))
        } finally {
            scope.cancel()
        }
    }

    /** Polls a real outcome; `delay` is wall-clock here, since nothing runs on a test scheduler. */
    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(5)
        }
    }

    private suspend fun KanbanTaskModel.openAndLoad(taskId: String = "t_1") {
        open(taskId)
        eventually { state.value.detail != null }
    }

    private val KanbanTaskModel.detailOrFail get() = assertNotNull(state.value.detail)

    @Test
    fun aCommentShowsAtOnceAndIsSentWithItsBody() {
        val recorder = Recorder()
        sheet(recorder) { model ->
            model.openAndLoad()

            model.comment("  status?  ")

            // The row is there before any reply: the sheet never waits for the network to show what was typed.
            val pending = model.state.value.pending.single()
            assertEquals("status?", pending.body)
            assertTrue(pending.sending)

            eventually { model.state.value.pending.none { it.sending } }

            assertEquals(1, recorder.count(HttpMethod.Post, "$tasksPath/comments"))
            assertTrue(model.state.value.pending.all { !it.failed }, "a comment that went must not stay marked")
        }
    }

    @Test
    fun aFailedCommentStaysMarkedWithItsTextAndCanBeRetried() {
        val recorder = Recorder()
        sheet(recorder, onComment = { respondError(HttpStatusCode.InternalServerError) }) { model ->
            model.openAndLoad()

            model.comment("hello")
            eventually { model.state.value.pending.singleOrNull()?.failed == true }

            val failed = model.state.value.pending.single()
            assertEquals("hello", failed.body)
            assertNotNull(model.state.value.notice)

            model.retry(failed)
            eventually { recorder.count(HttpMethod.Post, "$tasksPath/comments") == 2 }
        }
    }

    @Test
    fun stoppingARunAsksFirstAndSendsNothingOnCancel() {
        val recorder = Recorder()
        sheet(recorder) { model ->
            model.openAndLoad()
            val terminate = "/api/plugins/kanban/runs/7/terminate"

            val run = model.detailOrFail.runs.single()
            model.askStop(run)

            assertIs<KanbanConfirm.StopRun>(model.state.value.confirm)
            assertEquals(0, recorder.count(HttpMethod.Post, terminate), "a cancel must leave the worker alone")

            model.cancelConfirm()

            assertNull(model.state.value.confirm)
            assertEquals(0, recorder.count(HttpMethod.Post, terminate))

            model.askStop(run)
            model.confirm()
            eventually { recorder.count(HttpMethod.Post, terminate) == 1 }

            assertNull(model.state.value.confirm)
        }
    }

    @Test
    fun finishingACardAsksFirst() {
        val recorder = Recorder()
        sheet(recorder) { model ->
            model.openAndLoad()

            model.move("done")

            assertIs<KanbanConfirm.Finish>(model.state.value.confirm)
            assertEquals(0, recorder.count(HttpMethod.Patch, tasksPath))

            model.confirm()
            eventually { recorder.count(HttpMethod.Patch, tasksPath) == 1 }

            assertNull(model.state.value.confirm)
        }
    }

    @Test
    fun aPlainLaneMoveNeedsNoConfirmation() {
        val recorder = Recorder()
        sheet(recorder) { model ->
            model.openAndLoad()

            model.move("review")
            eventually { recorder.count(HttpMethod.Patch, tasksPath) == 1 }

            assertNull(model.state.value.confirm)
        }
    }

    @Test
    fun deletingAnAttachmentAsksFirst() {
        val recorder = Recorder()
        sheet(recorder) { model ->
            model.openAndLoad()
            val filePath = "/api/plugins/kanban/attachments/9"

            val file = model.detailOrFail.attachments.single()
            model.askDeleteFile(file)

            assertIs<KanbanConfirm.DeleteFile>(model.state.value.confirm)
            assertEquals(0, recorder.count(HttpMethod.Delete, filePath))

            model.confirm()
            eventually { recorder.count(HttpMethod.Delete, filePath) == 1 }
        }
    }

    @Test
    fun theOpenedCardCarriesItsBodyCommentsAndEvents() {
        sheet(Recorder()) { model ->
            model.openAndLoad()

            val loaded = model.detailOrFail
            assertEquals("old", loaded.task.body)
            assertEquals("go", loaded.comments.single().body)
            assertEquals("needs a decision", ((loaded.events.single().payload as JsonObject)["reason"] as JsonPrimitive).content)
            assertEquals(7, loaded.runs.single().id)
            assertFalse(model.state.value.loading)
        }
    }

    @Test
    fun aFailedWriteLeavesANoticeRatherThanSilence() {
        sheet(Recorder(), onPatch = { respondError(HttpStatusCode.Conflict) }) { model ->
            model.openAndLoad()

            model.move("review")
            eventually { model.state.value.notice != null }

            assertFalse(model.state.value.busy)
        }
    }
}
