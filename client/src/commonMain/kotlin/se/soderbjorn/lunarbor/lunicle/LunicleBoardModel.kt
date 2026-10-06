/*
 * LunicleBoardModel.kt (commonMain)
 * ---------------------------------
 * The pure half of board nodes (LBR-27): what [LunicleBoards] caches per
 * board, and the rules that turn it into rows and words — none of which
 * touch the network, so all of it is tested directly
 * (`LunicleBoardModelTest`):
 *
 *  - [LunicleBoardKey] / [LunicleBoardState]: one board node's reference
 *    and what is known about it (target, board, issue details, sync).
 *  - [LunicleBoardLayout]: columns in board order with the ones that need
 *    a resolution last (folded by default), issues by priority (the
 *    board's own order within a priority), the pills, the sync indicator,
 *    error sentences, relative times and the issue's web link.
 *  - [LunicleBoardDiff]: what a re-read changed (changed / new / gone
 *    issues), which issues to re-fetch in full, and the remote-change
 *    notice ("Linus commented on Lönerapporter", "3 issues changed").
 *  - [LunicleStreamMessage]: one message of a connection's change stream
 *    (Lunicle's SSE, LNL-224), parsed from what the main process relays.
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.lunicle

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import se.soderbjorn.lunarbor.data.LunicleNodeRef

/**
 * A board node's reference, normalized: what [LunicleBoards] caches by.
 * Two nodes naming the same board the same way share one cache entry
 * (and one poll), in any pane.
 *
 * @property connection The connection's name, lower-cased, or `null` for
 *   "the only connection".
 * @property key The project's key prefix, upper-cased; `""` for a
 *   malformed reference.
 */
data class LunicleBoardKey(val connection: String?, val key: String) {
    /** `<connection>/<KEY>` (or `/<KEY>`): the prefix of the pane's fold keys. */
    val id: String get() = "${connection.orEmpty()}/$key"

    companion object {
        /** The key of [ref]. */
        fun of(ref: LunicleNodeRef): LunicleBoardKey = LunicleBoardKey(ref.connection?.lowercase(), ref.key.uppercase())
    }
}

/**
 * What [LunicleBoards] knows about one board.
 *
 * @property key The board's key.
 * @property target The connection and project the key resolved to, or
 *   `null` before it has (or when it could not).
 * @property board The last board read, kept under an error so it can be
 *   shown dimmed; `null` before the first good read.
 * @property details Issue id → the issue in full (description, comments),
 *   for issues some pane has unfolded (and, once read, kept until the
 *   issue changes without being unfolded).
 * @property syncedAt When the last good read finished (epoch ms), or `null`.
 * @property live `true` while the connection's change stream is connected.
 * @property error Why the last read failed, or `null` after a good one.
 * @property notice The remote-change notice ([LunicleBoardDiff.remoteMessage]),
 *   shown until [noticeUntil].
 * @property noticeUntil When [notice] stops showing (epoch ms).
 * @property writes How many writes of this board are under way (LBR-29):
 *   the indicator says "Saving to Lunicle…" while there are any ([saving]).
 * @property loadingIssues Issue ids whose full read is under way.
 * @property titleEdits Issue id → the title a write under way is setting
 *   (optimistic, LBR-29): shown in place of the board's title until the
 *   write answers; dropped on failure, so the old title comes back.
 * @property creating Issues being created from drafts (LBR-29), shown in
 *   place until the board lists them ([LunicleCreatingIssue.createdId]).
 * @property createdIds A created draft's local id → the new issue's id, so
 *   drafts anchored after it find it once it is a real issue.
 * @property alert A write's error, or why the board cannot be edited
 *   ("This token is read-only…"), shown in red until [alertUntil].
 * @property alertUntil When [alert] stops showing (epoch ms).
 * @property savedAt When the last write succeeded (epoch ms): the
 *   indicator says "Synced just now" for [LunicleBoardLayout.SAVED_MS].
 * @property readOnlyToken `true` when the connection's token is read-only
 *   (`GET /me` scope, or a write answered 403 `insufficient_scope`).
 * @property propertyEdits Status, priority and assignee changes whose
 *   writes are under way (optimistic, LBR-30), oldest first: shown over
 *   the board's values ([shown]) until each write answers; dropped on
 *   failure, so the old value comes back.
 */
data class LunicleBoardState(
    val key: LunicleBoardKey,
    val target: LunicleBoardTarget? = null,
    val board: LunicleBoard? = null,
    val details: Map<Long, LunicleIssue> = emptyMap(),
    val syncedAt: Long? = null,
    val live: Boolean = false,
    val error: LunicleError? = null,
    val notice: String? = null,
    val noticeUntil: Long = 0,
    val writes: Int = 0,
    val loadingIssues: Set<Long> = emptySet(),
    val titleEdits: Map<Long, String> = emptyMap(),
    val creating: List<LunicleCreatingIssue> = emptyList(),
    val createdIds: Map<Long, Long> = emptyMap(),
    val alert: String? = null,
    val alertUntil: Long = 0,
    val savedAt: Long? = null,
    val readOnlyToken: Boolean = false,
    val propertyEdits: List<LunicleIssueEdit> = emptyList(),
) {
    /**
     * [issue] as the board shows it: with the title ([titleEdits]) and the
     * status, priority and assignee ([propertyEdits]) of writes under way.
     */
    fun shown(issue: LunicleBoardIssue): LunicleBoardIssue {
        var out = titleEdits[issue.id]?.let { issue.copy(title = it) } ?: issue
        for (edit in propertyEdits) if (edit.issueId == issue.id) out = edit.applyTo(out)
        return out
    }

    /** `true` while a write of this board is under way. */
    val saving: Boolean get() = writes > 0

    /**
     * Whether new issues can be filed on this board: not with a read-only
     * token, nor as a project viewer (Lunicle's `yourRole`).
     */
    val canCreate: Boolean
        get() = !readOnlyToken && (target?.project?.yourRole ?: board?.project?.yourRole).orEmpty() != "viewer"

    /** Whether [issue]'s title can be changed: its `canEdit`, and a write token. */
    fun canEdit(issue: LunicleBoardIssue): Boolean = issue.canEdit && !readOnlyToken
}

/**
 * Where a new issue's row sits in its column until the board lists it
 * (LBR-29): a draft's place, and the place of the issue it became while
 * Lunicle files it.
 */
sealed interface LunicleDraftAnchor {
    /** At the top of the column (Enter on the column's name). */
    data object Top : LunicleDraftAnchor

    /** At the end of the column (the "New issue" line). */
    data object End : LunicleDraftAnchor

    /** Right below the issue [issueId] (Enter on its title). */
    data class AfterIssue(val issueId: Long) : LunicleDraftAnchor

    /** Right below another new issue, by its local id (Enter on a draft). */
    data class AfterLocal(val localId: Long) : LunicleDraftAnchor
}

/**
 * A draft that has been sent to Lunicle (LBR-29): shown in place, with its
 * title, until the board lists the new issue.
 *
 * @property localId The draft's local id (negative, from
 *   [LunicleBoards.newLocalId]; unique app-wide).
 * @property status The column it is filed in.
 * @property priority Its priority, or `null` for the project's default.
 * @property title The title sent.
 * @property anchor Where its row sits.
 * @property createdId The new issue's id, once Lunicle answered.
 * @property createdKey The new issue's key (`FRA-12`), once Lunicle answered.
 */
data class LunicleCreatingIssue(
    val localId: Long,
    val status: String,
    val priority: String?,
    val title: String,
    val anchor: LunicleDraftAnchor,
    val createdId: Long? = null,
    val createdKey: String? = null,
)

/**
 * One status, priority or assignee change of an issue whose write is under
 * way (LBR-30), shown at once ([LunicleBoardState.shown]).
 *
 * @property seq Its number, unique per [LunicleBoards]; its write removes
 *   exactly this entry when it answers.
 * @property issueId The issue.
 * @property field Which property.
 * @property value The new status or priority name, or assignee name
 *   (`null`: nobody).
 * @property resolution For a move into a column that requires one, the
 *   resolution sent with it.
 */
data class LunicleIssueEdit(
    val seq: Long,
    val issueId: Long,
    val field: LuniclePill.Field,
    val value: String?,
    val resolution: String? = null,
) {
    /** [issue] with this change made. A move clears the resolution unless it sends one. */
    fun applyTo(issue: LunicleBoardIssue): LunicleBoardIssue = when (field) {
        LuniclePill.Field.STATUS -> issue.copy(status = value ?: issue.status, resolution = resolution)
        LuniclePill.Field.PRIORITY -> issue.copy(priority = value ?: issue.priority)
        LuniclePill.Field.ASSIGNEE -> issue.copy(assignee = value)
    }
}

/** The sync indicator's colour (theme variables in the view). */
enum class LunicleSyncKind {
    /** Before the first read. Dim. */
    LOADING,

    /** Read by polling: "Synced just now", "Synced 12s ago". Green. */
    SYNCED,

    /** The change stream is connected: "Live". Green. */
    LIVE,

    /** "Saving to Lunicle…". Amber. */
    SAVING,

    /** A remote change, briefly. Blue (the accent). */
    REMOTE,

    /** The last read failed. Red. */
    ERROR,
}

/**
 * The sync indicator on a board node's line.
 *
 * @property kind Its colour.
 * @property text What it says; for [LunicleSyncKind.SYNCED] the text at
 *   the moment it was made — the view keeps it current from [syncedAt].
 * @property syncedAt For [LunicleSyncKind.SYNCED], when the board was read.
 */
data class LunicleSyncLine(val kind: LunicleSyncKind, val text: String, val syncedAt: Long? = null)

/**
 * One column of a board as the node lists it.
 *
 * @property status The column.
 * @property issues Its issues, by priority ([LunicleBoardLayout.sortIssues]).
 * @property foldedByDefault `true` for a column that needs a resolution
 *   (e.g. Closed): it starts folded.
 */
data class LunicleColumn(val status: LunicleStatus, val issues: List<LunicleBoardIssue>, val foldedByDefault: Boolean) {
    /** How many issues it holds. */
    val count: Int get() = issues.size
}

/** One pill on an issue's line: `#status`, `#priority` or `@assignee`. */
data class LuniclePill(val text: String, val field: Field) {
    /** Which property a pill shows. */
    enum class Field { STATUS, PRIORITY, ASSIGNEE }
}

/** Rows, words and links for a board (see the file header). */
object LunicleBoardLayout {

    /**
     * The board's columns in board order, the ones that need a resolution
     * moved to the end (in their own order), each with its issues sorted
     * by priority. Issues of a status the board does not list are left out.
     */
    fun columns(board: LunicleBoard): List<LunicleColumn> {
        val byStatus = board.issues.groupBy { it.status }
        val (closing, open) = board.statuses.partition { it.requiresResolution }
        return (open + closing).map { status ->
            LunicleColumn(status, sortIssues(byStatus[status.name].orEmpty(), board.priorities), status.requiresResolution)
        }
    }

    /**
     * [issues] by priority, highest first ([priorities] is the project's
     * order, highest first); a priority the list does not know goes last.
     * Within a priority the board's own order is kept (the sort is stable).
     */
    fun sortIssues(issues: List<LunicleBoardIssue>, priorities: List<String>): List<LunicleBoardIssue> =
        issues.sortedBy { rank(it.priority, priorities) }

    private fun rank(priority: String, priorities: List<String>): Int =
        priorities.indexOf(priority).let { if (it < 0) priorities.size else it }

    /** `true` when [issue] has the project's highest priority (the folded line shows its pill). */
    fun isHighestPriority(issue: LunicleBoardIssue, priorities: List<String>): Boolean =
        priorities.isNotEmpty() && issue.priority == priorities.first()

    /** A property as a pill's text: `#` (or `@`) + the name lower-cased, spaces as `-`. */
    fun pillText(prefix: Char, name: String): String = prefix + name.trim().lowercase().replace(Regex("\\s+"), "-")

    /**
     * The pills of an issue's line: folded, only `#<priority>` when it is
     * the highest; unfolded, `#<status> #<priority> @<assignee>`
     * (`@nobody` when unassigned).
     */
    fun pills(issue: LunicleBoardIssue, priorities: List<String>, unfolded: Boolean): List<LuniclePill> =
        if (!unfolded) {
            if (isHighestPriority(issue, priorities)) listOf(LuniclePill(pillText('#', issue.priority), LuniclePill.Field.PRIORITY)) else emptyList()
        } else {
            listOf(
                LuniclePill(pillText('#', issue.status), LuniclePill.Field.STATUS),
                LuniclePill(pillText('#', issue.priority), LuniclePill.Field.PRIORITY),
                LuniclePill(pillText('@', issue.assignee ?: "nobody"), LuniclePill.Field.ASSIGNEE),
            )
        }

    /** "1 comment", "N comments", or `null` for none. */
    fun commentsLabel(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 comment"
        else -> "$count comments"
    }

    /**
     * The indicator for [state] at [now]: an error first (red), then a
     * write under way (amber), a fresh remote notice (blue), "Live" while
     * the stream is connected, "Synced …" after a read, else "Loading…".
     */
    fun syncLine(state: LunicleBoardState, now: Long): LunicleSyncLine {
        state.error?.let { return LunicleSyncLine(LunicleSyncKind.ERROR, errorText(it)) }
        val alert = state.alert
        if (alert != null && now < state.alertUntil) return LunicleSyncLine(LunicleSyncKind.ERROR, alert)
        if (state.saving) return LunicleSyncLine(LunicleSyncKind.SAVING, "Saving to Lunicle…")
        // Right after a write: "Synced just now", even while the stream is live.
        val saved = state.savedAt
        if (saved != null && now - saved < SAVED_MS) {
            return LunicleSyncLine(LunicleSyncKind.SYNCED, "Synced just now", syncedAt = if (state.live) null else saved)
        }
        val notice = state.notice
        if (notice != null && now < state.noticeUntil) return LunicleSyncLine(LunicleSyncKind.REMOTE, notice)
        val at = state.syncedAt ?: return LunicleSyncLine(LunicleSyncKind.LOADING, "Loading…")
        if (state.live) return LunicleSyncLine(LunicleSyncKind.LIVE, "Live")
        return LunicleSyncLine(LunicleSyncKind.SYNCED, syncedText(at, now), at)
    }

    /** "Synced just now" (under 5 s), "Synced 12s ago", "Synced 3 min ago", "Synced 2 h ago". */
    fun syncedText(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "Synced just now"
            s < 60 -> "Synced ${s}s ago"
            s < 3600 -> "Synced ${s / 60} min ago"
            else -> "Synced ${s / 3600} h ago"
        }
    }

    /**
     * The sentence the node line shows for [error]: the 401 one points at
     * the settings, a transport failure says the board is offline.
     */
    fun errorText(error: LunicleError): String = when (error) {
        is LunicleError.Http -> when {
            error.isInvalidToken -> "Check the token in App settings → Lunicle."
            error.isNotFound -> "This project is not visible to the token (404)."
            error.isRateLimited -> "Too many requests to Lunicle; trying again shortly."
            else -> error.message
        }
        is LunicleError.Transport -> "Offline: ${error.message}"
        else -> error.message
    }

    /** How long the indicator says "Synced just now" after a write (LBR-29). */
    const val SAVED_MS: Long = 4_000

    /**
     * The sentence for a write that failed (LBR-29), shown in red on the
     * indicator while the edit is reverted. A read-only token says so.
     */
    fun writeErrorText(error: LunicleError): String = when {
        error is LunicleError.Http && error.isReadOnlyToken -> READ_ONLY_TEXT
        error is LunicleError.Http && error.status == 403 -> "Lunicle refused the change: ${error.message}"
        else -> "Not saved: ${errorText(error)}"
    }

    /** Why nothing on a board can be edited with a read-only token (LBR-29). */
    const val READ_ONLY_TEXT: String = "This token is read-only: make a read-write token in Lunicle to edit here."

    /** Why an issue's title cannot be edited: Lunicle's `canEdit` is false. */
    fun noEditText(issueKey: String): String = "You can't edit $issueKey in Lunicle."

    /** Why no issue can be filed on a board: a project viewer. */
    fun noCreateText(projectKey: String): String = "You can't file issues in $projectKey (viewer)."

    /** The message for a reference that is not `[<connection>/]<KEY>`. */
    const val MALFORMED_TEXT: String = "Write it as {{lunicle: <connection>/<KEY>}}."

    /**
     * "just now", "5 min ago", "3 h ago", "2 d ago", else the date
     * (`2026-10-04`, UTC) — a comment's `when`.
     */
    fun whenText(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 7 * 86_400 -> "${s / 86_400} d ago"
            else -> dateText(at)
        }
    }

    /** [at] (epoch ms) as `YYYY-MM-DD`, UTC. */
    fun dateText(at: Long): String {
        // Civil date from days since the epoch (Howard Hinnant's algorithm).
        val days = at.floorDiv(86_400_000L)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /**
     * The issue's page in Lunicle's web app: the connection's base URL
     * plus Lunicle's deep link, `/?issue=<KEY>` (`main.kt`'s
     * `preferredTicket`). `null` unless the base URL is `https:` (the
     * system browser opens only those; `lunarbor:openExternalUrl`).
     */
    fun issueUrl(baseUrl: String, issueKey: String): String? {
        val base = baseUrl.trimEnd('/')
        if (!base.startsWith("https://", ignoreCase = true) || issueKey.isBlank()) return null
        val key = issueKey.filter { it.isLetterOrDigit() || it == '-' }
        return "$base/?issue=$key"
    }
}

/**
 * What a re-read of a board changed, by issue id.
 *
 * @property changed Issues whose `updatedAt` moved.
 * @property added Issues that were not on the board before.
 * @property removed Issues that are gone.
 */
data class LunicleChanges(val changed: Set<Long>, val added: Set<Long>, val removed: Set<Long>) {
    /** `true` when nothing changed. */
    val isEmpty: Boolean get() = changed.isEmpty() && added.isEmpty() && removed.isEmpty()

    /** Every issue the change touches. */
    val all: Set<Long> get() = changed + added + removed
}

/**
 * One change-stream event, as a hint for the notice: who did what to which
 * issue. Events carry no titles; those come from the boards around them.
 *
 * @property kind `issue.created`, `issue.updated`, `issue.moved`,
 *   `issue.deleted`, `comment.added`, `comment.edited`, `comment.deleted`.
 * @property issueId The issue, or `null`.
 * @property actor Who made the change, or `null` when nobody was signed in.
 */
data class LunicleChangeHint(val kind: String, val issueId: Long?, val actor: String?)

/** Diffing two reads of a board, and the words for what changed. */
object LunicleBoardDiff {

    /** What changed between [before] and [after] (by id and `updatedAt`). */
    fun diff(before: LunicleBoard?, after: LunicleBoard): LunicleChanges {
        if (before == null) return LunicleChanges(emptySet(), emptySet(), emptySet())
        val old = before.issues.associateBy { it.id }
        val new = after.issues.associateBy { it.id }
        val changed = new.values.filter { n -> old[n.id]?.let { it.updatedAt != n.updatedAt || it != n } == true }.map { it.id }.toSet()
        return LunicleChanges(changed, new.keys - old.keys, old.keys - new.keys)
    }

    /**
     * Which issues to `GET /issues/{id}` after a re-read: the changed, new
     * and [hinted] ones (a stream event names an issue, e.g. a comment that
     * may not move `updatedAt`) that some pane has [unfolded], plus every
     * unfolded issue whose details are not [known] yet. Gone issues are
     * never fetched.
     */
    fun toRefetch(changes: LunicleChanges, hinted: Set<Long>, unfolded: Set<Long>, known: Set<Long>, onBoard: Set<Long>): Set<Long> =
        ((changes.changed + changes.added + hinted) intersect unfolded).plus(unfolded - known)
            .filter { it in onBoard }.toSet()

    /**
     * The notice for a change the user did not make, or `null` when there
     * is nothing to say. One issue: with an actor from the stream,
     * "Linus commented on Lönerapporter", "Linus moved Lönerapporter to
     * Done", "Linus added …", "Linus removed …", "Linus changed …"; from a
     * poll, the newest new comment's author ("Linus commented on …"), a
     * column change ("Lönerapporter moved to Done"), else "Lönerapporter
     * changed" / "… was added" / "… was removed". Several issues:
     * "3 issues changed".
     *
     * @param changes The diff of the two reads.
     * @param before The board before (titles of removed issues).
     * @param after The board after.
     * @param hints The stream's events of the burst (empty for a poll).
     * @param detailsBefore Full issues known before (for new comments).
     * @param detailsAfter Full issues known after.
     */
    fun remoteMessage(
        changes: LunicleChanges,
        before: LunicleBoard,
        after: LunicleBoard,
        hints: List<LunicleChangeHint> = emptyList(),
        detailsBefore: Map<Long, LunicleIssue> = emptyMap(),
        detailsAfter: Map<Long, LunicleIssue> = emptyMap(),
    ): String? {
        val touched = changes.all + hints.mapNotNull { it.issueId }
        if (touched.isEmpty()) return null
        if (touched.size > 1) return "${touched.size} issues changed"
        val id = touched.single()
        val oldIssue = before.issues.firstOrNull { it.id == id }
        val newIssue = after.issues.firstOrNull { it.id == id }
        val title = (newIssue ?: oldIssue)?.title?.takeIf { it.isNotBlank() } ?: (newIssue ?: oldIssue)?.key ?: return null
        val hint = hints.lastOrNull { it.issueId == id && it.actor != null }
        val newComment = newCommentOf(detailsBefore[id], detailsAfter[id])
        val actor = hint?.actor
        if (actor != null) {
            return when (hint.kind) {
                "comment.added" -> "$actor commented on $title"
                "comment.edited", "comment.deleted" -> "$actor edited a comment on $title"
                "issue.created" -> "$actor added $title"
                "issue.deleted" -> "$actor removed $title"
                "issue.moved" -> if (newIssue != null && oldIssue != null && newIssue.status != oldIssue.status) {
                    "$actor moved $title to ${newIssue.status}"
                } else {
                    "$actor moved $title"
                }
                else -> "$actor changed $title"
            }
        }
        if (newComment != null && newComment.author.isNotBlank()) return "${newComment.author} commented on $title"
        return when {
            id in changes.added -> "$title was added"
            id in changes.removed -> "$title was removed"
            oldIssue != null && newIssue != null && oldIssue.status != newIssue.status -> "$title moved to ${newIssue.status}"
            else -> "$title changed"
        }
    }

    /** The newest comment of [after] that [before] did not have, or `null` (also when [before] is unknown). */
    private fun newCommentOf(before: LunicleIssue?, after: LunicleIssue?): LunicleComment? {
        if (before == null || after == null) return null
        val old = before.comments.map { it.id }.toSet()
        return after.comments.lastOrNull { it.id !in old }
    }
}

/**
 * One message of a connection's change stream, as the main process relays
 * it (`lunarbor:lunicleEvent`).
 */
sealed interface LunicleStreamMessage {
    /** The connection the stream belongs to. */
    val connectionId: String

    /**
     * An event.
     *
     * @property kind The SSE `event:` name (`issue.updated`, `board.changed`, `reset`, …).
     * @property projectId The project, or `null` (`reset`, `notification.changed`).
     * @property issueId The issue, or `null`.
     * @property commentId The comment, for the comment kinds.
     * @property actor Who made the change, if anyone was signed in.
     * @property self `true` when this app's own write caused it (its
     *   `X-Lunicle-Origin` matched the stream's `origin`): ignored.
     */
    data class Event(
        override val connectionId: String,
        val kind: String,
        val projectId: Long? = null,
        val issueId: Long? = null,
        val commentId: Long? = null,
        val actor: String? = null,
        val self: Boolean = false,
    ) : LunicleStreamMessage

    /**
     * The stream's state changed.
     *
     * @property state What it is now.
     */
    data class Status(override val connectionId: String, val state: StreamState) : LunicleStreamMessage

    /** A change stream's state. */
    enum class StreamState {
        /** Connected: events arrive; polling pauses. */
        CONNECTED,

        /** Not connected (yet, or any more); the main process retries with backoff, polling meanwhile. */
        DISCONNECTED,

        /** The server has no change stream (404): poll for good. */
        UNSUPPORTED,
    }

    companion object {
        /**
         * Parses a relayed message: `{connectionId, status}` or
         * `{connectionId, event, data}` with `data` the SSE data's JSON.
         * `null` for anything else.
         */
        fun parse(json: JsonElement?): LunicleStreamMessage? {
            val o = json as? JsonObject ?: return null
            val connectionId = o.str("connectionId") ?: return null
            o.str("status")?.let { s ->
                val state = when (s) {
                    "connected" -> StreamState.CONNECTED
                    "disconnected" -> StreamState.DISCONNECTED
                    "unsupported" -> StreamState.UNSUPPORTED
                    else -> return null
                }
                return Status(connectionId, state)
            }
            val kind = o.str("event") ?: return null
            val data = o["data"] as? JsonObject
            return Event(
                connectionId = connectionId,
                kind = kind,
                projectId = data?.long("projectId"),
                issueId = data?.long("issueId"),
                commentId = data?.long("commentId"),
                actor = data?.str("actor"),
                self = (data?.get("self") as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    }
}
