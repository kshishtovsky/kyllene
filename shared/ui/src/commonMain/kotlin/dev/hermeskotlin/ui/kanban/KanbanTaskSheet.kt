@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package dev.hermeskotlin.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanAttachment
import dev.hermeskotlin.core.kanban.KanbanComment
import dev.hermeskotlin.core.kanban.KanbanEvent
import dev.hermeskotlin.core.kanban.KanbanEventLine
import dev.hermeskotlin.core.kanban.KanbanRun
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.KanbanTaskDetail
import dev.hermeskotlin.core.kanban.kanbanDuration
import dev.hermeskotlin.core.kanban.kanbanEventLine
import dev.hermeskotlin.core.kanban.kanbanStatusLabel
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code as codeStyle
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusFull
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft
import dev.hermeskotlin.ui.chat.formatBytes
import dev.hermeskotlin.ui.chat.rememberMediaActions
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.isDesktop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A running card's comments and events change under the sheet; four seconds is the dashboard's own cadence. */
private const val COMMENT_POLL_MS = 4_000L

/** The lanes the status menu offers, in the board's order; a local copy keeps the sheet free of the board's file. */
private val statusLanes = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done")

/**
 * One card's sheet: body, comments, events, runs, attachments — the dashboard drawer's sections in one scroll.
 * [BottomSheet] splits it by host on its own: a phone gets it from the bottom, a desktop a centred panel.
 */
@Composable
internal fun KanbanTaskSheet(
    taskId: String?,
    gateway: SavedGateway,
    board: String? = null,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
    api: KanbanApi = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)
    val model = remember(api, gateway, board) {
        KanbanTaskModel(api, gateway.gatewayUrl, board, scope, onChanged = { changed() })
    }
    LaunchedEffect(taskId) { if (taskId == null) model.close() else model.open(taskId) }
    DisposableEffect(model) { onDispose { model.close() } }

    val state by model.state.collectAsStateWithLifecycle()
    val running = taskId != null && state.detail?.task?.status == "running"
    LaunchedEffect(taskId, running) {
        // shortcut: polls while the sheet is open, foreground or not; the socket's invalidations already cover
        // the app-in-background case, so this is the fallback the gateway's own dashboard keeps too.
        while (running) {
            delay(COMMENT_POLL_MS)
            model.refresh()
        }
    }
    KanbanTaskSheetContent(state = state, model = model, visible = taskId != null, onDismiss = onDismiss)
}

/** The sheet's body without a model of its own: what a preview and a Compose test drive. */
@Composable
internal fun KanbanTaskSheetContent(
    state: KanbanTaskState,
    model: KanbanTaskModel,
    visible: Boolean = true,
    onDismiss: () -> Unit = {},
) {
    BottomSheet(visible = visible, onDismiss = onDismiss, onEscape = onDismiss) {
        val detail = state.detail
        SheetHeader(
            title = detail?.task?.title?.ifBlank { "Task" } ?: "Task",
            subtitle = state.taskId,
        )
        if (detail == null) {
            LoadingOrError(state, model::reload)
            return@BottomSheet
        }
        val desktop = isDesktop
        Header(detail.task, onMove = model::move)
        state.notice?.let { Notice(it, model::dismissNotice) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (desktop) 500.dp else 540.dp)) {
            item(key = "links") { Links(detail) }
            item(key = "body") { Description(detail, state.editing, model) }
            item(key = "warnings") { Warnings(detail) }

            item(key = "comments-label") {
                SectionLabel("Comments · ${detail.comments.size + state.pending.size}", Modifier.padding(SECTION_PADDING))
            }
            items(state.pending, key = { "pending-${it.key}" }) { PendingCommentRow(it, model) }
            items(detail.comments, key = { "comment-${it.id}" }) { CommentRow(it) }
            if (detail.comments.isEmpty() && state.pending.isEmpty()) {
                item(key = "no-comments") { Quiet("No comments yet.", Modifier.padding(SECTION_PADDING)) }
            }

            item(key = "events-label") {
                SectionLabel("Activity", Modifier.padding(SECTION_PADDING))
            }
            items(detail.events.asReversed().mapNotNull { event -> kanbanEventLine(event)?.let { event to it } }, key = { "event-${it.first.id}" }) { (event, line) ->
                EventRow(event, line)
            }

            item(key = "runs-label") { SectionLabel("Runs", Modifier.padding(SECTION_PADDING)) }
            items(detail.runs.asReversed(), key = { "run-${it.id}" }) { run -> RunRow(run, state.busy, model) }
            if (detail.runs.isEmpty()) item(key = "no-runs") { Quiet("No runs yet.", Modifier.padding(SECTION_PADDING)) }
            if (state.logRun != null) item(key = "log") { LogPanel(state, model) }

            item(key = "estimate") { Estimate(state, model) }

            item(key = "attachments-label") {
                SectionLabel("Attachments · ${detail.attachments.size}", Modifier.padding(SECTION_PADDING))
            }
            items(detail.attachments, key = { "file-${it.id}" }) { file -> AttachmentRow(file, model) }
            if (detail.attachments.isEmpty()) item(key = "no-files") { Quiet("No files.", Modifier.padding(SECTION_PADDING)) }
        }
        CommentComposer(model)
        state.confirm?.let { ConfirmStep(it, model) }
    }
}

private val SECTION_PADDING = 18.dp

// ---- header ----

@Composable
private fun Header(task: KanbanTask, onMove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMenu(task.status, onMove)
        Text(
            "P${task.priority}",
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
        )
        Text(
            task.assignee ?: "unassigned",
            style = Theme[typography][caption],
            color = Theme[colors][textSecondary],
        )
        Spacer(Modifier.weight(1f))
        Text(
            "id ${task.id}",
            style = Theme[typography][caption],
            color = Theme[colors][textMuted],
            maxLines = 1,
        )
    }
}

/** The status chip is the menu: every lane, and the current one is simply where the card already is. */
@Composable
private fun StatusMenu(status: String, onMove: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        items = {
            statusLanes.forEach { lane ->
                MenuAction(kanbanStatusLabel(lane), Lucide.ChevronDown, { open = false; onMove(lane) })
            }
        },
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(Theme[colors][accentSoft])
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(kanbanStatusLabel(status), style = Theme[typography][bodySmall], color = Theme[colors][accentText])
            UnstyledIcon(Lucide.ChevronDown, contentDescription = "Change status", tint = Theme[colors][accentText], modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Links(detail: KanbanTaskDetail) {
    if (detail.linkTasks.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        detail.linkTasks.forEach { linked ->
            val parent = linked.id in detail.links.parents
            Row(
                Modifier
                    .clip(RoundedCornerShape(Theme[radii][radiusFull]))
                    .background(Theme[colors][surface2])
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (parent) "parent" else "child", style = Theme[typography][caption], color = Theme[colors][textMuted])
                Text(
                    linked.title.ifBlank { linked.id },
                    style = Theme[typography][caption],
                    color = Theme[colors][textSecondary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
    }
}

// ---- description ----

@Composable
private fun Description(detail: KanbanTaskDetail, editing: Boolean, model: KanbanTaskModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Description")
            Spacer(Modifier.weight(1f))
            if (!editing) IconButton(Lucide.Pencil, "Edit description", model::editBody, iconSize = 16.dp)
        }
        when {
            editing -> BodyEditor(detail.task.body.orEmpty(), model)
            detail.task.body.isNullOrBlank() -> Quiet("No description.")
            else -> MarkdownText(detail.task.body.orEmpty())
        }
    }
}

@Composable
private fun BodyEditor(body: String, model: KanbanTaskModel) {
    val field = rememberTextFieldState(body)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(field, Modifier.fillMaxWidth(), singleLine = false, maxLines = 12, placeholder = "What the card is about…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Save", { model.saveBody(field.text.toString()) }, size = ButtonSize.Small, enabled = field.text.isNotBlank())
            Button("Cancel", model::cancelEdit, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
        }
    }
}

@Composable
private fun Warnings(detail: KanbanTaskDetail) {
    val warnings = detail.task.warnings ?: return
    if (warnings.count <= 0) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 6.dp)) {
        Surface(Modifier.fillMaxWidth(), color = Theme[colors][warningSoft]) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(16.dp))
                Text("${warnings.count} warnings", style = Theme[typography][bodySmall], color = Theme[colors][text])
                Spacer(Modifier.weight(1f))
                UnstyledIcon(Lucide.ChevronDown, contentDescription = if (open) "Hide warnings" else "Show warnings", tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
            }
        }
        if (open) {
            warnings.kinds.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
                Text(
                    "$count × ${kind.replace('_', ' ')}",
                    style = Theme[typography][caption],
                    color = Theme[colors][textSecondary],
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }
    }
}

// ---- comments ----

@Composable
private fun CommentRow(comment: KanbanComment) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(comment.author.ifBlank { "someone" }, style = Theme[typography][label], color = Theme[colors][textSecondary])
            Text(relativeTime(comment.createdAt.toDouble()), style = Theme[typography][caption], color = Theme[colors][textMuted])
        }
        MarkdownText(comment.body)
    }
}

/** An optimistic row: the server's reply carries no id, so it lives here until a read brings the real one. */
@Composable
private fun PendingCommentRow(row: KanbanPendingComment, model: KanbanTaskModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    row.sending -> "Sending…"
                    row.failed -> "Not sent"
                    else -> "Sent"
                },
                style = Theme[typography][label],
                color = if (row.failed) Theme[colors][danger] else Theme[colors][textTertiary],
            )
            if (row.failed) {
                Button("Retry", { model.retry(row) }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                IconButton(Lucide.X, "Discard the comment", { model.discard(row) }, iconSize = 14.dp)
            }
        }
        Text(row.body, style = Theme[typography][body], color = Theme[colors][text])
    }
}

@Composable
private fun CommentComposer(model: KanbanTaskModel) {
    val field = rememberTextFieldState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            field,
            Modifier.weight(1f),
            placeholder = "Write a comment…",
            singleLine = false,
            maxLines = 4,
        )
        IconButton(
            Lucide.Send,
            "Send the comment",
            onClick = {
                model.comment(field.text.toString())
                field.clearText()
            },
            enabled = field.text.isNotBlank(),
            tint = Theme[colors][onAccent],
            containerColor = Theme[colors][accent],
        )
    }
}

// ---- events ----

@Composable
private fun EventRow(event: KanbanEvent, line: KanbanEventLine) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            relativeTime(event.createdAt.toDouble()),
            style = Theme[typography][caption],
            color = Theme[colors][textMuted],
        )
        Column(Modifier.weight(1f)) {
            Text(line.label, style = Theme[typography][bodySmall], color = Theme[colors][text])
            line.detail?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][textSecondary]) }
        }
    }
}

// ---- runs and the log ----

@Composable
private fun RunRow(run: KanbanRun, busy: Boolean, model: KanbanTaskModel) {
    val now = Clock.System.now().epochSeconds
    Column(Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${run.id}", style = Theme[typography][codeStyle], color = Theme[colors][textSecondary])
            Text(run.profile ?: "—", style = Theme[typography][caption], color = Theme[colors][textSecondary])
            Text(
                run.outcome ?: run.status,
                style = Theme[typography][caption],
                color = when (run.outcome) {
                    "completed" -> Theme[colors][success]
                    "crashed", "rate_limited" -> Theme[colors][danger]
                    else -> Theme[colors][textTertiary]
                },
            )
            run.startedAt?.let { start ->
                Text(
                    kanbanDuration((run.endedAt ?: now) - start),
                    style = Theme[typography][caption],
                    color = Theme[colors][textMuted],
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(Lucide.FileText, "Open the worker log", { model.openLog(run) }, iconSize = 16.dp)
            if (run.status == "running") {
                IconButton(
                    Lucide.Square,
                    "Stop the run",
                    { model.askStop(run) },
                    enabled = !busy,
                    tint = Theme[colors][danger],
                    iconSize = 16.dp,
                )
            }
        }
    }
}

@Composable
private fun LogPanel(state: KanbanTaskState, model: KanbanTaskModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Worker log · run #${state.logRun?.id}")
            Spacer(Modifier.weight(1f))
            IconButton(Lucide.X, "Close the log", model::closeLog, iconSize = 16.dp)
        }
        when {
            state.logLoading -> Spinner(Modifier.padding(12.dp).size(20.dp))
            state.log?.exists == false -> Quiet("The worker wrote no log for this run.")
            else -> Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(Theme[radii][radiusFull]))
                    .background(Theme[colors][surface2])
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                // The API serves one log per task, not one per run: the file is the worker's, whichever run asked.
                Text(state.log?.content.orEmpty(), style = Theme[typography][codeStyle], color = Theme[colors][textSecondary])
            }
        }
    }
}

// ---- estimate ----

@Composable
private fun Estimate(state: KanbanTaskState, model: KanbanTaskModel) {
    val estimate = state.estimate
    Column(Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Estimate")
            Spacer(Modifier.weight(1f))
            if (estimate == null || !estimate.ok) {
                Button(
                    "Estimate",
                    model::estimate,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small,
                    loading = state.estimating,
                )
            }
        }
        when {
            estimate == null -> Quiet("Not estimated: the gateway works one out on request (an LLM call).")
            !estimate.ok -> Quiet(estimate.reason ?: "The gateway couldn't estimate this task.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(
                        estimate.estTokens?.let { "~${it / 1000}k tokens" },
                        estimate.complexity,
                        estimate.model,
                    ).joinToString(" · "),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][text],
                )
                estimate.rationale?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][textSecondary]) }
            }
        }
    }
}

// ---- attachments ----

@Composable
private fun AttachmentRow(file: KanbanAttachment, model: KanbanTaskModel) {
    val media = rememberMediaActions()
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.Paperclip, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(file.filename, style = Theme[typography][bodySmall], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(formatBytes(file.size), file.uploadedBy).joinToString(" · "),
                style = Theme[typography][caption],
                color = Theme[colors][textMuted],
            )
        }
        IconButton(
            Lucide.Download,
            "Download ${file.filename}",
            {
                scope.launch {
                    model.download(file)?.let { bytes -> model.tell(media.save(bytes, file.filename)) }
                }
            },
            iconSize = 16.dp,
        )
        IconButton(Lucide.Trash2, "Delete ${file.filename}", { model.askDeleteFile(file) }, tint = Theme[colors][danger], iconSize = 16.dp)
    }
}

// ---- confirmations and notices ----

@Composable
private fun ConfirmStep(confirm: KanbanConfirm, model: KanbanTaskModel) {
    val (title, message, action) = when (confirm) {
        is KanbanConfirm.StopRun -> Triple(
            "Stop run #${confirm.run.id}?",
            "The worker is killed and the card goes back in the queue. The run keeps the log it wrote so far.",
            "Stop the run",
        )
        is KanbanConfirm.DeleteFile -> Triple(
            "Delete ${confirm.attachment.filename}?",
            "The file is deleted from the gateway as well, not just from this sheet.",
            "Delete",
        )
        is KanbanConfirm.Finish -> Triple(
            "Finish this card?",
            "Done is final for the dispatcher: the card leaves the queue and its worker stops being picked.",
            "Move to Done",
        )
    }
    Dialog(
        visible = true,
        onDismissRequest = model::cancelConfirm,
        title = title,
        message = message,
        actions = {
            Button("Cancel", model::cancelConfirm, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(action, model::confirm, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

@Composable
private fun Notice(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.weight(1f), color = Theme[colors][surface2]) {
            Text(
                message,
                style = Theme[typography][bodySmall],
                color = Theme[colors][text],
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        IconButton(Lucide.X, "Dismiss", onDismiss, iconSize = 14.dp)
    }
}

@Composable
private fun LoadingOrError(state: KanbanTaskState, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.loading) {
            Spinner()
        } else {
            Text(state.error ?: "This card is gone.", style = Theme[typography][body], color = Theme[colors][textSecondary])
            Button("Try again", onRetry, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
    }
}

@Composable
private fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Theme[typography][caption], color = Theme[colors][textMuted], modifier = modifier)
}
