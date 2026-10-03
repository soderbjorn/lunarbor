/*
 * Document.kt (commonMain)
 * ------------------------
 * KMP-world model for one loaded file — normally a node's `_node.md`
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
 * the entry is kept, pointing into the trash. A folder holding user files
 * (images, notes) stays in place instead, and only its outline and child
 * bullets go to the trash; the entry then also remembers where the files
 * stayed ([PromotedRef.keptAt]). If undo brings the id back, the next
 * save moves the folder back, or merges the trashed parts back into it. Cut and paste hand the folder to the
 * pasted row ([rememberCut] / [adoptCut]), so the folder is moved rather
 * than trashed and recreated.
 *
 * ### Images follow their row
 * A row references a pasted image by bare file name, resolved against
 * the folder the row is stored in ([storageFolderOf], [ImagePaths]).
 * [imageHomes] remembers, per row, which folder-backed bullet's folder
 * (or the document's own folder) its images live in. After each save,
 * a row that now lives in another folder takes its images along
 * ([NoteRepository.moveAttachments]); a second save then demotes a
 * folder left empty by that.
 *
 * ### Links follow renames and moves
 * After each save the document reports, through `onAfterSave`, every
 * folder the save renamed, moved or trashed and every image it moved
 * ([PathMove]s). `DocumentRegistry` then rewrites the `lunarbor:` links that
 * point at or through the old paths — in closed files on disk, and in open
 * documents through [rewriteLinks] (TRF-8).
 *
 * ### Changes made outside the app
 * When a coding agent or another program edits the file, or the outline
 * of a folder spliced into it, `DocumentRegistry.applyExternalChanges`
 * calls [reloadFromDisk]: the disk wins over unsaved edits, expanded
 * folders are loaded again and rows keep their ids where they still
 * match ([matchIds]), so zoom and folds hold.
 *
 * ### Markdown mode
 * A document that is not a `_node.md` outline ([bulletsOnly] `false`,
 * e.g. a `.md` note) is plain text: no folder-backed rows, saved exactly
 * as written, and its images resolve against the note's folder.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.main

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
import se.soderbjorn.lunarbor.data.AttachmentMove
import se.soderbjorn.lunarbor.data.ImagePaths
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.data.PromotedRef
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.data.LunarborLink

/**
 * One loaded lunarbor file.
 *
 * ### Callers
 * - Created and lifecycled exclusively by [DocumentRegistry].
 * - Read from / written to by `PaneBackingViewModel` for every editor
 *   intent that touches text content. Multiple panes may share one
 *   [Document] when they view the same file.
 *
 * @property fileRel The vault-relative path of the file this document
 *   represents (e.g. `_node.md`, `Recipes/_node.md`, `Starred.md`).
 *   Changes only when a `.md` note is renamed in place ([renameTo]); to
 *   view a different file, acquire a different [Document].
 * @param repository Persistent storage, shared across documents.
 * @param scope Coroutine scope that owns the initial load and the autosave
 *   loop. Normally the per-document jobs are cancelled by [shutdown].
 * @param saveDebounceMillis Quiet time after the last edit before a save.
 * @param maxSaveDelayMillis Longest a dirty document waits while the user
 *   keeps typing; a save is forced this long after the first unsaved edit.
 * @param onAfterSave Hook fired after every save with the folders and
 *   files that save renamed, moved or trashed (empty for a pure content
 *   save). Used by [DocumentRegistry] to refresh the shared vault-listings
 *   cache and to rewrite links to the moved paths.
 */
class Document(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    fileRel: String,
    private val saveDebounceMillis: Long = DEFAULT_SAVE_DEBOUNCE_MILLIS,
    private val maxSaveDelayMillis: Long = DEFAULT_MAX_SAVE_DELAY_MILLIS,
    private val onAfterSave: suspend (moves: List<PathMove>) -> Unit = {},
) {
    /** See the class doc's `fileRel`. Reassigned only by [renameTo]. */
    var fileRel: String = fileRel
        private set

    /**
     * Immutable snapshot of one file's content at a point in time.
     *
     * @property lines One entry per logical line. Invariant: always
     *   non-empty — an empty outline is `listOf("* ")`, an empty plain
     *   file `listOf("")`. In an outline ([bulletsOnly]) every line the
     *   editor creates is a bullet or a block row ([BlockLayout]).
     * @property lineIds Parallel list of stable identifiers, one per
     *   entry in [lines].
     * @property isLoaded `false` until the initial disk read completes.
     * @property isRestructuring `true` while a save that changes the
     *   folder structure (promote, demote, rename, move, trash) runs.
     * @property unloadedRefIds Folder-backed rows whose children are on
     *   disk only — not spliced into [lines]. Every folder-backed bullet starts here
     *   after a load; expanding one removes it, collapsing the last pane's
     *   expansion adds it back. A folder-backed row *not* in this set has
     *   its children in [lines] (possibly none), and save rewrites its
     *   outline from them. Stored as the unloaded set, not the loaded one,
     *   so an undo snapshot taken before a bullet was promoted still reads
     *   correctly after the promotion.
     * @property reloadCount How many times the content was replaced by
     *   [reloadFromDisk] after a change made outside the app. Panes clear
     *   their undo history when it changes: snapshots taken before the
     *   reload would bring the overwritten content back.
     */
    data class State(
        val lines: List<String> = listOf(""),
        val lineIds: List<LineId> = listOf(LineId(0L)),
        val isLoaded: Boolean = false,
        val isRestructuring: Boolean = false,
        val unloadedRefIds: Set<LineId> = emptySet(),
        val reloadCount: Int = 0,
    )

    /**
     * Location of the end of an inserted run, returned by [insertText]
     * so callers can place the cursor immediately after the new text.
     */
    data class InsertResult(val endRow: Int, val endCol: Int)

    /**
     * `true` when every line of this document is a bullet — the outline
     * mode of a `_node.md` node (TRF-4). The editing intents in
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

    /**
     * Vault-relative folder of this document's file: the node folder of
     * an outline (`""` for the vault root's `_node.md`), the containing
     * folder of any other file.
     */
    val folderRel: String =
        if (bulletsOnly) NoteRepository.folderOfOutline(fileRel)
        else fileRel.substringBeforeLast('/', missingDelimiterValue = "")

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
     * The folder of each row of [lastSavedText] (`null` for rows without
     * one). With the text it tells [reloadFromDisk] that the disk holds
     * exactly what this document last saved — an echo, not an outside
     * edit — so unsaved edits made since are kept.
     */
    private var lastSavedFolders: List<String?> = emptyList()

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
     * Where one row's images live: the folder of the folder-backed bullet
     * [anchor], or the document's own folder when [anchor] is `null`.
     * Keyed by the bullet's id rather than a path, so a rename or move of
     * that folder (which carries the images along) does not make it stale.
     */
    private data class ImageHome(val anchor: LineId?)

    /**
     * Row id → where the images it references by bare file name live, as
     * of the last load, splice, paste or save. Compared after each save
     * with where the row is stored now; see the file header.
     */
    private val imageHomes: MutableMap<LineId, ImageHome> = mutableMapOf()

    /** Rows of the last cut, by offset, that had an [ImageHome]; handed on by [adoptCut]. */
    private var lastCutImageHomes: Map<Int, ImageHome> = emptyMap()

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

    /**
     * Renames this `.md` note's file to [newRel] (same folder): saves any
     * pending edits under the old name, moves the file, and from then on
     * saves to [newRel]. Runs under the save lock, so no save can
     * interleave with the move. Content, row ids and undo are untouched.
     *
     * Called only by `DocumentRegistry.renameFile`, which re-keys the
     * document and tells the panes.
     *
     * @param newRel Vault-relative path in the same folder; the caller
     *   has checked nothing else is there ([NoteRepository.renameTargetOf]).
     */
    suspend fun renameTo(newRel: String) {
        require(!bulletsOnly) { "Document.renameTo on an outline: $fileRel" }
        saveLock.withLock {
            saveIfDirtyUnderLock()
            repository.moveNote(fileRel, newRel)
            fileRel = newRel
        }
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
    fun deleteLine(row: Int) = deleteRows(row, row)

    /**
     * Removes rows [startRow]..[endRow] (inclusive) entirely; every other
     * row keeps its [LineId]. Removing every row leaves one empty line
     * (`"* "` when [bulletsOnly]). Used to delete a whole block (TRF-5,
     * `PaneBackingViewModel.deleteBlock`) and, through [deleteLine], one
     * empty bullet. Out-of-range rows are clamped; an empty range is a
     * no-op.
     */
    fun deleteRows(startRow: Int, endRow: Int) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val from = startRow.coerceAtLeast(0)
        val to = endRow.coerceAtMost(state.lines.lastIndex)
        if (from > to) return
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        repeat(to - from + 1) {
            newLines.removeAt(from)
            newIds.removeAt(from)
        }
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
     * a single state emission. Used by undo/redo restore and by
     * `PaneBackingViewModel.sortChildrenByName` (a reorder of the same
     * rows and ids). Restoring an id
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
     * Wholesale-replaces the document's rows in one emission, keeping the
     * ids the caller names and giving every other row a fresh one. A
     * kept folder-backed id keeps its folder (a changed title renames it
     * on the next save); a folder-backed id left out is a deleted item,
     * whose folder the next save trashes. Rows still unloaded
     * ([State.unloadedRefIds]) stay unloaded when their id is kept.
     *
     * Called by the agent edit tools (`McpTools`), which diff an agent's
     * new outline text against the document's items.
     *
     * No-op when the document has not yet loaded.
     *
     * @param lines The new rows, in `Document` form; must not be empty.
     * @param keepIds One entry per row: the existing [LineId] it keeps,
     *   or `null` for a new row. Each id at most once.
     */
    fun rewriteRows(lines: List<String>, keepIds: List<LineId?>) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        require(lines.isNotEmpty() && lines.size == keepIds.size) { "rewriteRows: ${lines.size} lines, ${keepIds.size} ids" }
        val ids = keepIds.map { it ?: allocateId() }
        val present = ids.toHashSet()
        _stateFlow.value = state.copy(
            lines = lines,
            lineIds = ids,
            unloadedRefIds = state.unloadedRefIds.filterTo(HashSet()) { it in present },
        )
    }

    /**
     * Rewrites every `lunarbor:` link in the document's lines that points at or
     * through a path [moves] renamed or moved ([LunarborLink.rewriteText]).
     * Row ids are kept, so fold state, zoom and backing folders are
     * untouched; the change saves like any edit. Links into the trash are
     * left alone.
     *
     * Called by [DocumentRegistry] after any open document's save moved
     * folders or files.
     *
     * @return `true` when a line changed.
     */
    fun rewriteLinks(moves: List<PathMove>): Boolean {
        val state = _stateFlow.value
        if (!state.isLoaded || moves.isEmpty()) return false
        var changed = false
        val newLines = state.lines.map { line ->
            val rewritten = LunarborLink.rewriteText(line, moves)
            if (rewritten != null) { changed = true; rewritten } else line
        }
        if (changed) _stateFlow.value = state.copy(lines = newLines)
        return changed
    }

    /**
     * Points every image embed in the document's lines that shows the file
     * [from] at [to] instead ([ImagePaths.rewriteEmbeds], each row resolved
     * against its storage folder, [storageFolderOf]). Row ids are kept; the
     * change saves like any edit.
     *
     * Called by `DocumentRegistry.renameFile` when an image or drawing is
     * renamed.
     *
     * @return `true` when a line changed.
     */
    fun rewriteImageEmbeds(from: String, to: String): Boolean {
        val state = _stateFlow.value
        if (!state.isLoaded) return false
        var changed = false
        val newLines = state.lines.mapIndexed { row, line ->
            val rewritten = ImagePaths.rewriteEmbeds(line, storageFolderOf(row), from, to)
            if (rewritten != null) { changed = true; rewritten } else line
        }
        if (changed) _stateFlow.value = state.copy(lines = newLines)
        return changed
    }

    /**
     * Vault-relative note files whose content this document holds in
     * memory: its own file plus the outline of every folder-backed bullet
     * whose children are spliced in. A save of this document rewrites
     * them, so nothing else may edit them on disk meanwhile —
     * [DocumentRegistry] skips them in its on-disk link rewrite and
     * rewrites this document's lines instead.
     */
    fun heldFiles(): Set<String> {
        val out = HashSet<String>()
        out += fileRel
        val state = _stateFlow.value
        for ((id, ref) in promotedSubtrees) {
            if (id in trashedIds || id in state.unloadedRefIds) continue
            out += ref.fileRel
        }
        return out
    }

    /**
     * The live row whose folder-backed bullet is backed by [folderRel], or
     * `null` when no row in [State.lines] is. Used by
     * `PaneBackingViewModel.navigateToLink` to zoom into the bullet a
     * folder link names.
     */
    fun lineIdForFolder(folderRel: String): LineId? {
        val ids = _stateFlow.value.lineIds
        for (id in ids) {
            if (id in trashedIds) continue
            if (promotedSubtrees[id]?.folderRel == folderRel) return id
        }
        return null
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
     * @param rows The rows [text] holds, one line each, in order: every row
     *   of the selection, or — when a privacy mode hides some — the rest.
     */
    fun rememberCut(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        text: String,
        rows: List<Int> = (startRow..endRow).toList(),
    ) {
        val state = _stateFlow.value
        val refs = HashMap<Int, Pair<LineId, String>>()
        for ((offset, row) in rows.withIndex()) {
            val id = state.lineIds.getOrNull(row) ?: continue
            if (id !in promotedSubtrees) continue
            val line = state.lines[row]
            if (row == startRow && startCol > DocumentLayout.textStartCol(line)) continue
            if (row == endRow && endCol < line.length) continue
            refs[offset] = id to SubtreeCodec.titleOf(line)
        }
        val homes = HashMap<Int, ImageHome>()
        for ((offset, row) in rows.withIndex()) {
            val id = state.lineIds.getOrNull(row) ?: continue
            imageHomes[id]?.let { homes[offset] = it }
        }
        lastCutImageHomes = homes
        lastCut = if (refs.isEmpty() && homes.isEmpty()) null else CutRecord(text, refs)
    }

    /**
     * After [text] was inserted starting at [startRow]: if it is exactly
     * what the last [rememberCut] took, hand each cut folder-backed row's
     * folder (and its unloaded state) to the row now holding the same
     * title, so the next save moves the folder instead of trashing it.
     * Rows that referenced images keep their [ImageHome], so the images
     * follow them. One cut is adopted at most once; a second paste is a
     * plain copy.
     *
     * Called by `TextEditingViewModel.insertText` after every insert.
     */
    fun adoptCut(startRow: Int, text: String) {
        val cut = lastCut ?: return
        if (cut.text != text) return
        lastCut = null
        val state = _stateFlow.value
        // Pasted rows keep the image home of the rows they were cut from,
        // so the next save moves their images to wherever they landed.
        for ((offset, home) in lastCutImageHomes) {
            val id = state.lineIds.getOrNull(startRow + offset) ?: continue
            if (id !in imageHomes) imageHomes[id] = home
        }
        lastCutImageHomes = emptyMap()
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
                val childless = SubtreeCodec.composedSubtreeEnd(state.lines, oldRow) ==
                    DocumentLayout.itemLastRow(state.lines, oldRow)
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

    // ---------------------------------------------------------------- images

    /**
     * Vault-relative folder the line at [row] is stored in — the folder
     * its images resolve against ([ImagePaths.resolve]) and where an
     * image pasted into it is written:
     *
     * - Markdown mode: the note's folder.
     * - Outline: the folder of the nearest folder-backed ancestor bullet,
     *   or the document's own node folder for a top-level row. A bullet
     *   that just got its first child is folder-backed only after the
     *   next save; until then its children resolve against the folder
     *   above, where their images still are.
     *
     * Called by `PaneBackingViewModel` for pastes and by the view (through
     * `PaneBackingViewModel.imageFolderOf`) to draw a row's images.
     */
    fun storageFolderOf(row: Int): String {
        val state = _stateFlow.value
        if (row !in state.lines.indices) return folderRel
        return folderOfHome(ImageHome(storageAnchorOf(state.lines, state.lineIds, row))) ?: folderRel
    }

    /**
     * Records that the images row [row] references by bare file name
     * live in [storageFolderOf] that row right now. Called by
     * `PaneBackingViewModel.onImagePasted` after inserting a pasted image,
     * so if the next save moves the row (a paste under a leaf, which that
     * save promotes), the image goes with it.
     */
    fun noteImageHomeAt(row: Int) {
        if (!bulletsOnly) return
        val state = _stateFlow.value
        val id = state.lineIds.getOrNull(row) ?: return
        imageHomes[id] = ImageHome(storageAnchorOf(state.lines, state.lineIds, row))
    }

    /**
     * The nearest folder-backed ancestor bullet of [row] (walking up by
     * nesting column, as the save does), or `null` when the row belongs
     * to the document's own node. Always `null` in Markdown mode.
     */
    private fun storageAnchorOf(lines: List<String>, ids: List<LineId>, row: Int): LineId? {
        if (!bulletsOnly) return null
        val line = lines[row]
        val blockCol = BlockLayout.markerColumn(line)
        var lookingFor = if (blockCol >= 0) blockCol else DocumentLayout.indentOf(line)
        var r = row - 1
        while (r >= 0 && lookingFor > 0) {
            val col = DocumentLayout.itemColumn(lines, r)
            if (col in 0 until lookingFor) {
                val id = ids.getOrNull(r)
                if (id != null && id in promotedSubtrees && id !in trashedIds) return id
                lookingFor = col
            }
            r--
        }
        return null
    }

    /** Folder of [home], or `null` when its anchor bullet no longer has one. */
    private fun folderOfHome(home: ImageHome): String? {
        val anchor = home.anchor ?: return folderRel
        return promotedSubtrees[anchor]?.folderRel
    }

    /**
     * Bare file names of the images [line] references relative to its own
     * folder ([ImagePaths.isFolderLocal]); empty for most lines.
     */
    private fun localImageNames(line: String): List<String> {
        if ("![" !in line) return emptyList()
        val text = line.substring(DocumentLayout.textStartCol(line).coerceAtMost(line.length))
        return InlineMarkdownTokenizer.tokenize(text).runs
            .mapNotNull { it.imageSrc }
            .filter { ImagePaths.isFolderLocal(it) }
            .distinct()
    }

    /**
     * Gives every row that references local images and has no
     * [ImageHome] yet the one it has now. Called after a load and a
     * splice-in, when every such row is exactly where its file put it.
     */
    private fun recordMissingImageHomes() {
        if (!bulletsOnly) return
        val state = _stateFlow.value
        for ((row, line) in state.lines.withIndex()) {
            val id = state.lineIds[row]
            if (id in imageHomes || localImageNames(line).isEmpty()) continue
            imageHomes[id] = ImageHome(storageAnchorOf(state.lines, state.lineIds, row))
        }
    }

    /**
     * After a save of [saved]: moves the local images of every row that
     * now lives in a different folder than its [ImageHome] says, then
     * updates the homes. An image another row still uses in the old
     * folder stays. Caller holds [saveLock].
     *
     * @return The image moves that were applied, as [PathMove]s; non-empty
     *   means the old folder may now be empty, so one more save should run
     *   to demote it.
     */
    private suspend fun followImagesAfterSave(saved: State): List<PathMove> {
        if (!bulletsOnly) return emptyList()
        class Moved(val id: LineId, val names: List<String>, val from: String, val to: String, val home: ImageHome)
        val staying = HashSet<Pair<String, String>>()
        val moved = ArrayList<Moved>()
        for ((row, line) in saved.lines.withIndex()) {
            val id = saved.lineIds[row]
            val names = localImageNames(line)
            if (names.isEmpty()) continue
            val now = ImageHome(storageAnchorOf(saved.lines, saved.lineIds, row))
            val nowFolder = folderOfHome(now)
            val before = imageHomes[id]
            val beforeFolder = before?.let { folderOfHome(it) }
            if (before == null || beforeFolder == null || nowFolder == null || beforeFolder == nowFolder) {
                if (nowFolder != null) for (n in names) staying += nowFolder to n
                imageHomes[id] = now
                continue
            }
            moved += Moved(id, names, beforeFolder, nowFolder, now)
        }
        if (moved.isEmpty()) return emptyList()
        val moves = LinkedHashSet<AttachmentMove>()
        for (m in moved) {
            for (n in m.names) if ((m.from to n) !in staying) moves += AttachmentMove(m.from, m.to, n)
            imageHomes[m.id] = m.home
        }
        if (moves.isEmpty()) return emptyList()
        return repository.moveAttachments(moves.toList()).map {
            PathMove(joinPath(it.fromFolder, it.name), joinPath(it.toFolder, it.name))
        }
    }

    private fun joinPath(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    // ------------------------------------------------------------- expansion

    /**
     * `true` when [lineId] is a folder-backed bullet (the view shows a
     * chevron for it even when no children are spliced in).
     */
    fun isPromotedRef(lineId: LineId): Boolean = lineId in promotedSubtrees

    /**
     * Vault-relative folder backing the bullet [lineId], or `null` when
     * the bullet is a leaf (or not in this document). A bullet deleted
     * this session reports its path under `.trash/`.
     *
     * Called by `PaneBackingViewModel` to find the folder whose contents
     * list a zoomed pane shows, and the folder behind a count badge.
     */
    fun folderOf(lineId: LineId): String? = promotedSubtrees[lineId]?.folderRel

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
        val parentIndent = DocumentLayout.itemColumn(state.lines, row)
        if (parentIndent < 0) return false
        val loaded = repository.loadSubtree(ref.folderRel, parentIndent)
        return spliceLoaded(lineId, loaded)
    }

    /**
     * Inserts [loaded] right after [lineId]'s item — its row, or a
     * block's last row — before any children already in memory, and
     * marks the row loaded. Returns `false` when the row is gone.
     */
    private fun spliceLoaded(lineId: LineId, loaded: NoteRepository.Loaded): Boolean {
        val current = _stateFlow.value
        val currentRow = current.lineIds.indexOf(lineId)
        if (currentRow < 0) return false
        if (lineId !in current.unloadedRefIds) return true
        val childLines = loaded.lines
        val newIds = List(childLines.size) { allocateId() }
        val at = DocumentLayout.itemLastRow(current.lines, currentRow) + 1
        val mergedLines = current.lines.toMutableList()
        val mergedIds = current.lineIds.toMutableList()
        mergedLines.addAll(at, childLines)
        mergedIds.addAll(at, newIds)
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
        recordMissingImageHomes()
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
        // A block item keeps its own rows; only the children go.
        val ownLast = DocumentLayout.itemLastRow(state.lines, row)
        val newLines = state.lines.toMutableList()
        val newIds = state.lineIds.toMutableList()
        val droppedIds = HashSet<LineId>()
        for (i in (ownLast + 1)..endInclusive) droppedIds += state.lineIds[i]
        for (id in droppedIds) {
            promotedSubtrees.remove(id)
            expansionRefcounts.remove(id)
        }
        repeat(endInclusive - ownLast) {
            newLines.removeAt(ownLast + 1)
            newIds.removeAt(ownLast + 1)
        }
        _stateFlow.value = state.copy(
            lines = newLines,
            lineIds = newIds,
            unloadedRefIds = (state.unloadedRefIds - droppedIds) + lineId,
        )
        // Nothing changed on disk; the dropped rows are exactly what the
        // save before this wrote. Keep the dirty check honest.
        lastSavedText = currentText(_stateFlow.value)
        lastSavedFolders = _stateFlow.value.lineIds.map { id -> promotedSubtrees[id]?.folderRel.takeIf { id !in trashedIds } }
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
        lastSavedFolders = List(lines.size) { loaded.promotedByRow[it]?.folderRel }
        lastSavedUnloaded = unloaded
        _stateFlow.value = _stateFlow.value.copy(
            lines = lines,
            lineIds = ids,
            isLoaded = true,
            unloadedRefIds = unloaded,
        )
        imageHomes.clear()
        recordMissingImageHomes()
    }

    /**
     * `true` when a change outside the app to any of [pathsRel] (vault-
     * relative files or folders) concerns this document: its own file, or
     * the folder or outline of one of its folder-backed rows, loaded or
     * not (a folder-backed row whose folder was deleted becomes a leaf).
     *
     * Called by `DocumentRegistry.applyExternalChanges` to pick the open
     * documents to [reloadFromDisk].
     */
    fun isAffectedBy(pathsRel: Collection<String>): Boolean {
        val watched = HashSet<String>()
        watched += fileRel
        val live = _stateFlow.value.lineIds.toHashSet()
        for ((id, ref) in promotedSubtrees) {
            if (id !in live || id in trashedIds) continue
            watched += ref.folderRel
            watched += NoteRepository.outlineFileOf(ref.folderRel)
        }
        return pathsRel.any { it in watched }
    }

    /**
     * Replaces the content with what is on disk now, after the file (or
     * one of the folders spliced into it) was changed outside the app —
     * by a coding agent, a sync tool, an editor. Unsaved edits are
     * dropped: the disk wins.
     *
     * Keeps the view steady: every folder-backed row that was expanded
     * is loaded again (by folder), its pane expansions stay counted, and
     * a row keeps its [LineId] when it is the same folder or the same
     * text as before ([matchIds]), so zoom, folds and cursors survive
     * edits elsewhere. Bumps [State.reloadCount]. A disk that matches
     * the document, or what it last saved, row for row changes nothing:
     * that is an echo, and the unsaved edits stay.
     * Runs under the expansion and save locks, so it never interleaves
     * with a save.
     *
     * Called by `DocumentRegistry.applyExternalChanges` for documents
     * [isAffectedBy] the change.
     */
    suspend fun reloadFromDisk() {
        if (!_stateFlow.value.isLoaded) return
        expansionLock.withLock { saveLock.withLock { reloadUnderLocks() } }
    }

    private suspend fun reloadUnderLocks() {
        val old = _stateFlow.value
        val oldFolderOf = HashMap<LineId, String>()
        for (id in old.lineIds) {
            if (id in trashedIds) continue
            promotedSubtrees[id]?.let { oldFolderOf[id] = it.folderRel }
        }
        val expandedFolders = oldFolderOf.filterKeys { it !in old.unloadedRefIds }.values.toHashSet()
        val refcountByFolder = HashMap<String, Int>()
        for ((id, count) in expansionRefcounts) oldFolderOf[id]?.let { refcountByFolder[it] = count }

        // Load the file, then splice every previously expanded folder back
        // in; rows spliced in are visited later, so nested ones follow.
        val loaded = repository.loadFile(fileRel)
        val lines = loaded.lines.ifEmpty { listOf(emptyLine) }.toMutableList()
        val refs = MutableList<PromotedRef?>(lines.size) { loaded.promotedByRow[it] }
        val expanded = MutableList(lines.size) { false }
        var row = 0
        while (row < lines.size) {
            val ref = refs[row]
            val indent = if (ref != null && ref.folderRel in expandedFolders) DocumentLayout.itemColumn(lines, row) else -1
            if (ref != null && indent >= 0) {
                val sub = repository.loadSubtree(ref.folderRel, indent)
                val at = DocumentLayout.itemLastRow(lines, row) + 1
                lines.addAll(at, sub.lines)
                refs.addAll(at, List(sub.lines.size) { sub.promotedByRow[it] })
                expanded.addAll(at, List(sub.lines.size) { false })
                expanded[row] = true
            }
            row++
        }

        // The disk holds exactly what is shown, or exactly what this
        // document last saved (an echo of our own save, a sync client
        // re-touching the files): not an outside edit. Keep the unsaved
        // edits, the undo history and reloadCount.
        val folders = refs.map { it?.folderRel }
        val shown = lines == old.lines && folders == old.lineIds.map { oldFolderOf[it] }
        val saved = lines.joinToString("\n") == lastSavedText && folders == lastSavedFolders
        if (shown || saved) return

        val oldKeys = old.lineIds.mapIndexed { i, id -> oldFolderOf[id]?.let { "F:$it" } ?: "T:${old.lines[i]}" }
        val newKeys = List(lines.size) { i -> refs[i]?.let { "F:${it.folderRel}" } ?: "T:${lines[i]}" }
        val matched = matchIds(oldKeys, newKeys)
        val ids = List(lines.size) { i -> matched[i]?.let { old.lineIds[it] } ?: allocateId() }

        promotedSubtrees.clear()
        trashedIds.clear()
        expansionRefcounts.clear()
        val unloaded = HashSet<LineId>()
        for (i in lines.indices) {
            val ref = refs[i] ?: continue
            promotedSubtrees[ids[i]] = ref
            if (!expanded[i]) unloaded += ids[i]
            else refcountByFolder[ref.folderRel]?.let { expansionRefcounts[ids[i]] = it }
        }
        lastCut = null
        lastCutImageHomes = emptyMap()
        lastSavedText = lines.joinToString("\n")
        lastSavedFolders = refs.map { it?.folderRel }
        lastSavedUnloaded = unloaded
        _stateFlow.value = old.copy(
            lines = lines.toList(),
            lineIds = ids,
            isRestructuring = false,
            unloadedRefIds = unloaded,
            reloadCount = old.reloadCount + 1,
        )
        imageHomes.clear()
        recordMissingImageHomes()
        recomputeDirty()
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

    /**
     * Runs one save when anything changed since the last one — and a
     * second one right after when the first moved images out of a folder
     * ([followImagesAfterSave]), so a folder that is now empty is demoted
     * at once. Caller holds [saveLock].
     */
    private suspend fun saveIfDirtyUnderLock() {
        if (!isDirty(_stateFlow.value)) return
        if (runOneSave()) runOneSave()
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
     * demoted rows, trash paths for deleted rows. Finally moves the
     * images of rows that changed folder ([followImagesAfterSave]) and
     * reports every folder and file this save moved to `onAfterSave`.
     *
     * @return `true` when images were moved, so the caller saves once more.
     */
    private suspend fun runOneSave(): Boolean {
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
        // Every folder this save renamed or moved (old → new path), plus the
        // trash moves, for the link rewrite.
        val moves = ArrayList<PathMove>()
        for ((idx, old) in rowToRef) {
            val now = result.promotedByRow[idx] ?: continue
            if (old.folderRel != now.folderRel && old.folderRel.isNotEmpty()) moves += PathMove(old.folderRel, now.folderRel)
        }
        moves += result.trashMoves
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
                    // A folder that held user files stayed in place; undo
                    // merges the trashed outline back into it.
                    val keptAt = ref.folderRel.takeIf { it in result.keptInPlace }
                    promotedSubtrees[holder] = PromotedRef(trashPath, keptAt)
                    trashedIds += holder
                } else if (holder == id) {
                    promotedSubtrees.remove(id)
                }
            }
        }
        spliceAdoptedItems(state, result)
        // Rows demoted by this save can no longer be "unloaded".
        val after = _stateFlow.value
        val prunedUnloaded = after.unloadedRefIds.filterTo(HashSet()) { it in promotedSubtrees }
        if (prunedUnloaded.size != after.unloadedRefIds.size) {
            _stateFlow.value = after.copy(unloadedRefIds = prunedUnloaded)
        }
        lastSavedText = text
        lastSavedFolders = List(state.lines.size) { result.promotedByRow[it]?.folderRel }
        // What this save saw, minus rows it demoted. A splice during the
        // save leaves the live set different, so the document stays dirty.
        lastSavedUnloaded = state.unloadedRefIds.filterTo(HashSet()) { it in promotedSubtrees }
        recomputeDirty()
        val movedImages = followImagesAfterSave(state)
        moves += movedImages
        try { onAfterSave(moves) } catch (e: Throwable) {
            println("[autosave] after-save hook failed for $fileRel: $e")
        }
        return movedImages.isNotEmpty()
    }

    /**
     * Shows the bullets an adopted folder already held
     * ([NoteRepository.SaveResult.adoptedItems]): they go after the row's
     * own children, where the save wrote them, their folder-backed rows
     * folded. Bumps [State.reloadCount] so panes drop undo snapshots that
     * predate them. Caller holds [saveLock], right after the save.
     *
     * @param saved The state the save was run on; its rows are the
     *   result's row numbers.
     */
    private suspend fun spliceAdoptedItems(saved: State, result: NoteRepository.SaveResult) {
        for ((savedRow, items) in result.adoptedItems) {
            val id = saved.lineIds.getOrNull(savedRow) ?: continue
            val folder = promotedSubtrees[id]?.folderRel ?: continue
            val current = _stateFlow.value
            val row = current.lineIds.indexOf(id)
            val indent = DocumentLayout.itemColumn(current.lines, row)
            if (row < 0 || indent < 0) continue
            val loaded = repository.composeAdoptedItems(folder, items, indent)
            val at = SubtreeCodec.composedSubtreeEnd(current.lines, row) + 1
            val newIds = List(loaded.lines.size) { allocateId() }
            val nested = HashSet<LineId>()
            for ((localRow, ref) in loaded.promotedByRow) {
                val nestedId = newIds.getOrNull(localRow) ?: continue
                promotedSubtrees[nestedId] = ref
                nested += nestedId
            }
            _stateFlow.value = current.copy(
                lines = current.lines.toMutableList().apply { addAll(at, loaded.lines) },
                lineIds = current.lineIds.toMutableList().apply { addAll(at, newIds) },
                unloadedRefIds = current.unloadedRefIds + nested,
                reloadCount = current.reloadCount + 1,
            )
        }
        if (result.adoptedItems.isNotEmpty()) recordMissingImageHomes()
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
                row >= 0 && SubtreeCodec.composedSubtreeEnd(state.lines, row) >
                    DocumentLayout.itemLastRow(state.lines, row)
            } ?: return
            val ref = promotedSubtrees[target]
            val row = state.lineIds.indexOf(target)
            val indent = DocumentLayout.itemColumn(state.lines, row)
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
        /** Above this many cell comparisons [matchIds] skips the diff of the changed middle. */
        private const val MAX_DIFF_CELLS: Long = 4_000_000L

        /**
         * Pairs rows of a reloaded document with rows of the old one, for
         * id reuse in [reloadFromDisk]. Keys name a row: `F:<folder>` for a
         * folder-backed row (matched wherever it moved), `T:<line>` for
         * any other (matched in order: common prefix and suffix, then the
         * longest common subsequence of the rest).
         *
         * @return For each new row, the index of its old row, or `null`
         *   for a new row. No old row is used twice.
         */
        internal fun matchIds(oldKeys: List<String>, newKeys: List<String>): Array<Int?> {
            val out = arrayOfNulls<Int>(newKeys.size)
            val usedOld = HashSet<Int>()
            val oldFolderRow = HashMap<String, Int>()
            for ((i, k) in oldKeys.withIndex()) if (k.startsWith("F:") && k !in oldFolderRow) oldFolderRow[k] = i
            for ((j, k) in newKeys.withIndex()) {
                if (!k.startsWith("F:")) continue
                val i = oldFolderRow.remove(k) ?: continue
                out[j] = i
                usedOld += i
            }
            // Text rows, in order, among the rows not matched by folder.
            val olds = oldKeys.indices.filter { it !in usedOld && !oldKeys[it].startsWith("F:") }
            val news = newKeys.indices.filter { out[it] == null && !newKeys[it].startsWith("F:") }
            fun eq(i: Int, j: Int): Boolean = oldKeys[olds[i]] == newKeys[news[j]]
            fun take(i: Int, j: Int) { out[news[j]] = olds[i] }
            var lo = 0
            while (lo < olds.size && lo < news.size && eq(lo, lo)) { take(lo, lo); lo++ }
            var hiOld = olds.size
            var hiNew = news.size
            while (hiOld > lo && hiNew > lo && eq(hiOld - 1, hiNew - 1)) {
                take(hiOld - 1, hiNew - 1); hiOld--; hiNew--
            }
            val n = hiOld - lo
            val m = hiNew - lo
            if (n == 0 || m == 0 || n.toLong() * m > MAX_DIFF_CELLS) return out
            // dp[i][j] = LCS length of olds[lo+i..hiOld) and news[lo+j..hiNew).
            val w = m + 1
            val dp = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i * w + j] = if (eq(lo + i, lo + j)) dp[(i + 1) * w + j + 1] + 1
                else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    eq(lo + i, lo + j) -> { take(lo + i, lo + j); i++; j++ }
                    dp[(i + 1) * w + j] >= dp[i * w + j + 1] -> i++
                    else -> j++
                }
            }
            return out
        }

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
