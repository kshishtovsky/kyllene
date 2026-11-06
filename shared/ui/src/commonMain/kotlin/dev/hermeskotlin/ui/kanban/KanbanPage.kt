package dev.hermeskotlin.ui.kanban

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
import dev.hermeskotlin.core.kanban.KanbanRepository
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler
import kotlinx.coroutines.CoroutineScope
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
        onCreateTask = {},
        onSwitchBoard = { choosing = true },
        viewModel = viewModel,
    )
    KanbanBoardSwitcher(visible = choosing, model = switcher, onDismiss = { choosing = false })
    KanbanTaskSheet(
        taskId = route.task,
        gateway = gateway,
        board = boards.current,
        onDismiss = { route.back() },
        onChanged = viewModel::refresh,
    )
}
