/* LunicleClientTest.kt (commonTest)
 *
 * Pins how Lunicle requests are built and answered (LBR-26): ids in paths
 * only, vocabulary by name under snake_case argument names, omitted fields
 * left out and `Set(null)` sent as null (`assignee: null` unassigns), and
 * how statuses map onto results and errors — through a fake [LunicleApi]. */
package se.soderbjorn.lunarbor.lunicle

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [LunicleApi] answering from [routes] (`"GET /api/v1/projects"` → response)
 * and recording every request it is sent.
 */
class FakeLunicleApi(val routes: MutableMap<String, LunicleHttpResponse> = mutableMapOf()) : LunicleApi {
    /** Every request sent, in order. */
    val sent = ArrayList<Pair<String, LunicleRequest>>()

    override suspend fun request(
        connectionId: String,
        method: LunicleMethod,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): LunicleHttpResponse {
        sent += connectionId to LunicleRequest(method, path, query, body)
        return routes["$method $path"] ?: LunicleHttpResponse.Answer(404, Json.parseToJsonElement("""{"error":"not_found","message":"No such thing."}"""))
    }

    /** Answers [method] [path] with [status] and the JSON [text]. */
    fun answer(method: LunicleMethod, path: String, status: Int, text: String) {
        routes["$method $path"] = LunicleHttpResponse.Answer(status, Json.parseToJsonElement(text))
    }
}

class LunicleClientTest {

    private fun json(text: String): JsonObject? = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun reads_put_ids_in_paths() {
        assertEquals(LunicleRequest(LunicleMethod.GET, "/api/v1/me"), LunicleRequests.me())
        assertEquals(LunicleRequest(LunicleMethod.GET, "/api/v1/projects"), LunicleRequests.projects())
        assertEquals(LunicleRequest(LunicleMethod.GET, "/api/v1/projects/2/board"), LunicleRequests.board(2))
        assertEquals(mapOf("status" to "In progress"), LunicleRequests.board(2, "In progress").query)
        assertEquals(LunicleRequest(LunicleMethod.GET, "/api/v1/issues/774"), LunicleRequests.issue(774))
    }

    @Test
    fun create_sends_names_and_leaves_unset_fields_out() {
        val r = LunicleRequests.createIssue(
            2,
            LunicleNewIssue(title = "New one", status = "In progress", priority = "High", labels = listOf("manager"), assignee = "Linus", parentId = 700),
        )
        assertEquals(LunicleMethod.POST, r.method)
        assertEquals("/api/v1/projects/2/issues", r.path)
        assertEquals(
            json("""{ "title": "New one", "status": "In progress", "priority": "High", "labels": ["manager"], "assignee": "Linus", "parent_id": 700 }"""),
            r.body,
        )
        // No project_id in the body: it is in the path, and Lunicle refuses a repeat.
        assertEquals(json("""{ "title": "Bare" }"""), LunicleRequests.createIssue(2, LunicleNewIssue("Bare")).body)
    }

    @Test
    fun update_distinguishes_omitted_from_null() {
        val unassign = LunicleRequests.updateIssue(774, LunicleIssueChanges(assignee = LunicleField.Set(null)))
        assertEquals(LunicleMethod.PATCH, unassign.method)
        assertEquals("/api/v1/issues/774", unassign.path)
        assertEquals(JsonObject(mapOf("assignee" to JsonNull)), unassign.body)

        val retitle = LunicleRequests.updateIssue(
            774,
            LunicleIssueChanges(
                title = LunicleField.Set("Better title"),
                priority = LunicleField.Set("Low"),
                labels = LunicleField.Set(emptyList()),
                parentId = LunicleField.Set(null),
                sprint = LunicleField.Set("Sprint 4"),
            ),
        )
        assertEquals(
            json("""{ "title": "Better title", "priority": "Low", "labels": [], "sprint": "Sprint 4", "parent_id": null }"""),
            retitle.body,
        )
        // Nothing set: an empty body, never a field the caller did not mean.
        assertEquals(JsonObject(emptyMap()), LunicleRequests.updateIssue(774, LunicleIssueChanges()).body)
    }

    @Test
    fun move_and_comment() {
        assertEquals(
            LunicleRequest(LunicleMethod.POST, "/api/v1/issues/774/move", body = json("""{ "status": "Closed", "resolution": "Done" }""")),
            LunicleRequests.moveIssue(774, "Closed", "Done"),
        )
        assertEquals(json("""{ "status": "New" }"""), LunicleRequests.moveIssue(774, "New").body)
        assertEquals(
            LunicleRequest(LunicleMethod.POST, "/api/v1/issues/774/comments", body = json("""{ "body": "Looks good" }""")),
            LunicleRequests.addComment(774, "Looks good"),
        )
    }

    @Test
    fun answers_become_results() = runTest {
        val api = FakeLunicleApi()
        val client = LunicleClient(api, "c1")
        api.answer(LunicleMethod.POST, "/api/v1/projects/2/issues", 201, """{ "message": "Created FRA-14 (issue id 781): T", "id": 781, "key": "FRA-14" }""")
        api.answer(LunicleMethod.POST, "/api/v1/issues/774/move", 200, """{ "message": "Moved issue 774 to Closed." }""")
        api.answer(LunicleMethod.PATCH, "/api/v1/issues/774", 403, """{ "error": "insufficient_scope", "message": "This token is read-only." }""")
        api.answer(LunicleMethod.GET, "/api/v1/projects", 200, """{ "not": "a list" }""")
        api.routes["GET /api/v1/me"] = LunicleHttpResponse.TransportError("Could not reach https://x (no connection within 8 s).")

        assertEquals(LunicleCreated(781, "FRA-14", "Created FRA-14 (issue id 781): T"), client.createIssue(2, LunicleNewIssue("T")).valueOrNull())
        assertEquals("Moved issue 774 to Closed.", client.moveIssue(774, "Closed", "Done").valueOrNull())

        val readOnly = client.updateIssue(774, LunicleIssueChanges(title = LunicleField.Set("x"))).errorOrNull()
        assertIs<LunicleError.Http>(readOnly)
        assertTrue(readOnly.isReadOnlyToken)

        assertIs<LunicleError.Malformed>(client.projects().errorOrNull())
        assertEquals(LunicleError.Transport("Could not reach https://x (no connection within 8 s)."), client.me().errorOrNull())

        val missing = client.issue(1).errorOrNull()
        assertIs<LunicleError.Http>(missing)
        assertTrue(missing.isNotFound)
        assertNull(client.issue(1).valueOrNull())
        assertTrue(api.sent.all { it.first == "c1" })
    }
}
