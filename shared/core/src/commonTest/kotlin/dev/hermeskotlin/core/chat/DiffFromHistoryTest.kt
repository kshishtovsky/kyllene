package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The gateway stores a file edit's diff beside the tool row, under `display_metadata`, not in the result text: a
 * chat opened from history has to read it there, or the edit shows as a bare JSON result.
 */
class DiffFromHistoryTest {

    private fun call(id: String, name: String, arguments: String) = buildJsonArray {
        add(
            buildJsonObject {
                put("id", id)
                put("function", buildJsonObject { put("name", name); put("arguments", JsonPrimitive(arguments)) })
            },
        )
    }

    private fun metadata(inlineDiff: String): JsonElement = Json.parseToJsonElement(
        """{"tool_result_metadata":{"inline_diff":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(inlineDiff))}}}""",
    )

    private val diff = "a/src/app.ts → b/src/app.ts\n@@ -1,2 +1,2 @@\n-const a = 1\n+const a = 2\n const b = 3"

    @Test
    fun aStoredEditCarriesItsDiffIntoTheActivity() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("change it")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = call("c1", "patch", """{"path":"src/app.ts"}""")),
                SessionMessage(
                    id = 3,
                    role = "tool",
                    content = JsonPrimitive("""{"success":true}"""),
                    toolCallId = "c1",
                    toolName = "patch",
                    displayMetadata = metadata(diff),
                ),
            ),
        )
        val tool = messages.filterIsInstance<ChatMessage.Assistant>().single().tools.single()
        assertEquals(diff, tool.diff)
    }

    @Test
    fun anOlderStoredDiffInTheResultStillShows() {
        // Before the gateway kept `inline_diff`, `patch` put the diff in its own result.
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("change it")),
                SessionMessage(
                    id = 2,
                    role = "assistant",
                    content = JsonPrimitive(""),
                    toolCalls = call("c1", "patch", """{"path":"a.txt"}"""),
                ),
                SessionMessage(
                    id = 3,
                    role = "tool",
                    content = JsonPrimitive("""{"success":true,"diff":"--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-x\n+y"}"""),
                    toolCallId = "c1",
                ),
            ),
        )
        assertNotNull(messages.filterIsInstance<ChatMessage.Assistant>().single().tools.single().diff)
    }

    @Test
    fun aToolThatChangedNoFileHasNoDiff() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("run it")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = call("c1", "terminal", """{"command":"ls"}""")),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("""{"output":"a.txt"}"""), toolCallId = "c1", toolName = "terminal"),
            ),
        )
        assertNull(messages.filterIsInstance<ChatMessage.Assistant>().single().tools.single().diff)
    }
}
