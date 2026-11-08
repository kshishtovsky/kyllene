package dev.hermeskotlin.ui.split

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.chat.ToolRiskStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.projects.ProjectsApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.InMemoryBlobFile
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.threads.ThreadsApi
import dev.hermeskotlin.core.threads.ThreadsRepository
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.chat.ChatScreen
import dev.hermeskotlin.ui.chat.ChatTarget
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.chat.ReplySpeakerSource
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermeskotlin.ui.di.CHAT_WINDOW
import dev.hermeskotlin.ui.sessions.SessionsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The split screen as a person meets it: one chat at first, a pane added from its top bar, a chat picked for a pane,
 * the focus and the keyboard moving with a tap, and a pane's close giving its room away.
 *
 * The panes are drawn by [ChatScreen] where the button itself is under test, and by a stand-in that tags itself and
 * records what the screen told it (its chat, the keys, whether it may split) where a pane's own state is under test.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SplitScreenTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val target = ChatTarget(gateway, "s1", "Main")
    private val scope = CoroutineScope(dispatcher)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val viewModels = ViewModelStore()

    /** Every frame the app sent to the gateway, in order. */
    private val sent = mutableListOf<String>()

    private lateinit var mainChat: ChatViewModel
    private lateinit var sessions: SessionsViewModel

    /** A screen that is on: what the chat's `collectAsStateWithLifecycle` needs to hear the link. */
    private val screenLifecycle = object : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
    }

    private object NoRecorder : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
            error("not recording in tests")

        override fun finish() = Unit
    }

    private object NoPlayer : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private object NoVoiceKeepAlive : VoiceKeepAlive {
        override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = false
        override fun release() = Unit
    }

    /** A gateway socket that says it's ready, keeps what was sent, and answers every call with an empty result. */
    private inner class ReadyGateway : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            sent += text
            val frame = Json.parseToJsonElement(text).jsonObject
            val id = frame["id"] ?: return
            // A resumed session comes back with its runtime id: a prompt needs one to go out at all.
            val result = when (frame["method"]?.jsonPrimitive?.content) {
                "session.resume" -> """{"session_id":"rt-s1","running":false,"messages":[]}"""
                else -> "{}"
            }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    private fun chatViewModel(host: ChatHost): ChatViewModel = newChat(host)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngineConfig()
        engine.dispatcher = dispatcher
        engine.addHandler { request ->
            when {
                request.url.encodedPath == "/api/sessions" -> respond(
                    """{"sessions":[{"id":"a","title":"Alpha"},{"id":"b","title":"Beta"}],"total":2}""",
                    HttpStatusCode.OK, json,
                )
                request.url.encodedPath == "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                request.url.encodedPath.startsWith("/api/profiles") ->
                    respond("""{"profiles":[{"name":"default","is_default":true}],"current":"default"}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        live.http = createHttpClient(MockEngine(engine), cookies)
        live.scope = scope
        live.connection = GatewayConnection(AuthApi(live.http, cookies), { _, _ -> ReadyGateway() }, scope)
        // The app starts its link at launch; a chat only sends once it is up.
        live.connection.start(gateway.gatewayUrl)
        live.settings = SettingsStore(InMemoryKeyValueStore(), scope)
        live.mainHost = ChatHost(live.connection, SessionsApi(live.http), scope)
        mainChat = chatViewModel(live.mainHost)
        viewModels.put("main", mainChat)
        sessions = SessionsViewModel(
            SessionsApi(live.http), AuthApi(live.http, cookies), live.connection, LastChatStore(InMemoryKeyValueStore()),
            ProfilesApi(live.http), AttentionTracker(live.connection, live.mainHost, ActiveSessions(live.connection, scope), scope),
            SeenStore(InMemoryKeyValueStore()) { 0.0 }, DraftStore(InMemoryKeyValueStore()), ProjectsApi(live.connection),
            ThreadsRepository(ThreadsApi(live.connection), SessionsApi(live.http), ProfilesApi(live.http)),
        )
        sessions.bind(gateway)
        // One Koin for the class: a composition left over from a test may still ask it for a pane's chat.
        synchronized(koinLock) {
            if (GlobalContext.getOrNull() == null) startKoin { modules(screenModule()) }
        }
    }

    @AfterTest
    fun tearDown() {
        viewModels.clear()
        live.mainHost.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** The screen on a 2600×800 stage: room for four 640 dp panes side by side. */
    private fun content(
        pane: @Composable (ChatTarget, ChatViewModel, Boolean, Boolean, PaneActions) -> Unit,
    ): @Composable () -> Unit = {
        // The chat reads its state with the lifecycle, as on a device: without one it never hears the link is up.
        CompositionLocalProvider(LocalLifecycleOwner provides screenLifecycle) {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    SplitScreen(target, mainChat, sessions, Modifier.fillMaxSize(), pane)
                }
            }
        }
    }

    /** A pane drawn by the real chat screen, as the app draws it. */
    private val realPane: @Composable (ChatTarget, ChatViewModel, Boolean, Boolean, PaneActions) -> Unit =
        { paneTarget, chat, _, keys, actions ->
            ChatScreen(
                target = paneTarget,
                onOpenSidebar = {},
                onNewChat = {},
                onOpenMenu = null,
                split = actions.split,
                onOpenChat = { _, _ -> },
                onSwitchProfile = {},
                viewModel = chat,
                keysEnabled = keys,
            )
        }

    /** What a stand-in pane was told by the screen, by the title of the chat it shows. */
    private class Panes {
        val keys = mutableMapOf<String, Boolean>()
        val main = mutableMapOf<String, Boolean>()
        val splits = mutableMapOf<String, Boolean>()
    }

    /** A pane that tags itself by its chat's title, records what it was told, and offers a split button to press. */
    private fun spyPane(panes: Panes): @Composable (ChatTarget, ChatViewModel, Boolean, Boolean, PaneActions) -> Unit =
        { paneTarget, _, main, keys, actions ->
            val label = paneTarget.title ?: "untitled"
            panes.keys[label] = keys
            panes.main[label] = main
            panes.splits[label] = actions.split.enabled
            Box(Modifier.fillMaxSize().testTag("pane-$label")) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(40.dp)
                        .testTag("split-$label")
                        .clickable { actions.split.onClick() },
                )
            }
        }

    @Test
    fun onePaneShowsTheChatWithItsSplitButtonReady() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        setContent(content(realPane))
        waitForIdle()
        onAllNodesWithContentDescription("Split screen").assertCountEquals(1)
        onNodeWithContentDescription("Split screen").assertIsEnabled()
        // The chat's own top bar is there above the composer, as before the split screen existed.
        onNodeWithText("Main").assertExists()
    }

    @Test
    fun theSplitButtonAddsAPaneThatOffersTheChats() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        setContent(content(realPane))
        waitForIdle()
        onNodeWithContentDescription("Split screen").performClick()
        waitForIdle()
        onNodeWithText("Choose a chat").assertExists()
        // The empty pane lists the gateway's chats, read from the sidebar's view model.
        onNodeWithText("Alpha").assertExists()
        onNodeWithText("Beta").assertExists()
    }

    @Test
    fun pickingAChatShowsItInThatPaneAndGivesItTheFocus() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        val panes = Panes()
        setContent(content(spyPane(panes)))
        waitForIdle()
        onNodeWithTag("split-Main").performClick()
        waitForIdle()
        onNodeWithText("Choose a chat").assertExists()

        onNodeWithText("Beta").performClick()
        waitForIdle()

        onNodeWithTag("pane-Beta").assertExists()
        assertEquals(mapOf("Main" to false, "Beta" to true), panes.keys, "The chat's pane must be the focused one")
        assertEquals(mapOf("Main" to true, "Beta" to false), panes.main)
    }

    @Test
    fun theFourthPaneLeavesNoRoomToSplit() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        val panes = Panes()
        setContent(content(spyPane(panes)))
        waitForIdle()
        assertEquals(mapOf("Main" to true), panes.splits, "One pane on a wide stage may split")

        repeat(3) {
            onNodeWithTag("split-Main").performClick()
            waitForIdle()
        }
        onAllNodesWithContentDescription("Close pane").assertCountEquals(4)
        assertEquals(mapOf("Main" to false), panes.splits, "At four panes there is no room for a fifth")
    }

    @Test
    fun closingAPaneGivesItsRoomToTheOneLeft() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        val panes = Panes()
        setContent(content(spyPane(panes)))
        waitForIdle()
        onNodeWithTag("split-Main").performClick()
        waitForIdle()
        onAllNodesWithContentDescription("Close pane").assertCountEquals(2)
        assertEquals(1300f, onNodeWithTag("pane-Main").getUnclippedBoundsInRoot().right.value, 2f)

        onAllNodesWithContentDescription("Close pane")[1].performClick()
        waitForIdle()

        onAllNodesWithContentDescription("Close pane").assertCountEquals(0)
        val left = onNodeWithTag("pane-Main").getUnclippedBoundsInRoot()
        assertEquals(0f, left.left.value, 2f)
        assertEquals(0f, left.top.value, 2f)
        assertEquals(2600f, left.right.value, 2f)
        assertEquals(800f, left.bottom.value, 2f)
    }

    @Test
    fun aTapMovesTheFocusAndTheKeyboardToThatPane() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        val panes = Panes()
        setContent(content(spyPane(panes)))
        waitForIdle()
        onNodeWithTag("split-Main").performClick()
        waitForIdle()
        onNodeWithText("Beta").performClick()
        waitForIdle()
        assertEquals(mapOf("Main" to false, "Beta" to true), panes.keys)

        onNodeWithTag("pane-Main").performClick()
        waitForIdle()

        assertEquals(mapOf("Main" to true, "Beta" to false), panes.keys, "Only the pane under the focus may type")
    }

    @Test
    fun thePanesComeBackAfterARotation() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        setContent(content(spyPane(Panes())))
        waitForIdle()
        onNodeWithTag("split-Main").performClick()
        waitForIdle()
        onNodeWithText("Beta").performClick()
        waitForIdle()

        // Every pane and its chat is what the saver carries: a rotation restores the same tree.
        val layout = SplitLayout.single(MAIN_PANE, PaneChat("s1", "Main", null))
            .split(MAIN_PANE, SplitAxis.Row, "pane1")!!
            .replace("pane1", PaneChat("b", "Beta", null))
            .split("pane1", SplitAxis.Row, "pane2")!!
            .focus("pane1")
        with(SplitLayout.Saver) { AnyScope.save(layout) }.let { saved ->
            assertEquals(layout, SplitLayout.Saver.restore(saved!!))
        }
    }

    @Test
    fun theComposerSendsFromASinglePane() = runSkikoComposeUiTest(Size(2600f, 800f)) {
        setContent(content(realPane))
        waitForIdle()
        mainChat.composer.setTextAndPlaceCursorAtEnd("hello from the split test")
        waitForIdle()
        onNodeWithContentDescription("Send").performClick()
        waitForIdle()
        assertTrue(
            sent.any { it.contains("prompt.submit") && it.contains("hello from the split test") },
            "The prompt never reached the gateway; sent: $sent",
        )
        assertEquals("", mainChat.composer.text.toString(), "the field is emptied once the prompt is on its way")
    }

    /** The saver only asks whether a value may be written; a `String` always may. */
    private object AnyScope : SaverScope {
        override fun canBeSaved(state: Any): Boolean = true
    }

    companion object {
        /** What the app's own Koin hands out: the pieces of the test that is running. */
        private class Live {
            lateinit var http: HttpClient
            lateinit var connection: GatewayConnection
            lateinit var settings: SettingsStore
            lateinit var mainHost: ChatHost
            lateinit var scope: CoroutineScope
        }

        private val live = Live()
        private val koinLock = Any()

        private fun newChat(host: ChatHost) = ChatViewModel(
            live.connection, host, LastChatStore(InMemoryKeyValueStore()), DraftStore(InMemoryKeyValueStore()),
            ModelsApi(live.connection), MediaApi(live.http), SlashApi(live.connection), SessionsApi(live.http),
            ProfilesApi(live.http), live.settings, PetApi(live.connection), JourneyApi(live.http), AudioApi(live.http),
            NoRecorder, NoPlayer, live.scope, BotsApi(live.connection), { null },
            NoVoiceKeepAlive, NoDeviceDictation, { _, _ -> null },
        )

        /** What the app's modules provide that a pane asks for by itself. */
        private fun screenModule() = module {
            factory { live.connection }
            factory { SessionsApi(live.http) }
            factory<CoroutineScope> { live.scope }
            factory { ToolRiskStore(InMemoryKeyValueStore()) }
            factory { OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 0L } }
            factory { live.settings }
            factory { WallpaperStore(InMemoryBlobFile(), live.scope) }
            factory { ProjectsApi(live.connection) }
            factory { live.mainHost }
            factory { ReplySpeakerSource() }
            viewModel(named(CHAT_WINDOW)) { params -> newChat(params.get()) }
        }
    }
}
