package dev.hermeskotlin.core.kanban

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the board on screen current: the event socket patches cards as rows land, and while the socket is
 * down (or silent for 30 s) the board is read every 8 s and the open card every 4 s, as the desktop
 * dashboard does. The socket is an accelerator, never the source of truth.
 */
class KanbanLive(
    private val repository: KanbanRepository,
    private val socket: KanbanEventsSocket,
    private val scope: CoroutineScope,
    /** One tick of the fallback poller; two of them are the board's 8 s read. */
    private val pollMs: Long = 4_000,
) {
    private var job: Job? = null

    /** Reconnects since the app opened; the board's "no connection" banner reads the copy in the state. */
    private var reconnects = 0

    /** The card the task sheet is showing: reloaded by its own events and, socket down, every 4 s. */
    private var open: OpenTask? = null

    /** Set when a frame lands: its rows are patched in place, but only a filled board can show `claimed`, `created`… */
    private val dirty = MutableStateFlow(false)

    private class OpenTask(val id: String, val reload: suspend (String) -> Unit)

    /** Starts the live tail; call when the board's screen comes up and the app is in front. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { watchSocket() }
            launch { watchDetails() }
            launch { refreshOnFrames() }
            launch { pollFallback() }
        }
    }

    /** Stops it (Android `ON_STOP`, the tab is left): a socket in the background only burns the battery. */
    fun stop() {
        job?.cancel()
        job = null
        repository.setLive(alive = false, reconnects = reconnects)
    }

    /** The sheet shows [id] from now on; [reload] refetches it, and is kept for as long as it is open. */
    fun openTask(id: String, reload: suspend (String) -> Unit) {
        open = OpenTask(id, reload)
    }

    fun closeTask() {
        open = null
    }

    /** Follows the board the repository has selected; another board closes this socket and opens its own. */
    private suspend fun watchSocket() {
        repository.state.map { it.slug }.distinctUntilChanged().collectLatest { slug ->
            val url = repository.gateway ?: return@collectLatest
            if (slug == null) return@collectLatest
            // The board's tail as read from REST: the first frame then carries only what happened since.
            val tail = repository.state.value.board.latestEventId
            socket.events(url, slug, tail).collect { event ->
                when (event) {
                    is KanbanSocketEvent.Frame -> {
                        repository.applyEvents(event.frame.events)
                        dirty.value = true
                        repository.setLive(alive = true, reconnects = reconnects)
                    }
                    is KanbanSocketEvent.Retrying -> {
                        reconnects++
                        repository.setLive(alive = false, reconnects = reconnects)
                    }
                }
            }
        }
    }

    private suspend fun watchDetails() {
        repository.invalidated.collect { id ->
            val task = open ?: return@collect
            if (task.id == id) reload(task)
        }
    }

    /**
     * A frame's rows are patched in place, but the interesting lane changes don't all say where the card went
     * (`claimed` is `{lock, expires, run_id}`), so the board is reread once per burst instead of guessing.
     */
    private suspend fun refreshOnFrames() {
        dirty.collect { wanted ->
            if (!wanted) return@collect
            dirty.value = false
            if (repository.state.value.slug != null) refreshBoard()
        }
    }

    private suspend fun pollFallback() {
        var ticks = 0
        while (true) {
            delay(pollMs)
            // isSilent() with no argument keeps the socket's own clock: the poller and the socket must
            // agree on "now", or a test driving virtual time can never see the 30 s silence.
            if (!socket.isSilent()) continue
            open?.let { reload(it) }
            if (++ticks % 2 == 0 && repository.state.value.slug != null) refreshBoard()
        }
    }

    /** A refused read must not end the loop that keeps the board fresh. */
    private suspend fun refreshBoard() {
        try {
            repository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // refresh() puts its own failure in the state; the next frame or tick tries again.
        }
    }

    /** A reload the sheet wrote may fail; a throw escaping here would end every later update. */
    private suspend fun reload(task: OpenTask) {
        try {
            task.reload(task.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The sheet shows its own failure; the next tick tries again.
        }
    }
}
