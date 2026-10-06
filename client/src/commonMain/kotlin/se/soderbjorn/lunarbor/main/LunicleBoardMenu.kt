/* LunicleBoardMenu.kt (commonMain)
 *
 * The pure rules of changing an issue's properties from its line (LBR-30,
 * proposal 2a): the typeahead menu that `#` or `@` on an issue's title row
 * opens, the one-field menu a pill click opens, and the small resolution
 * popup a closing status asks for first. Tested in `LunicleBoardMenuTest`.
 *
 *  - **`#`** ([LunicleMenu.typed]): headed `#<query>`, sections **Status**
 *    (every column, in the board's column order, closed ones included) and
 *    **Priority** (the project's order). Options read `#manager`,
 *    `#high`, … ([LunicleBoardLayout.pillText]), ✓ on the current value.
 *  - **`@`**: one section, **Assignee** — `@nobody`, then who may be
 *    assigned ([assignees]).
 *  - **A pill** ([LunicleMenu.forField]): just that field, no section
 *    headers, headed by the field's name, the current value highlighted.
 *  - Typing narrows the list (substring, case-insensitive, against the
 *    name and its pill text); ↑ / ↓ move the highlight round the ends,
 *    Enter or Tab picks, Esc closes, ⌫ on an empty query closes, a space
 *    closes ([key]).
 *  - [LunicleResolutionChoice]: the closing status's resolutions, the
 *    first highlighted; ↑ / ↓ wrap, Enter picks, Esc cancels the move.
 *
 * The menu's state is view state (the web view's `LunicleBoardCursor`
 * holds it, like `SearchNodeHitCursor`'s highlight); picking is
 * `PaneBackingViewModel.pickLunicleOption`.
 *
 * commonMain only — no platform imports. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.lunicle.LunicleBoard
import se.soderbjorn.lunarbor.lunicle.LunicleBoardIssue
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleBoardLayout
import se.soderbjorn.lunarbor.lunicle.LuniclePill

/**
 * An open property menu on one issue's line (LBR-30). View state.
 *
 * @property board The board the issue is on.
 * @property issueId The issue.
 * @property trigger `#` or `@` for a typed menu; `null` for a pill's.
 * @property field For a pill's menu, the one field it changes; `null` for a typed one.
 * @property query What was typed after the trigger (a pill's menu takes none).
 * @property highlight Index of the highlighted option (clamped to the list
 *   by [LunicleBoardMenu.highlightOf]).
 */
data class LunicleMenu(
    val board: LunicleBoardKey,
    val issueId: Long,
    val trigger: Char? = null,
    val field: LuniclePill.Field? = null,
    val query: String = "",
    val highlight: Int = 0,
) {
    companion object {
        /** The menu `#` or `@` opens on issue [issueId] (an empty query, the first option highlighted). */
        fun typed(board: LunicleBoardKey, issueId: Long, trigger: Char): LunicleMenu = LunicleMenu(board, issueId, trigger = trigger)

        /**
         * The menu a click on [field]'s pill opens: that field only, its
         * current value highlighted ([options] is the menu's list, from
         * [LunicleBoardMenu.options]).
         */
        fun forField(board: LunicleBoardKey, issueId: Long, field: LuniclePill.Field, options: (LunicleMenu) -> List<LunicleMenuOption>): LunicleMenu {
            val menu = LunicleMenu(board, issueId, field = field)
            val current = options(menu).indexOfFirst { it.current }
            return menu.copy(highlight = current.coerceAtLeast(0))
        }
    }
}

/**
 * One option of a property menu.
 *
 * @property field The property it sets.
 * @property value The status, priority or assignee name; `null` for `@nobody`.
 * @property label `#in-progress`, `#high`, `@linus`, `@nobody`.
 * @property current It is the issue's value now (✓).
 * @property section The section's name: "Status", "Priority", "Assignee".
 * @property showHeader The first option of its section in a typed menu: the
 *   section's header is drawn above it.
 */
data class LunicleMenuOption(
    val field: LuniclePill.Field,
    val value: String?,
    val label: String,
    val current: Boolean,
    val section: String,
    val showHeader: Boolean,
)

/**
 * The resolution popup a closing status opens first (LBR-30). View state.
 *
 * @property board The board.
 * @property issueId The issue being moved.
 * @property status The status it moves to (one with `requiresResolution`).
 * @property resolutions The project's resolutions, in its order.
 * @property highlight Index of the highlighted one (the first to begin with).
 */
data class LunicleResolutionChoice(
    val board: LunicleBoardKey,
    val issueId: Long,
    val status: String,
    val resolutions: List<String>,
    val highlight: Int = 0,
) {
    /** One step down ([down]) or up, round the ends. */
    fun moved(down: Boolean): LunicleResolutionChoice =
        copy(highlight = LunicleBoardMenu.wrap(highlight, resolutions.size, down))

    /** The highlighted resolution, or `null` when there are none. */
    val picked: String? get() = resolutions.getOrNull(highlight.coerceIn(0, (resolutions.size - 1).coerceAtLeast(0)))
}

/** What a key does to an open menu ([LunicleBoardMenu.key]). */
sealed interface LunicleMenuStep {
    /** The menu stays open as [menu] (a new query or highlight). */
    data class Update(val menu: LunicleMenu) : LunicleMenuStep

    /** Picks [option]. */
    data class Pick(val option: LunicleMenuOption) : LunicleMenuStep

    /** The menu closes and the key is used up. */
    data object Close : LunicleMenuStep

    /** The menu closes and the key goes on as usual (typing on a pill's menu). */
    data object CloseAndPass : LunicleMenuStep

    /** Not the menu's key: it goes on and the menu stays. */
    data object Pass : LunicleMenuStep
}

/**
 * The board row flashing after a property change (LBR-30): pane state
 * (`PaneBackingViewModel.State.lunicleFlash`), drawn as a warm background
 * fading out over [LunicleBoardMenu.FLASH_MS].
 *
 * @property board The board.
 * @property rowKey The row's [LunicleRowRef.key]: `i:<id>` for the issue,
 *   `c:<status>` for a folded closing column it went into.
 * @property at When it started (epoch ms).
 */
data class LunicleFlash(val board: LunicleBoardKey, val rowKey: String, val at: Long)

/**
 * Where a property change leaves things ([LunicleBoardMenu.placeAfterChange]).
 *
 * @property caret The row the keyboard's caret goes to.
 * @property flash The row that flashes.
 * @property unfold A folded column to unfold first, or `null`.
 */
data class LunicleChangePlacement(
    val caret: LunicleRowRef,
    val flash: LunicleRowRef,
    val unfold: PaneBackingViewModel.LunicleColumnView? = null,
)

/** Options, filtering, highlight and keys of property menus (see the file header). */
object LunicleBoardMenu {

    /** How long a changed issue's row flashes (the fade's length). */
    const val FLASH_MS: Long = 1_500

    /**
     * Where issue [issueId] of [view] (the board as drawn before the
     * change) ends up after a change: moving to [newStatus] (`null`: it
     * stays in its column, a priority or assignee change) puts it under
     * that column, where it flashes and the caret follows. A folded target
     * column is unfolded ([LunicleChangePlacement.unfold]) — unless it is a
     * closing column that starts folded (`foldedByDefault`, e.g. Closed):
     * that stays folded, and its own row flashes and takes the caret.
     */
    fun placeAfterChange(view: PaneBackingViewModel.LunicleBoardView, issueId: Long, newStatus: String?): LunicleChangePlacement {
        val from = view.columns.firstOrNull { c -> c.issues.any { it.issue.id == issueId } }
        val status = newStatus ?: from?.column?.status?.name.orEmpty()
        val issueRef = LunicleRowRef(LunicleRowKind.ISSUE, status, issueId)
        val target = view.columns.firstOrNull { it.column.status.name == status } ?: return LunicleChangePlacement(issueRef, issueRef)
        if (!target.folded) return LunicleChangePlacement(issueRef, issueRef)
        if (target.column.foldedByDefault) {
            val columnRef = LunicleRowRef(LunicleRowKind.COLUMN, status)
            return LunicleChangePlacement(columnRef, columnRef)
        }
        return LunicleChangePlacement(issueRef, issueRef, unfold = target)
    }

    /** Section names, as headers and a pill menu's head. */
    fun sectionOf(field: LuniclePill.Field): String = when (field) {
        LuniclePill.Field.STATUS -> "Status"
        LuniclePill.Field.PRIORITY -> "Priority"
        LuniclePill.Field.ASSIGNEE -> "Assignee"
    }

    /**
     * Who the `@` menu offers besides `@nobody`, or `null` when there is
     * no `@` menu: the board's `assignableUsers` (LNL-223) as sent — same-
     * name users twice, as Lunicle lists them. Absent, a token that may
     * file issues ([canCreate]) is on an older Lunicle without the field:
     * then the distinct assignees already on the board, sorted, stand in.
     * Absent for someone who may not file issues (a viewer): no `@` menu.
     */
    fun assignees(board: LunicleBoard, canCreate: Boolean): List<String>? {
        board.assignableUsers?.let { return it }
        if (!canCreate) return null
        // Fallback for a Lunicle before LNL-223: the names already on the board.
        return board.issues.mapNotNull { it.assignee?.takeIf { a -> a.isNotBlank() } }.distinct().sortedBy { it.lowercase() }
    }

    /**
     * The options of [menu] for [issue] (as shown, optimistic edits
     * included) on [board], filtered by its query. [assignees] is
     * [LunicleBoardMenu.assignees]'s list; `null` leaves the `@` menu (and
     * an assignee pill's menu) with `@nobody` only.
     */
    fun options(menu: LunicleMenu, board: LunicleBoard, issue: LunicleBoardIssue, assignees: List<String>?): List<LunicleMenuOption> {
        val fields = when {
            menu.field != null -> listOf(menu.field)
            menu.trigger == '@' -> listOf(LuniclePill.Field.ASSIGNEE)
            else -> listOf(LuniclePill.Field.STATUS, LuniclePill.Field.PRIORITY)
        }
        val q = menu.query.trim().lowercase()
        val out = ArrayList<LunicleMenuOption>()
        for (field in fields) {
            val values: List<String?> = when (field) {
                LuniclePill.Field.STATUS -> LunicleBoardLayout.columns(board).map { it.status.name }
                LuniclePill.Field.PRIORITY -> board.priorities
                LuniclePill.Field.ASSIGNEE -> listOf(null) + assignees.orEmpty()
            }
            val current: String? = when (field) {
                LuniclePill.Field.STATUS -> issue.status
                LuniclePill.Field.PRIORITY -> issue.priority
                LuniclePill.Field.ASSIGNEE -> issue.assignee
            }
            var first = true
            for (value in values) {
                val prefix = if (field == LuniclePill.Field.ASSIGNEE) '@' else '#'
                val label = LunicleBoardLayout.pillText(prefix, value ?: "nobody")
                val name = value ?: "nobody"
                if (q.isNotEmpty() && q !in name.lowercase() && q !in label.substring(1)) continue
                out += LunicleMenuOption(
                    field = field,
                    value = value,
                    label = label,
                    current = value == current,
                    section = sectionOf(field),
                    showHeader = menu.field == null && first,
                )
                first = false
            }
        }
        return out
    }

    /** The menu's head: `#<query>` / `@<query>`, or a pill menu's field name. */
    fun head(menu: LunicleMenu): String = menu.field?.let { sectionOf(it) } ?: "${menu.trigger ?: '#'}${menu.query}"

    /** [menu]'s highlight within a list of [count] options (the last one when the list shrank). */
    fun highlightOf(menu: LunicleMenu, count: Int): Int = if (count <= 0) -1 else menu.highlight.coerceIn(0, count - 1)

    /** One step from [index] in a list of [count], down or up, round the ends. */
    fun wrap(index: Int, count: Int, down: Boolean): Int {
        if (count <= 0) return 0
        val at = index.coerceIn(0, count - 1)
        return ((at + if (down) 1 else -1) % count + count) % count
    }

    /**
     * What key [key] (a `KeyboardEvent.key`, unmodified) does to [menu],
     * whose options are [options]:
     *
     *  - ↑ / ↓ move the highlight round the ends;
     *  - Enter or Tab pick the highlighted option (close when none matches);
     *  - Escape closes;
     *  - on a typed menu: ⌫ deletes the query's last character, closing
     *    on an empty query; a space closes; any other character narrows
     *    the list (highlight back to the first option);
     *  - on a pill's menu a character closes it and is typed as usual;
     *  - anything else passes.
     */
    fun key(menu: LunicleMenu, options: List<LunicleMenuOption>, key: String): LunicleMenuStep {
        val n = options.size
        val hi = highlightOf(menu, n)
        return when (key) {
            "ArrowDown", "ArrowUp" -> LunicleMenuStep.Update(menu.copy(highlight = wrap(hi, n, key == "ArrowDown")))
            "Enter", "Tab" -> options.getOrNull(hi)?.let { LunicleMenuStep.Pick(it) } ?: LunicleMenuStep.Close
            "Escape" -> LunicleMenuStep.Close
            "Backspace" -> when {
                menu.trigger == null -> LunicleMenuStep.CloseAndPass
                menu.query.isEmpty() -> LunicleMenuStep.Close
                else -> LunicleMenuStep.Update(menu.copy(query = menu.query.dropLast(1), highlight = 0))
            }
            else -> when {
                key.length != 1 -> LunicleMenuStep.Pass
                menu.trigger == null -> LunicleMenuStep.CloseAndPass
                key == " " -> LunicleMenuStep.Close
                else -> LunicleMenuStep.Update(menu.copy(query = menu.query + key, highlight = 0))
            }
        }
    }

    /**
     * The resolution popup picking status [option] needs first, or `null`
     * when it needs none: only a status whose column has
     * `requiresResolution` and that is not already the issue's status.
     */
    fun resolutionStep(menu: LunicleMenu, option: LunicleMenuOption, board: LunicleBoard, issue: LunicleBoardIssue): LunicleResolutionChoice? {
        if (option.field != LuniclePill.Field.STATUS || option.value == null || option.value == issue.status) return null
        val status = board.statuses.firstOrNull { it.name == option.value } ?: return null
        if (!status.requiresResolution) return null
        return LunicleResolutionChoice(menu.board, menu.issueId, status.name, board.resolutions)
    }
}
