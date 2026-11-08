package dev.hermeskotlin.ui.split

import androidx.compose.runtime.saveable.Saver
import kotlin.math.roundToInt

/** Four panes is the most a phone screen reads; the fifth split is refused. */
const val MAX_PANES = 4

/** A branch never gives a part less than this share of its own space. */
const val MIN_FRACTION = 0.15f
const val MAX_FRACTION = 0.85f

/** Phone minimums; the desktop passes 320×240 instead. */
const val MIN_PANE_WIDTH_DP = 240f
const val MIN_PANE_HEIGHT_DP = 200f

enum class SplitAxis { Row, Column }

sealed interface SplitNode {
    data class Pane(val id: String, val chat: PaneChat) : SplitNode

    data class Branch(
        val id: String,
        val axis: SplitAxis,
        /** Share of the first part. */
        val fraction: Float,
        val first: SplitNode,
        val second: SplitNode,
    ) : SplitNode
}

/** What a pane shows: a gateway chat, or nothing yet. */
data class PaneChat(val storedSessionId: String?, val title: String?, val profile: String?)

private val EmptyChat = PaneChat(null, null, null)

/**
 * The screen's layout as a tree of splits. Every operation returns a new tree and leaves this one alone.
 * A branch always has two children; a layout always has at least one pane.
 */
data class SplitLayout private constructor(
    val root: SplitNode,
    val focusedPaneId: String,
) {
    /** Panes left to right, top to bottom. */
    val leaves: List<SplitNode.Pane> = buildList { collectPanes(root, this) }

    val count: Int get() = leaves.size

    /** The same id, under the name the screen reads. */
    val focused: String? get() = focusedPaneId

    /**
     * Splits [paneId] in two, the new empty pane taking the focus: second, or first with [newPaneFirst]. Null when
     * the tree is full, when [paneId] is not there, or when [paneDp] (what the pane currently measures along [axis])
     * cannot give both halves [minPaneDp].
     */
    fun split(
        paneId: String,
        axis: SplitAxis,
        newPaneId: String,
        paneDp: Float? = null,
        minPaneDp: Float = MIN_PANE_WIDTH_DP,
        newPaneFirst: Boolean = false,
    ): SplitLayout? {
        val pane = findPane(root, paneId) ?: return null
        if (count >= MAX_PANES) return null
        if (paneDp != null && paneDp < 2 * minPaneDp) return null
        val added = SplitNode.Pane(newPaneId, PaneChat(null, null, pane.chat.profile))
        val branch = SplitNode.Branch(
            id = "b:$newPaneId",
            axis = axis,
            fraction = 0.5f,
            first = if (newPaneFirst) added else pane,
            second = if (newPaneFirst) pane else added,
        )
        return copy(root = replacePane(root, paneId) { branch }, focusedPaneId = newPaneId)
    }

    /** The last pane cannot be closed: the screen never goes empty. The freed space goes to the sibling. */
    fun close(paneId: String): SplitLayout {
        if (count <= 1) return this
        val newRoot = removePane(root, paneId) ?: return this
        if (focusedPaneId != paneId && leaves.any { it.id == focusedPaneId }) return copy(root = newRoot)
        val at = leaves.indexOfFirst { it.id == paneId }.coerceAtLeast(0)
        val left = leaves.filter { it.id != paneId }
        return copy(root = newRoot, focusedPaneId = left[minOf(at, left.size - 1)].id)
    }

    fun replace(paneId: String, chat: PaneChat): SplitLayout =
        copy(root = replacePane(root, paneId) { it.copy(chat = chat) })

    fun focus(paneId: String): SplitLayout =
        if (findPane(root, paneId) == null) this else copy(focusedPaneId = paneId)

    /**
     * Sets a branch's share. Always inside [MIN_FRACTION]..[MAX_FRACTION]; when [containerDp] (the branch's size along
     * its axis) is known, both parts also keep at least [minPaneDp].
     */
    fun resize(
        branchId: String,
        fraction: Float,
        containerDp: Float? = null,
        minPaneDp: Float = MIN_PANE_WIDTH_DP,
    ): SplitLayout = copy(root = mapBranch(root, branchId) { clampFraction(fraction, containerDp, minPaneDp) })

    companion object {
        /** A layout of one pane, which is where the screen starts. */
        fun single(paneId: String, chat: PaneChat = EmptyChat): SplitLayout =
            SplitLayout(SplitNode.Pane(paneId, chat), paneId)

        /** Survives a rotation and a process death; garbage restores to null. */
        val Saver: Saver<SplitLayout, String> = Saver(save = { it.encode() }, restore = { decode(it) })

        private fun decode(text: String): SplitLayout? {
            val bar = text.indexOf('|')
            if (bar <= 0) return null
            val focus = unescape(text.substring(0, bar)) ?: return null
            val reader = NodeReader(text, bar + 1)
            val root = reader.node() ?: return null
            if (reader.at != text.length) return null
            if (countPanes(root) > MAX_PANES) return null
            return if (findPane(root, focus) != null) SplitLayout(root, focus)
            else SplitLayout(root, firstPane(root)!!.id)
        }
    }
}

private fun countPanes(node: SplitNode): Int = when (node) {
    is SplitNode.Pane -> 1
    is SplitNode.Branch -> countPanes(node.first) + countPanes(node.second)
}

private fun firstPane(node: SplitNode): SplitNode.Pane? = when (node) {
    is SplitNode.Pane -> node
    is SplitNode.Branch -> firstPane(node.first)
}

private fun collectPanes(node: SplitNode, into: MutableList<SplitNode.Pane>) {
    when (node) {
        is SplitNode.Pane -> into += node
        is SplitNode.Branch -> {
            collectPanes(node.first, into)
            collectPanes(node.second, into)
        }
    }
}

private fun findPane(node: SplitNode, paneId: String): SplitNode.Pane? = when (node) {
    is SplitNode.Pane -> node.takeIf { it.id == paneId }
    is SplitNode.Branch -> findPane(node.first, paneId) ?: findPane(node.second, paneId)
}

private fun replacePane(node: SplitNode, paneId: String, make: (SplitNode.Pane) -> SplitNode): SplitNode =
    when (node) {
        is SplitNode.Pane -> if (node.id == paneId) make(node) else node
        is SplitNode.Branch -> node.copy(
            first = replacePane(node.first, paneId, make),
            second = replacePane(node.second, paneId, make),
        )
    }

/** Null when this subtree held only the closed pane: its parent then takes over the sibling. */
private fun removePane(node: SplitNode, paneId: String): SplitNode? = when (node) {
    is SplitNode.Pane -> if (node.id == paneId) null else node
    is SplitNode.Branch -> {
        val first = removePane(node.first, paneId)
        val second = removePane(node.second, paneId)
        when {
            first == null -> second
            second == null -> first
            else -> node.copy(first = first, second = second)
        }
    }
}

private fun mapBranch(node: SplitNode, branchId: String, fraction: (Float) -> Float): SplitNode = when (node) {
    is SplitNode.Pane -> node
    is SplitNode.Branch -> node.copy(
        fraction = if (node.id == branchId) fraction(node.fraction) else node.fraction,
        first = mapBranch(node.first, branchId, fraction),
        second = mapBranch(node.second, branchId, fraction),
    )
}

/** Every fraction is stored rounded, so what the saver prints is what comes back. */
private fun round3(fraction: Float): Float = (fraction * 1000).roundToInt() / 1000f

private fun clampFraction(fraction: Float, containerDp: Float?, minPaneDp: Float): Float {
    if (containerDp == null || containerDp <= 0f) return round3(fraction.coerceIn(MIN_FRACTION, MAX_FRACTION))
    val min = (minPaneDp / containerDp).coerceIn(0f, 0.5f)
    val low = maxOf(MIN_FRACTION, min)
    val high = minOf(MAX_FRACTION, 1f - min)
    return round3(if (low > high) 0.5f else fraction.coerceIn(low, high))
}

/** `focus|<tree>`, e.g. `p2|R0.5(P:p1/dev/abc/,C0.7(P:p2///,P:p3/onru/def/))`. */
private fun SplitLayout.encode(): String = buildString {
    append(escape(focusedPaneId)).append('|')
    appendNode(root)
}

private fun StringBuilder.appendNode(node: SplitNode) {
    when (node) {
        is SplitNode.Pane -> {
            append("P:")
            append(escape(node.id)).append('/')
            append(escape(node.chat.profile ?: "")).append('/')
            append(escape(node.chat.storedSessionId ?: "")).append('/')
            append(escape(node.chat.title ?: ""))
        }

        is SplitNode.Branch -> {
            append(if (node.axis == SplitAxis.Row) "R" else "C")
            append(escape(node.id)).append(':')
            append(formatFraction(node.fraction))
            append('(')
            appendNode(node.first)
            append(',')
            appendNode(node.second)
            append(')')
        }
    }
}

/** Every share is inside 0.15..0.85, so it always has a fractional part. */
private fun formatFraction(fraction: Float): String =
    "0." + ((fraction * 1000).roundToInt()).toString().padStart(3, '0').trimEnd('0')

private const val Reserved = "%/,()|:"

private fun escape(text: String): String = buildString {
    for (char in text) {
        if (char in Reserved) append('%').append(char.code.toString(16).padStart(2, '0')) else append(char)
    }
}

private fun unescape(text: String): String? = buildString {
    var i = 0
    while (i < text.length) {
        if (text[i] != '%') {
            append(text[i++])
            continue
        }
        if (i + 2 >= text.length) return null
        val code = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
        append(code.toChar())
        i += 3
    }
}

private class NodeReader(private val text: String, var at: Int) {

    fun node(): SplitNode? = when (text.getOrNull(at)) {
        'P' -> pane()
        'R', 'C' -> branch()
        else -> null
    }

    private fun pane(): SplitNode? {
        at++ // 'P'
        if (text.getOrNull(at) != ':') return null
        at++
        val start = at
        while (at < text.length && text[at] != ',' && text[at] != ')') at++
        val fields = text.substring(start, at).split('/')
        if (fields.size != 4) return null
        val id = unescape(fields[0]) ?: return null
        val profile = unescape(fields[1]) ?: return null
        val session = unescape(fields[2]) ?: return null
        val title = unescape(fields[3]) ?: return null
        if (id.isEmpty()) return null
        return SplitNode.Pane(
            id,
            PaneChat(session.ifEmpty { null }, title.ifEmpty { null }, profile.ifEmpty { null }),
        )
    }

    private fun branch(): SplitNode? {
        val axis = if (text[at] == 'R') SplitAxis.Row else SplitAxis.Column
        at++
        val idStart = at
        while (at < text.length && text[at] != ':') at++
        if (at >= text.length) return null
        val id = unescape(text.substring(idStart, at)) ?: return null
        if (id.isEmpty()) return null
        at++
        val fractionStart = at
        while (at < text.length && text[at] != '(') at++
        val fraction = text.substring(fractionStart, at).toFloatOrNull() ?: return null
        at++ // '('
        val first = node() ?: return null
        if (text.getOrNull(at) != ',') return null
        at++
        val second = node() ?: return null
        if (text.getOrNull(at) != ')') return null
        at++
        return SplitNode.Branch(id, axis, round3(fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)), first, second)
    }
}
