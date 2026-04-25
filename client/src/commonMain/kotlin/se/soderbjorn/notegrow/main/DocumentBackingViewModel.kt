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
import kotlin.jvm.JvmInline
import se.soderbjorn.notegrow.data.NoteRepository

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
     */
    data class State(
        val lines: List<String> = listOf(""),
        val lineIds: List<LineId> = listOf(LineId(0L)),
        val isLoaded: Boolean = false
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

    private val _stateFlow = MutableStateFlow(State())

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
     * the repository has split into their own `.nogr` files, and the relative
     * directory each one currently lives in. Populated on load and updated
     * on every save tick. Not part of [State] because the view layer doesn't
     * need to see it.
     */
    private val promotedSubtrees: MutableMap<LineId, String> = mutableMapOf()

    init {
        scope.launch { loadFromDisk() }
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
     * One-shot initial load from [repository]. Recursively composes the
     * outline from `root.nogr` plus any nested promoted child files,
     * assigns a fresh [LineId] to every loaded line, and stashes the
     * row→dirRel map by [LineId] in [promotedSubtrees] for the autosave loop.
     *
     * Called exactly once from [init]; no caller should invoke it directly.
     */
    private suspend fun loadFromDisk() {
        val loaded = repository.load()
        val lines = loaded.lines.ifEmpty { listOf("") }
        val ids = List(lines.size) { allocateId() }
        promotedSubtrees.clear()
        for ((row, dir) in loaded.promotedByRow) {
            if (row in ids.indices) promotedSubtrees[ids[row]] = dir
        }
        lastSavedText = lines.joinToString("\n")
        _stateFlow.value = _stateFlow.value.copy(lines = lines, lineIds = ids, isLoaded = true)
    }

    /**
     * Periodically serializes the current document back to disk via
     * [repository] if — and only if — the composed text has changed since
     * the last save. After each save, [promotedSubtrees] is rebuilt from
     * the row→dirRel map the repository returns, keeping it consistent with
     * disk regardless of any renames or demotions the policy decided.
     *
     * Called exactly once from [init] and runs for the lifetime of [scope].
     */
    private suspend fun runAutoSaveLoop() {
        while (true) {
            delay(autoSaveIntervalMillis)
            val state = _stateFlow.value
            if (!state.isLoaded) continue
            val currentText = state.lines.joinToString("\n")
            if (currentText == lastSavedText) continue
            // Translate the LineId→dir map into row→dir for the repository.
            val rowToDir = HashMap<Int, String>(promotedSubtrees.size)
            for ((idx, id) in state.lineIds.withIndex()) {
                val dir = promotedSubtrees[id] ?: continue
                rowToDir[idx] = dir
            }
            val newRowToDir = repository.save(state.lines, rowToDir)
            promotedSubtrees.clear()
            for ((row, dir) in newRowToDir) {
                if (row in state.lineIds.indices) promotedSubtrees[state.lineIds[row]] = dir
            }
            lastSavedText = currentText
        }
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
