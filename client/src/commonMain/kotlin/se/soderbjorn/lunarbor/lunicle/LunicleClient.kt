/*
 * LunicleClient.kt (commonMain)
 * -----------------------------
 * Typed calls to one connection's Lunicle REST API (LBR-26), over the
 * [LunicleApi] port: `me()`, `projects()`, `board(projectId)`,
 * `issue(issueId)`, `createIssue`, `updateIssue`, `moveIssue`,
 * `addComment`. Every call returns a [LunicleResult]; nothing throws.
 *
 * The requests themselves are built by [LunicleRequests], pure and tested
 * in `LunicleClientTest`, following Lunicle's API rules (its `RestApi.kt`,
 * LNL-222):
 *
 *  - ids appear in paths only (`/projects/{project_id}/issues`), never in a
 *    body — a body repeating a path parameter is a 400;
 *  - statuses, priorities, resolutions, labels, components and assignees are
 *    sent **by name**, under the tools' snake_case argument names;
 *  - an argument the route does not take is a 400, so only fields that are
 *    set are sent — and on an update, *omitted* and *null* differ:
 *    [LunicleField.Omit] leaves a field alone, `LunicleField.Set(null)`
 *    clears it (`assignee: null` unassigns);
 *  - errors are `{error, message}` ([LunicleJson.error]); 404 also means
 *    "not visible"; 403 `insufficient_scope` means a read-only token; the
 *    rate limit is 600 requests a minute per token.
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.lunicle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One field of an update: left alone, or set — possibly to `null`, which
 * clears it where Lunicle allows (`assignee`, `sprint`, `parent_id`).
 */
sealed interface LunicleField<out T> {
    /** Not sent: the issue keeps what it has. */
    data object Omit : LunicleField<Nothing>

    /** Sent as [value]; `null` is sent as JSON null. */
    data class Set<out T>(val value: T) : LunicleField<T>
}

/**
 * A new issue (`create_issue`). Only [title] is required; every `null`
 * field is left out, so Lunicle applies its default (first column, default
 * priority, unassigned).
 *
 * @property title One line.
 * @property description Markdown.
 * @property status A status name; defaults to the first column.
 * @property priority A priority name; defaults to the project's default.
 * @property resolution Needed only when [status] requires one.
 * @property labels Label names.
 * @property components Component names.
 * @property assignee A display name (or account e-mail) from the board's
 *   `assignableUsers`.
 * @property parentId The epic to file it under.
 */
data class LunicleNewIssue(
    val title: String,
    val description: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val resolution: String? = null,
    val labels: List<String>? = null,
    val components: List<String>? = null,
    val assignee: String? = null,
    val parentId: Long? = null,
)

/**
 * Changes to an issue (`update_issue`). Every field defaults to
 * [LunicleField.Omit]; `labels` / `components` replace the whole set.
 *
 * @property title A new title.
 * @property description A new description (Markdown), replacing the old.
 * @property status A status name (prefer [LunicleClient.moveIssue] for a move).
 * @property priority A priority name.
 * @property resolution Needed when the resulting status requires one.
 * @property labels The full set of label names.
 * @property components The full set of component names.
 * @property assignee A name, or `Set(null)` to unassign.
 * @property sprint A sprint name, or `Set(null)` for the backlog.
 * @property parentId An epic's id, or `Set(null)` to detach.
 */
data class LunicleIssueChanges(
    val title: LunicleField<String> = LunicleField.Omit,
    val description: LunicleField<String> = LunicleField.Omit,
    val status: LunicleField<String> = LunicleField.Omit,
    val priority: LunicleField<String> = LunicleField.Omit,
    val resolution: LunicleField<String> = LunicleField.Omit,
    val labels: LunicleField<List<String>> = LunicleField.Omit,
    val components: LunicleField<List<String>> = LunicleField.Omit,
    val assignee: LunicleField<String?> = LunicleField.Omit,
    val sprint: LunicleField<String?> = LunicleField.Omit,
    val parentId: LunicleField<Long?> = LunicleField.Omit,
)

/**
 * One request, ready for [LunicleApi.request].
 *
 * @property method The HTTP method.
 * @property path The full path, `/api/v1/…`.
 * @property query Query parameters.
 * @property body The JSON body, or `null`.
 */
data class LunicleRequest(
    val method: LunicleMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
)

/**
 * Builds Lunicle REST requests (see the file header for the rules).
 * Pure; called by [LunicleClient] and tested directly.
 */
object LunicleRequests {
    /** Lunicle's REST API prefix. */
    const val PREFIX: String = "/api/v1"

    /** `GET /me`: who the token acts as. */
    fun me(): LunicleRequest = LunicleRequest(LunicleMethod.GET, "$PREFIX/me")

    /** `GET /projects` (`list_projects`). */
    fun projects(): LunicleRequest = LunicleRequest(LunicleMethod.GET, "$PREFIX/projects")

    /**
     * `GET /projects/{project_id}/board` (`get_board`).
     *
     * @param status Only this column's issues (the vocabulary comes back in full), or `null` for all.
     */
    fun board(projectId: Long, status: String? = null): LunicleRequest = LunicleRequest(
        LunicleMethod.GET,
        "$PREFIX/projects/$projectId/board",
        query = if (status != null) mapOf("status" to status) else emptyMap(),
    )

    /** `GET /issues/{issue_id}` (`get_issue`). */
    fun issue(issueId: Long): LunicleRequest = LunicleRequest(LunicleMethod.GET, "$PREFIX/issues/$issueId")

    /** `POST /projects/{project_id}/issues` (`create_issue`); unset fields are left out. */
    fun createIssue(projectId: Long, issue: LunicleNewIssue): LunicleRequest {
        val body = LinkedHashMap<String, JsonElement>()
        body["title"] = JsonPrimitive(issue.title)
        issue.description?.let { body["description"] = JsonPrimitive(it) }
        issue.status?.let { body["status"] = JsonPrimitive(it) }
        issue.priority?.let { body["priority"] = JsonPrimitive(it) }
        issue.resolution?.let { body["resolution"] = JsonPrimitive(it) }
        issue.labels?.let { body["labels"] = strings(it) }
        issue.components?.let { body["components"] = strings(it) }
        issue.assignee?.let { body["assignee"] = JsonPrimitive(it) }
        issue.parentId?.let { body["parent_id"] = JsonPrimitive(it) }
        return LunicleRequest(LunicleMethod.POST, "$PREFIX/projects/$projectId/issues", body = JsonObject(body))
    }

    /** `PATCH /issues/{issue_id}` (`update_issue`); [LunicleField.Omit] fields are left out, `Set(null)` sends null. */
    fun updateIssue(issueId: Long, changes: LunicleIssueChanges): LunicleRequest {
        val body = LinkedHashMap<String, JsonElement>()
        fun <T> put(name: String, field: LunicleField<T>, encode: (T & Any) -> JsonElement) {
            if (field is LunicleField.Set) body[name] = field.value?.let(encode) ?: JsonNull
        }
        put("title", changes.title) { JsonPrimitive(it) }
        put("description", changes.description) { JsonPrimitive(it) }
        put("status", changes.status) { JsonPrimitive(it) }
        put("priority", changes.priority) { JsonPrimitive(it) }
        put("resolution", changes.resolution) { JsonPrimitive(it) }
        put("labels", changes.labels) { strings(it) }
        put("components", changes.components) { strings(it) }
        put("assignee", changes.assignee) { JsonPrimitive(it) }
        put("sprint", changes.sprint) { JsonPrimitive(it) }
        put("parent_id", changes.parentId) { JsonPrimitive(it) }
        return LunicleRequest(LunicleMethod.PATCH, "$PREFIX/issues/$issueId", body = JsonObject(body))
    }

    /** `POST /issues/{issue_id}/move` (`move_issue`); [resolution] only when given. */
    fun moveIssue(issueId: Long, status: String, resolution: String? = null): LunicleRequest {
        val body = LinkedHashMap<String, JsonElement>()
        body["status"] = JsonPrimitive(status)
        resolution?.let { body["resolution"] = JsonPrimitive(it) }
        return LunicleRequest(LunicleMethod.POST, "$PREFIX/issues/$issueId/move", body = JsonObject(body))
    }

    /** `POST /issues/{issue_id}/comments` (`add_comment`). */
    fun addComment(issueId: Long, body: String): LunicleRequest = LunicleRequest(
        LunicleMethod.POST,
        "$PREFIX/issues/$issueId/comments",
        body = JsonObject(mapOf("body" to JsonPrimitive(body))),
    )

    private fun strings(list: List<String>): JsonArray = JsonArray(list.map { JsonPrimitive(it) })
}

/**
 * Typed calls to one connection's Lunicle API.
 *
 * Made by [LunicleService.client]; later LBR-25 children (the board node)
 * call it. Stateless apart from its two constructor arguments.
 *
 * @param api The request port (the Electron relay on the desktop).
 * @param connectionId The [LunicleConnection.id] every request goes to.
 */
class LunicleClient(private val api: LunicleApi, val connectionId: String) {

    /** Who the token acts as (the settings dialog's Test). */
    suspend fun me(): LunicleResult<LunicleMe> = send(LunicleRequests.me(), "user", LunicleJson::me)

    /** Every project the token's owner can see. */
    suspend fun projects(): LunicleResult<List<LunicleProject>> =
        send(LunicleRequests.projects(), "project list", LunicleJson::projects)

    /**
     * A project's board.
     *
     * @param status One column only, or `null` for the whole board.
     */
    suspend fun board(projectId: Long, status: String? = null): LunicleResult<LunicleBoard> =
        send(LunicleRequests.board(projectId, status), "board", LunicleJson::board)

    /** One issue in full: description, comments, links, history. */
    suspend fun issue(issueId: Long): LunicleResult<LunicleIssue> =
        send(LunicleRequests.issue(issueId), "issue", LunicleJson::issue)

    /** Files an issue; the answer carries its id and key. */
    suspend fun createIssue(projectId: Long, issue: LunicleNewIssue): LunicleResult<LunicleCreated> =
        send(LunicleRequests.createIssue(projectId, issue), "created issue", LunicleJson::created)

    /** Changes an issue; the answer is Lunicle's sentence. */
    suspend fun updateIssue(issueId: Long, changes: LunicleIssueChanges): LunicleResult<String> =
        send(LunicleRequests.updateIssue(issueId, changes), "answer", ::messageOf)

    /** Moves an issue to [status], with [resolution] when that column requires one. */
    suspend fun moveIssue(issueId: Long, status: String, resolution: String? = null): LunicleResult<String> =
        send(LunicleRequests.moveIssue(issueId, status, resolution), "answer", ::messageOf)

    /** Comments on an issue; the answer carries the comment's id. */
    suspend fun addComment(issueId: Long, body: String): LunicleResult<LunicleCreated> =
        send(LunicleRequests.addComment(issueId, body), "created comment", LunicleJson::created)

    /**
     * Sends [request] and parses a 2xx answer with [parse]; any other status
     * becomes a [LunicleError.Http], an unparsable 2xx a [LunicleError.Malformed].
     */
    private suspend fun <T> send(request: LunicleRequest, what: String, parse: (JsonElement?) -> T?): LunicleResult<T> =
        when (val r = api.request(connectionId, request.method, request.path, request.query, request.body)) {
            is LunicleHttpResponse.TransportError -> LunicleResult.Failure(LunicleError.Transport(r.message))
            is LunicleHttpResponse.Answer ->
                if (r.status in 200..299) {
                    parse(r.json)?.let { LunicleResult.Ok(it) }
                        ?: LunicleResult.Failure(LunicleError.Malformed("Lunicle's $what was not in the expected shape."))
                } else {
                    LunicleResult.Failure(LunicleJson.error(r.status, r.json))
                }
        }

    private fun messageOf(json: JsonElement?): String =
        ((json as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
}
