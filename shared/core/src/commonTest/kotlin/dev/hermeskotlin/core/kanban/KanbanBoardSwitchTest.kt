package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A board switch is a cache swap, not a merge: each board has its own task ids, so a card of the board left
 * behind must never be drawn under the one picked next.
 */
class KanbanBoardSwitchTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    /** Every board answers with one card named after its slug. */
    private fun repository() = KanbanRepository(
        KanbanApi(
            createHttpClient(
                MockEngine { request ->
                    val board = request.url.parameters["board"]
                    val json =
                        """{"columns":[{"name":"todo","tasks":[{"id":"t_$board","title":"$board","status":"todo"}]}],""" +
                            """"latest_event_id":1}"""
                    respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        ),
    )

    private fun KanbanRepository.cards() = state.value.board.columns.flatMap { it.tasks }.map { it.id }

    @Test
    fun switchingBackAndForthNeverShowsTheOtherBoardsCards() = runTest {
        val repo = repository()
        repo.bind(url, null)

        repo.selectBoard("a")
        assertEquals(listOf("t_a"), repo.cards())

        repo.selectBoard("b")
        assertEquals(listOf("t_b"), repo.cards())

        repo.selectBoard("a")
        assertEquals(listOf("t_a"), repo.cards())
    }
}
