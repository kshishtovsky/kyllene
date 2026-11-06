package dev.hermeskotlin.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanBoard
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.kanban.KanbanState
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.kanbanStatusLabel
import dev.hermeskotlin.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The lanes the screen draws, in the dashboard's order. Empty lanes stay visible: a board that hides them jumps. */
val kanbanLanes: List<Pair<String, String>> = listOf(
    "triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done",
).map { it to kanbanStatusLabel(it) }

/** A lane's cards; a lane the reply left out is empty rather than missing. */
fun laneTasks(board: KanbanBoard, status: String): List<KanbanTask> =
    board.columns.firstOrNull { it.name == status }?.tasks.orEmpty()

/** "coder" → "CO", "review agent" → "RA". Two letters, so a narrow card keeps room for its title. */
fun initials(name: String?): String {
    val words = name.orEmpty().split(' ', '\t', '-', '_', '.').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> words.take(2).map { it.first().uppercaseChar() }.joinToString("")
    }
}

/** `needs_input` → "needs input": the reason a card is blocked, in the words the dashboard uses. */
fun blockKindLabel(kind: String?): String? = kind?.replace('_', ' ')?.takeIf { it.isNotBlank() }

/** A running card's clock: whole seconds since this run started, as "12s", "4m 07s" or "1h 02m". */
fun formatRunClock(seconds: Long): String {
    val twoDigits = { value: Long -> value.toString().padStart(2, '0') }
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${twoDigits(seconds % 60)}s"
        else -> "${seconds / 3600}h ${twoDigits(seconds % 3600 / 60)}m"
    }
}

/**
 * The board screen's state: the repository's cache and live updates, plus the board's display name.
 * Socket events arrive elsewhere; this only binds the scope and reads.
 */
class KanbanBoardViewModel(
    private val kanban: KanbanRepository,
    private val api: KanbanApi,
) : ViewModel() {

    val state: StateFlow<KanbanState> = kanban.state

    private val _boardName = MutableStateFlow<String?>(null)

    /** `/boards` carries the name; the slug stands in until it's read. */
    val boardName: StateFlow<String?> = _boardName.asStateFlow()

    private var bound: Pair<String, String?>? = null

    fun bind(gateway: SavedGateway, profile: String?) {
        val key = gateway.url to profile
        if (bound == key) return
        bound = key
        _boardName.value = null
        kanban.bind(gateway.gatewayUrl, profile)
        refresh()
    }

    fun refresh() {
        val key = bound ?: return
        viewModelScope.launch {
            kanban.refresh()
            // The board may have been switched meanwhile: name whichever slug is on screen now.
            readName(GatewayUrl.parse(key.first), kanban.state.value.slug)
        }
    }

    private suspend fun readName(url: GatewayUrl, slug: String?) {
        val boards = (api.boards(url) as? ApiResult.Success)?.value ?: return
        val board = boards.boards.firstOrNull { it.slug == slug }
            ?: boards.boards.firstOrNull { it.slug == boards.current }
            ?: return
        _boardName.value = board.name?.takeIf { it.isNotBlank() } ?: board.slug
    }
}
