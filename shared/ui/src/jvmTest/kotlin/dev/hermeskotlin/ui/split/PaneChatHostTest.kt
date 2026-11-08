package dev.hermeskotlin.ui.split

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModel
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
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
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.di.CHAT_WINDOW
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A pane runs its chat as a window does: its own host and view model store, and its chat held in the main host. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PaneChatHostTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val scope = CoroutineScope(dispatcher)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private lateinit var http: HttpClient
    private lateinit var sessions: SessionsApi
    private lateinit var connection: GatewayConnection
    private lateinit var settings: SettingsStore
    private lateinit var mainHost: ChatHost
    private lateinit var koin: Koin

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

    /** A gateway socket that says it's ready and answers every call with an empty result. */
    private class ReadyGateway : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val id = Json.parseToJsonElement(text).jsonObject["id"] ?: return
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngineConfig()
        engine.dispatcher = dispatcher
        engine.addHandler { request ->
            if (request.url.encodedPath == "/api/auth/ws-ticket") respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
            else respond("{}", HttpStatusCode.OK, json)
        }
        http = createHttpClient(MockEngine(engine), cookies)
        connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> ReadyGateway() }, scope)
        sessions = SessionsApi(http)
        settings = SettingsStore(InMemoryKeyValueStore(), scope)
        val hostScope = scope
        mainHost = ChatHost(connection, sessions, hostScope)
        // The compose helpers find the container the way the app starts it, so a test starts one too.
        stopKoin()
        koin = startKoin {
            modules(
                module {
                    single { connection }
                    single { sessions }
                    single<CoroutineScope> { hostScope }
                    single { ToolRiskStore(InMemoryKeyValueStore()) }
                    single { OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 0L } }
                    single { settings }
                    // The app's own registration, with the pane's host as the parameter the window's carries.
                    viewModel(named(CHAT_WINDOW)) { params ->
                        ChatViewModel(
                            connection, params.get(), LastChatStore(InMemoryKeyValueStore()),
                            DraftStore(InMemoryKeyValueStore()),
                            ModelsApi(connection), MediaApi(http), SlashApi(connection), sessions, ProfilesApi(http),
                            settings, PetApi(connection), JourneyApi(http), AudioApi(http),
                            NoRecorder, NoPlayer, hostScope, BotsApi(connection), { null },
                            NoVoiceKeepAlive, NoDeviceDictation, { _, _ -> null },
                        )
                    }
                }
            )
        }.koin
    }

    @AfterTest
    fun tearDown() {
        mainHost.close()
        scope.cancel()
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun twoPanesOnOneChatEachRunItThemselves() = runComposeUiTest {
        var panes: Pair<PaneSession, PaneSession>? = null
        setContent {
            val chat = PaneChat("s1", "One", null)
            panes = rememberPaneChatHost("p1", mainHost, koin, gateway, chat) to
                rememberPaneChatHost("p2", mainHost, koin, gateway, chat)
        }
        waitForIdle()
        val (first, second) = assertNotNull(panes)
        assertNotSame(first.host, second.host)
        assertNotSame(first.viewModel, second.viewModel)
        assertNotSame(first.owner, second.owner)
        assertEquals("s1", assertNotNull(first.host.session.value).state.value.storedSessionId)
        assertEquals("s1", assertNotNull(second.host.session.value).state.value.storedSessionId)
    }

    @Test
    fun aPaneHoldsItsChatAndGivesBackEverythingItOwns() = runComposeUiTest {
        val shown = mutableStateOf(true)
        val chat = mutableStateOf(PaneChat("s1", "One", null))
        var pane: PaneSession? = null
        setContent {
            if (shown.value) pane = rememberPaneChatHost("p1", mainHost, koin, gateway, chat.value)
        }
        waitForIdle()
        val open = assertNotNull(pane)
        // The sidebar's watcher must leave this chat alone while the pane has it on screen.
        assertEquals(setOf("s1"), mainHost.sideChats.value)
        val marker = Marker()
        open.owner.viewModelStore.put("marker", marker)

        // Another chat in the same pane takes the host over: the window doesn't rebuild its own either.
        chat.value = PaneChat("s2", "Two", null)
        waitForIdle()
        assertSame(open.host, assertNotNull(pane).host)
        assertEquals("s2", assertNotNull(open.host.session.value).state.value.storedSessionId)
        assertEquals(setOf("s2"), mainHost.sideChats.value)

        shown.value = false
        waitForIdle()
        assertNull(open.host.session.value, "The pane's host is closed with the pane")
        assertTrue(marker.cleared, "The pane's view model store is cleared with the pane")
        assertEquals(emptySet(), mainHost.sideChats.value, "The pane's chat is released with it")
    }

    private class Marker : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }
}
