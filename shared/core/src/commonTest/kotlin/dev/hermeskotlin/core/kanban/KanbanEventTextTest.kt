package dev.hermeskotlin.core.kanban

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The feed's prose: every kind the backend logs, plus the ones it will add later. */
class KanbanEventTextTest {

    private fun text(kind: String, payload: JsonElement? = null): KanbanEventLine? =
        kanbanEventLine(KanbanEvent(id = 1, taskId = "t_1", kind = kind, payload = payload))

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
    private fun s(value: String) = JsonPrimitive(value)
    private fun n(value: Long) = JsonPrimitive(value)

    @Test
    fun aKnownKindReadsAsASentenceWithItsPayloadFoldedIn() {
        assertEquals(KanbanEventLine("created in Ready", "for coder"), text("created", obj("status" to s("ready"), "assignee" to s("coder"))))
        assertEquals(KanbanEventLine("created in To Do"), text("created"))
        assertEquals(KanbanEventLine("description edited"), text("edited"))
        assertEquals(KanbanEventLine("moved to Running"), text("status", obj("status" to s("running"))))
        assertEquals(KanbanEventLine("moved to Review"), text("status", obj("requested_status" to s("review"))))
        assertEquals(KanbanEventLine("moved to ?"), text("status", obj("status" to n(7))))
        assertEquals(
            KanbanEventLine("moved to Ready", "a parent reopened"),
            text("status", obj("status" to s("ready"), "reason" to s("parent_reopened"))),
        )
        assertEquals(
            KanbanEventLine("moved to Blocked", "gate failed"),
            text("status", obj("status" to s("blocked"), "reason" to s("gate failed"))),
        )
        assertEquals(KanbanEventLine("priority changed to 3"), text("reprioritized", obj("priority" to n(3))))
        assertEquals(KanbanEventLine("priority changed"), text("reprioritized"))
        assertEquals(KanbanEventLine("assigned to tester"), text("assigned", obj("assignee" to s("tester"))))
        assertEquals(KanbanEventLine("unassigned"), text("assigned"))
        assertEquals(KanbanEventLine("comment by boss"), text("commented", obj("author" to s("boss"))))
        assertEquals(KanbanEventLine("comment by someone"), text("comment"))
        assertEquals(KanbanEventLine("linked", "t_1 → t_2"), text("linked", obj("parent" to s("t_1"), "child" to s("t_2"))))
        assertEquals(KanbanEventLine("linked", "t_1"), text("linked", obj("parent" to s("t_1"))))
        assertEquals(KanbanEventLine("unlinked", "t_2"), text("unlinked", obj("child" to s("t_2"))))
        assertEquals(KanbanEventLine("waiting for a parent", "t_9"), text("dependency_wait", obj("parent" to s("t_9"))))
        assertEquals(KanbanEventLine("moved to Ready"), text("promoted"))
        assertEquals(KanbanEventLine("moved to Ready"), text("promoted_manual"))
        assertEquals(
            KanbanEventLine("blocked — needs human input", "user_block"),
            text("blocked", obj("reason" to s("initial_status"), "kind" to s("user_block"))),
        )
        assertEquals(
            KanbanEventLine("blocked — needs human input", "parked when the card was created"),
            text("blocked"),
        )
        assertEquals(
            KanbanEventLine("blocked — needs human input", "waiting on a person"),
            text("blocked", obj("reason" to s("waiting on a person"))),
        )
        assertEquals(
            KanbanEventLine("blocked again and again", "3 blocks in a row — needs a person"),
            text("block_loop_detected", obj("recurrences" to n(3))),
        )
        assertEquals(KanbanEventLine("blocked again and again"), text("block_loop_detected"))
        assertEquals(KanbanEventLine("unblocked"), text("unblocked"))
        assertEquals(KanbanEventLine("worker claimed the card", "run 42"), text("claimed", obj("run_id" to n(42))))
        assertEquals(KanbanEventLine("worker claimed the card"), text("claimed"))
        assertEquals(KanbanEventLine("claim released", "expired"), text("reclaimed", obj("reason" to s("expired"))))
        assertEquals(KanbanEventLine("worker started", "pid 1234"), text("spawned", obj("pid" to n(1234))))
        assertEquals(
            KanbanEventLine("completed", "first line"),
            text("completed", obj("summary" to s("first line\nsecond line"))),
        )
        assertEquals(KanbanEventLine("completed"), text("completed"))
        assertEquals(KanbanEventLine("gave up", "exit code 1"), text("gave_up", obj("exit_code" to n(1))))
        assertEquals(KanbanEventLine("gave up", "retry: queued"), text("gave_up", obj("retry_status" to s("queued"))))
        assertEquals(KanbanEventLine("gave up"), text("gave_up"))
        assertEquals(KanbanEventLine("worker crashed", "exit code -11"), text("crashed", obj("exit_code" to n(-11))))
        assertEquals(
            KanbanEventLine("ran out of time", "after 1h 30m"),
            text("timed_out", obj("max_runtime_seconds" to n(5_400))),
        )
        assertEquals(KanbanEventLine("ran out of time"), text("timed_out"))
        assertEquals(KanbanEventLine("rate limited by the provider"), text("rate_limited"))
        assertEquals(KanbanEventLine("archived"), text("archived"))
        assertEquals(KanbanEventLine("deleted"), text("deleted"))
        assertEquals(KanbanEventLine("file attached", "a.png"), text("attached", obj("filename" to s("a.png"))))
        assertEquals(KanbanEventLine("file removed", "a.png"), text("attachment_removed", obj("filename" to s("a.png"))))
    }

    @Test
    fun aHeartbeatSaysNothingAndAKindFromAFutureGatewayStillSaysItsName() {
        assertNull(text("heartbeat", obj("task_id" to s("t_1"))))

        assertEquals(
            KanbanEventLine("weird new thing", "a=1 b=2"),
            text("weird_new_thing", obj("a" to s("1"), "b" to n(2), "c" to JsonNull, "d" to obj("x" to n(1)))),
        )
        assertEquals(KanbanEventLine("weird new thing"), text("weird_new_thing", obj("c" to JsonNull)))
        assertEquals(KanbanEventLine("weird new thing"), text("weird_new_thing"))
        // A payload that isn't an object is not a crash: the label alone still reads.
        assertEquals(KanbanEventLine("weird"), text("weird", s("not an object")))
    }

    @Test
    fun aStatusTheAppDoesNotKnowStillReads() {
        assertEquals("Ready", kanbanStatusLabel("ready"))
        assertEquals("needs review", kanbanStatusLabel("needs_review"))
        assertEquals("", kanbanStatusLabel(""))
    }

    @Test
    fun aDurationIsReadInTheLargestUnitThatFits() {
        assertEquals("0s", kanbanDuration(-5))
        assertEquals("0s", kanbanDuration(0))
        assertEquals("59s", kanbanDuration(59))
        assertEquals("1m 00s", kanbanDuration(60))
        assertEquals("1m 30s", kanbanDuration(90))
        assertEquals("1h 00m", kanbanDuration(3_600))
        assertEquals("1h 30m", kanbanDuration(5_400))
        assertEquals("2h 05m", kanbanDuration(7_500))
    }
}
