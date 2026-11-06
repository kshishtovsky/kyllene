package dev.hermeskotlin.ui.kanban

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The task sheet's own gestures: a comment goes out and becomes a real row, a refused one is marked, stopping a run
 * asks first, and Escape closes the sheet.
 */
@OptIn(ExperimentalTestApi::class)
class KanbanTaskSheetUiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    private val calls = mutableListOf<String>()
    private val comments = mutableListOf(1L to "First look")
    private var refuseComment = false

    private fun detailJson(): String {
        val rows = comments.joinToString(",") { (id, body) ->
            """{"id":$id,"task_id":"t_1","author":"dashboard","body":"$body","created_at":1793925470}"""
        }
        return """
            {"task":{"id":"t_1","title":"Write the report","status":"running","priority":1,"assignee":"coder",
                     "body":"Fix the parser."},
             "comments":[$rows],
             "events":[],"attachments":[],"links":{"parents":[],"children":[]},"link_tasks":[],"child_results":[],
             "runs":[{"id":7,"task_id":"t_1","profile":"coder","status":"running","started_at":1793925400}]}
        """.trimIndent()
    }

    private fun client() = createHttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/api/plugins/kanban")
            calls += "${request.method.value} $path"
            val (status, json) = when {
                path == "/tasks/t_1" && request.method == HttpMethod.Get -> HttpStatusCode.OK to detailJson()
                path == "/tasks/t_1/comments" && request.method == HttpMethod.Post -> {
                    if (refuseComment) {
                        HttpStatusCode.InternalServerError to """{"error":"the gateway is down"}"""
                    } else {
                        comments += 99L to "Looks good now"
                        HttpStatusCode.OK to """{"ok":true}"""
                    }
                }
                path == "/runs/7/terminate" -> HttpStatusCode.OK to """{"ok":true}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
            respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    /** The sheet over a real model, opening the card the way the board does. */
    private fun ComposeUiTest.showSheet(scope: CoroutineScope, desktop: Boolean = false, onDismiss: () -> Unit = {}) {
        val model = KanbanTaskModel(KanbanApi(client()), url, board = "dev", scope = scope)
        setContent {
            val state by model.state.collectAsState()
            LaunchedEffect(Unit) { model.open("t_1") }
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides desktop) {
                    KanbanTaskSheetContent(state = state, model = model, onDismiss = onDismiss)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("DESCRIPTION").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aSentCommentShowsUpAndBecomesReal() = runComposeUiTest {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            showSheet(scope)
            onNode(hasSetTextAction()).performTextInput("Looks good now")
            onNodeWithContentDescription("Send the comment").performClick()
            // The row is the app's own until the next read carries the server's copy back.
            onNodeWithText("Looks good now").assertExists()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("COMMENTS · 2").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, onAllNodesWithText("Looks good now").fetchSemanticsNodes().size)
            assertTrue(calls.contains("POST /tasks/t_1/comments"), "the comment was never sent: $calls")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aRefusedCommentIsMarkedWithARetry() = runComposeUiTest {
        refuseComment = true
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            showSheet(scope)
            onNode(hasSetTextAction()).performTextInput("Looks good now")
            onNodeWithContentDescription("Send the comment").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Not sent").fetchSemanticsNodes().isNotEmpty() }
            // What the user typed is still there, and can be sent again without retyping it.
            onNodeWithText("Looks good now").assertExists()
            onNodeWithText("Retry").assertExists()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stoppingARunAsksBeforeItIsSent() = runComposeUiTest {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            showSheet(scope)
            onNodeWithContentDescription("Stop the run").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Stop run #7?").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(calls.contains("POST /runs/7/terminate"), "the run was killed before the user said yes")

            onNodeWithText("Cancel").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Stop run #7?").fetchSemanticsNodes().isEmpty() }
            assertFalse(calls.contains("POST /runs/7/terminate"), "Cancel still killed the run")

            onNodeWithContentDescription("Stop the run").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Stop run #7?").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Stop the run").performClick()
            waitUntil(timeoutMillis = 5_000) { calls.contains("POST /runs/7/terminate") }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun escapeClosesTheSheet() = runComposeUiTest {
        var dismissed = 0
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            showSheet(scope, desktop = true, onDismiss = { dismissed++ })
            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { dismissed > 0 }
            assertEquals(1, dismissed)
        } finally {
            scope.cancel()
        }
    }
}
