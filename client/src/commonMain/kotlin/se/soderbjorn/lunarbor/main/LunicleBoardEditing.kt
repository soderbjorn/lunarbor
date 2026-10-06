/* LunicleBoardEditing.kt (commonMain)
 *
 * The pure rules of editing a board node's rows (LBR-29): renaming an
 * issue, and new issues — drafts — from Enter on an issue's title, Enter on
 * a column's name, or the column's "New issue" line. Tested directly and
 * through a pane (`LunicleBoardEditingTest`).
 *
 *  - A **draft** ([LunicleDraft]) is pane state
 *    (`PaneBackingViewModel.State.lunicleDrafts`): never saved, never sent
 *    until it has a title and the caret leaves it, dropped when the pane
 *    navigates away. A poll never touches it.
 *  - Once sent, the new issue is the board cache's
 *    ([se.soderbjorn.lunarbor.lunicle.LunicleCreatingIssue], shared by every
 *    pane) until the board lists it.
 *  - [place] puts drafts and issues being created among a column's issues,
 *    by their [LunicleDraftAnchor]: right below an issue or another new
 *    issue, at the top, or at the end.
 *  - [committedTitle] says what a title edit sends: nothing for an empty or
 *    unchanged title (the old one comes back).
 *
 * The pane's intents (`PaneBackingViewModel.lunicleEnter`,
 * `leaveLunicleRow`, …) compose these with the board cache's writes; the
 * web view's `LunicleBoardCursor` holds the text being typed.
 *
 * commonMain only — no platform imports. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.lunicle.LunicleBoardIssue
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleCreatingIssue
import se.soderbjorn.lunarbor.lunicle.LunicleDraftAnchor

/**
 * A new issue being written on a board, not yet sent (LBR-29). Pane state:
 * its text lives in the view's field until the caret leaves it.
 *
 * @property localId Its id until Lunicle gives it one (negative, from
 *   `LunicleBoards.newLocalId`); its rows are named by it.
 * @property board The board it is on.
 * @property status The column it will be filed in.
 * @property priority Its priority, or `null` for the project's default
 *   (the "New issue" line).
 * @property anchor Where its row sits in the column.
 */
data class LunicleDraft(
    val localId: Long,
    val board: LunicleBoardKey,
    val status: String,
    val priority: String?,
    val anchor: LunicleDraftAnchor,
)

/**
 * The issue whose title a pane is editing (LBR-29): kept on the board even
 * when a poll no longer lists it, and the title the edit started from.
 *
 * @property board The board it is on.
 * @property issue The issue as it was when the edit started.
 * @property original The title shown when the edit started (an optimistic
 *   one included): the edit sends a change only when the text differs.
 */
data class LunicleEditingIssue(val board: LunicleBoardKey, val issue: LunicleBoardIssue, val original: String)

/** One entry of a column as drawn: an issue, a draft, or an issue Lunicle is filing. */
sealed interface LunicleColumnItem {
    /** An issue of the board. */
    data class Issue(val view: PaneBackingViewModel.LunicleIssueView) : LunicleColumnItem

    /** A draft of this pane. */
    data class Draft(val draft: LunicleDraft) : LunicleColumnItem

    /** A sent draft, until the board lists the new issue. */
    data class Creating(val entry: LunicleCreatingIssue) : LunicleColumnItem
}

/** Placement and commit rules for board edits (see the file header). */
object LunicleBoardEditing {

    /**
     * A column's entries in drawing order: its [issues] (already sorted),
     * with [creating] and [drafts] put in by their anchors, oldest first
     * (local ids count down, so a larger id is older) — an anchor only
     * ever names something older. `AfterIssue` goes right below that
     * issue, `AfterLocal` right below that new issue (or, once Lunicle
     * filed it, the issue it became, via [createdIds]), `Top` above
     * everything, `End` last. An anchor that names nothing in the column
     * (the issue moved or went) puts the entry last: a poll never removes a
     * draft.
     *
     * @param issues The column's issues, by priority.
     * @param creating Issues being filed in this column.
     * @param drafts This pane's drafts in this column.
     * @param createdIds Local id → the new issue's id, for filed drafts.
     */
    fun place(
        issues: List<PaneBackingViewModel.LunicleIssueView>,
        creating: List<LunicleCreatingIssue>,
        drafts: List<LunicleDraft>,
        createdIds: Map<Long, Long> = emptyMap(),
    ): List<LunicleColumnItem> {
        val out = ArrayList<LunicleColumnItem>(issues.size + creating.size + drafts.size)
        issues.mapTo(out) { LunicleColumnItem.Issue(it) }
        val extra = (creating.map { it.localId to (LunicleColumnItem.Creating(it) to it.anchor) } +
            drafts.map { it.localId to (LunicleColumnItem.Draft(it) to it.anchor) })
            .sortedByDescending { it.first }
        fun indexOfIssue(id: Long) = out.indexOfFirst { it is LunicleColumnItem.Issue && it.view.issue.id == id }
        fun indexOfLocal(id: Long) = out.indexOfFirst {
            (it is LunicleColumnItem.Draft && it.draft.localId == id) || (it is LunicleColumnItem.Creating && it.entry.localId == id)
        }
        for ((_, pair) in extra) {
            val (item, anchor) = pair
            val at = when (anchor) {
                LunicleDraftAnchor.Top -> 0
                LunicleDraftAnchor.End -> out.size
                is LunicleDraftAnchor.AfterIssue -> indexOfIssue(anchor.issueId).let { if (it < 0) out.size else it + 1 }
                is LunicleDraftAnchor.AfterLocal -> {
                    val local = indexOfLocal(anchor.localId)
                    val i = if (local >= 0) local else createdIds[anchor.localId]?.let { indexOfIssue(it) } ?: -1
                    if (i < 0) out.size else i + 1
                }
            }
            out.add(at, item)
        }
        return out
    }

    /**
     * What a title edit sends when the caret leaves it: the trimmed [text],
     * or `null` when it is empty (not sent: the old title comes back) or
     * the same as [original].
     */
    fun committedTitle(original: String, text: String): String? {
        val t = text.trim()
        return if (t.isEmpty() || t == original.trim()) null else t
    }

    /** A draft's title when the caret leaves it, or `null` when it is empty (the draft is removed). */
    fun draftTitle(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
}
