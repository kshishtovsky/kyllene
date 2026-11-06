package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.util.date.getTimeMillis
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/** One frame of the board's event stream: rows oldest first, [cursor] the id the next connect resumes after. */
data class KanbanFrame(val events: List<KanbanEvent>, val cursor: Long)

/** Server → client, the only shape `/events` sends (§8 of docs/kanban-api.md). */
@Serializable
private data class KanbanFrameDto(val events: List<KanbanEvent> = emptyList(), val cursor: Long = 0)

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * The frame's rows and cursor; null for anything that isn't one. A frame's cursor is the last row's id, and
 * never behind it, so a gateway that omits it still moves the board's cursor forward.
 */
fun parseKanbanFrame(text: String): KanbanFrame? {
    val frame = runCatching { JSON.decodeFromString<KanbanFrameDto>(text) }.getOrNull() ?: return null
    val cursor = maxOf(frame.cursor, frame.events.maxOfOrNull { it.id } ?: 0)
    return if (frame.events.isEmpty() && cursor <= 0) null else KanbanFrame(frame.events, cursor)
}

/** What the board's socket has to say. */
sealed interface KanbanSocketEvent {
    /** New rows for the board, already deduplicated against everything this socket delivered before. */
    data class Frame(val frame: KanbanFrame) : KanbanSocketEvent

    /** The socket dropped, went silent, or could not open; the next attempt is [delayMs] away. */
    data class Retrying(val delayMs: Long) : KanbanSocketEvent
}

/** One board's socket: text frames until it closes. */
interface KanbanEventStream {
    /** The next frame's text, or null once the socket is closed. */
    suspend fun next(): String?

    /** Closes the socket; one the gateway already closed is a no-op. */
    suspend fun close()
}

/** Opens one board's stream; null when the gateway refused a ticket or the handshake. */
fun interface KanbanEventSource {
    suspend fun open(url: GatewayUrl, slug: String, since: Long?): KanbanEventStream?
}

/**
 * The board's live tail. The server pins the board at the handshake and every board has its own event id
 * space, so a cursor belongs to one (gateway, board) pair and is never carried to another.
 *
 * It reconnects by itself: 1, 2, 4, 8, then every 15 s, back to 1 s at the first frame. A socket that has
 * said nothing for [silenceMs] counts as dead — the server sends no keepalive, so a dropped Wi-Fi and an idle
 * board look exactly alike, and reconnecting is how they are told apart.
 */
class KanbanEventsSocket(
    private val source: KanbanEventSource,
    private val firstDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 15_000,
    private val silenceMs: Long = 30_000,
    /** Test seam: the socket's own clock, for tests that can't wait 30 s. */
    private val now: () -> Long = { getTimeMillis() },
) {
    private data class Board(val gateway: String, val slug: String)

    private val cursors = mutableMapOf<Board, Long>()

    // Written by the socket's collector, read by the poller on another thread: the board's freshness has to be seen.
    @Volatile
    private var lastFrameAt = 0L

    /** When a frame was last heard, for the poller that stands in while the socket is away. */
    fun isSilent(at: Long = now()): Boolean = at - lastFrameAt > silenceMs

    /**
     * Frames for [slug] until the collector stops. [tail] is the board's `latest_event_id` as read from REST,
     * so the first connection opens right behind the snapshot instead of replaying the board's history; a
     * tail of 0 means "not read yet" and opens at the server's own tail.
     */
    fun events(url: GatewayUrl, slug: String, tail: Long): Flow<KanbanSocketEvent> = flow {
        val board = Board(url.value, slug)
        var delayMs = firstDelayMs
        while (true) {
            val since = cursors[board] ?: tail.takeIf { it > 0 }
            val stream = try {
                source.open(url, slug, since)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (stream == null) {
                emit(KanbanSocketEvent.Retrying(delayMs))
                delay(delayMs)
                delayMs = nextDelay(delayMs)
                continue
            }
            try {
                while (true) {
                    // Null here: 30 s of silence or a closed socket, both worth a fresh connection.
                    val text = withTimeoutOrNull(silenceMs) { stream.next() } ?: break
                    val frame = parseKanbanFrame(text) ?: continue
                    val seen = cursors[board] ?: 0
                    cursors[board] = maxOf(seen, frame.cursor)
                    lastFrameAt = now()
                    delayMs = firstDelayMs
                    // A reconnect replays what this socket already delivered; only rows past the cursor are new.
                    val fresh = frame.events.filter { it.id > seen }
                    if (fresh.isNotEmpty()) emit(KanbanSocketEvent.Frame(KanbanFrame(fresh, frame.cursor)))
                }
            } finally {
                stream.close()
            }
            emit(KanbanSocketEvent.Retrying(delayMs))
            delay(delayMs)
            delayMs = nextDelay(delayMs)
        }
    }

    private fun nextDelay(delayMs: Long): Long = minOf(delayMs * 2, maxDelayMs)
}

/**
 * [KanbanEventSource] over the gateway's own socket: a fresh single-use ticket per attempt (the old one dies
 * with the connection, in 30 s), then the plugin's `/events` route with the board and the cursor.
 */
class GatewayKanbanEvents(private val client: HttpClient, private val auth: AuthApi) : KanbanEventSource {

    override suspend fun open(url: GatewayUrl, slug: String, since: Long?): KanbanEventStream? {
        val ticket = (auth.mintWsTicket(url) as? ApiResult.Success)?.value?.ticket ?: return null
        val query = buildString {
            append("?board=").append(slug.encodeURLParameter())
            if (since != null) append("&since=").append(since)
            append("&ticket=").append(ticket.encodeURLParameter())
        }
        val session = try {
            client.webSocketSession(url.siblingSocketUrl("api/plugins/kanban/events") + query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A refused ticket (403) arrives here with no body; the caller backs off and mints another.
            return null
        }
        return KtorKanbanEventStream(session.incoming) { session.close() }
    }
}

/** [KanbanEventStream] over a socket's frames; a socket the gateway closed ends the stream, not the caller. */
internal class KtorKanbanEventStream(
    private val incoming: kotlinx.coroutines.channels.ReceiveChannel<Frame>,
    private val closeSocket: suspend () -> Unit,
) : KanbanEventStream {

    override suspend fun next(): String? {
        while (true) {
            val frame = try {
                incoming.receiveCatching().getOrNull()
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            } ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun close() {
        try {
            closeSocket()
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }
}
