package dev.hermeskotlin.core.kanban

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The kanban plugin's REST shapes (`plugins/kanban/dashboard/plugin_api.py`, captured in docs/kanban-api.md).
 * Everything is defaulted: every key here is optional in some reply (an older gateway, a card being deleted,
 * `include_archived` off), and a missing key must never lose the whole board.
 */

/** `GET /board` — the whole board, every lane in workflow order, no pagination. */
@Serializable
data class KanbanBoard(
    val columns: List<KanbanColumn> = emptyList(),
    val tenants: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    /** Tail of the board's `task_events`: the `since` cursor the event socket opens with. */
    @SerialName("latest_event_id") val latestEventId: Long = 0,
    val now: Long = 0,
)

@Serializable
data class KanbanColumn(
    /** A workflow key (`todo`, `running`, …), not a display label. */
    val name: String,
    val tasks: List<KanbanTask> = emptyList(),
)

/** Edge counts, not distinct tasks: a card with 2 parents and 7 children shows both. */
@Serializable
data class KanbanLinkCounts(val parents: Int = 0, val children: Int = 0)

/** Child completion of a parent card; null on the card for a leaf. */
@Serializable
data class KanbanProgress(val done: Int = 0, val total: Int = 0)

/** A card's diagnostics rollup, present only when the server flags the card. */
@Serializable
data class KanbanWarnings(
    val count: Int = 0,
    val kinds: Map<String, Int> = emptyMap(),
    @SerialName("latest_at") val latestAt: Long = 0,
    @SerialName("highest_severity") val highestSeverity: String? = null,
)

/**
 * One card. `GET /board` carries the rollups ([linkCounts], [commentCount], [progress], [warnings]); the task
 * detail carries the same row without them plus the worker/override fields below.
 */
@Serializable
data class KanbanTask(
    val id: String,
    val title: String = "",
    /** Full markdown, never truncated on a card — a 200-char preview lives in [latestSummary]. */
    val body: String? = null,
    /** The lane it sits in: `triage`, `todo`, `scheduled`, `ready`, `running`, `blocked`, `review`, `done`. */
    val status: String = "",
    val assignee: String? = null,
    val priority: Int = 0,
    val tenant: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    /** Unix seconds, like every time in this API. */
    @SerialName("created_at") val createdAt: Long? = null,
    /** Newest run summary; on a card cut to 200 chars mid-word, untruncated on the detail. */
    @SerialName("latest_summary") val latestSummary: String? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("link_counts") val linkCounts: KanbanLinkCounts = KanbanLinkCounts(),
    val progress: KanbanProgress? = null,
    val warnings: KanbanWarnings? = null,
    /** The task's first-ever start; the run clock ticks from [currentRunStartedAt]. */
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("current_run_started_at") val currentRunStartedAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("worker_pid") val workerPid: Long? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: Long? = null,
    @SerialName("last_failure_error") val lastFailureError: String? = null,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    /** `dependency`, `needs_input`, `capability` or `transient`; kept across unblock, so only current while blocked. */
    @SerialName("block_kind") val blockKind: String? = null,
    /** How often it re-blocked for the same reason after a human unblock. */
    @SerialName("block_recurrences") val blockRecurrences: Int = 0,
    @SerialName("workspace_kind") val workspaceKind: String? = null,
    @SerialName("workspace_path") val workspacePath: String? = null,
    @SerialName("branch_name") val branchName: String? = null,
    val result: String? = null,
    @SerialName("model_override") val modelOverride: String? = null,
    @SerialName("provider_override") val providerOverride: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class KanbanComment(
    val id: Long,
    @SerialName("task_id") val taskId: String = "",
    val author: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

/** One `task_events` row — the same rows the event socket streams. [payload] is a hint, never the content. */
@Serializable
data class KanbanEvent(
    val id: Long,
    @SerialName("task_id") val taskId: String = "",
    @SerialName("run_id") val runId: Long? = null,
    /** Free-form and open-ended: new kinds appear with gateway updates, so never switch exhaustively. */
    val kind: String = "",
    val payload: JsonElement? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class KanbanAttachment(
    val id: Long,
    @SerialName("task_id") val taskId: String = "",
    val filename: String = "",
    @SerialName("content_type") val contentType: String? = null,
    val size: Long = 0,
    @SerialName("uploaded_by") val uploadedBy: String? = null,
    /** A server-side path: meaningless to a phone, download through `/attachments/{id}` instead. */
    @SerialName("stored_path") val storedPath: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

/** Dependency ids only; [KanbanTaskDetail.linkTasks] carries their titles. */
@Serializable
data class KanbanLinks(
    val parents: List<String> = emptyList(),
    val children: List<String> = emptyList(),
)

@Serializable
data class KanbanLinkTask(val id: String, val title: String = "", val status: String = "")

@Serializable
data class KanbanChildResult(
    val id: String,
    val title: String = "",
    val status: String = "",
    @SerialName("latest_summary") val latestSummary: String? = null,
    val result: String? = null,
)

/** One attempt at a task; closed on complete/block/crash/timeout/reclaim. */
@Serializable
data class KanbanRun(
    val id: Long,
    @SerialName("task_id") val taskId: String = "",
    val profile: String? = null,
    @SerialName("step_key") val stepKey: String? = null,
    val status: String = "",
    @SerialName("claim_lock") val claimLock: String? = null,
    @SerialName("claim_expires") val claimExpires: Long? = null,
    @SerialName("worker_pid") val workerPid: Long? = null,
    @SerialName("max_runtime_seconds") val maxRuntimeSeconds: Long? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: Long? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("ended_at") val endedAt: Long? = null,
    val outcome: String? = null,
    val summary: String? = null,
    /** A parsed object, not a string; omitted when the run stored none. */
    val metadata: JsonElement? = null,
    val error: String? = null,
)

/** `GET /tasks/{id}` — the task plus its collections, which are siblings of `task`, not nested in it. */
@Serializable
data class KanbanTaskDetail(
    val task: KanbanTask,
    val comments: List<KanbanComment> = emptyList(),
    val events: List<KanbanEvent> = emptyList(),
    val attachments: List<KanbanAttachment> = emptyList(),
    val links: KanbanLinks = KanbanLinks(),
    @SerialName("link_tasks") val linkTasks: List<KanbanLinkTask> = emptyList(),
    @SerialName("child_results") val childResults: List<KanbanChildResult> = emptyList(),
    val runs: List<KanbanRun> = emptyList(),
    /** Never in a reply: an estimate is a POST-only LLM call (`POST /tasks/{id}/estimate`), nothing is stored. */
    val estimate: KanbanEstimate? = null,
)

/** `GET /boards` row. */
@Serializable
data class KanbanBoardMeta(
    val slug: String,
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    /** The board's project directory new tasks inherit; empty/null = none. */
    @SerialName("default_workdir") val defaultWorkdir: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("default_workspace_kind") val defaultWorkspaceKind: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val archived: Boolean = false,
    /** A server path — ignore it on the phone. */
    @SerialName("db_path") val dbPath: String? = null,
    /** The server's own current-board pointer, which the app must not depend on. */
    @SerialName("is_current") val isCurrent: Boolean = false,
    /** Keyed by status, empty statuses omitted. */
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
)

@Serializable
data class KanbanBoards(
    val boards: List<KanbanBoardMeta> = emptyList(),
    val current: String? = null,
)

@Serializable
data class KanbanProfile(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val description: String? = null,
    @SerialName("description_auto") val descriptionAuto: Boolean = false,
    @SerialName("skill_count") val skillCount: Int = 0,
)

/** `GET /assignees`: on-disk profiles united with assignees that hold tasks on this board. */
@Serializable
data class KanbanAssignee(
    val name: String,
    @SerialName("on_disk") val onDisk: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class KanbanStats(
    @SerialName("by_status") val byStatus: Map<String, Int> = emptyMap(),
    @SerialName("by_assignee") val byAssignee: Map<String, Map<String, Int>> = emptyMap(),
    @SerialName("oldest_ready_age_seconds") val oldestReadyAgeSeconds: Long? = null,
    val now: Long = 0,
)

@Serializable
data class KanbanConfig(
    @SerialName("default_tenant") val defaultTenant: String? = null,
    @SerialName("lane_by_profile") val laneByProfile: Boolean = false,
    @SerialName("include_archived_by_default") val includeArchivedByDefault: Boolean = false,
    @SerialName("render_markdown") val renderMarkdown: Boolean = false,
)

/** `GET /workflow`: column order, labels, and the manual-move allow-list. */
@Serializable
data class KanbanWorkflow(
    val columns: List<KanbanWorkflowColumn> = emptyList(),
    /** `manual[from]` = the statuses a manual move out of `from` may target. */
    val manual: Map<String, List<String>> = emptyMap(),
    val archived: String = "archived",
)

@Serializable
data class KanbanWorkflowColumn(
    val key: String,
    val label: String = "",
    val icon: String = "",
    @SerialName("drag_target") val dragTarget: Boolean = false,
)

/** `GET /tasks/{id}/log` — the worker's stdout/stderr. A missing log is not an error: `exists=false`. */
@Serializable
data class KanbanLog(
    @SerialName("task_id") val taskId: String = "",
    /** A server path, useless to a phone. */
    val path: String? = null,
    val exists: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val content: String = "",
    val truncated: Boolean = false,
)

/** `POST /dispatch` — `spawned` entries are `(task_id, assignee, workspace_path)`; the path is null for scratch. */
@Serializable
data class KanbanDispatchResult(
    val reclaimed: Int = 0,
    val promoted: Int = 0,
    val spawned: List<List<String?>> = emptyList(),
)

/** `POST /tasks/{id}/estimate` and `POST /estimate`: a rough token read, never dollars. */
@Serializable
data class KanbanEstimate(
    val ok: Boolean = false,
    /** Why not, when `ok` is false — still a `200`. */
    val reason: String? = null,
    @SerialName("est_tokens") val estTokens: Long? = null,
    /** `S`, `M` or `L`. */
    val complexity: String? = null,
    val rationale: String? = null,
    val model: String? = null,
)

/** `POST /boards/{slug}/export` — the archive the server wrote, on the server's own filesystem. */
@Serializable
data class KanbanExport(
    val board: String = "",
    val archive: String? = null,
    val size: Long = 0,
    val counts: Map<String, Int> = emptyMap(),
)

/** `POST /boards/import` — the new board the archive landed as. */
@Serializable
data class KanbanImport(
    val board: String = "",
    @SerialName("requested_board") val requestedBoard: String? = null,
    /** True when the archive's slug was taken and the import got a suffix. */
    val renamed: Boolean = false,
    val name: String? = null,
    val counts: Map<String, Int> = emptyMap(),
    @SerialName("attachments_restored") val attachmentsRestored: Int = 0,
    @SerialName("tasks_parked") val tasksParked: Int = 0,
    val warnings: List<String> = emptyList(),
    val activated: Boolean = false,
)

/** `DELETE /boards/{slug}` — archived by default, hard-deleted with `delete=1`. */
@Serializable
data class KanbanBoardRemoval(
    val slug: String = "",
    val action: String = "",
    @SerialName("new_path") val newPath: String? = null,
)

/**
 * The `PATCH /tasks/{id}` body. A null field is "not sent" (the gateway reads null as "leave it"), so clearing
 * the overrides needs the explicit `clear*` flags; an empty `assignee` unassigns.
 */
@Serializable
data class KanbanTaskPatch(
    val status: String? = null,
    val assignee: String? = null,
    val priority: Int? = null,
    val title: String? = null,
    val body: String? = null,
    val result: String? = null,
    /** Why a `blocked` task is blocked. */
    @SerialName("block_reason") val blockReason: String? = null,
    /** The handoff summary a `done` task carries. */
    val summary: String? = null,
    val metadata: JsonElement? = null,
    @SerialName("model_override") val modelOverride: String? = null,
    @SerialName("provider_override") val providerOverride: String? = null,
    @SerialName("clear_model_override") val clearModelOverride: Boolean = false,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("clear_reasoning_effort") val clearReasoningEffort: Boolean = false,
)

/** `POST /tasks`. Only [title] is required; the server validates the rest. */
@Serializable
data class KanbanNewTask(
    val title: String,
    val body: String? = null,
    val assignee: String? = null,
    val tenant: String? = null,
    val priority: Int = 0,
    /** Parents that must be done before this one promotes to `ready`. */
    val parents: List<String> = emptyList(),
)
