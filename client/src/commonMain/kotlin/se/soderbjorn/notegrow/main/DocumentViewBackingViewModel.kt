/*
 * DocumentViewBackingViewModel.kt
 * -------------------------------
 * Per-viewer state on top of the shared `DocumentBackingViewModel`. This file
 * owns everything that belongs to *one* viewer of the note — cursor position,
 * selection anchor, and the current "zoom target" (the bullet whose subtree
 * is currently displayed as the editor's root).
 *
 * The actual editing behavior is split across small siblings in this package:
 *
 *   - [TextEditingViewModel]  — typing, deletion, indent/outdent, movement,
 *     selection.
 *   - [ZoomNavigation]        — zoom in/out and zoom-info resolution.
 *   - SelectionHelper.kt      — pure helpers (no class).
 *
 * This class composes those slices and remains the consumer-facing aggregate
 * so platform `MainViewModel`s can keep their existing call sites.
 *
 * The "zoom" feature models Notegrow as an infinite outliner: clicking a
 * bullet marker makes that bullet the logical root of the current view, so
 * only its descendants are shown and edited. Zoom state is view-local — the
 * underlying document is never reshaped.
 *
 * This file is commonMain — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The view-layer ViewModel for a single viewer (one window / pane / device)
 * of the shared note. It mirrors `DocumentBackingViewModel`'s content and
 * adds cursor, selection anchor, and zoom target on top.
 *
 * ### Callers
 * - Created per viewer by the platform DI graph (`JsAppGraph`) and injected
 *   into the platform `MainViewModel`.
 * - `MainViewModel` delegates every intent to this class one-for-one.
 * - Tests exercise the intents directly without going through a platform
 *   VM.
 *
 * @param documentBackingViewModel The shared document VM this viewer reads
 *   from and writes to.
 * @param scope Coroutine scope owning the collector that mirrors the
 *   document VM's state flow.
 */
class DocumentViewBackingViewModel(
    private val documentBackingViewModel: DocumentBackingViewModel,
    private val scope: CoroutineScope,
) {
    /**
     * Immutable snapshot of one viewer's state.
     *
     * @property documentState A mirror of the latest `DocumentBackingViewModel.State`,
     *   or `null` before the first emission. Kept in sync by the collector
     *   in [init] and by [patch] after any edit, so every emission is a
     *   consistent snapshot of both document + viewer state.
     * @property cursorRow Row of the caret, in absolute document coordinates.
     * @property cursorCol Column of the caret on [cursorRow].
     * @property anchorRow If non-null, together with [anchorCol] defines the
     *   other end of the active selection. `null` means "no selection — the
     *   caret is just a point".
     * @property anchorCol Column companion to [anchorRow].
     * @property zoomedLineId If non-null, the [LineId] of the bullet whose
     *   subtree is being shown. `null` means "at root — show everything".
     *   Stored as an id (not a row) so the reference survives edits that
     *   shift rows above the zoom target.
     */
    data class State(
        val documentState: DocumentBackingViewModel.State? = null,
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null,
        val zoomedLineId: LineId? = null,
        /**
         * Within-file fold state. A bullet whose [LineId] is in this set is
         * rendered with its chevron rotated and its descendants hidden by
         * the paint loop and skipped by hit-testing. File-boundary refs
         * also live here while collapsed; expanding them additionally calls
         * `DocumentBackingViewModel.expandSubtree` to lazy-load the file.
         *
         * Within-file collapse is intentionally *not* an unload: the rows
         * already live in the parent file the user just loaded, so hiding
         * them visually has no further memory benefit and avoids
         * complications with autosave seeing a truncated parent file.
         */
        val collapsedIds: Set<LineId> = emptySet(),
        /**
         * Set of [LineId]s the default-collapse pass has already processed,
         * so the same id is not auto-collapsed again after the user has
         * explicitly expanded it. Each emission of `documentState` runs the
         * pass on `documentState.lineIds - seenLineIds`, adding parent
         * bullets and refs found in that diff to [collapsedIds]. Internal
         * — not meaningful to consumers.
         */
        internal val seenLineIds: Set<LineId> = emptySet(),
    ) {
        /** `true` once the document has loaded from disk at least once. */
        val isLoaded: Boolean get() = documentState?.isLoaded == true

        /**
         * `true` while the document VM is mid-save on a tick that promotes or
         * demotes a subtree across the per-file boundary — see
         * [DocumentBackingViewModel.State.isRestructuring]. Surfaced here so
         * platform views can render a small status indicator without poking
         * into the document-level state directly.
         */
        val isRestructuring: Boolean get() = documentState?.isRestructuring == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")
    }

    /**
     * A normalized selection range (start ≤ end, by row-then-column), or
     * absent when the caret is not extending any selection. Produced by
     * `selectionOf` from the raw anchor/cursor pair.
     */
    data class Selection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    /**
     * Resolved zoom geometry. Computed on demand by `zoomInfoOf` from the
     * current zoom id and document state — never stored, so it cannot go
     * stale.
     *
     * @property zoomRow The absolute row of the zoom target bullet itself.
     * @property zoomIndent The indent (leading-space count) of the zoom
     *   target line. Descendant lines are those with indent strictly greater
     *   than this.
     * @property startRow First visible row (`zoomRow + 1`).
     * @property endRowInclusive Last visible row. If `endRowInclusive < startRow`
     *   the subtree is currently empty.
     * @property titleText The display text of the zoom target with the
     *   leading indent and `"* "` marker stripped. Used by the view to
     *   render the sticky header.
     */
    data class ZoomInfo(
        val zoomRow: Int,
        val zoomIndent: Int,
        val startRow: Int,
        val endRowInclusive: Int,
        val titleText: String
    ) {
        /** `true` when the zoom target has at least one descendant bullet. */
        val hasVisibleRows: Boolean get() = startRow <= endRowInclusive
    }

    private val _stateFlow = MutableStateFlow(State())

    /**
     * Observable stream of view states. Platform `MainViewModel`s collect
     * this and re-emit through their own envelope.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val textEditing = TextEditingViewModel(
        documentBackingViewModel = documentBackingViewModel,
        stateProvider = { _stateFlow.value },
        applyState = { _stateFlow.value = it },
        mutate = { transform -> mutate(transform) },
        patch = { transform -> patch(transform) },
    )

    private val zoomNavigation = ZoomNavigation(
        documentBackingViewModel = documentBackingViewModel,
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        scope = scope,
    )

    init {
        scope.launch {
            documentBackingViewModel.stateFlow.collect { docState ->
                val merged = _stateFlow.value.copy(documentState = docState)
                val withDefaults = applyDefaultCollapseIfNeeded(merged)
                _stateFlow.value = reconcile(withDefaults)
            }
        }
    }

    /**
     * Adds newly observed parent-bullet and promoted-ref [LineId]s to
     * [State.collapsedIds] so each subtree starts folded the first time
     * we see it. The pass runs on `lineIds - seenLineIds` so it does not
     * re-collapse ids the user has explicitly expanded — once an id flows
     * through this once it joins [State.seenLineIds] and is never
     * re-considered.
     *
     * Triggered initially on `isLoaded: false → true`, and again on every
     * subsequent emission that introduces fresh ids (e.g. the document VM
     * spliced in children on `expandSubtree`, or the user typed a newline
     * which allocated a new id).
     */
    private fun applyDefaultCollapseIfNeeded(state: State): State {
        val docState = state.documentState ?: return state
        if (!docState.isLoaded) return state
        val seen = state.seenLineIds
        val currentIds = docState.lineIds
        // Fast path: no new ids to consider.
        if (seen.size == currentIds.size && seen.containsAll(currentIds)) return state

        val newCollapsed = HashSet<LineId>()
        for ((idx, id) in currentIds.withIndex()) {
            if (id in seen) continue
            if (idx !in docState.lines.indices) continue
            val line = docState.lines[idx]
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0) continue
            val isParent = DocumentLayout.hasChildren(docState.lines, idx, indent)
            val isRef = documentBackingViewModel.isPromotedRef(id)
            if (isParent || isRef) newCollapsed += id
        }
        val nextSeen = seen + currentIds
        if (newCollapsed.isEmpty() && nextSeen.size == seen.size) return state
        return state.copy(
            collapsedIds = state.collapsedIds + newCollapsed,
            seenLineIds = nextSeen,
        )
    }

    /**
     * `true` if [lineId] is a file-boundary reference whose children live
     * in a separate `.nogr` file. The paint loop uses this to decide
     * whether to render a chevron on a bullet that has no children
     * currently in `lines` (because the file is folded).
     */
    fun isPromotedRef(lineId: LineId): Boolean = documentBackingViewModel.isPromotedRef(lineId)

    /**
     * Toggle the fold state of [lineId]. For file-boundary references the
     * call also drives `DocumentBackingViewModel.expandSubtree` /
     * `collapseSubtree` so children are lazy-loaded on expand and unloaded
     * on collapse. For within-file parents, only [State.collapsedIds] is
     * mutated — the children are already in `lines` and stay there.
     */
    fun toggleCollapse(lineId: LineId) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val isRef = documentBackingViewModel.isPromotedRef(lineId)
        if (isRef) {
            val docState = current.documentState
            val isExpanded = docState != null && lineId in docState.expandedRefIds
            if (isExpanded) {
                documentBackingViewModel.collapseSubtree(lineId)
                patch { it.copy(collapsedIds = it.collapsedIds + lineId) }
            } else {
                // Optimistically mark as expanded in view state so the chevron
                // animates open immediately. The expand intent re-publishes
                // documentState; our collector then runs reconcile.
                patch { it.copy(collapsedIds = it.collapsedIds - lineId) }
                scope.launch { documentBackingViewModel.expandSubtree(lineId) }
            }
        } else {
            patch {
                val next = if (lineId in it.collapsedIds) {
                    it.collapsedIds - lineId
                } else {
                    it.collapsedIds + lineId
                }
                it.copy(collapsedIds = next)
            }
        }
    }

    /**
     * Removes every ancestor of [row] from [State.collapsedIds] so the row
     * is visible. Walks upward through bullet rows of strictly decreasing
     * indent. Used by editing intents (Tab indent, newline that creates a
     * child) to auto-expand the parent of newly inserted content.
     */
    internal fun revealAncestors(row: Int) {
        val current = _stateFlow.value
        val docState = current.documentState ?: return
        if (row !in docState.lines.indices) return
        val rowIndent = DocumentLayout.bulletAsteriskColumn(docState.lines[row])
        var lookingFor = if (rowIndent >= 0) rowIndent else Int.MAX_VALUE
        if (lookingFor <= 0) return
        val toReveal = mutableSetOf<LineId>()
        var r = row - 1
        while (r >= 0 && lookingFor > 0) {
            val col = DocumentLayout.bulletAsteriskColumn(docState.lines[r])
            if (col in 0 until lookingFor) {
                toReveal += docState.lineIds[r]
                lookingFor = col
            }
            r--
        }
        if (toReveal.isNotEmpty() && (toReveal intersect current.collapsedIds).isNotEmpty()) {
            patch { it.copy(collapsedIds = it.collapsedIds - toReveal) }
        }
    }

    // ------------------------------------------------------------------ edits

    /** See [TextEditingViewModel.insertChar]. */
    fun insertChar(char: Char) = textEditing.insertChar(char)

    /** See [TextEditingViewModel.insertNewline]. */
    fun insertNewline() {
        textEditing.insertNewline()
        revealAncestors(_stateFlow.value.cursorRow)
    }

    /** See [TextEditingViewModel.insertText]. */
    fun insertText(text: String) {
        textEditing.insertText(text)
        if ('\n' in text || '\r' in text) revealAncestors(_stateFlow.value.cursorRow)
    }

    /** See [TextEditingViewModel.backspace]. */
    fun backspace() = textEditing.backspace()

    /** See [TextEditingViewModel.indentLine]. */
    fun indentLine(amount: Int = TAB_SIZE) {
        textEditing.indentLine(amount)
        // Tab on a bullet line slots it under the previous bullet as a child;
        // if that parent was folded, reveal it so the new child stays visible.
        revealAncestors(_stateFlow.value.cursorRow)
    }

    /** See [TextEditingViewModel.outdentLine]. */
    fun outdentLine(amount: Int = TAB_SIZE) = textEditing.outdentLine(amount)

    /** See [TextEditingViewModel.isBulletLine]. */
    fun isBulletLine(): Boolean = textEditing.isBulletLine()

    // ------------------------------------------------------------------ zoom

    /** See [ZoomNavigation.zoomInto]. */
    fun zoomInto(row: Int) = zoomNavigation.zoomInto(row)

    /** See [ZoomNavigation.zoomOut]. */
    fun zoomOut() = zoomNavigation.zoomOut()

    /** See [ZoomNavigation.zoomTo]. */
    fun zoomTo(lineId: LineId?) = zoomNavigation.zoomTo(lineId)

    /** See [ZoomNavigation.zoomInfo]. */
    fun zoomInfo(state: State = _stateFlow.value): ZoomInfo? = zoomNavigation.zoomInfo(state)

    /** See [ZoomNavigation.bulletAncestors]. */
    fun bulletAncestors(state: State = _stateFlow.value): List<BreadcrumbAncestor> =
        zoomNavigation.bulletAncestors(state)

    /** See [ZoomNavigation.zoomPathSegments]. */
    fun zoomPathSegments(state: State = _stateFlow.value): List<String> =
        zoomNavigation.zoomPathSegments(state)

    // ------------------------------------------------------------------ movement

    /** See [TextEditingViewModel.moveLeft]. */
    fun moveLeft(extend: Boolean = false) = textEditing.moveLeft(extend)

    /** See [TextEditingViewModel.moveRight]. */
    fun moveRight(extend: Boolean = false) = textEditing.moveRight(extend)

    /** See [TextEditingViewModel.moveUp]. */
    fun moveUp(extend: Boolean = false) = textEditing.moveUp(extend)

    /** See [TextEditingViewModel.moveDown]. */
    fun moveDown(extend: Boolean = false) = textEditing.moveDown(extend)

    /** See [TextEditingViewModel.moveTo]. */
    fun moveTo(row: Int, col: Int, extend: Boolean = false) = textEditing.moveTo(row, col, extend)

    /** See [TextEditingViewModel.moveLineStart]. */
    fun moveLineStart(extend: Boolean = false) = textEditing.moveLineStart(extend)

    /** See [TextEditingViewModel.moveLineEnd]. */
    fun moveLineEnd(extend: Boolean = false) = textEditing.moveLineEnd(extend)

    /** See [TextEditingViewModel.moveDocStart]. */
    fun moveDocStart(extend: Boolean = false) = textEditing.moveDocStart(extend)

    /** See [TextEditingViewModel.moveDocEnd]. */
    fun moveDocEnd(extend: Boolean = false) = textEditing.moveDocEnd(extend)

    /** See [TextEditingViewModel.moveWordLeft]. */
    fun moveWordLeft(extend: Boolean = false) = textEditing.moveWordLeft(extend)

    /** See [TextEditingViewModel.moveWordRight]. */
    fun moveWordRight(extend: Boolean = false) = textEditing.moveWordRight(extend)

    // ------------------------------------------------------------------ selection

    /** See [TextEditingViewModel.selectAll]. */
    fun selectAll() = textEditing.selectAll()

    /** See [TextEditingViewModel.selectWord]. */
    fun selectWord(row: Int, col: Int) = textEditing.selectWord(row, col)

    /** See [TextEditingViewModel.selectLine]. */
    fun selectLine(row: Int) = textEditing.selectLine(row)

    /** See [TextEditingViewModel.clearSelection]. */
    fun clearSelection() = textEditing.clearSelection()

    /** See [TextEditingViewModel.deleteSelectionIfAny]. */
    fun deleteSelectionIfAny(): Boolean = textEditing.deleteSelectionIfAny()

    /** See [TextEditingViewModel.getSelectedText]. */
    fun getSelectedText(): String? = textEditing.getSelectedText()

    /** See [TextEditingViewModel.onCutRequested]. */
    fun onCutRequested(): String? = textEditing.onCutRequested()

    // ------------------------------------------------------------------ helpers

    /**
     * Applies [transform] to the current state, then re-runs [reconcile]
     * (cursor clamping, zoom validity) and publishes. Used by every movement
     * and selection intent that does not touch document content.
     */
    private inline fun mutate(transform: (State) -> State) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        _stateFlow.value = reconcile(transform(current))
    }

    /**
     * Patches the view state while ensuring [State.documentState] is
     * refreshed to the document VM's latest. Used right after calling a
     * document VM mutation so readers never see a view state whose cursor
     * references stale document content.
     */
    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        val patched = transform(current).copy(
            documentState = documentBackingViewModel.stateFlow.value
        )
        _stateFlow.value = reconcile(patched)
    }

    /**
     * Post-mutation normalization: clamps caret/anchor into the current
     * document, self-heals zoom state when the target disappears or its
     * subtree becomes empty, and clamps caret/anchor into the zoom range
     * when zoomed.
     *
     * Called from every publish path ([init]'s collector, [mutate],
     * [patch]).
     *
     * @param state The candidate state produced by an intent.
     * @return A normalized state safe to publish.
     */
    private fun reconcile(state: State): State {
        val docState = state.documentState
        if (docState?.isLoaded != true) return state
        val lines = state.lines
        val lastRow = lines.lastIndex
        val baseRow = state.cursorRow.coerceIn(0, lastRow)
        // Defensive lower bound: on bullet rows the cursor must sit at or after the
        // `"* "` marker. Anchors are *not* clamped — explicit `selectLine` / `selectAll`
        // intentionally anchor at column 0, and we don't want this safety net to
        // alter their semantics.
        val baseLineMin = DocumentLayout.textStartCol(lines[baseRow])
        val baseCol = state.cursorCol.coerceIn(baseLineMin, lines[baseRow].length)
        val baseAr = state.anchorRow?.coerceIn(0, lastRow)
        val baseAc = if (baseAr != null) state.anchorCol?.coerceIn(0, lines[baseAr].length) else null
        var clamped = state.copy(
            cursorRow = baseRow, cursorCol = baseCol,
            anchorRow = baseAr, anchorCol = baseAc
        )
        if (clamped.zoomedLineId != null) {
            val zoom = zoomInfoOf(clamped)
            if (zoom == null || !zoom.hasVisibleRows) {
                clamped = clamped.copy(zoomedLineId = null)
            } else {
                val zRow = clamped.cursorRow.coerceIn(zoom.startRow, zoom.endRowInclusive)
                val zLineMin = DocumentLayout.textStartCol(lines[zRow])
                val zCol = clamped.cursorCol.coerceIn(zLineMin, lines[zRow].length)
                val zAr = clamped.anchorRow?.coerceIn(zoom.startRow, zoom.endRowInclusive)
                val zAc = if (zAr != null) clamped.anchorCol?.coerceIn(0, lines[zAr].length) else null
                clamped = clamped.copy(cursorRow = zRow, cursorCol = zCol, anchorRow = zAr, anchorCol = zAc)
            }
        }
        // Clamp into visible rows: if the cursor sits inside a folded subtree
        // (or on a now-removed row from a ref collapse), walk up to the
        // nearest visible ancestor and clear selection.
        return clampToVisible(clamped)
    }

    /**
     * Walks the cursor (and anchor) up to the nearest visible row when the
     * current row sits inside a folded subtree. Selections are cleared on
     * any such adjustment — preserving them through a fold would require
     * mapping ranges through hidden rows, which is rarely useful and easy
     * to get wrong.
     */
    private fun clampToVisible(state: State): State {
        val docState = state.documentState ?: return state
        val zoom = zoomInfoOf(state)
        val startRow = zoom?.startRow ?: 0
        val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
        if (endRowInclusive < startRow) return state
        val visible = DocumentLayout.visibleRowsOf(
            docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
        )
        if (visible.isEmpty()) return state
        val visibleSet = visible.toHashSet()
        if (state.cursorRow in visibleSet) return state
        // Walk upward to find the nearest visible ancestor.
        var target = state.cursorRow
        while (target > 0 && target !in visibleSet) target--
        if (target !in visibleSet) target = visible.first()
        val targetLine = docState.lines[target]
        val safeCol = state.cursorCol.coerceIn(
            DocumentLayout.textStartCol(targetLine), targetLine.length
        )
        return state.copy(
            cursorRow = target,
            cursorCol = safeCol,
            anchorRow = null,
            anchorCol = null,
        )
    }

    companion object {
        /** Standard indent step across the app (two spaces). */
        const val TAB_SIZE: Int = 2

        /**
         * Normalizes an anchor + cursor pair into a [Selection]. Returns
         * `null` when there is no anchor (caret only) or when the anchor
         * and caret coincide (empty selection). Used by the view layer so
         * it doesn't re-implement the "which end comes first" logic.
         */
        fun selectionOf(state: State): Selection? = se.soderbjorn.notegrow.main.selectionOf(state)
    }
}
