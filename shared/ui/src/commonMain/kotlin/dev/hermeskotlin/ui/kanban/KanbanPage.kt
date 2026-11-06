package dev.hermeskotlin.ui.kanban

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanLive
import dev.hermeskotlin.core.kanban.KanbanNewTask
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The board over the chat: the profile's own board from the switch list, the live stream while it's on screen, and
 * the card's sheet [route] names — a tap or a `herald://kanban/<board>/<taskId>` link.
 */
@Composable
fun KanbanPage(
    gateway: SavedGateway,
    profile: String?,
    route: KanbanRouteState,
    onBack: () -> Unit,
    api: KanbanApi = koinInject(),
    repository: KanbanRepository = koinInject(),
    viewModel: KanbanBoardViewModel = koinViewModel(),
    live: KanbanLive = koinInject(),
) {
    val store: KeyValueStore = koinInject()
    val scope: CoroutineScope = koinInject()
    // The switch list is the per-profile memory: it picks the board this profile last had open.
    val switcher = remember(api, repository, store, scope) { KanbanBoardSwitcherModel(api, repository, store, scope) }
    val boards by switcher.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var creatingSaving by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    // A write the user made never fails silently: its refusal is a banner on the board until dismissed.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gateway.url, profile) { switcher.load(gateway.gatewayUrl, profile) }
    // A link names the board to open; one this gateway doesn't have leaves the remembered board on screen.
    val wanted = route.board
    LaunchedEffect(wanted, boards.boards) {
        if (wanted != null && wanted != boards.current && boards.boards.any { it.slug == wanted }) switcher.select(wanted)
    }
    // Esc leaves the board; the sheet under it takes its own first.
    ShortcutHandler { shortcut ->
        if (shortcut != Shortcut.Escape) return@ShortcutHandler false
        onBack()
        true
    }
    KanbanLiveEffect(live)
    KanbanBoardScreen(
        gateway = gateway,
        profile = profile,
        visible = true,
        onBack = onBack,
        onOpenTask = route::openTask,
        onCreateTask = { createError = null; creating = true },
        onSwitchBoard = { choosing = true },
        onAssign = { taskId, assignee -> scope.launch { notice = repository.assign(taskId, assignee) } },
        notice = notice,
        onDismissNotice = { notice = null },
        viewModel = viewModel,
    )
    KanbanBoardSwitcher(visible = choosing, model = switcher, onDismiss = { choosing = false })
    NewTaskDialog(
        visible = creating,
        saving = creatingSaving,
        error = createError,
        onCreate = { title, body ->
            creatingSaving = true
            createError = null
            scope.launch {
                when (val result = repository.create(KanbanNewTask(title = title, body = body))) {
                    // The repository put the card in its lane already; the dialog has nothing left to say.
                    is ApiResult.Success -> creating = false
                    else -> createError = result.errorMessage ?: "Couldn't create the task."
                }
                creatingSaving = false
            }
        },
        onDismiss = {
            if (!creatingSaving) {
                creating = false
                createError = null
            }
        },
    )
    KanbanTaskSheet(
        taskId = route.task,
        gateway = gateway,
        board = boards.current,
        onDismiss = { route.back() },
        onChanged = viewModel::refresh,
        live = live,
    )
}

/** The board's new card: the title the gateway needs, and the description the sheet then shows. */
@Composable
private fun NewTaskDialog(
    visible: Boolean,
    saving: Boolean,
    error: String?,
    onCreate: (title: String, body: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val title = rememberTextFieldState()
    val body = rememberTextFieldState()
    val ready = title.text.isNotBlank()
    val submit = {
        if (ready && !saving) onCreate(title.text.toString().trim(), body.text.toString().trim().ifEmpty { null })
    }
    Dialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = "New task",
        actions = {
            Button("Cancel", onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small, enabled = !saving)
            Button(
                "Create",
                submit,
                variant = ButtonVariant.Primary,
                size = ButtonSize.Small,
                enabled = ready && !saving,
                loading = saving,
            )
        },
    ) {
        TextField(title, label = "Title", placeholder = "What needs doing", enabled = !saving, autofocus = true, error = error)
        TextField(
            body,
            label = "Description",
            placeholder = "Anything the worker should know",
            enabled = !saving,
            singleLine = false,
            maxLines = 6,
        )
    }
}
