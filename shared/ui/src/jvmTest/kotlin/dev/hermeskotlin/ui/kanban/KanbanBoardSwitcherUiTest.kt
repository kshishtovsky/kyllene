package dev.hermeskotlin.ui.kanban

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.designsystem.HermesTheme
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

/** The board picker: the list it draws, a pick, and a delete that asks first. */
@OptIn(ExperimentalTestApi::class)
class KanbanBoardSwitcherUiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    private val calls = mutableListOf<String>()

    /** What the gateway holds; a DELETE really drops the board here, so the reload can't bring it back. */
    private val boards = mutableListOf(
        """{"slug":"dev","name":"Dev","counts":{"running":2,"todo":1},"total":3}""",
        """{"slug":"other","name":"Other","counts":{},"total":0}""",
    )

    private fun client() = createHttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/api/plugins/kanban")
            calls += "${request.method.value} $path" + (request.url.parameters["board"]?.let { "?board=$it" } ?: "")
            val json = when {
                path == "/boards" && request.method == HttpMethod.Get ->
                    """{"boards":[${boards.joinToString(",")}],"current":"dev"}"""
                path == "/board" && request.method == HttpMethod.Get ->
                    """{"columns":[{"name":"todo","tasks":[{"id":"t_1","title":"Fix the driveway","status":"todo"}]}],"latest_event_id":7}"""
                path.startsWith("/boards/") && request.method == HttpMethod.Delete -> {
                    boards.removeAll { it.contains("\"slug\":\"${path.removePrefix("/boards/")}\"") }
                    """{"result":{"slug":"other","action":"archived"}}"""
                }
                else -> error("unexpected ${request.method.value} $path")
            }
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private fun ComposeUiTest.showSwitcher(scope: CoroutineScope, onDismiss: () -> Unit = {}): KanbanBoardSwitcherModel {
        val api = KanbanApi(client())
        val model = KanbanBoardSwitcherModel(api, KanbanRepository(api), InMemoryKeyValueStore(), scope)
        setContent {
            LaunchedEffect(Unit) { model.load(url, profile = "work") }
            HermesTheme(ColorScheme.Dark) {
                KanbanBoardSwitcher(visible = true, model = model, onDismiss = onDismiss)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Other").fetchSemanticsNodes().isNotEmpty() }
        return model
    }

    @Test
    fun theListShowsEveryBoardWithItsCountsAndTheCurrentOne() = runComposeUiTest {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            showSwitcher(scope)
            onNodeWithText("Dev").assertExists()
            onNodeWithText("dev · 2 running · 3 tasks").assertExists()
            onNodeWithText("other · 0 running · 0 tasks").assertExists()
            // The board on screen is the only one that carries the tick.
            assertEquals(1, onAllNodesWithContentDescription("Current board").fetchSemanticsNodes().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pickingABoardClosesTheListAndSwitchesTheCache() = runComposeUiTest {
        var dismissed = 0
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val model = showSwitcher(scope, onDismiss = { dismissed++ })
            onNodeWithText("Other").performClick()
            assertEquals(1, dismissed)
            waitUntil(timeoutMillis = 5_000) { calls.contains("GET /board?board=other") }
            assertEquals("other", model.state.value.current)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deletingABoardAsksBeforeItIsArchived() = runComposeUiTest {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            showSwitcher(scope)
            onNodeWithContentDescription("Delete other").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete “Other”?").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(calls.any { it.startsWith("DELETE") }, "the board was archived before the user said yes")

            onNodeWithText("Cancel").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete “Other”?").fetchSemanticsNodes().isEmpty() }
            assertFalse(calls.any { it.startsWith("DELETE") }, "Cancel still archived the board")

            onNodeWithContentDescription("Delete other").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete “Other”?").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Delete").performClick()
            waitUntil(timeoutMillis = 5_000) { calls.contains("DELETE /boards/other") }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Other").fetchSemanticsNodes().isEmpty() }
        } finally {
            scope.cancel()
        }
    }
}
