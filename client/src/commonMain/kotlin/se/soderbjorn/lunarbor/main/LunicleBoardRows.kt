/* LunicleBoardRows.kt (commonMain)
 *
 * The rows of a board node (LBR-28) that the keyboard walks: column names,
 * issues, and under an unfolded issue its description, comments and
 * "Comment…"; drafts, issues being filed and each unfolded column's
 * "New issue" line (LBR-29) — in the order the view draws them, from the pane's
 * [PaneBackingViewModel.LunicleBoardView] (board + the pane's board folds).
 * Pure, so the order, entering and leaving at both ends, and finding the
 * caret's row again after a re-read are tested directly
 * (`LunicleBoardRowsTest`, `LunicleBoardNodeTest`).
 *
 * A row is named by a [LunicleRowRef] — issue id plus row kind (a column by
 * its status name, a comment by its id) — never by its index, so a poll
 * that adds issues above, or moves the caret's issue to another column,
 * keeps the caret on the same row ([LunicleBoardRows.relocate]).
 *
 * The web view's `LunicleBoardCursor` owns which row the caret is on (view
 * state, like `SearchNodeHitCursor`); this file only says what the rows are.
 *
 * commonMain only — no platform imports. */
package se.soderbjorn.lunarbor.main

/**
 * What a board row is. [editable] rows take a caret (an issue's title, a
 * draft, the "New issue" line, its description, "Comment…") — a row's own
 * [LunicleBoardRow.editable] can still say no (a read-only token,
 * `canEdit: false`); the others are drawn as a highlight and swallow typing.
 */
enum class LunicleRowKind(val editable: Boolean) {
    /** A column's name and count. */
    COLUMN(false),

    /** An issue's line: title, pills, key. Its title is edited in place (LBR-29). */
    ISSUE(true),

    /** A new issue being written (LBR-29, [LunicleDraft]); named by its local id. */
    DRAFT(true),

    /** A sent draft until the board lists it (LBR-29); named by its local id. */
    CREATING(false),

    /** The last row of an unfolded column: "New issue" (LBR-29). */
    NEW_ISSUE(true),

    /** An unfolded issue's description (its "Loading…" row until it is read). */
    DESCRIPTION(true),

    /** One comment of an unfolded issue. Comments are add-only here. */
    COMMENT(false),

    /** The "Comment…" row under an unfolded issue's comments. */
    ADD_COMMENT(true),
}

/**
 * One board row's name: stable across re-reads of the board.
 *
 * @property kind What the row is.
 * @property status The column the row is in (for [LunicleRowKind.COLUMN]
 *   its name; for issue rows the column it was last seen in, used only as
 *   the fallback when the issue is gone).
 * @property issueId The issue, for the issue kinds; the local id for
 *   [LunicleRowKind.DRAFT] and [LunicleRowKind.CREATING]; `null` for a
 *   column and its "New issue" line.
 * @property commentId The comment, for [LunicleRowKind.COMMENT].
 */
data class LunicleRowRef(
    val kind: LunicleRowKind,
    val status: String,
    val issueId: Long? = null,
    val commentId: Long? = null,
) {
    /**
     * The row's identity: kind plus issue id (plus comment id), or the
     * column's status — never the issue's column, so an issue that moves
     * keeps its key. Also the view's `data-lunicle-key` attribute.
     */
    val key: String
        get() = when (kind) {
            LunicleRowKind.COLUMN -> "c:$status"
            LunicleRowKind.ISSUE -> "i:$issueId"
            LunicleRowKind.DESCRIPTION -> "d:$issueId"
            LunicleRowKind.COMMENT -> "m:$issueId:$commentId"
            LunicleRowKind.ADD_COMMENT -> "a:$issueId"
            LunicleRowKind.DRAFT -> "n:$issueId"
            LunicleRowKind.CREATING -> "p:$issueId"
            LunicleRowKind.NEW_ISSUE -> "e:$status"
        }

    /**
     * The issue's own row, for an issue's row or a row under an issue;
     * `null` for a column, a draft, an issue being filed and "New issue".
     */
    val issueRef: LunicleRowRef?
        get() = when (kind) {
            LunicleRowKind.COLUMN, LunicleRowKind.DRAFT, LunicleRowKind.CREATING, LunicleRowKind.NEW_ISSUE -> null
            else -> issueId?.let { LunicleRowRef(LunicleRowKind.ISSUE, status, it) }
        }
}

/**
 * One navigable board row.
 *
 * @property ref Its name.
 * @property depth Board depth: 0 a column, 1 an issue, 2 an issue's child.
 * @property editable Whether it takes a caret ([LunicleRowKind.editable];
 *   a description only once the issue has been read; a title only when it
 *   can be changed — `canEdit` and a write token, LBR-29).
 */
data class LunicleBoardRow(val ref: LunicleRowRef, val depth: Int, val editable: Boolean)

/** Rows, steps and relocation over a board's navigable rows (see the file header). */
object LunicleBoardRows {

    /**
     * The navigable rows of [view], top to bottom, exactly as
     * `LunicleBoardView.buildLunicleBoard` draws them: each column, and
     * unless it is folded its entries ([PaneBackingViewModel.LunicleColumnView.items]:
     * issues, drafts, issues being filed), each unfolded issue followed by
     * its description, its comments (once read) and "Comment…" (once
     * read), and last the column's "New issue" line when it has one.
     * Empty before the first read and for a malformed reference.
     */
    fun of(view: PaneBackingViewModel.LunicleBoardView): List<LunicleBoardRow> {
        val out = ArrayList<LunicleBoardRow>()
        for (column in view.columns) {
            val status = column.column.status.name
            out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.COLUMN, status), 0, editable = false)
            if (column.folded) continue
            for (item in column.items) {
                val issue = when (item) {
                    is LunicleColumnItem.Draft -> {
                        out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.DRAFT, status, item.draft.localId), 1, editable = true)
                        continue
                    }
                    is LunicleColumnItem.Creating -> {
                        out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.CREATING, status, item.entry.localId), 1, editable = false)
                        continue
                    }
                    is LunicleColumnItem.Issue -> item.view
                }
                val id = issue.issue.id
                out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.ISSUE, status, id), 1, editable = issue.editable)
                if (!issue.unfolded) continue
                val detail = issue.detail
                out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.DESCRIPTION, status, id), 2, editable = detail != null)
                if (detail == null) continue
                for (comment in detail.comments) {
                    out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.COMMENT, status, id, comment.id), 2, editable = false)
                }
                out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.ADD_COMMENT, status, id), 2, editable = true)
            }
            if (column.newIssueLine) out += LunicleBoardRow(LunicleRowRef(LunicleRowKind.NEW_ISSUE, status), 1, editable = true)
        }
        return out
    }

    /** Index of the row named [ref] in [rows] (by [LunicleRowRef.key]), or -1. */
    fun indexOf(rows: List<LunicleBoardRow>, ref: LunicleRowRef): Int = rows.indexOfFirst { it.ref.key == ref.key }

    /**
     * Where the caret goes on entering the board: its first row going
     * down (from the node's line, or at the top of the node's page), its
     * last going up (from the row below the board). `null` when the board
     * has no rows yet.
     */
    fun entry(rows: List<LunicleBoardRow>, down: Boolean): LunicleRowRef? =
        (if (down) rows.firstOrNull() else rows.lastOrNull())?.ref

    /** The outcome of one Arrow Down / Up on the board ([step]). */
    sealed interface Step {
        /** The caret moves to [ref]. */
        data class To(val ref: LunicleRowRef) : Step

        /** Past the first row: back onto the node's line. */
        data object LeaveUp : Step

        /** Past the last row: on to the row below the board. */
        data object LeaveDown : Step
    }

    /**
     * One row down ([down]) or up from [ref]. Past either end the caret
     * leaves the board ([Step.LeaveUp] / [Step.LeaveDown]); a [ref] that is
     * no longer on the board is first [relocate]d, and leaves upwards when
     * nothing is left.
     */
    fun step(rows: List<LunicleBoardRow>, ref: LunicleRowRef, down: Boolean): Step {
        val at = relocate(rows, ref)?.let { indexOf(rows, it) } ?: return Step.LeaveUp
        val next = if (down) at + 1 else at - 1
        return when {
            next < 0 -> Step.LeaveUp
            next >= rows.size -> Step.LeaveDown
            else -> Step.To(rows[next].ref)
        }
    }

    /**
     * The row [ref] names after the board was re-read or refolded: the
     * same row when it is still there (wherever it moved); else, for a
     * comment or "Comment…" that is gone (deleted, or the issue folded),
     * its issue's row; a draft that was sent, its "being filed" row, and
     * either, once Lunicle filed it, the new issue's row (via
     * [createdIds]); for an issue that is gone, its column's row; `null`
     * when not even that is left (the caret then leaves the board).
     * Called by the view's cursor after every repaint.
     *
     * @param createdIds A sent draft's local id → the new issue's id
     *   ([PaneBackingViewModel.LunicleBoardView.createdIds]).
     */
    fun relocate(rows: List<LunicleBoardRow>, ref: LunicleRowRef, createdIds: Map<Long, Long> = emptyMap()): LunicleRowRef? {
        rows.firstOrNull { it.ref.key == ref.key }?.let { return it.ref }
        if (ref.kind == LunicleRowKind.DRAFT || ref.kind == LunicleRowKind.CREATING) {
            val local = ref.issueId
            val filing = LunicleRowRef(LunicleRowKind.CREATING, ref.status, local)
            if (ref.kind == LunicleRowKind.DRAFT) rows.firstOrNull { it.ref.key == filing.key }?.let { return it.ref }
            createdIds[local]?.let { id ->
                val issue = LunicleRowRef(LunicleRowKind.ISSUE, ref.status, id)
                rows.firstOrNull { it.ref.key == issue.key }?.let { return it.ref }
            }
        }
        ref.issueRef?.takeIf { ref.kind != LunicleRowKind.ISSUE }?.let { issue ->
            rows.firstOrNull { it.ref.key == issue.key }?.let { return it.ref }
        }
        val column = LunicleRowRef(LunicleRowKind.COLUMN, ref.status)
        return rows.firstOrNull { it.ref.key == column.key }?.ref
    }
}
