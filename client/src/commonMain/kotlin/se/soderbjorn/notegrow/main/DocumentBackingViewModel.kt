/*
 * DocumentBackingViewModel.kt
 * ---------------------------
 * Canonical, persisted document model for a Notegrow note. This file owns the
 * single source of truth for the document's textual content — an ordered list
 * of plain-text lines — and the autosave loop that writes it to disk via
 * [NoteRepository].
 *
 * This VM is deliberately content-only. It knows nothing about cursors,
 * selections, zoom, syntax highlighting, or any other per-viewer state; those
 * concerns live one layer up in `DocumentViewBackingViewModel`. Keeping this
 * VM free of view concerns is what lets a future second viewer (another
 * window, a collab peer) subscribe to the same flow and see consistent
 * content.
 *
 * Alongside each line we carry a stable [LineId]. These ids survive row
 * shifts caused by insertions and deletions above them, which lets view-local
 * state (cursor anchor, zoom target) reference a logical line independent of
 * its current index. Ids are in-memory only — persistence still writes plain
 * text without any id markup.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline
import se.soderbjorn.notegrow.data.NoteRepository
import se.soderbjorn.notegrow.data.PromotedRef
import se.soderbjorn.notegrow.data.VaultEntry

/**
 * The document-level ViewModel that holds the canonical note content and
 * persists it on a background autosave loop.
 *
 * Only primitive, view-agnostic edits are exposed ([insertText],
 * [insertNewline], [delete]). Higher-level editor behaviors (typing that
 * replaces a selection, bullet continuation on Enter, indent/outdent, zoom
 * navigation, …) are composed on top of these primitives by
 * `DocumentViewBackingViewModel`.
 *
 * ### Callers
 * - Created once per application process by the platform DI graph
 *   (see `JsAppGraph`), and shared with every viewer.
 * - Read by `DocumentViewBackingViewModel` which mirrors [stateFlow] and adds
 *   cursor/selection/zoom state on top.
 * - Written to by `DocumentViewBackingViewModel` through the primitive edit
 *   methods on this class.
 *
 * @property repository Persistent storage used by [loadFromDisk] and the
 *   autosave loop. Injected so tests can swap in an in-memory implementation.
 * @property scope Coroutine scope owning the initial load and the autosave
 *   loop. Typically an app-scoped `GlobalScope` or equivalent.
 * @property autoSaveIntervalMillis How often the autosave loop wakes up and
 *   flushes changed text. Defaults to five seconds, which matches typical
 *   outliner products.
 */
class DocumentBackingViewModel(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    private val autoSaveIntervalMillis: Long = 5_000L
) {
    /**
     * Immutable snapshot of the document at a point in time.
     *
     * @property lines The note content, one entry per logical line. Invariant:
     *   always non-empty — an empty document is represented as `listOf("")`.
     * @property lineIds Parallel list of stable identifiers, one per entry in
     *   [lines]. Invariant: `lineIds.size == lines.size` and every id is
     *   unique within this VM's lifetime. View layers reference lines by id
     *   when they need to survive edits that shift row indices.
     * @property isLoaded `false` until [loadFromDisk] has completed once, at
     *   which point it flips to `true` and stays there. The editor view uses
     *   this to render a "Loading…" placeholder on cold start.
     * @property isRestructuring `true` while the autosave loop is in the middle
     *   of a save tick that promotes a fresh subtree into its own file or
     *   demotes a previously promoted one back inline — i.e. one that fans
     *   out into multiple file writes (and possibly directory deletes). Plain
     *   saves where the on-disk shape is unchanged keep this `false`. The view
     *   layer can use this to surface a "restructuring…" indicator.
     */
    data class State(
        /**
         * Vault-relative path of the file the editor is currently
         * displaying. Defaults to the configured root (`Root.md`) at boot;
         * changes when the user clicks a different file in the vault-tree
         * footer (see `switchTo`). Persisted only in memory — every cold
         * start opens the root file again.
         */
        val activeFileRel: String = "",
        val lines: List<String> = listOf(""),
        val lineIds: List<LineId> = listOf(LineId(0L)),
        val isLoaded: Boolean = false,
        val isRestructuring: Boolean = false,
        /**
         * The subset of `[[ref]]` rows whose child file is currently spliced
         * into [lines]. Rows that are file-boundaries on disk (registered in
         * the private `promotedSubtrees` map) but absent from this set are
         * folded — their children have not been loaded from disk, and any
         * autosave tick must leave the corresponding file untouched.
         *
         * Mutated only by [expandSubtree] / [collapseSubtree].
         */
        val expandedRefIds: Set<LineId> = emptySet(),
        /**
         * Lazy cache of directory listings backing the editor's filesystem-tree
         * footer. Keys are directory paths relative to the vault root (`""`
         * for the vault root itself). Values are the direct entries returned
         * by [NoteRepository.listVaultLevel] for that directory. Missing keys
         * mean "not yet fetched"; the footer renders a "Loading…" placeholder
         * while it waits for [ensureVaultListing] to populate the entry.
         *
         * The cache is shared across viewers (it's filesystem state, same as
         * file content), so opening another window doesn't re-fetch every
         * folder the user has already expanded. Per-viewer "which folders are
         * open" state lives on the view VM as `expandedVaultPaths`.
         *
         * Refreshed after every successful autosave tick so newly created
         * files (e.g. authored in another editor) surface within ~5 seconds.
         */
        val vaultListings: Map<String, List<VaultEntry>> = emptyMap(),
    )

    /**
     * Location of the end of an inserted run, returned by [insertText] so the
     * caller can place the cursor immediately after the newly inserted text
     * without having to re-derive the position.
     *
     * @property endRow Final row index after the insertion.
     * @property endCol Column on [endRow] immediately after the last inserted
     *   character.
     */
    data class InsertResult(val endRow: Int, val endCol: Int)

    /** Vault-relative path of the root file (mirrors [NoteRepository.rootFileName]). */
    val rootFileName: String = repository.rootFileName

    private val _stateFlow = MutableStateFlow(State(activeFileRel = repository.rootFileName))

    /**
     * Observable stream of document snapshots. Emits once with the initial
     * empty state, again after [loadFromDisk] completes, and then on every
     * edit. View-backing VMs collect this to stay in sync.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private var lastSavedText: String = ""
    private var nextIdValue: Long = 1L

    /**
     * Persistence-only metadata: which logical lines correspond to subtrees
     * the repository has split into their own `.md` files, and the
     * [PromotedRef] (file path + safety flags) each one currently has.
     * Populated on load and updated on every save tick. Not part of [State]
     * because the view layer doesn't need to see it. Every entry here is a
     * file boundary regardless of whether its children are currently
     * spliced in (see [State.expandedRefIds]).
     */
    private val promotedSubtrees: MutableMap<LineId, PromotedRef> = mutableMapOf()

    /**
     * Single-flight lock that serializes the autosave loop's saves with
     * any [switchTo] file swap. switchTo flushes the current file
     * synchronously before loading the new one; without this lock the
     * autosave tick could race with the swap and write half-loaded
     * content back to the wrong file.
     */
    private val saveLock = Mutex()

    /**
     * Guard against concurrent [expandSubtree] calls for the same id. The
     * intent does I/O and then mutates state; without this guard, two clicks
     * on the same chevron in quick succession could splice the same children
     * in twice.
     */
    private val inflightExpandIds: MutableSet<LineId> = mutableSetOf()

    init {
        scope.launch { loadActive() }
        scope.launch { runAutoSaveLoop() }
    }

    /**
     * Inserts [text] into the document at the given cursor position. If
     * [text] contains `\n` (or `\r\n` / `\r`, which are normalized to `\n`)
     * the insertion spans multiple rows and the trailing portion of the
     * original row is moved to the last new row.
     *
     * Stable ids on existing rows are preserved; every *new* row gets a
     * freshly allocated [LineId], so view-local references to pre-existing
     * rows remain valid.
     *
     * Called by `DocumentViewBackingViewModel` to implement single-character
     * typing, paste, newline, and bullet continuation. Also used indirectly
     * by zoom to create an empty child bullet when zooming into a leaf.
     *
     * @param row Zero-based row index to insert at. Must be a valid index
     *   into [State.lines].
     * @param col Zero-based column within that row. Must satisfy
     *   `0 <= col <= lines[row].length`.
     * @param text The text to insert. May be empty (returns a no-op result)
     *   or may contain newline characters.
     * @return The end position of the inserted run, for cursor placement.
     *   If the document is not yet loaded, or [text] is empty, the input
     *   position is returned unchanged.
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

    /**
     * Convenience for the most common multi-line insertion: splits the row
     * at ([row], [col]) by inserting a single newline. Equivalent to
     * `insertText(row, col, "\n")`.
     *
     * Called by `DocumentViewBackingViewModel.insertNewline` in its naive
     * path; most platform Enter handling goes through the smarter
     * bullet-continuation path instead.
     *
     * @param row Row to split.
     * @param col Column within [row] where the split occurs.
     * @return Position at the start of the newly created line.
     */
    fun insertNewline(row: Int, col: Int): InsertResult = insertText(row, col, "\n")

    /**
     * Deletes the run of text from ([startRow], [startCol]) up to, but not
     * including, ([endRow], [endCol]). Multi-row deletions merge the tail of
     * [endRow] onto [startRow] and remove the intermediate rows entirely;
     * their stable ids are dropped, while [startRow]'s id is retained.
     *
     * Called by `DocumentViewBackingViewModel` to implement backspace, the
     * forward Delete key, selection deletion, and outdent.
     *
     * @param startRow Starting row (inclusive).
     * @param startCol Starting column on [startRow] (inclusive).
     * @param endRow Ending row (inclusive for the line itself, exclusive in
     *   the column sense).
     * @param endCol Column on [endRow] up to which characters are deleted
     *   (exclusive). Passing start == end makes the call a no-op.
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
     * Wholesale-replaces the document's text content and stable ids in a
     * single state emission. Used by `DocumentViewBackingViewModel`'s
     * undo/redo path to restore a full snapshot — the alternative would be
     * to replay inverse operations through [insertText] / [delete], which
     * would emit one state per step and risk transient invalid states (e.g.
     * a half-restored selection straddling rows that don't yet exist).
     *
     * No-op when the document has not yet finished its initial load — the
     * undo stack is empty in that window so this is purely defensive.
     *
     * Does not touch [State.activeFileRel] / [State.isLoaded] / autosave
     * bookkeeping. The next autosave tick simply observes the new content
     * and persists it; saved-on-disk state catches up to the in-memory
     * state on the normal cadence.
     *
     * @param lines New document content. Must be non-empty (use `listOf("")`
     *   for an empty document to preserve the invariant).
     * @param lineIds Parallel id list. Must be the same size as [lines]; ids
     *   come from a previously-captured snapshot so all values are within
     *   the range previously allocated by this VM.
     * @param expandedRefIds The set of file-boundary refs that were
     *   expanded at the time the snapshot was captured. Restored verbatim.
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
     * One-shot initial load from [repository]. Reads only `root.nogr` (no
     * recursion into `[[…]]` references), assigns a fresh [LineId] to every
     * loaded line, and stashes the row→dirRel map by [LineId] in
     * [promotedSubtrees]. The view layer starts with every reference folded
     * via [State.expandedRefIds] = `emptySet()`; the user's first click on a
     * chevron triggers [expandSubtree] which lazy-loads that file.
     *
     * Called exactly once from [init]; no caller should invoke it directly.
     */
    /**
     * Loads the file currently at [State.activeFileRel] from disk and
     * publishes a fresh state that reflects it. Called once at boot and
     * again from [switchTo] whenever the user opens a different file.
     */
    private suspend fun loadActive() {
        val fileRel = _stateFlow.value.activeFileRel
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
        // Eagerly populate the vault root listing so the footer's first
        // level paints without a flash of "Loading…" right after boot.
        ensureVaultListing("")
    }

    /**
     * Switches the editor's active document to the file at [fileRel].
     * Synchronously flushes any pending changes for the current file
     * (under [saveLock] so the autosave loop can't race) and then loads
     * the new file. After this returns, [State.activeFileRel] is
     * [fileRel] and [State.lines] is its content.
     *
     * No-op when the requested file is already active and loaded.
     *
     * Used by the view VM's `navigateToVaultFile` intent: every footer
     * click in the filesystem-tree footer goes through here.
     */
    suspend fun switchTo(fileRel: String) {
        saveLock.withLock {
            val current = _stateFlow.value
            if (current.activeFileRel == fileRel && current.isLoaded) return@withLock
            // Flush pending changes for the OUTGOING file before swapping —
            // otherwise edits made in the previous file would be discarded.
            if (current.isLoaded) {
                val currentText = current.lines.joinToString("\n")
                if (currentText != lastSavedText) {
                    runOneSave(current)
                }
            }
            promotedSubtrees.clear()
            // Mark unloaded and swap the active path; `loadActive` reads it.
            _stateFlow.value = current.copy(
                activeFileRel = fileRel,
                isLoaded = false,
                expandedRefIds = emptySet(),
            )
            loadActive()
        }
    }

    /**
     * Loads the direct entries under `<vaultRoot>/<dirRel>` if they are not
     * already cached, and merges them into [State.vaultListings]. No-op when
     * the entry is already present — the user is free to call this on every
     * folder click without worrying about redundant fetches.
     *
     * Called by the view VM when the user expands a folder in the
     * filesystem-tree footer. Empty string means the vault root.
     */
    suspend fun ensureVaultListing(dirRel: String) {
        if (_stateFlow.value.vaultListings[dirRel] != null) return
        val entries = repository.listVaultLevel(dirRel)
        val current = _stateFlow.value
        // A concurrent caller may have populated the same key while we awaited;
        // prefer the existing entry over a stale re-fetch.
        if (current.vaultListings[dirRel] != null) return
        _stateFlow.value = current.copy(
            vaultListings = current.vaultListings + (dirRel to entries),
        )
    }

    /**
     * Re-fetches every directory currently in [State.vaultListings] and
     * replaces each entry with the fresh result. Called after each autosave
     * tick so files created or removed by another editor (Obsidian, the
     * shell, …) surface in the footer within one autosave cycle.
     *
     * Folders the user has never expanded are not in the cache and therefore
     * not refreshed — they'll be fetched fresh on the first expand.
     */
    private suspend fun refreshLoadedVaultListings() {
        val keys = _stateFlow.value.vaultListings.keys.toList()
        if (keys.isEmpty()) return
        val updates = HashMap<String, List<VaultEntry>>()
        for (k in keys) {
            updates[k] = repository.listVaultLevel(k)
        }
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            vaultListings = current.vaultListings + updates,
        )
    }

    /**
     * Returns `true` if [lineId] is a file-boundary reference — i.e. its
     * subtree lives in a separate `.nogr` file and can be lazy-loaded via
     * [expandSubtree]. Used by the view layer to choose between view-local
     * collapse (within-file) and document-level expand/collapse (file
     * boundary).
     */
    fun isPromotedRef(lineId: LineId): Boolean = lineId in promotedSubtrees

    /**
     * Loads the child file referenced by [lineId] and splices its content
     * into [State.lines] immediately after the reference row. No-op if
     * [lineId] is not a registered file boundary, is already expanded, or
     * is currently being expanded by another caller. New rows allocated
     * during this call get fresh [LineId]s; nested `[[…]]` references in
     * the loaded slice are registered in [promotedSubtrees] (and start
     * unexpanded — the user must click their chevron to dive deeper).
     *
     * Called by the view layer's `toggleCollapse` intent when the user
     * clicks a folded reference's chevron, and by `ZoomNavigation.zoomInto`
     * when the user attempts to zoom into a folded ref.
     */
    suspend fun expandSubtree(lineId: LineId) {
        val ref = promotedSubtrees[lineId] ?: return
        val state = _stateFlow.value
        if (lineId in state.expandedRefIds) return
        if (!inflightExpandIds.add(lineId)) return
        try {
            val row = state.lineIds.indexOf(lineId)
            if (row < 0) return
            val parentIndent = DocumentLayout.bulletAsteriskColumn(state.lines[row])
            if (parentIndent < 0) return
            val loaded = repository.loadSubtree(ref.fileRel, parentIndent)
            // Re-read state in case it changed during the suspend; resolve row
            // again by id and bail if the row is gone.
            val current = _stateFlow.value
            val currentRow = current.lineIds.indexOf(lineId)
            if (currentRow < 0) return
            if (lineId in current.expandedRefIds) return
            val childLines = loaded.lines
            if (childLines.isEmpty()) {
                // Broken ref — flip the chevron open with no children, so the
                // user sees that the click was registered. The next collapse
                // simply removes the (empty) entry from expandedRefIds.
                _stateFlow.value = current.copy(
                    expandedRefIds = current.expandedRefIds + lineId,
                )
                return
            }
            val newIds = List(childLines.size) { allocateId() }
            val mergedLines = current.lines.toMutableList()
            val mergedIdList = current.lineIds.toMutableList()
            mergedLines.addAll(currentRow + 1, childLines)
            mergedIdList.addAll(currentRow + 1, newIds)
            // Register nested refs (rows are local to the loaded slice; offset
            // them by currentRow + 1 to get absolute document rows).
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
        } finally {
            inflightExpandIds.remove(lineId)
        }
    }

    /**
     * Removes the children of the reference at [lineId] from [State.lines],
     * dropping their [LineId]s entirely (and any nested promoted-ref
     * registrations within the dropped range). The reference row itself
     * stays — its [LineId] is preserved across collapse/expand cycles so
     * view-local state can keep pointing at it.
     *
     * No-op if [lineId] is not currently expanded.
     *
     * Called by the view layer's `toggleCollapse` intent when the user
     * clicks an expanded reference's chevron.
     */
    fun collapseSubtree(lineId: LineId) {
        val state = _stateFlow.value
        if (lineId !in state.expandedRefIds) return
        val row = state.lineIds.indexOf(lineId)
        if (row < 0) return
        val parentIndent = DocumentLayout.bulletAsteriskColumn(state.lines[row])
        if (parentIndent < 0) return
        val endInclusive = DocumentLayout.subtreeEnd(state.lines, row, parentIndent)
        if (endInclusive <= row) {
            _stateFlow.value = state.copy(expandedRefIds = state.expandedRefIds - lineId)
            return
        }
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        // Drop nested promoted-ref registrations and any nested expanded ids
        // for rows we're about to remove — this is the actual memory release.
        val droppedIds = HashSet<LineId>()
        for (i in (row + 1)..endInclusive) droppedIds += state.lineIds[i]
        for (id in droppedIds) promotedSubtrees.remove(id)
        repeat(endInclusive - row) {
            newLines.removeAt(row + 1)
            newIds.removeAt(row + 1)
        }
        val newExpanded = (state.expandedRefIds - lineId) - droppedIds
        _stateFlow.value = state.copy(
            lines = newLines,
            lineIds = newIds,
            expandedRefIds = newExpanded,
        )
    }

    /**
     * Periodically serializes the active document back to disk if — and
     * only if — its text has changed since the last save. Each tick takes
     * [saveLock] so a concurrent [switchTo] cannot interleave with a save.
     *
     * Called exactly once from [init] and runs for the lifetime of [scope].
     */
    private suspend fun runAutoSaveLoop() {
        while (true) {
            delay(autoSaveIntervalMillis)
            saveLock.withLock {
                val state = _stateFlow.value
                if (!state.isLoaded) {
                    println("[autosave] tick: not loaded, skipping")
                    return@withLock
                }
                val currentText = state.lines.joinToString("\n")
                if (currentText == lastSavedText) {
                    println("[autosave] tick: no change (lines=${state.lines.size}, len=${currentText.length})")
                    return@withLock
                }
                runOneSave(state)
            }
        }
    }

    /**
     * Performs one save tick for [state] (assumed to be the latest
     * loaded state). Caller must hold [saveLock]. Used by both the
     * autosave loop and [switchTo] (to flush pending changes before a
     * file swap).
     */
    private suspend fun runOneSave(state: State) {
        val currentText = state.lines.joinToString("\n")
        println("[autosave] tick: saving file=${state.activeFileRel} " +
            "(lines=${state.lines.size}, len=${currentText.length}, prevLen=${lastSavedText.length})")
        println("[autosave] promotedSubtrees size=${promotedSubtrees.size}, expandedRefIds size=${state.expandedRefIds.size}")
        for ((id, ref) in promotedSubtrees) {
            val row = state.lineIds.indexOf(id)
            val expanded = id in state.expandedRefIds
            println("[autosave]   ref id=$id row=$row file=${ref.fileRel} pinned=${ref.noAutoPromote} expanded=$expanded")
        }
        // Translate the LineId→PromotedRef map into row→PromotedRef for
        // the repository, and project expandedRefIds onto current row
        // indices so the save can leave unloaded subtrees untouched.
        val rowToRef = HashMap<Int, PromotedRef>(promotedSubtrees.size)
        val expandedRefRows = HashSet<Int>(state.expandedRefIds.size)
        val snapshotPromotedIds = HashSet<LineId>(promotedSubtrees.size)
        for ((idx, id) in state.lineIds.withIndex()) {
            val ref = promotedSubtrees[id] ?: continue
            rowToRef[idx] = ref
            snapshotPromotedIds += id
            if (id in state.expandedRefIds) expandedRefRows += idx
        }
        val newRowToRef = try {
            repository.save(state.activeFileRel, state.lines, rowToRef, expandedRefRows) { active ->
                _stateFlow.value = _stateFlow.value.copy(isRestructuring = active)
            }
        } finally {
            if (_stateFlow.value.isRestructuring) {
                _stateFlow.value = _stateFlow.value.copy(isRestructuring = false)
            }
        }
        // Apply the save's row→ref result without disturbing entries that
        // a concurrent expand/collapse may have added or removed.
        val currentLineIds = _stateFlow.value.lineIds.toHashSet()
        val keptIds = HashSet<LineId>(newRowToRef.size)
        for ((row, ref) in newRowToRef) {
            if (row !in state.lineIds.indices) continue
            val id = state.lineIds[row]
            if (id !in currentLineIds) continue
            promotedSubtrees[id] = ref
            keptIds += id
        }
        for (id in snapshotPromotedIds) {
            if (id !in keptIds) promotedSubtrees.remove(id)
        }
        lastSavedText = currentText
        // Refresh the footer's directory cache after every save.
        try { refreshLoadedVaultListings() } catch (_: Throwable) {}
    }

    /**
     * Allocates the next monotonically increasing [LineId]. Thread-safety is
     * not required: every call site runs on the single coroutine dispatcher
     * that owns [_stateFlow].
     *
     * @return A fresh id guaranteed unique within this VM instance.
     */
    private fun allocateId(): LineId = LineId(nextIdValue++)
}

/**
 * Stable identifier for a line within one [DocumentBackingViewModel]
 * instance. Ids are allocated monotonically and never reused. They exist so
 * view-local state (cursor, zoom target) can reference a logical line
 * independent of its current row index, which shifts whenever content is
 * inserted or deleted above it.
 *
 * This is a value class so that id comparisons compile down to a raw [Long]
 * compare with no allocation.
 *
 * @property value Raw numeric id.
 */
@JvmInline
value class LineId(val value: Long)
