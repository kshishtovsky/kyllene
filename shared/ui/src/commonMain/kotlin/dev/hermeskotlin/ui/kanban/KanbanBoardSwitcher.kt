package dev.hermeskotlin.ui.kanban

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.ui.isDesktop

/**
 * The board picker: every board with its slug and task counts, the current one ticked, plus create, rename and
 * delete. A pick goes through [KanbanBoardSwitcherModel.select], which drops the old board's cards and reopens the
 * live stream; a delete asks first, saying how many tasks go with the board.
 */
@Composable
fun KanbanBoardSwitcher(visible: Boolean, model: KanbanBoardSwitcherModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf<BoardForm?>(null) }
    LaunchedEffect(visible) { if (!visible) form = null }
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = "Boards",
        message = "One board at a time. Picking another drops this one's cards and reopens the live feed.",
        actions = {
            Button("Close", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                "New board",
                onClick = { form = BoardForm.New },
                variant = ButtonVariant.Primary,
                size = ButtonSize.Small,
                enabled = !state.saving,
                leadingIcon = Lucide.Plus,
            )
        },
    ) {
        when {
            state.boards.isEmpty() && state.loading ->
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
            state.boards.isEmpty() ->
                Text(
                    state.error ?: "No boards on this gateway yet.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                )
            else -> Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScrollWithBar()) {
                state.boards.forEach { board ->
                    key(board.slug) {
                        BoardRow(
                            board,
                            current = board.slug == state.current,
                            enabled = !state.saving,
                            onSelect = { onDismiss(); model.select(board.slug) },
                            onRename = { form = BoardForm.Rename(board) },
                            onDelete = { model.askDelete(board) },
                        )
                    }
                }
            }
        }
        val error = state.error
        if (error != null && state.boards.isNotEmpty()) {
            CopyableOnDesktop { Text(error, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
        }
    }
    BoardFormDialog(form, state.saving, onDismiss = { form = null }, onCreate = model::create, onRename = model::rename)
    state.deleting?.let { board -> DeleteBoardDialog(board, onCancel = model::dismissDelete, onConfirm = model::confirmDelete) }
}

@Composable
private fun BoardRow(
    board: KanbanBoardMeta,
    current: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .handCursor(enabled && !current)
            .clickable(enabled = enabled && !current, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                board.name?.takeIf { it.isNotBlank() } ?: board.slug,
                style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                counts(board),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) UnstyledIcon(Lucide.Check, contentDescription = "Current board", tint = Theme[colors][accent], modifier = Modifier.size(18.dp))
        IconButton(Lucide.Pencil, contentDescription = "Rename ${board.slug}", onClick = onRename, enabled = enabled)
        IconButton(Lucide.Trash2, contentDescription = "Delete ${board.slug}", onClick = onDelete, enabled = enabled, tint = Theme[colors][danger])
    }
}

/** "dev · 4 running · 23 tasks": the gateway's counts are keyed by status and omit the empty ones. */
private fun counts(board: KanbanBoardMeta): String {
    val running = board.counts["running"] ?: 0
    return "${board.slug} · ${if (running == 1) "1 running" else "$running running"} · " +
        if (board.total == 1) "1 task" else "${board.total} tasks"
}

/** What the switcher's own form is open for: nothing of the gateway's, so it stays out of the model. */
private sealed interface BoardForm {
    data object New : BoardForm
    data class Rename(val board: KanbanBoardMeta) : BoardForm
}

@Composable
private fun BoardFormDialog(
    form: BoardForm?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (slug: String, name: String?) -> Unit,
    onRename: (slug: String, name: String) -> Unit,
) {
    if (form == null) return
    // A fresh form per ask: it starts from the board's own name, never the last one's.
    key(form) {
        val name = rememberTextFieldState((form as? BoardForm.Rename)?.board?.let { it.name ?: it.slug } ?: "")
        val slug = rememberTextFieldState("")
        val nameText = name.text.toString().trim()
        val slugText = slug.text.toString().trim().lowercase()
        val ready = when (form) {
            BoardForm.New -> isBoardSlug(slugText)
            is BoardForm.Rename -> nameText.isNotEmpty()
        }
        val submit = {
            if (ready && !saving) {
                when (form) {
                    BoardForm.New -> {
                        onDismiss()
                        onCreate(slugText, nameText.ifEmpty { null })
                    }
                    is BoardForm.Rename -> {
                        onDismiss()
                        onRename(form.board.slug, nameText)
                    }
                }
            }
        }
        Dialog(
            visible = true,
            onDismissRequest = onDismiss,
            title = if (form is BoardForm.Rename) "Rename board" else "New board",
            actions = {
                Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small, enabled = !saving)
                Button(
                    if (form is BoardForm.Rename) "Rename" else "Create",
                    onClick = submit,
                    variant = ButtonVariant.Primary,
                    size = ButtonSize.Small,
                    enabled = ready && !saving,
                    loading = saving,
                )
            },
        ) {
            TextField(
                name,
                label = "Name",
                placeholder = (form as? BoardForm.Rename)?.board?.slug ?: "Release board",
                enabled = !saving,
                autofocus = form is BoardForm.Rename,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            if (form is BoardForm.New) {
                TextField(
                    slug,
                    label = "Slug",
                    placeholder = "release",
                    supportingText = "Lowercase letters, digits, - or _. The gateway uses it in the URL.",
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // Enter makes the board on the desktop; a phone's Done only closes the keyboard, as elsewhere.
                    onKeyboardAction = { closeKeyboard -> if (isDesktop) submit() else closeKeyboard() },
                )
            }
        }
    }
}

@Composable
private fun DeleteBoardDialog(board: KanbanBoardMeta, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        visible = true,
        onDismissRequest = onCancel,
        title = "Delete “${board.name?.takeIf { it.isNotBlank() } ?: board.slug}”?",
        message = "It is archived with its ${board.total} tasks, not erased: the gateway can restore the board. " +
            "Cards in flight stop belonging to a board you can see.",
        actions = {
            Button("Cancel", onClick = onCancel, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Delete", onClick = onConfirm, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}
