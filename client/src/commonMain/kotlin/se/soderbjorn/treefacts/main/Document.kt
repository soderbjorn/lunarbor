/*
 * Document.kt
 * -----------
 * KMP-world model for one loaded treefacts file. Owns the canonical text
 * content of a single `.md` file (lines, stable per-line ids, expanded-ref
 * state, and the in-memory mirror of the on-disk promoted-subtree map) and
 * the autosave loop that flushes that content back to disk.
 *
 * One [Document] = one file. Multiple panes (`PaneBackingViewModel`) can
 * subscribe to the same [Document] to view/edit the same file together; the
 * [DocumentRegistry] hands out a shared instance when more than one pane
 * acquires the same `fileRel`.
 *
 * This class is intentionally view-agnostic — no cursors, selections, zoom,
 * undo, or platform UI live here. Those concerns belong on
 * `PaneBackingViewModel`. Keeping this layer free of view state is what
 * lets two panes on the same file see each other's edits in real time.
 *
 * Lifecycle: created by [DocumentRegistry] on first acquire of a `fileRel`,
 * kicked off via [start] (which schedules the initial disk load + the
 * autosave loop on the supplied scope), torn down via [shutdown] when the
 * last pane releases it. [shutdown] flushes one final save synchronously
 * before cancelling the autosave job, so closing a pane never loses
 * unsaved changes.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.data.PromotedRef

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
 *   represents (e.g. `Home.md`, `Recipes/Quick Granola.md`). Immutable —
 *   to view a different file, acquire a different [Document] via the
 *   registry.
 * @param repository Persistent storage. Shared across documents — only
 *   one repository instance per app.
 * @param scope Coroutine scope that owns the initial load and the
 *   autosave loop. Cancelling the scope cancels both, but normally the
 *   per-document jobs are cancelled by [shutdown].
 * @param autoSaveIntervalMillis How often the autosave loop wakes up and
 *   flushes changed text. Defaults to five seconds.
 * @param onAfterSave Hook fired after every successful save tick, used by
 *   [DocumentRegistry] to refresh the shared vault-listings cache (since
 *   a save may have created or removed files visible in the footer).
 */
class Document(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    val fileRel: String,
    private val autoSaveIntervalMillis: Long = 5_000L,
    private val onAfterSave: suspend () -> Unit = {},
) {
    /**
     * Immutable snapshot of one file's content at a point in time.
     *
     * @property lines One entry per logical line. Invariant: always
     *   non-empty — an empty file is `listOf("")`.
     * @property lineIds Parallel list of stable identifiers, one per
     *   entry in [lines]. Pane-local references (cursor anchor, zoom
     *   target) hold ids so they survive row shifts.
     * @property isLoaded `false` until the initial disk read completes;
     *   flips to `true` and stays there.
     * @property isRestructuring `true` while a save tick is mid-flight
     *   on a tick that promotes or demotes a subtree across a per-file
     *   boundary. Pure-content saves leave this `false`.
     * @property expandedRefIds The subset of `[Title](path#treefacts)`
     *   rows whose child file is currently spliced into [lines]. Rows
     *   registered as file boundaries but absent from this set are
     *   folded — autosave leaves their child files untouched. This is
     *   the file-level "is currently materialized" set, shared across
     *   panes; per-pane "do I want this expanded" intent lives on
     *   `PaneBackingViewModel.State.expandedRefIdsLocal`.
     */
    data class State(
        val lines: List<String> = listOf(""),
        val lineIds: List<LineId> = listOf(LineId(0L)),
        val isLoaded: Boolean = false,
        val isRestructuring: Boolean = false,
        val expandedRefIds: Set<LineId> = emptySet(),
    )

    /**
     * Location of the end of an inserted run, returned by [insertText]
     * so callers can place the cursor immediately after the new text.
     */
    data class InsertResult(val endRow: Int, val endCol: Int)

    private val _stateFlow = MutableStateFlow(State())

    /**
     * Observable stream of document snapshots. Emits once with the
     * empty initial state, again after the initial disk read completes,
     * and then on every edit. Pane VMs collect this to mirror content.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val _dirtyFlow = MutableStateFlow(false)

    /**
     * `true` while this document holds edits the autosave loop has not
     * yet flushed to disk — i.e. exactly when the next autosave tick
     * would write. Recomputed on every [stateFlow] emission (via the
     * watcher started in [start]) and after every save tick, using the
     * same current-text vs last-saved-text comparison the autosave loop
     * itself uses, so the flag can never disagree with what a save
     * would do.
     *
     * Collected by [DocumentRegistry], which aggregates the per-file
     * flags into `unsavedFilesFlow` for app-chrome indicators (the
     * sidebar logo's unsaved-changes dot on the web).
     */
    val dirtyFlow: StateFlow<Boolean> = _dirtyFlow.asStateFlow()

    private var lastSavedText: String = ""
    private var nextIdValue: Long = 1L

    /**
     * In-memory mirror of the on-disk promoted-subtree map: which
     * [LineId]s correspond to subtrees the repository has split into
     * their own `.md` files, and the [PromotedRef] each currently has.
     * Populated on load and updated on every save tick.
     *
     * An entry whose id has vanished from `lineIds` (its ref bullet was
     * deleted) is deliberately kept until the next save tick: [delete]
     * does not touch this map, so an undo restoring the old ids
     * resurrects the association intact. [runOneSave] treats entries
     * still dead at save time as deleted refs — their child files are
     * removed from disk (adopted-foreign refs are only unlinked) and the
     * entries purged.
     */
    private val promotedSubtrees: MutableMap<LineId, PromotedRef> = mutableMapOf()

    /**
     * Single-flight lock that serializes the autosave loop with
     * [shutdown]'s final flush so the two cannot race and write
     * inconsistent content.
     */
    private val saveLock = Mutex()

    /**
     * Serializes [acquireExpansion] / [releaseExpansion] across panes so
     * the refcount map and the splice-in / splice-out of child file
     * content stay consistent. Held across the suspending file load on
     * first acquire of an id, so a second pane racing in on the same id
     * waits and then sees the splice already done.
     */
    private val expansionLock = Mutex()

    /**
     * Per-id refcount of "panes that want this ref expanded right now".
     * Zero ⇒ not in [State.expandedRefIds] (children not spliced).
     * One or more ⇒ children are spliced in. Mutated only under
     * [expansionLock].
     */
    private val expansionRefcounts: MutableMap<LineId, Int> = mutableMapOf()

    private var loadJob: Job? = null
    private var autoSaveJob: Job? = null
    private var dirtyWatchJob: Job? = null

    /**
     * Schedules the initial disk read and starts the autosave loop on
     * [scope]. Idempotent — calling this twice is safe; the second call
     * is a no-op. Called by [DocumentRegistry] right after construction.
     */
    fun start() {
        if (loadJob != null) return
        loadJob = scope.launch { loadFromDisk() }
        autoSaveJob = scope.launch { runAutoSaveLoop() }
        // Every edit lands as a stateFlow emission, so collecting it is
        // sufficient to keep dirtyFlow current for content changes. The
        // other half — lastSavedText moving without a state emission —
        // is covered by the explicit recomputeDirty() in runOneSave.
        dirtyWatchJob = scope.launch { _stateFlow.collect { recomputeDirty() } }
    }

    /**
     * Cancels the autosave loop and flushes one final save under
     * [saveLock]. Called by [DocumentRegistry] when the last pane
     * releases this document.
     */
    suspend fun shutdown() {
        autoSaveJob?.cancelAndJoin()
        autoSaveJob = null
        loadJob?.cancelAndJoin()
        loadJob = null
        dirtyWatchJob?.cancelAndJoin()
        dirtyWatchJob = null
        saveLock.withLock {
            val state = _stateFlow.value
            if (!state.isLoaded) return@withLock
            val currentText = state.lines.joinToString("\n")
            if (currentText != lastSavedText) {
                runOneSave(state)
            }
        }
        // The watcher is already cancelled, so drop the flag explicitly:
        // the final flush above (or the no-op path) leaves nothing unsaved.
        _dirtyFlow.value = false
    }

    /**
     * Recomputes [dirtyFlow] from the current state: dirty ⇔ loaded and
     * the joined text differs from what the last save wrote. This is the
     * same check [runAutoSaveLoop] performs, so "dirty" always means
     * "the next autosave tick would write". The join is O(document
     * size) per emission — fine for note-sized files.
     */
    private fun recomputeDirty() {
        val state = _stateFlow.value
        _dirtyFlow.value = state.isLoaded && state.lines.joinToString("\n") != lastSavedText
    }

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
     * row gets a fresh [LineId]; every existing row keeps its id.
     *
     * This is NOT expressible via [insertText]: a col-0 split leaves the
     * *original* id on the first resulting row, so inserting a line's
     * worth of text plus `"\n"` at `(row, 0)` would hand row [row]'s
     * identity — fold state, zoom target, promoted-ref association — to
     * the newly inserted line. Called by
     * `TextEditingViewModel.insertSiblingAboveAtTextStartIfAny` (Enter at
     * the start of a bullet's text), which must leave the caret row's
     * identity untouched.
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
     * Deletes the run from `(startRow, startCol)` up to but not
     * including `(endRow, endCol)`. Multi-row deletions merge the tail
     * of [endRow] onto [startRow] and drop the intermediate rows
     * entirely; their stable ids are released, [startRow]'s id is kept.
     */
    fun delete(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        if (startRow == endRow && startCol == endCol) return
        val lines = state.lines
        val startLine = lines[startRow]
        val endLine = lines[endRow]
        val merged = startLine.substring(0, startCol) + endLine.substring(endCol)
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
     * Wholesale-replaces the document's text content and stable ids in
     * a single state emission. Used by undo/redo restore.
     *
     * No-op when the document has not yet loaded — undo stacks are
     * empty in that window so this is purely defensive.
     */
    fun replaceContent(
        lines: List<String>,
        lineIds: List<LineId>,
        expandedRefIds: Set<LineId>,
    ) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        _stateFlow.value = state.copy(
            lines = lines,
            lineIds = lineIds,
            expandedRefIds = expandedRefIds,
        )
    }

    /**
     * `true` when [lineId] is a file-boundary reference — i.e. its
     * subtree lives in a separate `.md` file and can be lazy-loaded via
     * [acquireExpansion].
     */
    fun isPromotedRef(lineId: LineId): Boolean = lineId in promotedSubtrees

    /**
     * Snapshot of which rows in the current [State.lines] are promoted
     * refs, keyed by row index. The [PromotedRef.fileRel] tells you which
     * child file each ref points at — exactly the same shape
     * [NoteRepository.Loaded.promotedByRow] returns from a cold disk read.
     *
     * Computed on demand from [promotedSubtrees] and the current
     * `lineIds`. `VaultIndex` calls this to read the live promoted-ref
     * structure without paying for a re-parse of every line.
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
     * Records that one more pane wants the subtree at [lineId] expanded.
     * On the first acquire (refcount 0 → 1) the child file is loaded
     * and its content spliced into [State.lines] right after the
     * reference row. Subsequent acquires from other panes just bump the
     * refcount and return immediately — the splice is already in place.
     *
     * Symmetric with [releaseExpansion]; every successful acquire MUST
     * be paired with exactly one release when the pane no longer wants
     * the ref expanded (chevron collapse, file switch, pane teardown).
     *
     * No-op when [lineId] is not a registered file boundary or its row
     * is no longer in the document.
     */
    suspend fun acquireExpansion(lineId: LineId) {
        expansionLock.withLock {
            if (lineId !in promotedSubtrees) return@withLock
            val current = expansionRefcounts[lineId] ?: 0
            if (current > 0) {
                expansionRefcounts[lineId] = current + 1
                return@withLock
            }
            if (spliceInUnderLock(lineId)) {
                expansionRefcounts[lineId] = 1
            }
        }
    }

    /**
     * Records that one fewer pane wants the subtree at [lineId]
     * expanded. On the last release (refcount 1 → 0) the child rows are
     * removed from [State.lines]; the reference row itself stays.
     * Subsequent releases while other panes still want the ref expanded
     * just decrement the refcount.
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
            spliceOutUnderLock(lineId)
        }
    }

    /**
     * Performs the file load + line splice for [lineId]. Caller must
     * hold [expansionLock]. Returns `true` on a successful splice (or
     * an empty-child no-op that still flips [State.expandedRefIds]).
     * Returns `false` when the row vanished mid-load or the line is
     * malformed; caller should not bump the refcount in that case.
     */
    private suspend fun spliceInUnderLock(lineId: LineId): Boolean {
        val ref = promotedSubtrees[lineId] ?: return false
        val state = _stateFlow.value
        if (lineId in state.expandedRefIds) return true
        val row = state.lineIds.indexOf(lineId)
        if (row < 0) return false
        val parentIndent = DocumentLayout.bulletAsteriskColumn(state.lines[row])
        if (parentIndent < 0) return false
        val loaded = repository.loadSubtree(ref.fileRel, parentIndent)
        val current = _stateFlow.value
        val currentRow = current.lineIds.indexOf(lineId)
        if (currentRow < 0) return false
        if (lineId in current.expandedRefIds) return true
        val childLines = loaded.lines
        if (childLines.isEmpty()) {
            _stateFlow.value = current.copy(
                expandedRefIds = current.expandedRefIds + lineId,
            )
            return true
        }
        val newIds = List(childLines.size) { allocateId() }
        val mergedLines = current.lines.toMutableList()
        val mergedIdList = current.lineIds.toMutableList()
        mergedLines.addAll(currentRow + 1, childLines)
        mergedIdList.addAll(currentRow + 1, newIds)
        for ((localRow, nestedRef) in loaded.promotedByRow) {
            val absRow = currentRow + 1 + localRow
            if (absRow in mergedIdList.indices) {
                promotedSubtrees[mergedIdList[absRow]] = nestedRef
            }
        }
        _stateFlow.value = current.copy(
            lines = mergedLines,
            lineIds = mergedIdList,
            expandedRefIds = current.expandedRefIds + lineId,
        )
        return true
    }

    /**
     * Removes the children of the reference at [lineId] from
     * [State.lines], dropping their ids and any nested promoted-ref
     * registrations. Caller must hold [expansionLock]. No-op when the
     * ref is not currently spliced in.
     */
    private fun spliceOutUnderLock(lineId: LineId) {
        val state = _stateFlow.value
        if (lineId !in state.expandedRefIds) return
        val row = state.lineIds.indexOf(lineId)
        if (row < 0) {
            _stateFlow.value = state.copy(expandedRefIds = state.expandedRefIds - lineId)
            return
        }
        val parentIndent = DocumentLayout.bulletAsteriskColumn(state.lines[row])
        if (parentIndent < 0) {
            _stateFlow.value = state.copy(expandedRefIds = state.expandedRefIds - lineId)
            return
        }
        val endInclusive = DocumentLayout.subtreeEnd(state.lines, row, parentIndent)
        if (endInclusive <= row) {
            _stateFlow.value = state.copy(expandedRefIds = state.expandedRefIds - lineId)
            return
        }
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        val droppedIds = HashSet<LineId>()
        for (i in (row + 1)..endInclusive) droppedIds += state.lineIds[i]
        for (id in droppedIds) promotedSubtrees.remove(id)
        repeat(endInclusive - row) {
            newLines.removeAt(row + 1)
            newIds.removeAt(row + 1)
        }
        val newExpanded = (state.expandedRefIds - lineId) - droppedIds
        // Drop refcounts for nested refs whose rows we just deleted, so
        // re-acquiring those ids later starts fresh from zero.
        for (id in droppedIds) expansionRefcounts.remove(id)
        _stateFlow.value = state.copy(
            lines = newLines,
            lineIds = newIds,
            expandedRefIds = newExpanded,
        )
    }

    private suspend fun loadFromDisk() {
        val loaded = repository.loadFile(fileRel)
        val lines = loaded.lines.ifEmpty { listOf("") }
        val ids = List(lines.size) { allocateId() }
        promotedSubtrees.clear()
        for ((row, ref) in loaded.promotedByRow) {
            if (row in ids.indices) promotedSubtrees[ids[row]] = ref
        }
        lastSavedText = lines.joinToString("\n")
        _stateFlow.value = _stateFlow.value.copy(
            lines = lines,
            lineIds = ids,
            isLoaded = true,
            expandedRefIds = emptySet(),
        )
    }

    private suspend fun runAutoSaveLoop() {
        while (true) {
            delay(autoSaveIntervalMillis)
            saveLock.withLock {
                val state = _stateFlow.value
                if (!state.isLoaded) return@withLock
                val currentText = state.lines.joinToString("\n")
                if (currentText == lastSavedText) return@withLock
                runOneSave(state)
            }
        }
    }

    /**
     * One save tick. Caller must hold [saveLock]. Used by the autosave
     * loop and by [shutdown]'s final flush.
     */
    private suspend fun runOneSave(state: State) {
        val currentText = state.lines.joinToString("\n")
        val rowToRef = HashMap<Int, PromotedRef>(promotedSubtrees.size)
        val expandedRefRows = HashSet<Int>(state.expandedRefIds.size)
        val snapshotPromotedIds = HashSet<LineId>(promotedSubtrees.size)
        for ((idx, id) in state.lineIds.withIndex()) {
            val ref = promotedSubtrees[id] ?: continue
            rowToRef[idx] = ref
            snapshotPromotedIds += id
            if (id in state.expandedRefIds) expandedRefRows += idx
        }
        // Dead refs: promotedSubtrees entries whose line no longer exists —
        // the user deleted the ref bullet (e.g. select + delete). Their
        // child files are orphans the repository's own old-vs-new map diff
        // can't see, so their fileRels ride along for step-4 deletion.
        // Detecting this lazily at save time (instead of hooking [delete])
        // means an undo *before* the tick resurrects the id and nothing is
        // deleted. Adopted-foreign refs (noAutoPromote) are exempt: their
        // files are hand-authored, so deleting the bullet only unlinks.
        // [collapseRefSubtree] never produces dead entries — it removes the
        // unspliced rows' entries itself, keeping their files untouched.
        val deadRefs = HashMap<LineId, PromotedRef>()
        for ((id, ref) in promotedSubtrees) {
            if (id !in snapshotPromotedIds) deadRefs[id] = ref
        }
        val deletedRefFiles = deadRefs.values.filterNot { it.noAutoPromote }.map { it.fileRel }
        val newRowToRef = try {
            repository.save(fileRel, state.lines, rowToRef, expandedRefRows, deletedRefFiles) { active ->
                _stateFlow.value = _stateFlow.value.copy(isRestructuring = active)
            }
        } finally {
            if (_stateFlow.value.isRestructuring) {
                _stateFlow.value = _stateFlow.value.copy(isRestructuring = false)
            }
        }
        val currentLineIds = _stateFlow.value.lineIds.toHashSet()
        val keptIds = HashSet<LineId>(newRowToRef.size)
        for ((rowIdx, ref) in newRowToRef) {
            if (rowIdx !in state.lineIds.indices) continue
            val id = state.lineIds[rowIdx]
            if (id !in currentLineIds) continue
            promotedSubtrees[id] = ref
            keptIds += id
        }
        for (id in snapshotPromotedIds) {
            if (id !in keptIds) promotedSubtrees.remove(id)
        }
        // Purge the dead entries whose files this save just deleted (or,
        // for noAutoPromote refs, just unlinked). Re-check liveness against
        // the post-save lineIds: an undo that raced the suspended save has
        // resurrected the id, and dropping the entry then would sever a ref
        // the outline still shows.
        for (id in deadRefs.keys) {
            if (id !in currentLineIds) promotedSubtrees.remove(id)
        }
        lastSavedText = currentText
        // lastSavedText moved without a state emission, so the stateFlow
        // watcher won't fire — refresh the dirty flag here.
        recomputeDirty()
        try { onAfterSave() } catch (_: Throwable) {}
    }

    private fun allocateId(): LineId = LineId(nextIdValue++)
}

/**
 * Stable identifier for a line within one [Document] instance. Ids are
 * allocated monotonically and never reused. View layers reference lines
 * by id when they need to survive row shifts.
 *
 * Each [Document] has its own id space — comparing ids across documents
 * is meaningless. (Pane navigation always swaps both the document and
 * any pane-local id-keyed state, so this never matters in practice.)
 */
@kotlin.jvm.JvmInline
value class LineId(val value: Long)
