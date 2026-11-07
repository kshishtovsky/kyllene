package dev.hermeskotlin.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Kanban
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.User
import com.composeunstyled.AnchorAlignment
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanBoard
import dev.hermeskotlin.core.kanban.KanbanState
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code as codeStyle
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuChoice
import dev.hermeskotlin.designsystem.components.Skeleton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.clearOfWindowControls
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.isDesktop
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/** A lane that is 86% of the phone's width leaves the next one peeking: the board reads as a row of lanes. */
private const val PHONE_LANE_FRACTION = 0.86f

/** Wide enough for a card's badges, narrow enough that all lanes are in reach on a desktop window. */
private val DESKTOP_LANE_WIDTH = 200.dp

/** The window's margins plus the gaps between lanes: what the eight lanes share. */
private val DESKTOP_GUTTERS = 24.dp + 10.dp * 7

/** The board: the repository's cache, patched by socket events, and its name from `/boards`. */
@Composable
internal fun KanbanBoardScreen(
    gateway: SavedGateway,
    profile: String?,
    visible: Boolean,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onCreateTask: () -> Unit,
    onSwitchBoard: () -> Unit,
    onAssign: (taskId: String, assignee: String?) -> Unit = { _, _ -> },
    viewModel: KanbanBoardViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val name by viewModel.boardName.collectAsStateWithLifecycle()
    LaunchedEffect(gateway.url, profile) { viewModel.bind(gateway, profile) }
    KanbanBoardContent(
        state = state,
        boardName = name ?: state.slug ?: "Kanban",
        visible = visible,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onOpenTask = onOpenTask,
        onCreateTask = onCreateTask,
        onSwitchBoard = onSwitchBoard,
        onAssign = onAssign,
    )
}

@Composable
internal fun KanbanBoardContent(
    state: KanbanState,
    boardName: String,
    visible: Boolean = true,
    desktop: Boolean = isDesktop,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onCreateTask: () -> Unit = {},
    onSwitchBoard: () -> Unit = {},
    onAssign: (taskId: String, assignee: String?) -> Unit = { _, _ -> },
) {
    val board = state.board
    val tasks = kanbanLanes.sumOf { laneTasks(board, it.first).size }

    Column(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoardHeader(boardName, desktop, onBack, onSwitchBoard, onCreateTask)
        state.error?.let { BoardErrorBanner(it, onRetry) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // No cached board at all: the first read is in flight, so a skeleton beats an empty screen.
                board.columns.isEmpty() -> LaneSkeleton(desktop)
                tasks == 0 -> EmptyBoard(onCreateTask)
                desktop -> DesktopLanes(board, visible, onOpenTask, onAssign)
                else -> PhoneLanes(board, visible, onOpenTask, onAssign)
            }
        }
    }
}

@Composable
private fun BoardHeader(
    name: String,
    desktop: Boolean,
    onBack: () -> Unit,
    onSwitchBoard: () -> Unit,
    onCreateTask: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (desktop) Modifier.clearOfWindowControls() else Modifier)
            .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Lucide.ArrowLeft, "Back", onBack, tint = Theme[colors][textSecondary])
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = Theme[typography][if (desktop) title else display],
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            IconButton(Lucide.ChevronDown, "Switch board", onSwitchBoard, tint = Theme[colors][textTertiary])
        }
        IconButton(Lucide.Plus, "New task", onCreateTask, tint = Theme[colors][textSecondary])
    }
}

@Composable
private fun BoardErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
        color = Theme[colors][dangerSoft],
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            UnstyledIcon(Lucide.CircleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
            Text(
                message,
                style = Theme[typography][bodySmall],
                color = Theme[colors][text],
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Button("Try again", onRetry, variant = ButtonVariant.Outline, size = ButtonSize.Small)
        }
    }
}

@Composable
private fun EmptyBoard(onCreateTask: () -> Unit) {
    EmptyState(
        icon = Lucide.Kanban,
        title = "No tasks",
        body = "Nothing on this board yet.",
        action = {
            Button("New task", onCreateTask, leadingIcon = Lucide.Plus)
        },
    )
}

@Composable
private fun LaneSkeleton(desktop: Boolean) {
    Row(
        Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .testTag("kanban-skeleton")
            .semantics { contentDescription = "Loading" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        kanbanLanes.forEach {
            Column(Modifier.width(if (desktop) DESKTOP_LANE_WIDTH else 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Skeleton(Modifier.padding(vertical = 8.dp).width(96.dp).height(14.dp))
                repeat(3) {
                    Skeleton(Modifier.fillMaxWidth().height(76.dp), RoundedCornerShape(Theme[radii][radiusMedium]))
                }
            }
        }
    }
}

@Composable
private fun DesktopLanes(
    board: KanbanBoard,
    visible: Boolean,
    onOpenTask: (String) -> Unit,
    onAssign: (String, String?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Share the window between the eight lanes while each stays readable; a narrower window scrolls instead.
        val laneWidth = maxOf(DESKTOP_LANE_WIDTH, (maxWidth - DESKTOP_GUTTERS) / kanbanLanes.size)
        Row(
            Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            kanbanLanes.forEach { (status, label) ->
                BoardLane(
                    status = status,
                    label = label,
                    board = board,
                    visible = visible,
                    onOpenTask = onOpenTask,
                    onAssign = onAssign,
                    modifier = Modifier.width(laneWidth).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun PhoneLanes(
    board: KanbanBoard,
    visible: Boolean,
    onOpenTask: (String) -> Unit,
    onAssign: (String, String?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val laneWidth = maxWidth * PHONE_LANE_FRACTION
        // One state for the row: a new board snapshot must not snap the user back to the first lane.
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = rememberSnapFlingBehavior(rowState),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(kanbanLanes, key = { it.first }) { lane ->
                BoardLane(
                    status = lane.first,
                    label = lane.second,
                    board = board,
                    visible = visible,
                    onOpenTask = onOpenTask,
                    onAssign = onAssign,
                    modifier = Modifier.width(laneWidth).fillParentMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun BoardLane(
    status: String,
    label: String,
    board: dev.hermeskotlin.core.kanban.KanbanBoard,
    visible: Boolean,
    onOpenTask: (String) -> Unit,
    onAssign: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks = laneTasks(board, status)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(laneTint(status), CircleShape))
            Text(
                label,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text("${tasks.size}", style = Theme[typography][codeStyle].copy(fontSize = 11.sp), color = Theme[colors][textMuted])
        }
        if (tasks.isEmpty()) {
            Text("Empty", style = Theme[typography][bodySmall], color = Theme[colors][textMuted], modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        } else {
            LazyColumn(
                // Per-lane state, kept across snapshots: a live update must not scroll the lane to the top.
                state = rememberLazyListState(),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    KanbanCard(
                        task = task,
                        assignees = board.assignees,
                        boardNow = board.now,
                        visible = visible,
                        onClick = { onOpenTask(task.id) },
                        onAssign = { onAssign(task.id, it) },
                    )
                }
            }
        }
    }
}

/** The lane's marker: the semantic colour of its state, never a literal. */
@Composable
private fun laneTint(status: String): Color = Theme[colors][when (status) {
    "running", "done" -> success
    "blocked" -> danger
    "review" -> warning
    "scheduled", "ready" -> accentText
    else -> textMuted
}]

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KanbanCard(
    task: KanbanTask,
    assignees: List<String>,
    /** The board's own clock, which a run's start is measured against. */
    boardNow: Long,
    visible: Boolean,
    onClick: () -> Unit,
    onAssign: (String?) -> Unit,
) {
    val running = task.status == "running"
    Surface(
        Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick).testTag("kanban-card-${task.id}"),
        color = Theme[colors][surface2],
    ) {
        // The card is a title over one footer line, as the dashboard's is: the footer hugs the card's bottom
        // edge, so nothing floats in a band of its own.
        Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                task.title.ifBlank { "Untitled" },
                style = Theme[typography][body],
                color = Theme[colors][text],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // A lane is 150–200dp wide: the badges wrap onto a second line there rather than being crushed to
            // nothing, which is what a single non-wrapping row did.
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag("kanban-card-meta-${task.id}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssigneeAvatar(task.assignee, assignees, onAssign)
                if (running) {
                    val seconds = rememberRunClock(task, boardNow, visible)
                    if (seconds != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Spinner(Modifier.size(10.dp), Theme[colors][success])
                            Text(formatRunClock(seconds), style = Theme[typography][codeStyle].copy(fontSize = 11.sp), color = Theme[colors][success])
                        }
                    }
                }
                if (task.status == "blocked") {
                    blockKindLabel(task.blockKind)?.let {
                        Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.sp), color = Theme[colors][danger], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (task.blockRecurrences > 0) {
                        Text("×${task.blockRecurrences}", style = Theme[typography][codeStyle].copy(fontSize = 11.sp), color = Theme[colors][textTertiary])
                    }
                }
                task.tenant?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (task.priority != 0) {
                    Glyph(Lucide.Flag, "${task.priority}", "Priority ${task.priority}", if (task.priority >= 5) Theme[colors][warning] else Theme[colors][textTertiary])
                }
                task.progress?.let {
                    Text("${it.done}/${it.total}", style = Theme[typography][codeStyle].copy(fontSize = 11.sp), color = Theme[colors][textSecondary])
                }
                if (task.commentCount > 0) {
                    val word = if (task.commentCount == 1) "comment" else "comments"
                    Glyph(Lucide.MessageSquare, "${task.commentCount}", "${task.commentCount} $word")
                }
                if (task.linkCounts.parents + task.linkCounts.children > 0) {
                    val parentsWord = if (task.linkCounts.parents == 1) "parent" else "parents"
                    val childrenWord = if (task.linkCounts.children == 1) "child" else "children"
                    Glyph(
                        Lucide.GitFork,
                        "${task.linkCounts.parents}/${task.linkCounts.children}",
                        "${task.linkCounts.parents} $parentsWord, ${task.linkCounts.children} $childrenWord",
                    )
                }
                task.warnings?.takeIf { it.count > 0 }?.let { warnings ->
                    Glyph(
                        Lucide.CircleAlert,
                        "${warnings.count}",
                        "${warnings.count} warnings",
                        Theme[colors][warning],
                        onClick = onClick,
                    )
                }
                Text(
                    task.id,
                    style = Theme[typography][codeStyle].copy(fontSize = 11.sp),
                    color = Theme[colors][textMuted],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An icon and a number: the description carries what the glyph alone would not say aloud. */
@Composable
private fun Glyph(
    icon: ImageVector,
    value: String,
    description: String,
    tint: Color = Theme[colors][textTertiary],
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .then(if (onClick != null) Modifier.handCursor().clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(value, style = Theme[typography][codeStyle].copy(fontSize = 11.sp), color = tint, maxLines = 1)
    }
}

@Composable
private fun AssigneeAvatar(assignee: String?, assignees: List<String>, onAssign: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        alignment = AnchorAlignment.Start,
        items = {
            MenuChoice("Unassigned", Lucide.User, selected = assignee == null, onClick = { open = false; onAssign(null) })
            assignees.forEach { name ->
                MenuChoice(name, Lucide.User, selected = assignee == name, onClick = { open = false; onAssign(name) })
            }
        },
        anchor = {
            // The avatar is the anchor: tap it to hand the card to someone else.
            Box(
                Modifier
                    .size(28.dp)
                    .handCursor()
                    .clickable { open = true }
                    .semantics { contentDescription = assignee?.let { "Assignee $it" } ?: "Unassigned" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (assignee == null) Theme[colors][surface3] else Theme[colors][accentSoft])
                        .border(1.dp, Theme[colors][stroke], CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initials(assignee),
                        style = Theme[typography][codeStyle].copy(fontSize = 10.sp),
                        color = if (assignee == null) Theme[colors][textMuted] else Theme[colors][accentText],
                    )
                }
            }
        },
    )
}

/** The running card's clock, ticking only while the card is on screen and the page is in front. */
@Composable
private fun rememberRunClock(task: KanbanTask, boardNow: Long, active: Boolean): Long? {
    val startedAt = task.currentRunStartedAt ?: return null
    // Anchored to the board's own `now`: a phone whose clock is off must not show a run of 26092h.
    var ticks by remember(boardNow) { mutableStateOf(0L) }
    LaunchedEffect(boardNow, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(1_000)
            ticks += 1
        }
    }
    return (boardNow + ticks - startedAt).coerceAtLeast(0)
}
