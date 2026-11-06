package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.chat.int
import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** The board as the app shows it. [board] is empty until the first read, or when that read failed ([error]). */
data class KanbanState(
    /** The board being shown; null means "whatever the gateway calls current", which only happens before a pick. */
    val slug: String? = null,
    val board: KanbanBoard = KanbanBoard(),
    val loading: Boolean = false,
    /** Why the last read failed, for a banner; null once a read succeeds. */
    val error: String? = null,
    /** How often the event socket has had to reconnect: the board's "no connection" banner. */
    val reconnects: Int = 0,
    /** The newest event the socket delivered; null until the first one. */
    val lastEvent: KanbanEvent? = null,
    /** Whether the board is being kept current by the socket; false means only as fresh as the last poll. */
    val live: Boolean = false,
)

/**
 * The board of one (gateway, profile), shared by the screen and the event socket: [refresh] reads it whole,
 * [applyEvent] patches one card from a socket event so a status change doesn't cost a 100 KB refetch.
 *
 * Every write goes through here too: the board is patched first, so a card moves lanes the moment a finger
 * lifts; the server's reply replaces that guess; a refusal puts the old card back and hands the server's own
 * message to the caller. A second tap while the first call is still open sends nothing.
 */
class KanbanRepository(private val api: KanbanApi) {

    private val _state = MutableStateFlow(KanbanState())

    /** Empty until the first read; [KanbanState.loading] while one is in flight. */
    val state: StateFlow<KanbanState> = _state.asStateFlow()

    private val _invalidated = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ids of cards whose detail just changed, for the open sheet to reload; see [applyEvents]. */
    val invalidated: Flow<String> = _invalidated.asSharedFlow()

    private var scope: Scope? = null

    /** The gateway the board belongs to, once [bind] has named one; the event socket opens against it. */
    val gateway: GatewayUrl? get() = scope?.url

    /** The gateway and profile (null: the gateway's launch profile) the board belongs to. */
    private data class Scope(val url: GatewayUrl, val profile: String?)

    /** Shows [profile]'s board on [url] from now on; another scope's board is dropped at once. */
    fun bind(url: GatewayUrl, profile: String?) {
        val next = Scope(url, profile)
        if (scope == next) return
        scope = next
        _state.value = KanbanState()
    }

    /** Switches to [slug] and reads it; a board picked before the read is never shown under the old one. */
    suspend fun selectBoard(slug: String) {
        _state.update { it.copy(slug = slug, board = KanbanBoard(), error = null) }
        refresh()
    }

    /** Reads the board again. A reply for a board switched away from meanwhile is dropped. */
    suspend fun refresh() {
        val scope = scope ?: return
        val slug = _state.value.slug
        _state.update { it.copy(loading = true) }
        val result = api.board(scope.url, board = slug)
        if (scope != this.scope || slug != _state.value.slug) return
        _state.update { state ->
            when (result) {
                is ApiResult.Success -> state.copy(board = result.value.deduped(), loading = false, error = null)
                else -> state.copy(loading = false, error = result.errorMessage)
            }
        }
    }

    /**
     * Applies a whole socket frame at once, so a drained backlog doesn't recompose the board once per row,
     * and tells the open sheet about every card whose detail the frame touched.
     */
    fun applyEvents(events: List<KanbanEvent>) {
        if (events.isEmpty()) return
        val touched = mutableSetOf<String>()
        _state.update { state ->
            var board = state.board
            for (event in events) {
                val patched = board.patched(event) ?: continue
                board = patched
                touched += event.taskId
            }
            state.copy(board = board, lastEvent = events.last())
        }
        for (id in touched) _invalidated.tryEmit(id)
    }

    /** One event, as [applyEvents] takes a frame's worth. */
    fun applyEvent(event: KanbanEvent) = applyEvents(listOf(event))

    /** Records the socket's health for the board's "no connection" banner. */
    fun setLive(alive: Boolean, reconnects: Int) {
        _state.update { it.copy(live = alive, reconnects = reconnects) }
    }

    /**
     * One event applied to the board it names; null when it changes nothing (a card this board doesn't hold).
     * Event payloads never carry content (`commented` is `{author,len}`), so only the fields the event *does*
     * state are patched; comment text, bodies and attachments still need the detail fetch.
     */
    private fun KanbanBoard.patched(event: KanbanEvent): KanbanBoard? {
        val task = card(event.taskId) ?: return null
        val payload = event.payload as? JsonObject
        val next = when (event.kind) {
            "status" -> task.copy(status = payload.string("status") ?: payload.string("requested_status") ?: task.status)
            "completed" -> task.copy(status = "done")
            "reprioritized" -> task.copy(priority = payload.int("priority") ?: task.priority)
            "assigned" -> task.copy(assignee = payload.string("assignee"))
            // The text is not in the event, so the count is all a card can learn from it.
            "commented" -> task.copy(commentCount = task.commentCount + 1)
            "archived", "deleted" -> null
            else -> task
        }
        // shortcut: the moved card lands at the end of its lane, not in the server's dispatch order — the
        // next full refresh restores it; sort here once a lane's order starts to matter to the UI.
        return withTask(event.taskId, next).copy(latestEventId = maxOf(latestEventId, event.id))
    }

    // --- Writes -------------------------------------------------------------------------------------
    // Each answers the text to show the user, or null when the write went through (create answers the card).

    /**
     * `POST /tasks`, then `PATCH {status}` when [column] names a lane other than the one the server picked:
     * `triage` is a create flag, every other column is a follow-up patch.
     */
    suspend fun create(draft: KanbanNewTask, column: String? = null): ApiResult<KanbanTask> =
        once<ApiResult<KanbanTask>>(CREATE, ApiResult.Failed(0, ALREADY_SENDING)) {
            val scope = scope ?: return@once ApiResult.Unavailable(NOT_CONNECTED)
            when (val created = api.createTask(scope.url, draft, _state.value.slug)) {
                is ApiResult.Success -> {
                    val moved = if (column != null && column != created.value.status) {
                        api.updateTask(scope.url, created.value.id, KanbanTaskPatch(status = column), _state.value.slug)
                    } else {
                        null
                    }
                    when (moved) {
                        null -> ApiResult.Success(place(created.value))
                        is ApiResult.Success -> ApiResult.Success(place(moved.value))
                        // The card exists, only not in the lane that was asked for: show where the server put it.
                        else -> {
                            refresh()
                            moved
                        }
                    }
                }
                else -> created
            }
        }

    /** `PATCH {status}` — the manual move from a status menu. */
    suspend fun move(taskId: String, status: String): String? = onceText("move:$taskId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        val previous = _state.value.board.card(taskId)
        previous?.let { place(it.copy(status = status)) }
        when (val result = api.updateTask(scope.url, taskId, KanbanTaskPatch(status = status), _state.value.slug)) {
            is ApiResult.Success -> {
                place(result.value)
                null
            }
            else -> {
                previous?.let(::place)
                result.errorMessage
            }
        }
    }

    /** `POST /tasks/{id}/reassign`; a running card answers 409 until [reclaimFirst] releases the claim. */
    suspend fun assign(taskId: String, profile: String?, reclaimFirst: Boolean = false): String? =
        onceText("assign:$taskId") {
            val scope = scope ?: return@onceText NOT_CONNECTED
            val previous = _state.value.board.card(taskId)
            previous?.let { place(it.copy(assignee = profile)) }
            when (val result = api.reassign(scope.url, taskId, profile, reclaimFirst, _state.value.slug)) {
                is ApiResult.Success -> null
                else -> {
                    previous?.let(::place)
                    result.errorMessage
                }
            }
        }

    /** `POST /tasks/{id}/reclaim` — release a dead worker's claim now; 409 means there was nothing to reclaim. */
    suspend fun reclaim(taskId: String, reason: String? = null): String? = onceText("reclaim:$taskId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        when (val result = api.reclaim(scope.url, taskId, reason, _state.value.slug)) {
            is ApiResult.Success -> {
                refresh()
                null
            }
            else -> result.errorMessage
        }
    }

    /** `POST /dispatch` — "run it now" instead of waiting out the dispatcher's tick. */
    suspend fun dispatch(): String? = onceText(DISPATCH) {
        val scope = scope ?: return@onceText NOT_CONNECTED
        when (val result = api.dispatch(scope.url, board = _state.value.slug)) {
            is ApiResult.Success -> null
            else -> result.errorMessage
        }
    }

    /** `PATCH {status: done}` — a human override; the screen confirms first when the card is somebody else's. */
    suspend fun complete(taskId: String, summary: String? = null): String? = onceText("complete:$taskId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        val previous = _state.value.board.card(taskId)
        previous?.let { place(it.copy(status = "done")) }
        when (val result = api.complete(scope.url, taskId, summary, board = _state.value.slug)) {
            is ApiResult.Success -> {
                place(result.value)
                null
            }
            else -> {
                previous?.let(::place)
                result.errorMessage
            }
        }
    }

    /**
     * `PATCH {status: blocked, block_reason}`.
     *
     * shortcut: the plugin's PATCH carries no `block_kind`, so the kind stays whatever an agent last set;
     * offer it in the block dialog once the dashboard's UpdateTaskBody accepts the field.
     */
    suspend fun block(taskId: String, reason: String? = null): String? = onceText("block:$taskId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        val previous = _state.value.board.card(taskId)
        previous?.let { place(it.copy(status = "blocked")) }
        when (val result = api.block(scope.url, taskId, reason, _state.value.slug)) {
            is ApiResult.Success -> {
                place(result.value)
                null
            }
            else -> {
                previous?.let(::place)
                result.errorMessage
            }
        }
    }

    /** `PATCH {status: ready}` — the server's own unblock path; 409 while a parent is still open. */
    suspend fun unblock(taskId: String): String? = onceText("unblock:$taskId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        val previous = _state.value.board.card(taskId)
        previous?.let { place(it.copy(status = "ready")) }
        when (val result = api.unblock(scope.url, taskId, _state.value.slug)) {
            is ApiResult.Success -> {
                place(result.value)
                null
            }
            else -> {
                previous?.let(::place)
                result.errorMessage
            }
        }
    }

    /** `POST /runs/{id}/terminate` — kills the worker and re-queues the card; 409 when the run already ended. */
    suspend fun terminate(runId: Long, reason: String? = null): String? = onceText("terminate:$runId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        when (val result = api.terminateRun(scope.url, runId, reason, _state.value.slug)) {
            is ApiResult.Success -> {
                refresh()
                null
            }
            else -> result.errorMessage
        }
    }

    /** `POST /links`; a cycle, a self-link or a foreign task is refused by the server, whose text is the reply. */
    suspend fun addLink(parentId: String, childId: String): String? = onceText("link:$parentId:$childId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        val parent = _state.value.board.card(parentId)
        val child = _state.value.board.card(childId)
        parent?.let { place(it.copy(linkCounts = it.linkCounts.copy(children = it.linkCounts.children + 1))) }
        child?.let { place(it.copy(linkCounts = it.linkCounts.copy(parents = it.linkCounts.parents + 1))) }
        when (val result = api.addLink(scope.url, parentId, childId, _state.value.slug)) {
            is ApiResult.Success -> null
            else -> {
                parent?.let(::place)
                child?.let(::place)
                result.errorMessage
            }
        }
    }

    /**
     * `DELETE /links` — query parameters here, unlike the POST.
     *
     * shortcut: an edge the server doesn't have answers `200 {ok:false}`, which reads as success; the counts
     * then stay one too low until the next refresh — fix once [KanbanApi.removeLink] returns the flag.
     */
    suspend fun removeLink(parentId: String, childId: String): String? = onceText("link:$parentId:$childId") {
        val scope = scope ?: return@onceText NOT_CONNECTED
        val parent = _state.value.board.card(parentId)
        val child = _state.value.board.card(childId)
        parent?.let {
            place(it.copy(linkCounts = it.linkCounts.copy(children = (it.linkCounts.children - 1).coerceAtLeast(0))))
        }
        child?.let {
            place(it.copy(linkCounts = it.linkCounts.copy(parents = (it.linkCounts.parents - 1).coerceAtLeast(0))))
        }
        when (val result = api.removeLink(scope.url, parentId, childId, _state.value.slug)) {
            is ApiResult.Success -> null
            else -> {
                parent?.let(::place)
                child?.let(::place)
                result.errorMessage
            }
        }
    }

    // --- Plumbing -----------------------------------------------------------------------------------

    private val inflight = mutableSetOf<String>()
    private val inflightLock = Mutex()

    /** Runs [block] unless a call under the same [key] is still open, in which case [duplicate] is the answer. */
    private suspend fun <T> once(key: String, duplicate: T, block: suspend () -> T): T {
        val accepted = inflightLock.withLock { inflight.add(key) }
        if (!accepted) return duplicate
        try {
            return block()
        } finally {
            inflightLock.withLock { inflight.remove(key) }
        }
    }

    /** [once] for the writes that answer only an error text (null = done); a duplicate also answers null. */
    private suspend fun onceText(key: String, block: suspend () -> String?): String? = once(key, null, block)

    /** Shows a server-confirmed card on the board; the lane follows its status. */
    private fun place(task: KanbanTask): KanbanTask {
        _state.update { it.copy(board = it.board.withTask(task.id, task)) }
        return task
    }

    private fun KanbanBoard.card(id: String): KanbanTask? =
        columns.asSequence().flatMap { it.tasks.asSequence() }.firstOrNull { it.id == id }

    /** Puts [task] in the lane its status names, or drops it from every lane when null; also the dedupe. */
    private fun KanbanBoard.withTask(taskId: String, task: KanbanTask?): KanbanBoard =
        copy(columns = columns.map { column ->
            val rest = column.tasks.filterNot { it.id == taskId }
            if (task != null && column.name == task.status) column.copy(tasks = rest + task) else column.copy(tasks = rest)
        })

    /** Drops a repeated card id inside a lane: a gateway that repeats one would crash the list that keys by id. */
    private fun KanbanBoard.deduped(): KanbanBoard =
        copy(columns = columns.map { it.copy(tasks = it.tasks.distinctBy { task -> task.id }) })

    private companion object {
        const val CREATE = "create"
        const val DISPATCH = "dispatch"
        const val ALREADY_SENDING = "That is still being sent."
        const val NOT_CONNECTED = "Not connected to a gateway."
    }
}
