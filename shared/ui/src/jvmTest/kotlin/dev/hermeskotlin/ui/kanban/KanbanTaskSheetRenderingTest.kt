package dev.hermeskotlin.ui.kanban

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanTaskDetail
import dev.hermeskotlin.core.network.HermesJson
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
import kotlin.test.Test

/** The sheet draws a loaded card: a smoke test of its sections at once, since each one is a Compose tree. */
@OptIn(ExperimentalTestApi::class)
class KanbanTaskSheetRenderingTest {

    private val reply = """
        {"task":{"id":"t_1","title":"Write the report","status":"running","priority":1,"assignee":"coder",
                  "body":"Fix the **parser**."},
         "comments":[{"id":4,"task_id":"t_1","author":"dashboard","body":"go","created_at":1793925470}],
         "events":[{"id":307,"task_id":"t_1","run_id":null,"kind":"blocked","payload":{"reason":"needs a decision"},
                    "created_at":1793925272}],
         "attachments":[{"id":9,"task_id":"t_1","filename":"log.txt","content_type":"text/plain","size":17,
                         "uploaded_by":"probe","stored_path":null,"created_at":1793925473}],
         "links":{"parents":[],"children":[]},"link_tasks":[],"child_results":[],
         "runs":[{"id":7,"task_id":"t_1","profile":"coder","status":"running"}]}
    """.trimIndent()

    @Test
    fun theSheetDrawsTheCardItsCommentsAndItsComposer() = runComposeUiTest {
        val api = KanbanApi(
            createHttpClient(
                MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            ),
        )
        val scope = CoroutineScope(Dispatchers.Default)
        val model = KanbanTaskModel(api, GatewayUrl.parse("https://hermes.example.ts.net"), board = "dev", scope = scope)
        val state = KanbanTaskState(taskId = "t_1", detail = HermesJson.decodeFromString<KanbanTaskDetail>(reply))
        try {
            setContent {
                HermesTheme(ColorScheme.Dark) { KanbanTaskSheetContent(state = state, model = model) }
            }
            waitForIdle()

            onNodeWithText("Running").assertExists()
            onNodeWithText("DESCRIPTION").assertExists()
            onNodeWithText("COMMENTS · 1").assertExists()
            onNodeWithText("go").assertExists()
            onNodeWithContentDescription("Send the comment").assertExists()
            onNodeWithText("Write a comment…").assertExists()
        } finally {
            scope.cancel()
        }
    }
}
