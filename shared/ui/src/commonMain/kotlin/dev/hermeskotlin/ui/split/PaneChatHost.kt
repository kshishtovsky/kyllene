package dev.hermeskotlin.ui.split

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.ui.chat.ChatTarget
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.di.CHAT_WINDOW
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

/** A pane's chat, as a window's own: its [ChatHost], its view model, and the store that goes with the pane. */
class PaneSession(val host: ChatHost, val viewModel: ChatViewModel, val owner: ViewModelStoreOwner)

/**
 * The chat shown by pane [paneId], as [ChatWindow] runs one: the pane has its own [ChatHost] and its own view model
 * store, so two panes on the same chat stream it separately instead of sharing one session.
 *
 * [chat] opens in that host; another [chat] in the same pane takes the host over rather than rebuilding it, as the
 * window does. The app's [mainHost] is told that this chat is on screen, so the sidebar's watcher leaves it alone.
 */
@Composable
fun rememberPaneChatHost(
    paneId: String,
    mainHost: ChatHost,
    koin: Koin,
    gateway: SavedGateway,
    chat: PaneChat,
): PaneSession {
    val host = remember(paneId) { ChatHost(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()) }
    val owner = remember(paneId) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    val viewModel: ChatViewModel = koinViewModel(
        qualifier = named(CHAT_WINDOW),
        viewModelStoreOwner = owner,
    ) { parametersOf(host) }
    // The pane is gone with its chat: its host's session and everything its view models held go too.
    DisposableEffect(host, owner) {
        onDispose {
            host.close()
            owner.viewModelStore.clear()
        }
    }
    val target = remember(gateway, chat) { ChatTarget(gateway, chat.storedSessionId, chat.title, profile = chat.profile) }
    LaunchedEffect(target) { viewModel.open(target) }
    // A new chat's id only exists once the gateway has stored it: the pane holds that one too, from then on.
    val storedId = viewModel.state.collectAsState().value.storedSessionId
    val shownId = storedId ?: chat.storedSessionId
    DisposableEffect(shownId) {
        shownId?.let(mainHost::holdSideChat)
        onDispose { shownId?.let(mainHost::releaseSideChat) }
    }
    return remember(host, viewModel, owner) { PaneSession(host, viewModel, owner) }
}
