/*
 * LunicleModels.kt (commonMain)
 * -----------------------------
 * The shapes Lunicle's REST API answers with (LBR-26) — `GET /api/v1/me`,
 * `GET /projects`, `GET /projects/{id}/board`, `GET /issues/{id}`, the
 * created-thing answers of the writes, and the `{error, message}` error body
 * — and their parsers ([LunicleJson]).
 *
 * The shapes mirror Lunicle's `McpTools` (`listProjects`, `getBoard`,
 * `getIssue`; the REST API answers with the same JSON the tools build).
 * Optional fields there are *absent* rather than null (`resolution`,
 * `assignee`, `sprints`, `assignableUsers`, …); here they are nullable or
 * empty, so absent and missing read the same.
 *
 * Parsing is lenient: unknown keys are ignored (a newer Lunicle adds
 * fields), a missing optional field reads as its default, and a malformed
 * entry in a list is skipped rather than failing the whole answer. Only the
 * fields an entry cannot do without (an issue's `id`, a project's `id` and
 * `keyPrefix`) make it malformed. Hand-written over `JsonElement`, like
 * `NewsFeedParser`, since this module applies no serialization compiler
 * plugin.
 *
 * Also the error model ([LunicleError]) and the result wrapper
 * ([LunicleResult]) every client call returns.
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.lunicle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// ── Results and errors ──────────────────────────────────────────────────────

/**
 * What a Lunicle call returns: a value or a [LunicleError]. Never thrown.
 */
sealed interface LunicleResult<out T> {
    /** @property value The parsed answer. */
    data class Ok<out T>(val value: T) : LunicleResult<T>

    /** @property error Why there is no answer. */
    data class Failure(val error: LunicleError) : LunicleResult<Nothing>

    /** The value, or `null` on failure. */
    fun valueOrNull(): T? = (this as? Ok<T>)?.value

    /** The error, or `null` on success. */
    fun errorOrNull(): LunicleError? = (this as? Failure)?.error
}

/** Maps an [LunicleResult.Ok]'s value; a failure passes through. */
inline fun <T, R> LunicleResult<T>.map(transform: (T) -> R): LunicleResult<R> = when (this) {
    is LunicleResult.Ok -> LunicleResult.Ok(transform(value))
    is LunicleResult.Failure -> this
}

/**
 * Why a Lunicle call has no answer. [message] is always a sentence for the
 * user — the sync indicator and the settings dialog show it as it is.
 */
sealed interface LunicleError {
    /** A sentence for the user. */
    val message: String

    /**
     * No HTTP answer (unreachable host, timeout, refused path).
     *
     * @property message The relay's sentence.
     */
    data class Transport(override val message: String) : LunicleError

    /**
     * An HTTP error answer, parsed from Lunicle's `{error, message}` body.
     *
     * @property status The HTTP status.
     * @property code Lunicle's `error` code (`invalid_token`,
     *   `insufficient_scope`, `invalid_request`, `not_found`, `forbidden`,
     *   `refused`, `rate_limited`, `internal_error`), or `""` when the body
     *   had none.
     * @property message Lunicle's `message`, or a generic sentence.
     */
    data class Http(val status: Int, val code: String, override val message: String) : LunicleError {
        /** 403 `insufficient_scope`: the token is read-only, so the board is too. */
        val isReadOnlyToken: Boolean get() = status == 403 && code == "insufficient_scope"

        /** 404: no such thing, or nothing this token may see — Lunicle answers both alike. */
        val isNotFound: Boolean get() = status == 404

        /** 401: the token is invalid, expired or revoked, or API access is off. */
        val isInvalidToken: Boolean get() = status == 401

        /** 429: over the 600-requests-a-minute budget. */
        val isRateLimited: Boolean get() = status == 429
    }

    /**
     * A 2xx answer whose body was not the expected shape.
     *
     * @property message What was wrong.
     */
    data class Malformed(override val message: String) : LunicleError

    /**
     * No connection by that name (or, with no name given, not exactly one connection).
     *
     * @property name The name asked for, or `null` when none was given.
     * @property count How many connections exist.
     */
    data class NoConnection(val name: String?, val count: Int) : LunicleError {
        override val message: String
            get() = when {
                name != null -> "No Lunicle connection is called “$name” (App settings → Lunicle)."
                count == 0 -> "No Lunicle connection is set up (App settings → Lunicle)."
                else -> "There are $count Lunicle connections; name one, as in {{lunicle: <connection>/<KEY>}}."
            }
    }

    /**
     * The connection exists but has no token.
     *
     * @property connectionName Its name.
     */
    data class NoToken(val connectionName: String) : LunicleError {
        override val message: String
            get() = "The Lunicle connection “$connectionName” has no token (App settings → Lunicle)."
    }

    /**
     * No project with that key prefix is visible through the connection.
     *
     * @property key The key prefix asked for, e.g. `FRA`.
     * @property connectionName The connection searched.
     */
    data class NoProject(val key: String, val connectionName: String) : LunicleError {
        override val message: String
            get() = "No project with the key $key is visible through “$connectionName”."
    }
}

// ── Models ───────────────────────────────────────────────────────────────────

/**
 * `GET /api/v1/me`: who a token acts as.
 *
 * @property userId The user's id.
 * @property userName The user's display name.
 * @property tokenName The token's own name, as made in Lunicle.
 * @property tokenScope `read` or `write`.
 * @property tokenExpiresAt When the token expires (epoch ms), or `null` for never.
 */
data class LunicleMe(
    val userId: Long,
    val userName: String,
    val tokenName: String,
    val tokenScope: String,
    val tokenExpiresAt: Long? = null,
) {
    /** A read-only token: boards it shows cannot be edited. */
    val isReadOnly: Boolean get() = tokenScope != "write"
}

/**
 * One entry of `GET /api/v1/projects`.
 *
 * @property id The project's id (paths take it).
 * @property name Its name (may change; nodes name projects by [keyPrefix]).
 * @property keyPrefix The FOO in FOO-123.
 * @property yourRole The token owner's role key there, `""` when not given.
 */
data class LunicleProject(
    val id: Long,
    val name: String,
    val keyPrefix: String,
    val yourRole: String = "",
)

/**
 * A board column.
 *
 * @property name The status name (writes send it).
 * @property requiresResolution Moving an issue here needs a resolution (e.g. Closed).
 */
data class LunicleStatus(val name: String, val requiresResolution: Boolean = false)

/**
 * A sprint of the project.
 *
 * @property name Its name.
 * @property completedAt When it was completed (epoch ms), `null` while open.
 */
data class LunicleSprint(val name: String, val completedAt: Long? = null)

/**
 * A way two issues can be linked.
 *
 * @property name The from-side label (`Blocked by`).
 * @property inverseName The to-side label, `null` for a symmetric kind.
 * @property marksBlocked Whether the link blocks the from-side issue.
 */
data class LunicleRelationKind(val name: String, val inverseName: String? = null, val marksBlocked: Boolean = false)

/**
 * An issue's estimate.
 *
 * @property amount Minutes or points, as [unit] says.
 * @property unit `minutes` or `points` (Lunicle's key, kept as sent).
 */
data class LunicleEstimate(val amount: Long, val unit: String)

/**
 * An issue as `get_board` lists it.
 *
 * @property id Its id (paths take it).
 * @property key `FOO-123`.
 * @property title One line.
 * @property status Its column's name.
 * @property priority Its priority's name.
 * @property resolution Present only in a column that requires one.
 * @property labels Label names.
 * @property components Component names.
 * @property author Who filed it.
 * @property assignee Who holds it, `null` for nobody.
 * @property assigneeIsAgent The work is flagged for the assignee's agent.
 * @property agentName The agent that filed it, if one did.
 * @property estimate Its estimate, if any.
 * @property sprint Its sprint, `null` for the backlog.
 * @property plannedVersion Its planned version, if any.
 * @property fixedVersion Its fixed version, if any.
 * @property parentId The epic it belongs under, if any.
 * @property isBlocked An open issue blocks it.
 * @property blockedBy The keys of the open issues blocking it.
 * @property updatedAt Last touched (epoch ms) — what polling compares.
 * @property canEdit Whether the token's owner may edit it.
 */
data class LunicleBoardIssue(
    val id: Long,
    val key: String,
    val title: String,
    val status: String,
    val priority: String,
    val resolution: String? = null,
    val labels: List<String> = emptyList(),
    val components: List<String> = emptyList(),
    val author: String = "",
    val assignee: String? = null,
    val assigneeIsAgent: Boolean = false,
    val agentName: String? = null,
    val estimate: LunicleEstimate? = null,
    val sprint: String? = null,
    val plannedVersion: String? = null,
    val fixedVersion: String? = null,
    val parentId: Long? = null,
    val isBlocked: Boolean = false,
    val blockedBy: List<String> = emptyList(),
    val updatedAt: Long = 0,
    val canEdit: Boolean = false,
)

/**
 * `GET /api/v1/projects/{id}/board`: the project's vocabulary and its issues.
 *
 * @property project The project.
 * @property statuses The columns, in board order.
 * @property priorities Priority names, in the project's order (highest first).
 * @property resolutions Resolution names.
 * @property labels Label names.
 * @property components Component names.
 * @property sprints The sprints, empty for a project without any.
 * @property activeSprint The active sprint's name, if any.
 * @property versions Version names, empty for a project without any.
 * @property relationKinds Link kinds, empty for a project without any.
 * @property estimateMode `none`, `time` or `points`.
 * @property assignableUsers Who `assignee` accepts here (display names,
 *   sorted) — `null` when absent: an older Lunicle (before LNL-223), or a
 *   token whose owner may not create issues on this board.
 * @property issues Every issue, in the board's own order.
 */
data class LunicleBoard(
    val project: LunicleProject,
    val statuses: List<LunicleStatus>,
    val priorities: List<String>,
    val resolutions: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val components: List<String> = emptyList(),
    val sprints: List<LunicleSprint> = emptyList(),
    val activeSprint: String? = null,
    val versions: List<String> = emptyList(),
    val relationKinds: List<LunicleRelationKind> = emptyList(),
    val estimateMode: String = "none",
    val assignableUsers: List<String>? = null,
    val issues: List<LunicleBoardIssue> = emptyList(),
)

/**
 * A comment on an issue.
 *
 * @property id Its id.
 * @property body Markdown.
 * @property author Who wrote it.
 * @property agentName The agent that wrote it, if one did.
 * @property createdAt When (epoch ms).
 */
data class LunicleComment(
    val id: Long,
    val body: String,
    val author: String = "",
    val agentName: String? = null,
    val createdAt: Long = 0,
)

/**
 * Another issue an issue points at (its parent, a child, a link's far end).
 *
 * @property id Its id.
 * @property key `FOO-9`.
 * @property title Its title.
 * @property label For a link, this side's wording (`Blocked by`); `null` otherwise.
 * @property relationId For a link, the link's own id; `null` otherwise.
 */
data class LunicleIssueRef(
    val id: Long,
    val key: String,
    val title: String,
    val label: String? = null,
    val relationId: Long? = null,
)

/**
 * One entry of an issue's history.
 *
 * @property id The event's id.
 * @property kind `CREATED`, `TITLE_CHANGED`, `STATUS_CHANGED`, … (newer builds add kinds).
 * @property value The new value, for the kinds that carry one.
 * @property values The whole set afterwards, for label / component changes.
 * @property author Who made the change.
 * @property agentName The agent that made it, if one did.
 * @property viaToken The personal access token used, if one was.
 * @property createdAt When (epoch ms).
 */
data class LunicleHistoryEvent(
    val id: Long,
    val kind: String,
    val value: String? = null,
    val values: List<String> = emptyList(),
    val author: String = "",
    val agentName: String? = null,
    val viaToken: String? = null,
    val createdAt: Long = 0,
)

/**
 * `GET /api/v1/issues/{id}`: one issue in full.
 *
 * @property summary The fields it shares with a board entry.
 * @property projectId The project's id.
 * @property description Markdown.
 * @property createdAt When it was filed (epoch ms).
 * @property canComment Whether the token's owner may comment.
 * @property watchers Who watches it, by name.
 * @property parent The epic it belongs under, if any.
 * @property children Its children, in work order (an epic's).
 * @property relations Its links, worded from its side.
 * @property comments Its comments, oldest first.
 * @property history What changed, oldest first.
 */
data class LunicleIssue(
    val summary: LunicleBoardIssue,
    val projectId: Long,
    val description: String = "",
    val createdAt: Long = 0,
    val canComment: Boolean = false,
    val watchers: List<String> = emptyList(),
    val parent: LunicleIssueRef? = null,
    val children: List<LunicleIssueRef> = emptyList(),
    val relations: List<LunicleIssueRef> = emptyList(),
    val comments: List<LunicleComment> = emptyList(),
    val history: List<LunicleHistoryEvent> = emptyList(),
) {
    /** See [LunicleBoardIssue.id]. */
    val id: Long get() = summary.id

    /** See [LunicleBoardIssue.key]. */
    val key: String get() = summary.key
}

/**
 * What a create answers with (`create_issue`, `add_comment`): the sentence
 * and the new ids.
 *
 * @property id The new thing's id.
 * @property key The new issue's key (`FOO-124`); `null` for a comment.
 * @property message Lunicle's sentence ("Created FOO-124 …").
 */
data class LunicleCreated(val id: Long, val key: String? = null, val message: String = "")

// ── Parsing ──────────────────────────────────────────────────────────────────

/**
 * Lenient parsers for the models above. Each returns `null` when the JSON
 * is not the shape at all; inside lists, malformed entries are skipped.
 *
 * Called by [LunicleClient]; tested in `LunicleModelsTest`.
 */
object LunicleJson {

    /** `GET /me`. */
    fun me(json: JsonElement?): LunicleMe? {
        val o = json as? JsonObject ?: return null
        val user = o["user"] as? JsonObject ?: return null
        val token = o["token"] as? JsonObject
        return LunicleMe(
            userId = user.long("id") ?: return null,
            userName = user.string("name").orEmpty(),
            tokenName = token?.string("name").orEmpty(),
            tokenScope = token?.string("scope").orEmpty(),
            tokenExpiresAt = token?.long("expiresAt"),
        )
    }

    /** `GET /projects`. */
    fun projects(json: JsonElement?): List<LunicleProject>? =
        (json as? JsonArray)?.mapNotNull { project(it) }

    /** One project object (also `get_board`'s `project`). */
    fun project(json: JsonElement?): LunicleProject? {
        val o = json as? JsonObject ?: return null
        return LunicleProject(
            id = o.long("id") ?: return null,
            name = o.string("name").orEmpty(),
            keyPrefix = o.string("keyPrefix")?.takeIf { it.isNotEmpty() } ?: return null,
            yourRole = o.string("yourRole").orEmpty(),
        )
    }

    /** `GET /projects/{id}/board`. */
    fun board(json: JsonElement?): LunicleBoard? {
        val o = json as? JsonObject ?: return null
        val project = project(o["project"]) ?: return null
        return LunicleBoard(
            project = project,
            statuses = o.array("statuses").mapNotNull { s ->
                when (s) {
                    is JsonObject -> s.string("name")?.let { LunicleStatus(it, s.bool("requiresResolution") ?: false) }
                    is JsonPrimitive -> s.contentOrNull?.let { LunicleStatus(it) }
                    else -> null
                }
            },
            priorities = o.strings("priorities"),
            resolutions = o.strings("resolutions"),
            labels = o.strings("labels"),
            components = o.strings("components"),
            sprints = o.array("sprints").mapNotNull { s ->
                (s as? JsonObject)?.string("name")?.let { LunicleSprint(it, s.long("completedAt")) }
            },
            activeSprint = o.string("activeSprint"),
            versions = o.strings("versions"),
            relationKinds = o.array("relationKinds").mapNotNull { k ->
                (k as? JsonObject)?.string("name")?.let {
                    LunicleRelationKind(it, k.string("inverseName"), k.bool("marksBlocked") ?: false)
                }
            },
            estimateMode = o.string("estimateMode") ?: "none",
            assignableUsers = (o["assignableUsers"] as? JsonArray)?.mapNotNull { u ->
                when (u) {
                    is JsonObject -> u.string("name")
                    is JsonPrimitive -> u.contentOrNull
                    else -> null
                }
            },
            issues = o.array("issues").mapNotNull { boardIssue(it) },
        )
    }

    /** One issue of a board (or the shared fields of a full issue). */
    fun boardIssue(json: JsonElement?): LunicleBoardIssue? {
        val o = json as? JsonObject ?: return null
        return LunicleBoardIssue(
            id = o.long("id") ?: return null,
            key = o.string("key").orEmpty(),
            title = o.string("title").orEmpty(),
            status = o.string("status").orEmpty(),
            priority = o.string("priority").orEmpty(),
            resolution = o.string("resolution"),
            labels = o.strings("labels"),
            components = o.strings("components"),
            author = o.string("author").orEmpty(),
            assignee = o.string("assignee"),
            assigneeIsAgent = o.bool("assigneeIsAgent") ?: false,
            agentName = o.string("agentName"),
            estimate = (o["estimate"] as? JsonObject)?.let { e ->
                e.long("amount")?.let { LunicleEstimate(it, e.string("unit").orEmpty()) }
            },
            sprint = o.string("sprint"),
            plannedVersion = o.string("plannedVersion"),
            fixedVersion = o.string("fixedVersion"),
            parentId = o.long("parentId"),
            isBlocked = o.bool("isBlocked") ?: false,
            blockedBy = o.strings("blockedBy"),
            updatedAt = o.long("updatedAt") ?: 0,
            canEdit = o.bool("canEdit") ?: false,
        )
    }

    /** `GET /issues/{id}`. */
    fun issue(json: JsonElement?): LunicleIssue? {
        val o = json as? JsonObject ?: return null
        val summary = boardIssue(o) ?: return null
        return LunicleIssue(
            summary = summary,
            projectId = o.long("projectId") ?: 0,
            description = o.string("description").orEmpty(),
            createdAt = o.long("createdAt") ?: 0,
            canComment = o.bool("canComment") ?: false,
            watchers = o.strings("watchers"),
            parent = ref(o["parent"]),
            children = o.array("children").mapNotNull { ref(it) },
            relations = o.array("relations").mapNotNull { ref(it) },
            comments = o.array("comments").mapNotNull { c ->
                (c as? JsonObject)?.long("id")?.let {
                    LunicleComment(it, c.string("body").orEmpty(), c.string("author").orEmpty(), c.string("agentName"), c.long("createdAt") ?: 0)
                }
            },
            history = o.array("history").mapNotNull { h ->
                (h as? JsonObject)?.long("id")?.let {
                    LunicleHistoryEvent(
                        id = it,
                        kind = h.string("kind").orEmpty(),
                        value = h.string("value"),
                        values = h.strings("values"),
                        author = h.string("author").orEmpty(),
                        agentName = h.string("agentName"),
                        viaToken = h.string("viaToken"),
                        createdAt = h.long("createdAt") ?: 0,
                    )
                }
            },
        )
    }

    /** A create's answer: `{message, id, key?}` (`create_issue`) or `{message, id, issueId}` (`add_comment`). */
    fun created(json: JsonElement?): LunicleCreated? {
        val o = json as? JsonObject ?: return null
        return LunicleCreated(o.long("id") ?: return null, o.string("key"), o.string("message").orEmpty())
    }

    /**
     * An error answer as a [LunicleError.Http]: Lunicle's `{error, message}`
     * body when there is one, else a generic sentence for [status].
     */
    fun error(status: Int, json: JsonElement?): LunicleError.Http {
        val o = json as? JsonObject
        val code = o?.string("error").orEmpty()
        val message = o?.string("message")?.takeIf { it.isNotBlank() } ?: when (status) {
            401 -> "This token is not valid. It may have expired or been revoked."
            403 -> "This token may not do that."
            404 -> "Not found, or not visible to this token."
            429 -> "Too many requests to Lunicle. Try again in a minute."
            in 500..599 -> "Lunicle had a problem ($status)."
            else -> "Lunicle refused the request ($status)."
        }
        return LunicleError.Http(status, code, message)
    }

    private fun ref(json: JsonElement?): LunicleIssueRef? {
        val o = json as? JsonObject ?: return null
        return LunicleIssueRef(
            id = o.long("id") ?: return null,
            key = o.string("key").orEmpty(),
            title = o.string("title").orEmpty(),
            label = o.string("label"),
            relationId = o.long("relationId"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.strings(key: String): List<String> =
        array(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
}
