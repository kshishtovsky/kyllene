package dev.hermeskotlin.ui.split

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** A chat lifted out of the sidebar: what it is and where the pointer holding it is, in the window. */
class DraggedChat(val chatId: String, val title: String?, val position: Offset)

/** Which part of a pane the pointer is over: an edge splits the pane that way, the middle takes the pane's chat. */
enum class SplitDropZone { Center, Left, Right, Top, Bottom }

/** An edge of a pane is this share of it, measured from that edge in; the rest of the pane is its middle. */
const val DROP_EDGE_SHARE = 0.25f

/** The zone of a [width] by [height] pane at ([x], [y]): the nearest edge's quarter, else the middle; null off it. */
fun dropZoneAt(x: Float, y: Float, width: Float, height: Float): SplitDropZone? {
    if (width <= 0f || height <= 0f || x < 0f || y < 0f || x > width || y > height) return null
    val across = minOf(x, width - x) / width
    val down = minOf(y, height - y) / height
    val onSide = across < DROP_EDGE_SHARE
    val onEnd = down < DROP_EDGE_SHARE
    return when {
        // A corner belongs to the side it is nearer along, the left and the top on a tie.
        onSide && (!onEnd || across <= down) -> if (x < width / 2) SplitDropZone.Left else SplitDropZone.Right
        onEnd -> if (y < height / 2) SplitDropZone.Top else SplitDropZone.Bottom
        else -> SplitDropZone.Center
    }
}

/** The way a zone splits a pane: a side splits it across, an end along it; the middle doesn't split it at all. */
internal fun SplitDropZone.axis(): SplitAxis? = when (this) {
    SplitDropZone.Left, SplitDropZone.Right -> SplitAxis.Row
    SplitDropZone.Top, SplitDropZone.Bottom -> SplitAxis.Column
    SplitDropZone.Center -> null
}

/** Left and top give the new pane the first half, the way the chat was dragged in. */
internal fun SplitDropZone.takesFirstHalf(): Boolean = this == SplitDropZone.Left || this == SplitDropZone.Top

/**
 * [layout] with [chat] dropped on [paneId] by [zone]: an edge puts it in a new pane on that side, the middle puts it
 * in the pane's place. A pane with no room to split takes the chat itself.
 */
internal fun SplitLayout.dropChat(
    paneId: String,
    chat: PaneChat,
    zone: SplitDropZone,
    newPaneId: String,
    paneDp: Float?,
    minPaneDp: Float,
): SplitLayout {
    val axis = zone.axis() ?: return replace(paneId, chat).focus(paneId)
    val split = split(paneId, axis, newPaneId, paneDp, minPaneDp, newPaneFirst = zone.takesFirstHalf())
        ?: return replace(paneId, chat).focus(paneId)
    return split.replace(newPaneId, chat)
}

/** A pane's side of the drag: its bounds in the window, and what a chat dropped inside them does. */
class ChatDropTarget(val bounds: () -> Rect, val onDrop: (DraggedChat, SplitDropZone) -> Unit)

/**
 * The app's one drag of a chat out of the sidebar, shared by the row that lifts it and the panes that can take it:
 * the row writes, the panes read, and the same row lets go where the pointer is.
 */
object ChatDrag {
    /** The chat in the hand, null when nothing is dragged. */
    var chat by mutableStateOf<DraggedChat?>(null)
        private set

    /** A chat the sidebar asked to open beside the focused pane: the split screen takes it once, and clears it. */
    var beside by mutableStateOf<PaneChat?>(null)
        private set

    /**
     * Whether the focused pane has room to split. The split screen keeps it current; the sidebar leaves "Open beside"
     * out while it is off, rather than offering a click that would do nothing.
     */
    var besideAllowed by mutableStateOf(true)

    private val panes = mutableMapOf<String, ChatDropTarget>()

    /** A row's long press: the chat comes out of the list, at no place until the pointer moves. */
    fun lift(chatId: String, title: String?) {
        chat = DraggedChat(chatId, title, Offset.Unspecified)
    }

    fun moveTo(position: Offset) {
        val held = chat ?: return
        chat = DraggedChat(held.chatId, held.title, position)
    }

    fun cancel() {
        chat = null
    }

    /** The pointer let go: the pane under it takes the chat, or nothing happens. */
    fun drop() {
        val held = chat ?: return
        chat = null
        val (pane, zone) = panes.values.firstNotNullOfOrNull { pane -> zoneIn(pane, held.position)?.let { pane to it } }
            ?: return
        pane.onDrop(held, zone)
    }

    /** The zone of [paneId] the chat in the hand is over: what that pane tints while it is held. */
    fun zoneOver(paneId: String): SplitDropZone? {
        val at = chat?.position ?: return null
        return panes[paneId]?.let { zoneIn(it, at) }
    }

    fun hold(paneId: String, target: ChatDropTarget) {
        panes[paneId] = target
    }

    fun forget(paneId: String) {
        panes.remove(paneId)
    }

    /** Opens [chat] beside the focused pane; a phone's way to split without dragging. */
    fun openBeside(chat: PaneChat) {
        beside = chat
    }

    fun takeBeside(): PaneChat? = beside.also { beside = null }

    private fun zoneIn(pane: ChatDropTarget, position: Offset): SplitDropZone? {
        val bounds = pane.bounds()
        if (!bounds.contains(position)) return null
        return dropZoneAt(position.x - bounds.left, position.y - bounds.top, bounds.width, bounds.height)
    }
}
