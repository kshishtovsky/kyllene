package dev.hermeskotlin.core.kanban

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A board to open from outside the screens: [board]'s slug, at [taskId]'s card. */
data class KanbanDeepLink(val board: String, val taskId: String)

/**
 * A `herald://kanban/<board>/<taskId>` link, waiting for the screens to take it: a notification of a finished card
 * taps into the board it ran on, without the app having to guess which board that was.
 */
class KanbanDeepLinks {
    private val _pending = MutableStateFlow<KanbanDeepLink?>(null)
    val pending: StateFlow<KanbanDeepLink?> = _pending.asStateFlow()

    fun open(board: String, taskId: String) {
        _pending.value = KanbanDeepLink(board, taskId)
    }

    /** Takes [link] once it's opened, unless a newer one came meanwhile. */
    fun consume(link: KanbanDeepLink) = _pending.update { if (it == link) null else it }
}
