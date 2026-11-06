package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.chat.long
import dev.hermeskotlin.core.chat.string
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `task_events` row as a person reads it: a phrase, plus whatever part of the payload says something. */
data class KanbanEventLine(val label: String, val detail: String? = null)

/** The board's lanes in words; a status the app doesn't know still reads (`needs_review` → "needs review"). */
fun kanbanStatusLabel(status: String): String = when (status) {
    "triage" -> "Triage"
    "todo" -> "To Do"
    "scheduled" -> "Scheduled"
    "ready" -> "Ready"
    "running" -> "Running"
    "blocked" -> "Blocked"
    "review" -> "Review"
    "done" -> "Done"
    "archived" -> "Archived"
    else -> status.replace('_', ' ')
}

/**
 * The feed's prose. The backend logs machine payloads (`status` + `{"status":"ready"}`), and rendering the raw kind
 * made the feed unreadable, so known kinds get a sentence with the payload folded in. Heartbeats (one a minute per
 * running card) are dropped. An unknown kind still says something: its name plus `key=value` for the payload.
 */
fun kanbanEventLine(event: KanbanEvent): KanbanEventLine? {
    val payload = event.payload as? JsonObject
    fun text(key: String) = payload.string(key)
    fun number(key: String) = payload.long(key)

    return when (event.kind) {
        "heartbeat" -> null
        "created" -> KanbanEventLine(
            "created in ${kanbanStatusLabel(text("status") ?: "todo")}",
            text("assignee")?.let { "for $it" },
        )
        "edited" -> KanbanEventLine("description edited")
        "status" -> KanbanEventLine(
            "moved to ${kanbanStatusLabel(text("status") ?: text("requested_status") ?: "?")}",
            when (val reason = text("reason")) {
                null -> null
                "parent_reopened" -> "a parent reopened"
                else -> reason
            },
        )
        "reprioritized" -> KanbanEventLine("priority ${number("priority")?.let { "changed to $it" } ?: "changed"}")
        "assigned" -> KanbanEventLine(text("assignee")?.let { "assigned to $it" } ?: "unassigned")
        "commented", "comment" -> KanbanEventLine("comment by ${text("author") ?: "someone"}")
        "linked" -> KanbanEventLine("linked", edge(text("parent"), text("child")))
        "unlinked" -> KanbanEventLine("unlinked", edge(text("parent"), text("child")))
        "dependency_wait" -> KanbanEventLine("waiting for a parent", text("parent"))
        "promoted", "promoted_manual" -> KanbanEventLine("moved to Ready")
        "blocked" -> KanbanEventLine(
            "blocked — needs human input",
            when (val reason = text("reason")) {
                null, "initial_status" -> text("kind") ?: "parked when the card was created"
                else -> reason
            },
        )
        "block_loop_detected" -> KanbanEventLine(
            "blocked again and again",
            number("recurrences")?.let { "${it} blocks in a row — needs a person" },
        )
        "unblocked" -> KanbanEventLine("unblocked")
        "claimed" -> KanbanEventLine("worker claimed the card", number("run_id")?.let { "run $it" })
        "reclaimed" -> KanbanEventLine("claim released", text("reason"))
        "spawned" -> KanbanEventLine("worker started", number("pid")?.let { "pid $it" })
        "completed" -> KanbanEventLine("completed", text("summary")?.lineSequence()?.firstOrNull()?.take(120))
        "gave_up" -> KanbanEventLine("gave up", failure(text("retry_status"), number("exit_code")))
        "crashed" -> KanbanEventLine("worker crashed", failure(null, number("exit_code")))
        "timed_out" -> KanbanEventLine(
            "ran out of time",
            number("max_runtime_seconds")?.let { "after ${kanbanDuration(it)}" },
        )
        "rate_limited" -> KanbanEventLine("rate limited by the provider")
        "archived" -> KanbanEventLine("archived")
        "deleted" -> KanbanEventLine("deleted")
        "attached" -> KanbanEventLine("file attached", text("filename"))
        "attachment_removed" -> KanbanEventLine("file removed", text("filename"))
        else -> KanbanEventLine(
            event.kind.replace('_', ' '),
            payload?.entries
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeUnless { value is JsonNull }?.let { "$key=${it.content}" } }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" "),
        )
    }
}

private fun edge(parent: String?, child: String?): String? =
    if (parent != null && child != null) "$parent → $child" else parent ?: child

private fun failure(retry: String?, exitCode: Long?): String? = when {
    exitCode != null -> "exit code $exitCode"
    retry != null -> "retry: $retry"
    else -> null
}

/** Payload durations are seconds; "90 s" is worse than "1m 30s". */
fun kanbanDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
        else -> "${s / 3600}h ${(s % 3600 / 60).toString().padStart(2, '0')}m"
    }
}
