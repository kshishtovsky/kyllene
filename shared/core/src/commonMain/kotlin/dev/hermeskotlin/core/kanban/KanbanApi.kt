package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `POST /tasks` and `PATCH /tasks/{id}` answer `{task}`, null only when the row vanished in the same transaction. */
@Serializable
private data class TaskReply(val task: KanbanTask? = null)

@Serializable
private data class RunReply(val run: KanbanRun? = null)

@Serializable
private data class AttachmentsReply(val attachments: List<KanbanAttachment> = emptyList())

@Serializable
private data class AssigneesReply(val assignees: List<KanbanAssignee> = emptyList())

@Serializable
private data class ProfilesReply(val profiles: List<KanbanProfile> = emptyList())

@Serializable
private data class BoardReply(val board: KanbanBoardMeta? = null)

@Serializable
private data class BoardRemovalReply(val result: KanbanBoardRemoval? = null)

@Serializable
private data class CommentBody(val body: String, val author: String? = null)

@Serializable
private data class ReassignBody(val profile: String?, @SerialName("reclaim_first") val reclaimFirst: Boolean = false)

@Serializable
private data class ReasonBody(val reason: String? = null)

@Serializable
private data class LinkBody(@SerialName("parent_id") val parentId: String, @SerialName("child_id") val childId: String)

@Serializable
private data class NewBoardBody(
    val slug: String,
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    @SerialName("default_workdir") val defaultWorkdir: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    /** Make the new board the server's current one. */
    val switch: Boolean = false,
)

@Serializable
private data class BoardPatchBody(val name: String? = null)

@Serializable
private data class ExportBody(
    /** Where the server writes the archive; null = its own `board-exports` folder. */
    val output: String? = null,
    val attachments: Boolean = true,
    val logs: Boolean = false,
)

@Serializable
private data class ImportBody(val archive: String, val slug: String? = null, val switch: Boolean = false)

/**
 * REST surface of the kanban plugin, mounted at `/api/plugins/kanban` (docs/kanban-api.md). Every call takes
 * the board slug explicitly: the server's own "current board" pointer is a CLI convenience the app must not
 * depend on. Nulls are dropped from request bodies ([dev.hermeskotlin.core.network.HermesJson]',
 * `explicitNulls = false`), which is what `PATCH` reads as "leave this field alone".
 */
class KanbanApi(private val client: HttpClient) {

    /** `GET /board` — the whole board in one reply; 100 KB at 23 cards, no pagination. */
    suspend fun board(
        url: GatewayUrl,
        board: String? = null,
        includeArchived: Boolean = false,
        tenant: String? = null,
    ): ApiResult<KanbanBoard> = apiCall {
        client.get(url.resolve("$PATH/board")) {
            boardQuery(board)
            tenant?.let { parameter("tenant", it) }
            if (includeArchived) parameter("include_archived", true)
        }
    }.map { it.body<KanbanBoard>() }

    /** `GET /boards` — every board on disk, with task counts and the server's current slug. */
    suspend fun boards(url: GatewayUrl, includeArchived: Boolean = false): ApiResult<KanbanBoards> = apiCall {
        client.get(url.resolve("$PATH/boards")) {
            if (includeArchived) parameter("include_archived", true)
        }
    }.map { it.body<KanbanBoards>() }

    /** `GET /tasks/{id}` — the card plus comments, events, attachments, links, child results and runs. */
    suspend fun task(url: GatewayUrl, taskId: String, board: String? = null): ApiResult<KanbanTaskDetail> = apiCall {
        client.get(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}")) { boardQuery(board) }
    }.map { it.body<KanbanTaskDetail>() }

    suspend fun createTask(url: GatewayUrl, task: KanbanNewTask, board: String? = null): ApiResult<KanbanTask> = apiCall {
        client.post(url.resolve("$PATH/tasks")) {
            boardQuery(board)
            contentType(ContentType.Application.Json)
            setBody(task)
        }
    }.map { it.body<TaskReply>().task ?: error("The gateway created nothing.") }

    /** `PATCH /tasks/{id}` — any subset; the reply is the updated card. */
    suspend fun updateTask(
        url: GatewayUrl,
        taskId: String,
        patch: KanbanTaskPatch,
        board: String? = null,
    ): ApiResult<KanbanTask> = apiCall {
        client.patch(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}")) {
            boardQuery(board)
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
    }.map { it.body<TaskReply>().task ?: error("The gateway returned no task.") }

    /** `POST /tasks/{id}/comments`. [author] is free text; the server does not authenticate it. */
    suspend fun addComment(
        url: GatewayUrl,
        taskId: String,
        body: String,
        author: String = "dashboard",
        board: String? = null,
    ): ApiResult<Unit> = apiCall {
        client.post(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}/comments")) {
            boardQuery(board)
            contentType(ContentType.Application.Json)
            setBody(CommentBody(body, author))
        }
    }.map { }

    /** `POST /tasks/{id}/reassign`; a running task needs [reclaimFirst] or it answers 409. */
    suspend fun reassign(
        url: GatewayUrl,
        taskId: String,
        profile: String?,
        reclaimFirst: Boolean = false,
        board: String? = null,
    ): ApiResult<Unit> = apiCall {
        client.post(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}/reassign")) {
            boardQuery(board)
            contentType(ContentType.Application.Json)
            setBody(ReassignBody(profile, reclaimFirst))
        }
    }.map { }

    /** `POST /tasks/{id}/reclaim` — release a worker's claim now; 409 when the task isn't running. */
    suspend fun reclaim(url: GatewayUrl, taskId: String, reason: String? = null, board: String? = null): ApiResult<Unit> =
        apiCall {
            client.post(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}/reclaim")) {
                boardQuery(board)
                contentType(ContentType.Application.Json)
                setBody(ReasonBody(reason))
            }
        }.map { }

    /**
     * `POST /dispatch` — kick the dispatcher instead of waiting out its 60 s tick. With [dryRun] it reports
     * what it would spawn and writes nothing.
     */
    suspend fun dispatch(
        url: GatewayUrl,
        board: String? = null,
        dryRun: Boolean = false,
        max: Int = 8,
    ): ApiResult<KanbanDispatchResult> = apiCall {
        client.post(url.resolve("$PATH/dispatch")) {
            boardQuery(board)
            if (dryRun) parameter("dry_run", true)
            parameter("max", max)
        }
    }.map { it.body<KanbanDispatchResult>() }

    /** `PATCH {status: done}` — the dashboard's complete: a human override of a live claim. */
    suspend fun complete(
        url: GatewayUrl,
        taskId: String,
        summary: String? = null,
        result: String? = null,
        metadata: JsonElement? = null,
        board: String? = null,
    ): ApiResult<KanbanTask> =
        updateTask(url, taskId, KanbanTaskPatch(status = "done", summary = summary, result = result, metadata = metadata), board)

    /** `PATCH {status: blocked}`. */
    suspend fun block(url: GatewayUrl, taskId: String, reason: String? = null, board: String? = null): ApiResult<KanbanTask> =
        updateTask(url, taskId, KanbanTaskPatch(status = "blocked", blockReason = reason), board)

    /** `PATCH {status: ready}` — a blocked card re-opens through the server's unblock path. */
    suspend fun unblock(url: GatewayUrl, taskId: String, board: String? = null): ApiResult<KanbanTask> =
        updateTask(url, taskId, KanbanTaskPatch(status = "ready"), board)

    /** `POST /runs/{id}/terminate` — kills the worker and re-queues the card; not a cancel. */
    suspend fun terminateRun(url: GatewayUrl, runId: Long, reason: String? = null, board: String? = null): ApiResult<Unit> =
        apiCall {
            client.post(url.resolve("$PATH/runs/$runId/terminate")) {
                boardQuery(board)
                contentType(ContentType.Application.Json)
                setBody(ReasonBody(reason))
            }
        }.map { }

    suspend fun addLink(url: GatewayUrl, parentId: String, childId: String, board: String? = null): ApiResult<Unit> =
        apiCall {
            client.post(url.resolve("$PATH/links")) {
                boardQuery(board)
                contentType(ContentType.Application.Json)
                setBody(LinkBody(parentId, childId))
            }
        }.map { }

    /** `DELETE /links` — query parameters here, unlike the POST. A missing edge is `200 {ok:false}`, not an error. */
    suspend fun removeLink(url: GatewayUrl, parentId: String, childId: String, board: String? = null): ApiResult<Unit> =
        apiCall {
            client.delete(url.resolve("$PATH/links")) {
                boardQuery(board)
                parameter("parent_id", parentId)
                parameter("child_id", childId)
            }
        }.map { }

    suspend fun createBoard(url: GatewayUrl, slug: String, name: String? = null): ApiResult<KanbanBoardMeta> = apiCall {
        client.post(url.resolve("$PATH/boards")) {
            contentType(ContentType.Application.Json)
            setBody(NewBoardBody(slug, name))
        }
    }.map { it.body<BoardReply>().board ?: error("The gateway returned no board.") }

    /** `PATCH /boards/{slug}` — the slug itself is immutable; this renames/re-describes the board. */
    suspend fun renameBoard(url: GatewayUrl, slug: String, name: String): ApiResult<KanbanBoardMeta> = apiCall {
        client.patch(url.resolve("$PATH/boards/${slug.encodeURLPathPart()}")) {
            contentType(ContentType.Application.Json)
            setBody(BoardPatchBody(name))
        }
    }.map { it.body<BoardReply>().board ?: error("The gateway returned no board.") }

    /** `DELETE /boards/{slug}` — archive (recoverable) unless [delete], and `default` is refused. */
    suspend fun deleteBoard(url: GatewayUrl, slug: String, delete: Boolean = false): ApiResult<KanbanBoardRemoval> =
        apiCall {
            client.delete(url.resolve("$PATH/boards/${slug.encodeURLPathPart()}")) {
                if (delete) parameter("delete", true)
            }
        }.map { it.body<BoardRemovalReply>().result ?: error("The gateway returned no board removal.") }

    /**
     * `POST /boards/{slug}/export` — the server writes the archive on its own filesystem and answers its path,
     * so this is only useful when the app and the gateway share a machine.
     */
    suspend fun exportBoard(
        url: GatewayUrl,
        slug: String,
        output: String? = null,
        attachments: Boolean = true,
        logs: Boolean = false,
    ): ApiResult<KanbanExport> = apiCall {
        client.post(url.resolve("$PATH/boards/${slug.encodeURLPathPart()}/export")) {
            contentType(ContentType.Application.Json)
            setBody(ExportBody(output, attachments, logs))
        }
    }.map { it.body<KanbanExport>() }

    /** `POST /boards/import` — imports an archive from a server-side path as a new board. */
    suspend fun importBoard(
        url: GatewayUrl,
        archive: String,
        slug: String? = null,
        activate: Boolean = false,
    ): ApiResult<KanbanImport> = apiCall {
        client.post(url.resolve("$PATH/boards/import")) {
            contentType(ContentType.Application.Json)
            setBody(ImportBody(archive, slug, activate))
        }
    }.map { it.body<KanbanImport>() }

    /** `GET /profiles` — the roster tasks are routed across. */
    suspend fun profiles(url: GatewayUrl): ApiResult<List<KanbanProfile>> = apiCall {
        client.get(url.resolve("$PATH/profiles"))
    }.map { it.body<ProfilesReply>().profiles }

    /** `GET /assignees` — the union of on-disk profiles and this board's assignees. */
    suspend fun assignees(url: GatewayUrl, board: String? = null): ApiResult<List<KanbanAssignee>> = apiCall {
        client.get(url.resolve("$PATH/assignees")) { boardQuery(board) }
    }.map { it.body<AssigneesReply>().assignees }

    suspend fun stats(url: GatewayUrl, board: String? = null): ApiResult<KanbanStats> = apiCall {
        client.get(url.resolve("$PATH/stats")) { boardQuery(board) }
    }.map { it.body<KanbanStats>() }

    suspend fun config(url: GatewayUrl): ApiResult<KanbanConfig> = apiCall {
        client.get(url.resolve("$PATH/config"))
    }.map { it.body<KanbanConfig>() }

    suspend fun workflow(url: GatewayUrl): ApiResult<KanbanWorkflow> = apiCall {
        client.get(url.resolve("$PATH/workflow"))
    }.map { it.body<KanbanWorkflow>() }

    suspend fun attachments(url: GatewayUrl, taskId: String, board: String? = null): ApiResult<List<KanbanAttachment>> =
        apiCall {
            client.get(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}/attachments")) { boardQuery(board) }
        }.map { it.body<AttachmentsReply>().attachments }

    /** `GET /attachments/{id}` — the file's bytes. `stored_path` is a server path and never a download route. */
    suspend fun attachment(url: GatewayUrl, attachmentId: Long, board: String? = null): ApiResult<ByteArray> =
        apiCall {
            client.get(url.resolve("$PATH/attachments/$attachmentId")) { boardQuery(board) }
        }.map { it.body<ByteArray>() }

    /** `DELETE /attachments/{id}` — the row and the file on the server's disk both go. */
    suspend fun deleteAttachment(url: GatewayUrl, attachmentId: Long): ApiResult<Unit> = apiCall {
        client.delete(url.resolve("$PATH/attachments/$attachmentId"))
    }.map { }

    /**
     * `POST /tasks/{id}/estimate` — an auxiliary LLM call that takes a second or two and stores nothing. A refusal
     * ("too short") is a 200 with `ok=false` and a [KanbanEstimate.reason], not an error.
     */
    suspend fun estimateTask(url: GatewayUrl, taskId: String, board: String? = null): ApiResult<KanbanEstimate> =
        apiCall {
            client.post(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}/estimate")) {
                boardQuery(board)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }.map { it.body<KanbanEstimate>() }

    /** `GET /tasks/{id}/log` — the worker's output; [tail] caps the bytes the server reads. */
    suspend fun log(url: GatewayUrl, taskId: String, tail: Int? = null, board: String? = null): ApiResult<KanbanLog> =
        apiCall {
            client.get(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}/log")) {
                boardQuery(board)
                tail?.let { parameter("tail", it) }
            }
        }.map { it.body<KanbanLog>() }

    suspend fun run(url: GatewayUrl, runId: Long, board: String? = null): ApiResult<KanbanRun> = apiCall {
        client.get(url.resolve("$PATH/runs/$runId")) { boardQuery(board) }
    }.map { it.body<RunReply>().run ?: error("The gateway returned no run.") }

    /**
     * A task's runs. The plugin serves no run list of its own: the rows come with `GET /tasks/{id}`, where
     * `run_state_type` (`status`/`outcome`) plus `run_state_name` filter them (docs/kanban-api.md §4).
     */
    suspend fun runs(
        url: GatewayUrl,
        taskId: String,
        board: String? = null,
        stateType: String? = null,
        stateName: String? = null,
    ): ApiResult<List<KanbanRun>> = apiCall {
        client.get(url.resolve("$PATH/tasks/${taskId.encodeURLPathPart()}")) {
            boardQuery(board)
            if (stateType != null && stateName != null) {
                parameter("run_state_type", stateType)
                parameter("run_state_name", stateName)
            }
        }
    }.map { it.body<KanbanTaskDetail>().runs }

    private fun HttpRequestBuilder.boardQuery(slug: String?) {
        slug?.let { parameter("board", it) }
    }

    private companion object {
        const val PATH = "api/plugins/kanban"
    }
}
