/*
 * Document.kt (commonMain)
 * ------------------------
 * KMP-world model for one loaded file — normally a node's `.treefacts`
 * outline. Owns the canonical text content (lines, stable per-line ids),
 * which rows are folder-backed bullets and where their folders are, which
 * of those have their children on disk only, and the autosave loop that
 * flushes it all back through `NoteRepository.save`.
 *
 * One [Document] = one file. Multiple panes (`PaneBackingViewModel`) can
 * subscribe to the same [Document] to view/edit the same file together; the
 * [DocumentRegistry] hands out a shared instance when more than one pane
 * acquires the same `fileRel`.
 *
 * This class is intentionally view-agnostic — no cursors, selections, zoom,
 * undo, or platform UI live here. Those concerns belong on
 * `PaneBackingViewModel`.
 *
 * ### Saving
 * A save runs 1 s after the last edit, and at most 5 s apart while the user
 * types continuously. [shutdown] flushes one final save. Each save hands
 * the whole outline to the repository, which reconciles it with the
 * folders on disk (promotions, demotions, renames, moves, trash) in one
 * pass.
 *
 * ### Folder-backed bullets and undo
 * Deleting a folder-backed bullet leaves its entry in [promotedSubtrees];
 * the next save sees the id gone and moves the folder to the trash, and
 * the entry is kept, pointing into the trash. If undo brings the id back,
 * the next save moves the folder back. Cut and paste hand the folder to the
 * pasted row ([rememberCut] / [adoptCut]), so the folder is moved rather
 * than trashed and recreated.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.data.PromotedRef
import se.soderbjorn.treefacts.data.SubtreeCodec

/**
 * One loaded treefacts file.
 *
 * ### Callers
 * - Created and lifecycled exclusively by [DocumentRegistry].
 * - Read from / written to by `PaneBackingViewModel` for every editor
 *   intent that touches text content. Multiple panes may share one
 *   [Document] when they view the same file.
 *
 * @property fileRel The vault-relative path of the file this document
 *   represents (e.g. `.treefacts`, `Recipes/.treefacts`, `Starred.md`).
 *   Immutable — to view a different file, acquire a different [Document].
 * @param repository Persistent storage, shared across documents.
 * @param scope Coroutine scope that owns the initial load and the autosave
 *   loop. Normally the per-document jobs are cancelled by [shutdown].
 * @param saveDebounceMillis Quiet time after the last edit before a save.
 * @param maxSaveDelayMillis Longest a dirty document waits while the user
 *   keeps typing; a save is forced this long after the first unsaved edit.
 * @param onAfterSave Hook fired after every save, used by
 *   [DocumentRegistry] to refresh the shared vault-listings cache.
 */
class Document(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    val fileRel: String,
    private val saveDebounceMillis: Long = DEFAULT_SAVE_DEBOUNCE_MILLIS,
    private val maxSaveDelayMillis: Long = DEFAULT_MAX_SAVE_DELAY_MILLIS,
    private val onAfterSave: suspend () -> Unit = {},
) {
    /**
     * Immutable snapshot of one file's content at a point in time.
     *
     * @property lines One entry per logical line. Invariant: always
     *   non-empty — an empty outline is `listOf("* ")`, an empty plain
     *   file `listOf("")`. In an outline ([bulletsOnly]) every line the
     *   editor creates is a bullet.
     * @property lineIds Parallel list of stable identifiers, one per
     *   entry in [lines].
     * @property isLoaded `false` until the initial disk read completes.
     * @property isRestructuring `true` while a save that changes the
     *   folder structure (promote, demote, rename, move, trash) runs.
     * @property unloadedRefIds Folder-backed rows whose children are on
     *   disk only — not spliced into [lines]. Every `+` bullet starts here
     *   after a load; expanding one removes it, collapsing the last pane's
     *   expansion adds it back. A folder-backed row *not* in this set has
     *   its children in [lines] (possibly none), and save rewrites its
     *   outline from them. Stored as the unloaded set, not the loaded one,
     *   so an undo snapshot taken before a bullet was promoted still reads
     *   correctly after the promotion.
     */
    data class State(
        val lines: List<String> = listOf(""),
        val lineIds: List<LineId> = listOf(LineId(0L)),
        val isLoaded: Boolean = false,
        val isRestructuring: Boolean = false,
        val unloadedRefIds: Set<LineId> = emptySet(),
    )

    /**
     * Location of the end of an inserted run, returned by [insertText]
     * so callers can place the cursor immediately after the new text.
     */
    data class InsertResult(val endRow: Int, val endCol: Int)

    /**
     * `true` when every line of this document is a bullet — the outline
     * mode of a `.treefacts` node (TRF-4). The editing intents in
     * `TextEditingViewModel` then never produce a non-bullet line: Enter
     * and Backspace never strip the `"* "` marker, pasted text becomes one
     * bullet per line, and an empty document is a single empty bullet.
     *
     * `false` for any other file (plain Markdown such as `Starred.md`),
     * which keeps the plain-line editing and rendering paths. This is the
     * switch the Markdown mode for foreign `.md` files (TRF-7) builds on.
     */
    val bulletsOnly: Boolean = NoteRepository.isOutlineFile(fileRel)

    /** The line an empty document holds: an empty bullet in an outline. */
    private val emptyLine: String get() = if (bulletsOnly) NoteRepository.EMPTY_OUTLINE_LINE else ""

    private val _stateFlow = MutableStateFlow(State())

    /**
     * Observable stream of document snapshots. Emits once with the
     * empty initial state, again after the initial disk read completes,
     * and then on every edit. Pane VMs collect this to mirror content.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val _dirtyFlow = MutableStateFlow(false)

    /**
     * `true` while this document holds edits not yet saved — exactly when
     * the autosave loop would write. Collected by [DocumentRegistry] for
     * the app-chrome unsaved-changes indicator.
     */
    val dirtyFlow: StateFlow<Boolean> = _dirtyFlow.asStateFlow()

    private var lastSavedText: String = ""

    /**
     * [State.unloadedRefIds] as of the last save. Part of the dirty check
     * because the text alone can come back to the saved value while the
     * meaning changed — expand a folder-backed bullet and delete all its
     * children, and the text equals the collapsed text again, yet the
     * folder must now be demoted.
     */
    private var lastSavedUnloaded: Set<LineId> = emptySet()
    private var nextIdValue: Long = 1L

    /**
     * Folder-backed rows and where their folders are. Populated on load
     * and splice-in, updated after every save. An entry whose id vanished
     * from `lineIds` is a deleted bullet: the next save trashes its folder
     * and the entry then points into the trash (and joins [trashedIds]).
     */
    private val promotedSubtrees: MutableMap<LineId, PromotedRef> = mutableMapOf()

    /** Ids in [promotedSubtrees] whose folder has been moved to the trash. */
    private val trashedIds: MutableSet<LineId> = mutableSetOf()

    /**
     * What the last [rememberCut] took out, so [adoptCut] can hand the
     * cut bullets' folders to the pasted rows.
     *
     * @property text The cut text, exactly as returned to the clipboard.
     * @property refs Row offset within [text] → the cut row's old id and
     *   title, for every folder-backed row the cut contained whole.
     */
    private data class CutRecord(val text: String, val refs: Map<Int, Pair<LineId, String>>)

    private var lastCut: CutRecord? = null

    /**
     * Single-flight lock serialising saves with each other, with
     * [shutdown]'s final flush, and with collapsing a folder-backed
     * bullet (which must never interleave with a save).
     */
    private val saveLock = Mutex()

    /**
     * Serializes [acquireExpansion] / [releaseExpansion] across panes so
     * the refcount map and the splice-in / splice-out stay consistent.
     */
    private val expansionLock = Mutex()

    /**
     * Per-id refcount of "panes that want this ref expanded right now".
     * Mutated only under [expansionLock].
     */
    private val expansionRefcounts: MutableMap<LineId, Int> = mutableMapOf()

    private var loadJob: Job? = null
    private var autoSaveJob: Job? = null
    private var dirtyWatchJob: Job? = null

    /**
     * Schedules the initial disk read and starts the autosave loop on
     * [scope]. Idempotent. Called by [DocumentRegistry] right after
     * construction.
     */
    fun start() {
        if (loadJob != null) return
        loadJob = scope.launch { loadFromDisk() }
        autoSaveJob = scope.launch { runAutoSaveLoop() }
        dirtyWatchJob = scope.launch { _stateFlow.collect { recomputeDirty() } }
    }

    /**
     * Cancels the autosave loop and flushes one final save. Called by
     * [DocumentRegistry] when the last pane releases this document.
     */
    suspend fun shutdown() {
        autoSaveJob?.cancelAndJoin()
        autoSaveJob = null
        loadJob?.cancelAndJoin()
        loadJob = null
        dirtyWatchJob?.cancelAndJoin()
        dirtyWatchJob = null
        saveLock.withLock { saveIfDirtyUnderLock() }
        _dirtyFlow.value = false
    }

    /**
     * Saves now if anything is unsaved, waiting for any save already in
     * flight. Used by tests and by callers that need the disk current.
     */
    suspend fun flush() {
        saveLock.withLock { saveIfDirtyUnderLock() }
    }

    private fun currentText(state: State): String = state.lines.joinToString("\n")

    /**
     * Dirty ⇔ loaded and either the text or the set of unloaded
     * folder-backed rows differs from the last save.
     */
    private fun isDirty(state: State): Boolean =
        state.isLoaded && (currentText(state) != lastSavedText || state.unloadedRefIds != lastSavedUnloaded)

    private fun recomputeDirty() {
        _dirtyFlow.value = isDirty(_stateFlow.value)
    }

    // ------------------------------------------------------------ primitives

    /**
     * Inserts [text] into the document at the given cursor position. If
     * [text] contains `\n` (or `\r\n` / `\r`, normalized to `\n`) the
     * insertion spans multiple rows. Stable ids on existing rows are
     * preserved; every new row gets a fresh [LineId].
     */
    fun insertText(row: Int, col: Int, text: String): InsertResult {
        val state = _stateFlow.value
        if (!state.isLoaded) return InsertResult(row, col)
        if (text.isEmpty()) return InsertResult(row, col)
        val incoming = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val currentLine = state.lines[row]
        val before = currentLine.substring(0, col)
        val after = currentLine.substring(col)
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        val result: InsertResult
        if (incoming.size == 1) {
            newLines[row] = before + incoming[0] + after
            result = InsertResult(row, col + incoming[0].length)
        } else {
            newLines[row] = before + incoming.first()
            for (i in 1 until incoming.size - 1) {
                newLines.add(row + i, incoming[i])
                newIds.add(row + i, allocateId())
            }
            val lastIdx = row + incoming.size - 1
            newLines.add(lastIdx, incoming.last() + after)
            newIds.add(lastIdx, allocateId())
            result = InsertResult(lastIdx, incoming.last().length)
        }
        _stateFlow.value = state.copy(lines = newLines, lineIds = newIds)
        return result
    }

    /** Convenience for `insertText(row, col, "\n")`. */
    fun insertNewline(row: Int, col: Int): InsertResult = insertText(row, col, "\n")

    /**
     * Inserts [content] as a whole new line at index [row], shifting the
     * existing row at [row] and everything below it down by one. The new
     * row gets a fresh [LineId]; every existing row keeps its id — unlike
     * a col-0 [insertText], which would hand row [row]'s identity (fold
     * state, zoom target, backing folder) to the inserted line. Called by
     * `TextEditingViewModel.insertSiblingAboveAtTextStartIfAny`.
     *
     * @param content Full line content including any indent and bullet
     *   marker. Must not contain `"\n"`.
     */
    fun insertLine(row: Int, content: String) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        newLines.add(row, content)
        newIds.add(row, allocateId())
        _stateFlow.value = state.copy(lines = newLines, lineIds = newIds)
    }

    /**
     * Removes row [row] entirely. Every other row keeps its [LineId] —
     * unlike a [delete] across the newline, which keeps the *upper* row's
     * id and would drop the identity (fold state, backing folder) of the
     * row that moves up. Removing the last remaining row leaves one empty
     * line (`"* "` when [bulletsOnly]). Called by
     * `TextEditingViewModel.deleteEmptyBulletWithoutMerge`.
     */
    fun deleteLine(row: Int) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        if (row !in state.lines.indices) return
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        newLines.removeAt(row)
        newIds.removeAt(row)
        if (newLines.isEmpty()) {
            newLines += emptyLine
            newIds += allocateId()
        }
        _stateFlow.value = state.copy(lines = newLines, lineIds = newIds)
    }

    /**
     * Deletes the run from `(startRow, startCol)` up to but not
     * including `(endRow, endCol)`. Multi-row deletions merge the tail
     * of [endRow] onto [startRow] and drop the intermediate rows
     * entirely; their stable ids are released, [startRow]'s id is kept.
     * A deleted folder-backed row's folder goes to the trash on the next
     * save (see the file header).
     */
    fun delete(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        if (startRow == endRow && startCol == endCol) return
        val lines = state.lines
        val merged = lines[startRow].substring(0, startCol) + lines[endRow].substring(endCol)
        val newLines = lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        newLines[startRow] = merged
        repeat(endRow - startRow) {
            newLines.removeAt(startRow + 1)
            newIds.removeAt(startRow + 1)
        }
        _stateFlow.value = state.copy(lines = newLines, lineIds = newIds)
    }

    /**
     * Moves rows [fromStart]..[fromEnd] (inclusive) so they land before
     * the row currently at [insertBefore], replacing their text with
     * [newTexts] (typically re-indented). Unlike a delete plus insert, the
     * moved rows keep their ids — so folder-backed bullets keep their
     * folders and the next save moves them on disk. Called by
     * `PaneBackingViewModel.moveLineRange` (drag and drop).
     *
     * @param insertBefore Row index in the current lines, outside the
     *   moved range; `lines.size` appends.
     * @param newTexts One line per moved row, in order.
     * @return The row index where the first moved row now sits.
     */
    fun moveRows(fromStart: Int, fromEnd: Int, insertBefore: Int, newTexts: List<String>): Int {
        val state = _stateFlow.value
        if (!state.isLoaded) return fromStart
        val count = fromEnd - fromStart + 1
        require(newTexts.size == count) { "moveRows: expected $count lines, got ${newTexts.size}" }
        val lines = state.lines.toMutableList()
        val ids = state.lineIds.toMutableList()
        val movedIds = ids.subList(fromStart, fromEnd + 1).toList()
        repeat(count) {
            lines.removeAt(fromStart)
            ids.removeAt(fromStart)
        }
        val at = (if (insertBefore <= fromStart) insertBefore else insertBefore - count).coerceIn(0, lines.size)
        lines.addAll(at, newTexts)
        ids.addAll(at, movedIds)
        if (lines.isEmpty()) {
            lines += emptyLine
            ids += allocateId()
        }
        _stateFlow.value = state.copy(lines = lines, lineIds = ids)
        return at
    }

    /**
     * Wholesale-replaces the document's text content and stable ids in
     * a single state emission. Used by undo/redo restore. Restoring an id
     * whose folder was trashed brings the folder back on the next save.
     *
     * No-op when the document has not yet loaded.
     */
    fun replaceContent(
        lines: List<String>,
        lineIds: List<LineId>,
        unloadedRefIds: Set<LineId>,
    ) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        _stateFlow.value = state.copy(
            lines = lines,
            lineIds = lineIds,
            unloadedRefIds = unloadedRefIds,
        )
    }

    /**
     * Records what a cut is about to remove, so a later [adoptCut] of the
     * same text can hand the cut bullets' folders to the pasted rows.
     * Called by `TextEditingViewModel.onCutRequested` *before* it deletes
     * the selection.
     *
     * A row counts when the cut contains its whole title: the first row
     * only if the cut starts at or before its text, the last row only if
     * the cut runs to its end. Cutting just a collapsed bullet's title
     * (leaving an empty bullet behind) counts too: pasting the title moves
     * the folder, and the empty bullet left behind becomes a plain leaf.
     *
     * @param startRow First row of the selection.
     * @param startCol Selection start column on [startRow].
     * @param endRow Last row of the selection.
     * @param endCol Selection end column on [endRow].
     * @param text The text the cut puts on the clipboard.
     */
    fun rememberCut(startRow: Int, startCol: Int, endRow: Int, endCol: Int, text: String) {
        val state = _stateFlow.value
        val refs = HashMap<Int, Pair<LineId, String>>()
        for (row in startRow..endRow) {
            val id = state.lineIds.getOrNull(row) ?: continue
            if (id !in promotedSubtrees) continue
            val line = state.lines[row]
            if (row == startRow && startCol > DocumentLayout.textStartCol(line)) continue
            if (row == endRow && endCol < line.length) continue
            refs[row - startRow] = id to SubtreeCodec.titleOf(line)
        }
        lastCut = if (refs.isEmpty()) null else CutRecord(text, refs)
    }

    /**
     * After [text] was inserted starting at [startRow]: if it is exactly
     * what the last [rememberCut] took, hand each cut folder-backed row's
     * folder (and its unloaded state) to the row now holding the same
     * title, so the next save moves the folder instead of trashing it.
     * One cut is adopted at most once; a second paste is a plain copy.
     *
     * Called by `TextEditingViewModel.insertText` after every insert.
     */
    fun adoptCut(startRow: Int, text: String) {
        val cut = lastCut ?: return
        if (cut.text != text) return
        lastCut = null
        val state = _stateFlow.value
        var unloaded = state.unloadedRefIds
        for ((offset, pair) in cut.refs) {
            val (oldId, title) = pair
            val row = startRow + offset
            val newId = state.lineIds.getOrNull(row) ?: continue
            if (newId == oldId || newId in promotedSubtrees) continue
            val oldRow = state.lineIds.indexOf(oldId)
            if (oldRow >= 0) {
                // The cut took only the title and left the bullet behind
                // (now empty). Move the folder along with the title, but
                // only when no children stay behind under the empty row.
                val emptied = SubtreeCodec.titleOf(state.lines[oldRow]).isBlank()
                val childless = SubtreeCodec.composedSubtreeEnd(state.lines, oldRow) == oldRow
                if (!emptied || !childless) continue
            }
            if (SubtreeCodec.titleOf(state.lines[row]) != title) continue
            val ref = promotedSubtrees.remove(oldId) ?: continue
            promotedSubtrees[newId] = ref
            if (trashedIds.remove(oldId)) trashedIds += newId
            if (oldId in unloaded) unloaded = unloaded - oldId + newId
        }
        if (unloaded != state.unloadedRefIds) _stateFlow.value = state.copy(unloadedRefIds = unloaded)
    }

    // ------------------------------------------------------------- expansion

    /**
     * `true` when [lineId] is a folder-backed bullet (the view shows a
     * chevron for it even when no children are spliced in).
     */
    fun isPromotedRef(lineId: LineId): Boolean = lineId in promotedSubtrees

    /**
     * Folder-backed rows of the current [State.lines], keyed by row, with
     * each row's backing folder. Same shape as
     * [NoteRepository.Loaded.promotedByRow]; `VaultIndex` reads it to walk
     * open documents without re-parsing.
     */
    fun promotedByRow(): Map<Int, PromotedRef> {
        val state = _stateFlow.value
        if (promotedSubtrees.isEmpty()) return emptyMap()
        val out = HashMap<Int, PromotedRef>(promotedSubtrees.size)
        for ((row, id) in state.lineIds.withIndex()) {
            val ref = promotedSubtrees[id] ?: continue
            out[row] = ref
        }
        return out
    }

    /**
     * Records that one more pane wants the folder-backed bullet at
     * [lineId] expanded. On the first acquire, if its children are on
     * disk only, they are loaded and spliced in right after the row.
     * Every successful acquire MUST be paired with one [releaseExpansion].
     *
     * No-op when [lineId] is not folder-backed or its row is gone.
     */
    suspend fun acquireExpansion(lineId: LineId) {
        expansionLock.withLock {
            if (lineId !in promotedSubtrees) return@withLock
            val current = expansionRefcounts[lineId] ?: 0
            if (current > 0) {
                expansionRefcounts[lineId] = current + 1
                return@withLock
            }
            if (spliceInUnderLock(lineId)) expansionRefcounts[lineId] = 1
        }
    }

    /**
     * Records that one fewer pane wants [lineId] expanded. On the last
     * release the children are saved (if anything is unsaved) and then
     * removed from [State.lines]; the bullet row itself stays. Both run
     * under the save lock, so no save can see the half-collapsed state.
     *
     * No-op when [lineId] has no outstanding acquires.
     */
    suspend fun releaseExpansion(lineId: LineId) {
        expansionLock.withLock {
            val current = expansionRefcounts[lineId] ?: return@withLock
            if (current > 1) {
                expansionRefcounts[lineId] = current - 1
                return@withLock
            }
            expansionRefcounts.remove(lineId)
            saveLock.withLock {
                saveIfDirtyUnderLock()
                spliceOutNow(lineId)
            }
        }
    }

    /**
     * Loads and splices in [lineId]'s children. Caller holds
     * [expansionLock]. Returns `true` when the children are now in
     * [State.lines] (including when they already were), `false` when the
     * row vanished mid-load.
     */
    private suspend fun spliceInUnderLock(lineId: LineId): Boolean {
        val ref = promotedSubtrees[lineId] ?: return false
        val state = _stateFlow.value
        if (lineId !in state.unloadedRefIds) return true
        val row = state.lineIds.indexOf(lineId)
        if (row < 0) return false
        val parentIndent = DocumentLayout.bulletAsteriskColumn(state.lines[row])
        if (parentIndent < 0) return false
        val loaded = repository.loadSubtree(ref.folderRel, parentIndent)
        return spliceLoaded(lineId, loaded)
    }

    /**
     * Inserts [loaded] right after [lineId]'s row (before any children
     * already in memory) and marks the row loaded. Returns `false` when
     * the row is gone.
     */
    private fun spliceLoaded(lineId: LineId, loaded: NoteRepository.Loaded): Boolean {
        val current = _stateFlow.value
        val currentRow = current.lineIds.indexOf(lineId)
        if (currentRow < 0) return false
        if (lineId !in current.unloadedRefIds) return true
        val childLines = loaded.lines
        val newIds = List(childLines.size) { allocateId() }
        val mergedLines = current.lines.toMutableList()
        val mergedIds = current.lineIds.toMutableList()
        mergedLines.addAll(currentRow + 1, childLines)
        mergedIds.addAll(currentRow + 1, newIds)
        val nested = HashSet<LineId>()
        for ((localRow, nestedRef) in loaded.promotedByRow) {
            val id = newIds.getOrNull(localRow) ?: continue
            promotedSubtrees[id] = nestedRef
            nested += id
        }
        _stateFlow.value = current.copy(
            lines = mergedLines,
            lineIds = mergedIds,
            unloadedRefIds = current.unloadedRefIds - lineId + nested,
        )
        return true
    }

    /**
     * Removes the children of [lineId] from [State.lines] and marks it
     * unloaded again. Caller holds [saveLock] and has just saved, so the
     * disk holds exactly what is dropped here.
     */
    private fun spliceOutNow(lineId: LineId) {
        val state = _stateFlow.value
        if (lineId !in promotedSubtrees || lineId in state.unloadedRefIds) return
        val row = state.lineIds.indexOf(lineId)
        if (row < 0) return
        val endInclusive = SubtreeCodec.composedSubtreeEnd(state.lines, row)
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        val droppedIds = HashSet<LineId>()
        for (i in (row + 1)..endInclusive) droppedIds += state.lineIds[i]
        for (id in droppedIds) {
            promotedSubtrees.remove(id)
            expansionRefcounts.remove(id)
        }
        repeat(endInclusive - row) {
            newLines.removeAt(row + 1)
            newIds.removeAt(row + 1)
        }
        _stateFlow.value = state.copy(
            lines = newLines,
            lineIds = newIds,
            unloadedRefIds = (state.unloadedRefIds - droppedIds) + lineId,
        )
        // Nothing changed on disk; the dropped rows are exactly what the
        // save before this wrote. Keep the dirty check honest.
        lastSavedText = currentText(_stateFlow.value)
        lastSavedUnloaded = _stateFlow.value.unloadedRefIds
        recomputeDirty()
    }

    // ------------------------------------------------------------ load/save

    private suspend fun loadFromDisk() {
        val loaded = repository.loadFile(fileRel)
        val lines = loaded.lines.ifEmpty { listOf(emptyLine) }
        val ids = List(lines.size) { allocateId() }
        promotedSubtrees.clear()
        trashedIds.clear()
        val unloaded = HashSet<LineId>()
        for ((row, ref) in loaded.promotedByRow) {
            val id = ids.getOrNull(row) ?: continue
            promotedSubtrees[id] = ref
            unloaded += id
        }
        lastSavedText = lines.joinToString("\n")
        lastSavedUnloaded = unloaded
        _stateFlow.value = _stateFlow.value.copy(
            lines = lines,
            lineIds = ids,
            isLoaded = true,
            unloadedRefIds = unloaded,
        )
    }

    /**
     * Saves after [saveDebounceMillis] of quiet, or [maxSaveDelayMillis]
     * after the first unsaved edit, whichever comes first.
     */
    private suspend fun runAutoSaveLoop() {
        _stateFlow.first { it.isLoaded }
        while (true) {
            _dirtyFlow.first { it }
            withTimeoutOrNull(maxSaveDelayMillis) {
                while (true) {
                    val seen = _stateFlow.value.lines
                    val edited = withTimeoutOrNull(saveDebounceMillis) {
                        _stateFlow.first { it.lines !== seen }
                    }
                    if (edited == null) break
                }
            }
            saveLock.withLock { saveIfDirtyUnderLock() }
        }
    }

    /** Runs one save when anything changed since the last one. Caller holds [saveLock]. */
    private suspend fun saveIfDirtyUnderLock() {
        if (!isDirty(_stateFlow.value)) return
        runOneSave()
    }

    /**
     * One save. Caller holds [saveLock].
     *
     * First splices the on-disk children of any unloaded folder-backed
     * row that has gained rows in memory (a paste or indent under a
     * collapsed bullet), so the repository sees the complete subtree.
     * Then hands the outline, the folder-backed rows, the unloaded rows
     * and the deleted rows' folders to [NoteRepository.save], and applies
     * the result: new folders for promoted rows, removed entries for
     * demoted rows, trash paths for deleted rows.
     */
    private suspend fun runOneSave() {
        materializeUnloadedWithChildren()
        val state = _stateFlow.value
        val text = currentText(state)
        val rowToRef = HashMap<Int, PromotedRef>(promotedSubtrees.size)
        val unloadedRows = HashSet<Int>()
        val liveIds = HashSet<LineId>(state.lineIds.size)
        for ((idx, id) in state.lineIds.withIndex()) {
            liveIds += id
            val ref = promotedSubtrees[id] ?: continue
            rowToRef[idx] = ref
            if (id in state.unloadedRefIds) unloadedRows += idx
        }
        val dead = promotedSubtrees.filterKeys { it !in liveIds && it !in trashedIds }
        val result = try {
            repository.save(fileRel, state.lines, rowToRef, unloadedRows, dead.values) { active ->
                _stateFlow.value = _stateFlow.value.copy(isRestructuring = active)
            }
        } finally {
            if (_stateFlow.value.isRestructuring) {
                _stateFlow.value = _stateFlow.value.copy(isRestructuring = false)
            }
        }
        for ((idx, id) in state.lineIds.withIndex()) {
            val newRef = result.promotedByRow[idx]
            if (newRef != null) {
                promotedSubtrees[id] = newRef
                trashedIds -= id
            } else if (id in promotedSubtrees) {
                // Demoted to a leaf: the folder is gone.
                promotedSubtrees.remove(id)
            }
        }
        for ((id, ref) in dead) {
            val trashPath = result.trashed[ref.folderRel]
            // A paste during the save may have handed this folder to a new
            // row (adoptCut); follow it to whichever id holds it now.
            val holders = promotedSubtrees.filterValues { it == ref }.keys
            for (holder in holders) {
                if (trashPath != null) {
                    promotedSubtrees[holder] = PromotedRef(trashPath)
                    trashedIds += holder
                } else if (holder == id) {
                    promotedSubtrees.remove(id)
                }
            }
        }
        // Rows demoted by this save can no longer be "unloaded".
        val after = _stateFlow.value
        val prunedUnloaded = after.unloadedRefIds.filterTo(HashSet()) { it in promotedSubtrees }
        if (prunedUnloaded.size != after.unloadedRefIds.size) {
            _stateFlow.value = after.copy(unloadedRefIds = prunedUnloaded)
        }
        lastSavedText = text
        // What this save saw, minus rows it demoted. A splice during the
        // save leaves the live set different, so the document stays dirty.
        lastSavedUnloaded = state.unloadedRefIds.filterTo(HashSet()) { it in promotedSubtrees }
        recomputeDirty()
        try { onAfterSave() } catch (_: Throwable) {}
    }

    /**
     * Splices in the disk children of every unloaded folder-backed row
     * that has rows nested under it in memory, so a save can never
     * mistake those rows for the whole subtree. Caller holds [saveLock].
     */
    private suspend fun materializeUnloadedWithChildren() {
        while (true) {
            val state = _stateFlow.value
            val target = state.unloadedRefIds.firstOrNull { id ->
                val row = state.lineIds.indexOf(id)
                row >= 0 && SubtreeCodec.composedSubtreeEnd(state.lines, row) > row
            } ?: return
            val ref = promotedSubtrees[target]
            val row = state.lineIds.indexOf(target)
            val indent = DocumentLayout.bulletAsteriskColumn(state.lines[row])
            if (ref == null || indent < 0) {
                _stateFlow.value = state.copy(unloadedRefIds = state.unloadedRefIds - target)
                continue
            }
            val loaded = repository.loadSubtree(ref.folderRel, indent)
            if (!spliceLoaded(target, loaded)) {
                val s = _stateFlow.value
                _stateFlow.value = s.copy(unloadedRefIds = s.unloadedRefIds - target)
            }
        }
    }

    private fun allocateId(): LineId = LineId(nextIdValue++)

    companion object {
        /** Quiet time after the last edit before a save. */
        const val DEFAULT_SAVE_DEBOUNCE_MILLIS: Long = 1_000L

        /** Longest a dirty document waits while the user keeps typing. */
        const val DEFAULT_MAX_SAVE_DELAY_MILLIS: Long = 5_000L
    }
}

/**
 * Stable identifier for a line within one [Document] instance. Ids are
 * allocated monotonically and never reused. View layers reference lines
 * by id when they need to survive row shifts.
 *
 * Each [Document] has its own id space — comparing ids across documents
 * is meaningless.
 */
@kotlin.jvm.JvmInline
value class LineId(val value: Long)
