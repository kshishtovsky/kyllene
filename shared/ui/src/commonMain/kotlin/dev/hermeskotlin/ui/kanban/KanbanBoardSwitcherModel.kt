package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the switcher draws: every board on the gateway, the one the app shows, and the call in flight. */
data class KanbanBoardsState(
    val boards: List<KanbanBoardMeta> = emptyList(),
    /** The board the app shows; null before the first read, or when the gateway has none. */
    val current: String? = null,
    val loading: Boolean = false,
    /** Why the last call failed, shown in the dialog until the next one succeeds. */
    val error: String? = null,
    /** A board whose delete waits for a confirmation; nothing is sent to the gateway while it's set. */
    val deleting: KanbanBoardMeta? = null,
    /** A create, rename or delete is on its way. */
    val saving: Boolean = false,
)

/** The gateway's own slug rule: 1–64 chars, lowercase letters, digits, `-`, `_`, not starting with `-` or `_`. */
internal fun isBoardSlug(slug: String): Boolean = BOARD_SLUG.matches(slug)

private val BOARD_SLUG = Regex("[a-z0-9][a-z0-9_-]{0,63}")

/**
 * The boards of one (gateway, profile) behind [KanbanBoardSwitcher]. A pick drops the old board's cards at once
 * ([KanbanRepository.selectBoard]) and restarts the live stream on the new slug: the server pins a socket to its
 * board at the handshake, and two boards' event cursors are not comparable (docs/kanban-api.md §4). The choice is
 * remembered per gateway and profile in [store], as the app remembers the last chat.
 */
class KanbanBoardSwitcherModel(
    private val api: KanbanApi,
    private val repository: KanbanRepository,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    /** Restarts the stream on [slug]: the old socket is closed, the new one opens at that board's own cursor. */
    private val reopenEvents: suspend (slug: String) -> Unit = {},
) {

    private val _state = MutableStateFlow(KanbanBoardsState())
    val state: StateFlow<KanbanBoardsState> = _state.asStateFlow()

    private var gateway: GatewayUrl? = null
    private var profile: String? = null

    /** The switch still running; a newer pick cancels it, so two boards never race for the cache. */
    private var switching: Job? = null

    /**
     * Reads the boards of [gateway] and [profile] and opens the one the user had last, else the gateway's own
     * current one, else the first. A remembered slug the gateway no longer has is ignored, not opened.
     */
    fun load(gateway: GatewayUrl, profile: String?) {
        if (gateway != this.gateway || profile != this.profile) _state.value = KanbanBoardsState()
        this.gateway = gateway
        this.profile = profile
        repository.bind(gateway, profile)
        scope.launch { readBoards(select = true) }
    }

    /** Shows [slug]: the old board's cards are dropped and the live stream restarts on the new one. */
    fun select(slug: String) {
        if (slug == _state.value.current) return
        _state.update { it.copy(current = slug, error = null) }
        switch(slug, reload = true)
    }

    fun create(slug: String, name: String? = null) {
        val slug = slug.trim().lowercase()
        if (!isBoardSlug(slug)) {
            _state.update { it.copy(error = "A board slug is 1–64 lowercase letters, digits, - or _, and can't start with - or _.") }
            return
        }
        save(select = true) { api.createBoard(it, slug, name?.trim()?.ifEmpty { null }) }
    }

    fun rename(slug: String, name: String) {
        val name = name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(error = "A board needs a name.") }
            return
        }
        save { api.renameBoard(it, slug, name) }
    }

    /** Only asks: [confirmDelete] sends. The dialog warns how many tasks go with the board. */
    fun askDelete(board: KanbanBoardMeta) = _state.update { it.copy(deleting = board) }

    fun dismissDelete() = _state.update { it.copy(deleting = null) }

    /** Archives the board the dialog names — the API's default; `?delete=1` would erase it — and moves to another. */
    fun confirmDelete() {
        val board = _state.value.deleting ?: return
        val gateway = gateway ?: return
        _state.update { it.copy(deleting = null, saving = true, error = null) }
        scope.launch {
            when (val result = api.deleteBoard(gateway, board.slug)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(saving = false, boards = it.boards.filterNot { b -> b.slug == board.slug }) }
                    if (_state.value.current == board.slug) {
                        _state.update { it.copy(current = null) }
                        // shortcut: with no board left the old cache stays until the next load; the gateway refuses
                        // to delete `default`, so zero boards isn't a state the app can reach.
                        _state.value.boards.firstOrNull()?.let { select(it.slug) }
                    }
                    readBoards(select = false)
                }
                else -> _state.update { it.copy(saving = false, error = result.errorMessage ?: "Couldn't delete the board.") }
            }
        }
    }

    private fun save(select: Boolean = false, call: suspend (GatewayUrl) -> ApiResult<KanbanBoardMeta>) {
        val gateway = gateway ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            when (val result = call(gateway)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(saving = false) }
                    // A board the user just made is the one they came to work in.
                    if (select) {
                        _state.update { it.copy(current = result.value.slug) }
                        switch(result.value.slug)
                    }
                    readBoards(select = false)
                }
                else -> _state.update { it.copy(saving = false, error = result.errorMessage ?: "The gateway refused.") }
            }
        }
    }

    private suspend fun readBoards(select: Boolean) {
        val gateway = gateway ?: return
        _state.update { it.copy(loading = true, error = null) }
        when (val result = api.boards(gateway)) {
            is ApiResult.Success -> {
                val boards = result.value.boards
                var slug = _state.value.current
                if (select) {
                    slug = listOfNotNull(store.get(key(gateway, profile)), result.value.current)
                        .firstOrNull { remembered -> boards.any { it.slug == remembered } }
                        ?: boards.firstOrNull()?.slug
                }
                _state.update { it.copy(boards = boards, current = slug, loading = false) }
                if (select && slug != null && repository.state.value.slug != slug) switch(slug)
            }
            else -> _state.update { it.copy(loading = false, error = result.errorMessage ?: "Couldn't load the boards.") }
        }
    }

    /**
     * Moves the cache and the stream to [slug]. The stream goes first: an event for the old board must never be
     * patched into the new one — each board has its own task id space, so its ids match a stranger's cards.
     */
    private fun switch(slug: String, reload: Boolean = false) {
        val gateway = gateway ?: return
        switching?.cancel()
        switching = scope.launch {
            reopenEvents(slug)
            if (_state.value.current != slug) return@launch
            repository.selectBoard(slug)
            if (_state.value.current != slug) return@launch
            store.put(key(gateway, profile), slug)
            if (reload) readBoards(select = false)
        }
    }

    private fun key(gateway: GatewayUrl, profile: String?) =
        "kanban.board.v1.$gateway" + (profile?.let { "#$it" } ?: "")
}
