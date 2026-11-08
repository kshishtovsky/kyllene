package dev.hermeskotlin.ui.split

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler
import dev.hermeskotlin.ui.chat.ChatTarget
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.chat.ProvideReplySpeakerFor
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.sessions.SessionsUiState
import dev.hermeskotlin.ui.sessions.SessionsViewModel
import kotlin.math.roundToInt
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/** The pane showing the app's open chat; the panes beside it hold chats of their own. */
const val MAIN_PANE = "main"

/** A desktop pane keeps this room along its axis, as [MIN_PANE_WIDTH_DP] does on a phone. */
private const val DESKTOP_MIN_PANE_WIDTH_DP = 320f
private const val DESKTOP_MIN_PANE_HEIGHT_DP = 240f

/** The split button in a chat's top bar: a click adds a pane beside that one; off when there is no room. */
class SplitAction(val enabled: Boolean, val onClick: () -> Unit)

/** What a pane can do beside its own chat: split it, or start another chat in it. */
class PaneActions(val split: SplitAction, val newChat: () -> Unit)

/** Whether the screen can split again: panes left, and room for two halves of [paneDp] along the axis. */
internal fun canSplit(count: Int, paneDp: Float?, minPaneDp: Float): Boolean =
    count < MAX_PANES && paneDp != null && paneDp >= 2 * minPaneDp

/**
 * The chat screen as [SplitContainer] lays its panes out: [target] and [mainChat] in the [MAIN_PANE] pane (the app's
 * open chat, as the screen always showed it), the other panes' chats as [PaneChatHost] runs them.
 *
 * [pane] draws one pane's chat, which the caller does with its own [ChatScreen]; the split screen decides only where
 * the panes go, which one has the focus, and what an empty one offers.
 */
@Composable
fun SplitScreen(
    target: ChatTarget,
    mainChat: ChatViewModel,
    sessions: SessionsViewModel,
    modifier: Modifier = Modifier,
    pane: @Composable (target: ChatTarget, chat: ChatViewModel, main: Boolean, keysEnabled: Boolean, actions: PaneActions) -> Unit,
) {
    val gateway = target.gateway
    // Saved with the activity, so a rotation and a process death keep the panes as they were.
    var layout by rememberSaveable(stateSaver = SplitLayout.Saver) {
        mutableStateOf(SplitLayout.single(MAIN_PANE, target.toPaneChat()))
    }
    var nextPane by rememberSaveable { mutableStateOf(1) }
    // A pane told "New chat" holds a chat with no id either, as a pane waiting for one does: this tells them apart.
    var blankPanes by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // The app opened another chat (the sidebar, a notification, a link): it shows in the focused pane, and the
    // layout stays as it is rather than going back to one pane.
    LaunchedEffect(target) {
        val chat = target.toPaneChat()
        layout = layout.replace(MAIN_PANE, chat)
        val focusedId = layout.focused
        if (focusedId != null && focusedId != MAIN_PANE) {
            layout = layout.replace(focusedId, chat)
            blankPanes = blankPanes - focusedId
        }
    }

    // A phone splits top from bottom, a desktop side by side: the axis the panes are least cramped on.
    val axis = if (isDesktop) SplitAxis.Row else SplitAxis.Column
    fun minPaneFor(along: SplitAxis) = if (along == SplitAxis.Row) DESKTOP_MIN_PANE_WIDTH_DP else DESKTOP_MIN_PANE_HEIGHT_DP
    val minPane = minPaneFor(axis)
    // Each pane as it last measured: what a split of it has to fit in, and where it is for a chat dragged over it
    // from the sidebar.
    val sizes = remember { mutableMapOf<String, DpSize>() }
    val bounds = remember { mutableMapOf<String, Rect>() }
    val koin = getKoin()
    val mainHost = koinInject<ChatHost>()

    fun sizeAlong(paneId: String, along: SplitAxis): Float? =
        sizes[paneId]?.let { (if (along == SplitAxis.Row) it.width else it.height).value }

    /** A pane makes room for a chat dropped on it: an edge gives it half, the middle puts it in the pane's place. */
    fun dropOn(paneId: String, held: DraggedChat, zone: SplitDropZone) {
        val along = zone.axis() ?: axis
        val newId = "pane$nextPane"
        layout = layout.dropChat(paneId, PaneChat(held.chatId, held.title, target.profile), zone, newId, sizeAlong(paneId, along), minPaneFor(along))
        if (layout.leaves.any { it.id == newId }) {
            nextPane++
            blankPanes = blankPanes - newId
        }
    }

    /** The sidebar's "Open beside": the focused pane splits, and the chat goes in the new half. */
    fun openBeside(chat: PaneChat) {
        val paneId = layout.focused ?: return
        val newId = "pane$nextPane"
        val split = layout.split(paneId, axis, newId, sizeAlong(paneId, axis), minPane) ?: return
        nextPane++
        blankPanes = blankPanes - newId
        layout = split.replace(newId, chat)
    }

    fun splitPane(paneId: String) {
        val split = layout.split(paneId, axis, "pane$nextPane", sizeAlong(paneId, axis), minPane) ?: return
        nextPane++
        layout = split
    }

    // A chat the sidebar asked to open beside the focused pane: a phone's way to split without dragging one in.
    val beside = ChatDrag.beside
    LaunchedEffect(beside) {
        if (beside == null) return@LaunchedEffect
        ChatDrag.takeBeside()?.let { openBeside(it) }
    }

    ShortcutHandler { shortcut ->
        val focusedId = layout.focused
        if (shortcut != Shortcut.SplitScreen || focusedId == null) return@ShortcutHandler false
        splitPane(focusedId)
        true
    }

    // The list an empty pane offers: read only while a pane waits for a chat, and from the view model the sidebar
    // already polls rather than a second read of the gateway.
    val awaiting = layout.leaves.any { it.awaitsChat(blankPanes) }
    val listed = if (awaiting) sessions.state.collectAsStateWithLifecycle().value else null
    val profile = sessions.binding.collectAsStateWithLifecycle().value?.profile

    SplitContainer(
        layout = layout,
        onResize = { branchId, fraction -> layout = layout.resize(branchId, fraction, minPaneDp = minPane) },
        onReset = { branchId -> layout = layout.resize(branchId, 0.5f, minPaneDp = minPane) },
        modifier = modifier,
    ) { node ->
        val focused = node.id == layout.focused
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { bounds[node.id] = it.boundsInRoot() }
                // A tap anywhere on the pane that its own controls didn't take makes it the focused one.
                .pointerInput(node.id) { detectTapGestures { layout = layout.focus(node.id) } },
        ) {
            val along = sizeAlong(node.id, axis)
            SideEffect { sizes[node.id] = DpSize(maxWidth, maxHeight) }
            DisposableEffect(node.id, target) {
                ChatDrag.hold(node.id, ChatDropTarget({ bounds[node.id] ?: Rect.Zero }) { held, zone -> dropOn(node.id, held, zone) })
                onDispose { ChatDrag.forget(node.id) }
            }
            Column(Modifier.fillMaxSize()) {
                if (layout.count > 1) {
                    PaneHeader(node.chat.title ?: "Chat", focused) { layout = layout.close(node.id) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val actions = PaneActions(
                        split = SplitAction(canSplit(layout.count, along, minPane)) { splitPane(node.id) },
                        newChat = { blankPanes = blankPanes + node.id },
                    )
                    when {
                        node.id == MAIN_PANE -> pane(target, mainChat, true, focused, actions)
                        node.awaitsChat(blankPanes) -> EmptyPane(
                            state = listed,
                            onPick = { session ->
                                layout = layout.replace(
                                    node.id,
                                    PaneChat(session.id, session.displayTitle, profile),
                                ).focus(node.id)
                            },
                            onNewChat = actions.newChat,
                        )

                        else -> {
                            val session = rememberPaneChatHost(node.id, mainHost, koin, gateway, node.chat)
                            val chatTarget = remember(gateway, node.chat) {
                                ChatTarget(gateway, node.chat.storedSessionId, node.chat.title, profile = node.chat.profile)
                            }
                            ProvideReplySpeakerFor(chatTarget) { pane(chatTarget, session.viewModel, false, focused, actions) }
                        }
                    }
                    // A chat dragged in from the sidebar: the part of this pane it would take, and the chat itself.
                    ChatDrag.zoneOver(node.id)?.let { zone -> DropHint(zone) }
                    ChatDrag.chat?.let { held -> DragGhost(held, bounds[node.id]) }
                }
            }
        }
    }
}

/** A pane waiting for a chat: the others' panes are named already, and "New chat" is not a choice of one. */
private fun SplitNode.Pane.awaitsChat(blank: List<String>): Boolean =
    id != MAIN_PANE && id !in blank && chat.storedSessionId == null && chat.title == null

/** The part of a pane a chat dropped on it would take: its middle, or the quarter along that edge. */
@Composable
private fun BoxScope.DropHint(zone: SplitDropZone) {
    val at = when (zone) {
        SplitDropZone.Center -> Modifier.fillMaxSize()
        SplitDropZone.Left -> Modifier.fillMaxHeight().fillMaxWidth(DROP_EDGE_SHARE).align(Alignment.CenterStart)
        SplitDropZone.Right -> Modifier.fillMaxHeight().fillMaxWidth(DROP_EDGE_SHARE).align(Alignment.CenterEnd)
        SplitDropZone.Top -> Modifier.fillMaxWidth().fillMaxHeight(DROP_EDGE_SHARE).align(Alignment.TopCenter)
        SplitDropZone.Bottom -> Modifier.fillMaxWidth().fillMaxHeight(DROP_EDGE_SHARE).align(Alignment.BottomCenter)
    }
    Box(at.background(Theme[colors][accentText].copy(alpha = 0.16f)).clearAndSetSemantics { })
}

/** The chat in the hand, under the pointer: the row that lifted out of the sidebar, see-through. */
@Composable
private fun DragGhost(held: DraggedChat, pane: Rect?) {
    if (pane == null || held.position == Offset.Unspecified) return
    Box(
        Modifier
            .offset {
                IntOffset(
                    (held.position.x - pane.left).roundToInt() + 8,
                    (held.position.y - pane.top).roundToInt() - 24,
                )
            }
            .widthIn(max = 200.dp)
            .graphicsLayer { alpha = 0.85f }
            .background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusSmall]))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            held.title ?: "Chat",
            style = Theme[typography][bodySmall],
            color = Theme[colors][text],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ChatTarget.toPaneChat(): PaneChat = PaneChat(storedSessionId, title, profile)

/**
 * A split pane's own bar: the chat it shows and its close, with the accent on the pane that has the focus.
 * Overlaid rather than stacked, so the bar costs a pane 32 dp and no more.
 */
@Composable
private fun PaneHeader(title: String, focused: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(32.dp).background(Theme[colors][surface])) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (focused) Theme[colors][accentText] else Theme[colors][stroke]),
        )
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = Theme[typography][caption],
                color = if (focused) Theme[colors][text] else Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UnstyledButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp),
                indication = rememberHoverIndication(Theme[colors][text]),
            ) {
                UnstyledIcon(Lucide.X, contentDescription = "Close pane", tint = Theme[colors][textSecondary], modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A pane with no chat yet: a new one, or one of the chats this gateway and profile already have. */
@Composable
private fun EmptyPane(state: SessionsUiState?, onPick: (SessionSummary) -> Unit, onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Choose a chat", style = Theme[typography][title], color = Theme[colors][text], maxLines = 1)
        Button(
            "New chat",
            onClick = onNewChat,
            variant = ButtonVariant.Secondary,
            leadingIcon = Lucide.SquarePen,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state == null || (state.loading && state.listed.isEmpty()) -> Unit
            state.error != null -> Text(state.error, style = Theme[typography][caption], color = Theme[colors][danger])
            state.listed.isEmpty() -> Text("No chats on this gateway yet.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(state.listed, key = { it.id }) { session ->
                    UnstyledButton(
                        onClick = { onPick(session) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouchTarget)
                            .clip(RoundedCornerShape(Theme[radii][radiusSmall])),
                        indication = rememberHoverIndication(Theme[colors][text]),
                    ) {
                        Text(
                            session.displayTitle,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                            style = Theme[typography][body],
                            color = Theme[colors][text],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
