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
        val columns = columns
        val task = columns.asSequence().flatMap { it.tasks.asSequence() }.firstOrNull { it.id == event.taskId }
            ?: return null
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
        val moved = columns.map { column ->
            // Dropping the card from every lane is also the dedupe: it lands in exactly one.
            val rest = column.tasks.filterNot { it.id == event.taskId }
            if (next != null && column.name == next.status) column.copy(tasks = rest + next) else column.copy(tasks = rest)
        }
        // shortcut: the moved card lands at the end of its lane, not in the server's dispatch order — the
        // next full refresh restores it; sort here once a lane's order starts to matter to the UI.
        return copy(columns = moved, latestEventId = maxOf(latestEventId, event.id))
    }

    /** Drops a repeated card id inside a lane: a gateway that repeats one would crash the list that keys by id. */
    private fun KanbanBoard.deduped(): KanbanBoard =
        copy(columns = columns.map { it.copy(tasks = it.tasks.distinctBy { task -> task.id }) })
}
