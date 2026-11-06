package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class KanbanApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KanbanApi(createHttpClient(MockEngine(handler)))

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun boardReadsThePluginRouteUnderTheBoardParam() = runTest {
        var seen: HttpRequestData? = null
        val api = api { request ->
            seen = request
            respond(
                """{"columns":[{"name":"todo","tasks":[]}],"latest_event_id":285,"now":1}""",
                HttpStatusCode.OK, json,
            )
        }

        val board = assertIs<ApiResult.Success<KanbanBoard>>(api.board(url, board = "dev", includeArchived = true)).value

        assertEquals("/api/plugins/kanban/board", seen?.url?.encodedPath)
        assertEquals("board=dev&include_archived=true", seen?.url?.encodedQuery)
        assertEquals(285, board.latestEventId)
    }

    @Test
    fun aMissingBoardParamLeavesTheChoiceToTheServer() = runTest {
        var seen: HttpRequestData? = null
        val api = api { request ->
            seen = request
            respond("""{"columns":[]}""", HttpStatusCode.OK, json)
        }

        api.board(url)

        assertEquals("", seen?.url?.encodedQuery)
    }

    @Test
    fun completeSendsOnlyTheFieldsItMeans() = runTest {
        var body = ""
        val api = api { request ->
            body = request.bodyText()
            respond("""{"task":{"id":"t_1","title":"x","status":"done"}}""", HttpStatusCode.OK, json)
        }

        val task = assertIs<ApiResult.Success<KanbanTask>>(api.complete(url, "t_1", summary = "shipped", board = "dev")).value

        val sent = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("done", sent["status"]?.jsonPrimitive?.content)
        assertEquals("shipped", sent["summary"]?.jsonPrimitive?.content)
        assertFalse("assignee" in sent)
        assertFalse("block_reason" in sent)
        assertEquals("done", task.status)
    }

    @Test
    fun aConflictKeepsTheServerMessage() = runTest {
        val api = api { respond("""{"detail":"cannot reclaim t_1: not in a claimable state"}""", HttpStatusCode.Conflict, json) }

        val result = api.reclaim(url, "t_1", board = "dev")

        val failed = assertIs<ApiResult.Failed>(result)
        assertEquals(409, failed.status)
        assertEquals("cannot reclaim t_1: not in a claimable state", failed.message)
    }

    @Test
    fun aLogTailIsSentAndAMissingLogIsNotAnError() = runTest {
        var seen: HttpRequestData? = null
        val api = api { request ->
            seen = request
            respond("""{"task_id":"t_1","exists":false,"size_bytes":0,"content":"","truncated":false}""", HttpStatusCode.OK, json)
        }

        val log = assertIs<ApiResult.Success<KanbanLog>>(api.log(url, "t_1", tail = 16384, board = "dev")).value

        assertEquals("/api/plugins/kanban/tasks/t_1/log", seen?.url?.encodedPath)
        assertEquals("board=dev&tail=16384", seen?.url?.encodedQuery)
        assertFalse(log.exists)
    }
}
