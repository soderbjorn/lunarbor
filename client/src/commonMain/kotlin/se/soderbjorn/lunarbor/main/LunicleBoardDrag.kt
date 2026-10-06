/* LunicleBoardDrag.kt (commonMain)
 *
 * The pure rules of dragging an issue on a board node by its dot, as on
 * Lunicle's own board: where a drop lands for the board row under the
 * pointer ([LunicleBoardDrag.dropAt]), and how the view marks it.
 * Tested in `LunicleBoardDragTest`.
 *
 *  - **On an issue's line**: its upper half puts the dragged issue directly
 *    above it, its lower half directly below — in that issue's column and
 *    priority. So the boundary between two priorities offers both: below
 *    the last `#high` issue stays high, above the first `#medium` one
 *    becomes medium. Below an unfolded issue means below its whole subtree
 *    (description, comments), where the line is drawn.
 *  - **On an issue's child rows** (description, comments, "Comment…"):
 *    below that issue.
 *  - **On a column's "New issue" line**: below the column's last issue (into
 *    the column, when it has none).
 *  - **On a column's name**: into that column, keeping the priority, last in
 *    its group — the line is drawn round the column's row.
 *  - **A column that requires a resolution** (Closed, folded by default):
 *    Lunicle groups it by resolution, not priority, so any drop there means
 *    only "into this column" (the resolution popup comes first) and a drag
 *    inside it does nothing.
 *  - Drafts and issues being filed, the dragged issue itself and a drop
 *    where it already is give no drop.
 *
 * The drag itself (press, threshold, pointer tracking, the drop line) is
 * view state in the web view; the drop is written by
 * `PaneBackingViewModel.dropLunicleIssue`.
 *
 * commonMain only — no platform imports. */
package se.soderbjorn.lunarbor.main

/** How the view marks a drop ([LunicleDrop.indicatorKey]'s row). */
enum class LunicleDropIndicator {
    /** A line along the row's top. */
    BEFORE,

    /** A line along the row's bottom. */
    AFTER,

    /** A ring round the row (a column's name: into that column). */
    INTO,
}

/**
 * Where a dragged issue would land ([LunicleBoardDrag.dropAt]).
 *
 * @property issueId The dragged issue.
 * @property status The column it lands in.
 * @property priority The priority group it lands in.
 * @property beforeId The issue it lands directly above, or `null`.
 * @property afterId The issue it lands directly below, or `null` (with
 *   [beforeId] also `null`: last in its group).
 * @property indicatorKey The [LunicleRowRef.key] of the row the view marks.
 * @property indicator How it marks it.
 * @property needsResolution The column requires a resolution: the
 *   resolution popup comes before the move.
 */
data class LunicleDrop(
    val issueId: Long,
    val status: String,
    val priority: String,
    val beforeId: Long? = null,
    val afterId: Long? = null,
    val indicatorKey: String,
    val indicator: LunicleDropIndicator,
    val needsResolution: Boolean = false,
)

/** Drop rules of a board drag (see the file header). */
object LunicleBoardDrag {

    /**
     * Whether issue [issueId] of [view] can be dragged: it is on the board
     * and editable ([PaneBackingViewModel.LunicleIssueView.editable]: its
     * `canEdit`, and a write token).
     */
    fun canDrag(view: PaneBackingViewModel.LunicleBoardView, issueId: Long): Boolean =
        view.columns.any { c -> c.issues.any { it.issue.id == issueId && it.editable } }

    /**
     * Where issue [issueId] would land on [view] with the pointer over the
     * board row [overKey] (a [LunicleRowRef.key]) — in its lower half when
     * [lowerHalf]. `null` for no drop (see the file header).
     *
     * Called by `PaneBackingViewModel.lunicleDropAt` on every pointer move
     * of a drag.
     */
    fun dropAt(view: PaneBackingViewModel.LunicleBoardView, issueId: Long, overKey: String, lowerHalf: Boolean): LunicleDrop? {
        val fromColumn = view.columns.firstOrNull { c -> c.issues.any { it.issue.id == issueId } } ?: return null
        val dragged = fromColumn.issues.first { it.issue.id == issueId }.issue
        val rows = LunicleBoardRows.of(view)
        val over = rows.indexOfFirst { it.ref.key == overKey }
        if (over < 0) return null
        val ref = rows[over].ref
        val column = view.columns.firstOrNull { it.column.status.name == ref.status } ?: return null

        fun into(): LunicleDrop? {
            if (column.column.status.name == dragged.status) return null
            return LunicleDrop(
                issueId, column.column.status.name, dragged.priority,
                indicatorKey = LunicleRowRef(LunicleRowKind.COLUMN, column.column.status.name).key,
                indicator = LunicleDropIndicator.INTO,
                needsResolution = column.column.status.requiresResolution,
            )
        }

        // The issue the drop is next to, and on which side.
        val (anchorId, after) = when (ref.kind) {
            LunicleRowKind.COLUMN -> return into()
            LunicleRowKind.ISSUE -> ref.issueId to lowerHalf
            LunicleRowKind.DESCRIPTION, LunicleRowKind.COMMENT, LunicleRowKind.ADD_COMMENT -> ref.issueId to true
            LunicleRowKind.NEW_ISSUE -> (column.issues.lastOrNull()?.issue?.id ?: return into()) to true
            LunicleRowKind.DRAFT, LunicleRowKind.CREATING -> return null
        }
        if (anchorId == null || anchorId == issueId) return null
        // Lunicle groups a closing column by resolution: only "into" means anything there.
        if (column.column.status.requiresResolution) return into()
        val anchor = column.issues.firstOrNull { it.issue.id == anchorId }?.issue ?: return null

        if (anchor.status == dragged.status && anchor.priority == dragged.priority) {
            // Dropped where it already is: right above the issue below it, or right below the one above.
            val group = column.issues.map { it.issue }.filter { it.priority == dragged.priority }
            val at = group.indexOfFirst { it.id == issueId }
            val neighbour = group.getOrNull(if (after) at - 1 else at + 1)
            if (neighbour?.id == anchorId) return null
        }
        val indicatorKey = if (!after) {
            LunicleRowRef(LunicleRowKind.ISSUE, column.column.status.name, anchorId).key
        } else {
            // Below the issue's subtree: the last of its child rows, if unfolded.
            val anchorRow = rows.indexOfFirst { it.ref.kind == LunicleRowKind.ISSUE && it.ref.issueId == anchorId }
            var last = anchorRow
            while (last + 1 < rows.size && rows[last + 1].depth > 1) last++
            rows[last].ref.key
        }
        return LunicleDrop(
            issueId,
            column.column.status.name,
            anchor.priority,
            beforeId = if (after) null else anchorId,
            afterId = if (after) anchorId else null,
            indicatorKey = indicatorKey,
            indicator = if (after) LunicleDropIndicator.AFTER else LunicleDropIndicator.BEFORE,
        )
    }
}
