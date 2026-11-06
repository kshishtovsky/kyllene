package dev.hermeskotlin.ui.kanban

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.hermeskotlin.core.kanban.KanbanDeepLink

/**
 * The board the app shows over the chat: [board]'s slug — the profile's remembered one when null — at [task]'s card.
 * Saved with the activity, so a rotation brings the board and its sheet back as they were.
 */
class KanbanRouteState {
    var open by mutableStateOf(false)
        private set
    var board by mutableStateOf<String?>(null)
        private set
    var task by mutableStateOf<String?>(null)
        private set

    /** The tab, or [link]'s board at its card. */
    fun show(link: KanbanDeepLink? = null) {
        board = link?.board
        task = link?.taskId
        open = true
    }

    fun openTask(id: String) {
        task = id
    }

    /** Back or Esc: the card's sheet first, then the board. Whether it closed something. */
    fun back(): Boolean {
        when {
            task != null -> task = null
            open -> close()
            else -> return false
        }
        return true
    }

    /** The board goes as a whole, its sheet with it. */
    fun close() {
        open = false
        board = null
        task = null
    }

    companion object {
        val Saver: Saver<KanbanRouteState, Any> = listSaver(
            save = { listOf(it.open, it.board, it.task) },
            restore = { saved ->
                KanbanRouteState().also {
                    it.open = saved[0] as Boolean
                    it.board = saved[1] as String?
                    it.task = saved[2] as String?
                }
            },
        )
    }
}
