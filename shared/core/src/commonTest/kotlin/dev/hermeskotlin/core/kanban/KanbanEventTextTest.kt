package dev.hermeskotlin.core.kanban

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanEventTextTest {

    private fun event(kind: String, payload: JsonElement? = null) =
        KanbanEvent(id = 1, taskId = "t_1", runId = null, kind = kind, payload = payload, createdAt = 0)

    private fun line(kind: String, payload: JsonElement? = null) = kanbanEventLine(event(kind, payload))

    @Test
    fun blockedSaysWhyInWords() {
        val line = line("blocked", buildJsonObject { put("reason", "needs a decision on the schema") })

        assertEquals("blocked — needs human input", line?.label)
        assertEquals("needs a decision on the schema", line?.detail)
    }

    @Test
    fun aBlockWithNoReasonStillSaysSomething() {
        val line = line("blocked", buildJsonObject { put("reason", "initial_status"); put("kind", "waiting") })

        assertEquals("waiting", line?.detail)
    }

    @Test
    fun everyFeedKindHasProse() {
        val kinds = listOf("status", "comment", "completed", "blocked", "gave_up", "crashed", "timed_out", "block_loop_detected", "edited", "linked")
        kinds.forEach { kind ->
            val rendered = line(kind)
            assertTrue(rendered != null && rendered.label.isNotBlank(), "no prose for $kind")
        }
    }

    @Test
    fun heartbeatsAreDropped() {
        assertNull(line("heartbeat"))
    }

    @Test
    fun aTimeoutKeepsItsLimitReadable() {
        val rendered = line("timed_out", buildJsonObject { put("max_runtime_seconds", 5_400) })

        assertEquals("ran out of time", rendered?.label)
        assertTrue(rendered?.detail?.contains("1h 30m") == true, "got ${rendered?.detail}")
    }

    @Test
    fun anUnknownKindStillPrintsItsPayload() {
        val rendered = line("bespoke", buildJsonObject { put("step", "3") })

        assertTrue(rendered?.detail?.contains("step=3") == true, "got ${rendered?.detail}")
    }

    @Test
    fun aStatusMoveReadsAsALaneAndItsReason() {
        val rendered = line("status", buildJsonObject { put("status", "review"); put("reason", "parent_reopened") })

        assertEquals("moved to Review", rendered?.label)
        assertEquals("a parent reopened", rendered?.detail)
    }
}
