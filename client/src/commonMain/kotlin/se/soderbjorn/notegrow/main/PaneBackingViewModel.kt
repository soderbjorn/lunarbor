/*
 * PaneBackingViewModel.kt
 * -----------------------
 * The per-pane backing view-model. One [PaneBackingViewModel] per visible
 * editor pane (window / device / split). Owns every piece of state that
 * is logically per-viewer:
 *
 *   - cursor, selection anchor
 *   - zoom target, zoom history, file history (cross-file back / forward)
 *   - within-file fold state (`collapsedIds`)
 *   - undo / redo stack
 *   - pending inline styles (Cmd-B armed + waiting for the next keystroke)
 *
 * It also holds a reference to *one* [Document] at a time — whichever
 * file the pane is currently viewing. Two panes pointed at the same
 * `fileRel` share one [Document] instance via [DocumentRegistry], so
 * concurrent edits show up live in both. Navigating to a different file
 * (via [navigateToVaultFile] / [fileBack] / [fileForward]) acquires the
 * new document, swaps the inner collector, and releases the old one.
 *
 * Editing behavior is split across small siblings in this package:
 *
 *   - [TextEditingViewModel] — typing, deletion, indent/outdent,
 *     movement, selection.
 *   - [ZoomNavigation]       — zoom in/out and zoom-info resolution.
 *   - [MarkdownStyleViewModel] — inline / line-level markdown styling.
 *   - SelectionHelper.kt     — pure helpers (no class).
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import se.soderbjorn.notegrow.data.InlineMarkdownTokenizer
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineStyle
import se.soderbjorn.notegrow.data.LinkUrl
import se.soderbjorn.notegrow.data.SubtreeCodec
import se.soderbjorn.notegrow.data.VaultEntry
import se.soderbjorn.notegrow.data.VaultIndex

/**
 * Sort modes the vault footer's "Files" list can be in. Direction
 * (ascending vs descending) is tracked separately per mode in
 * [PaneBackingViewModel.State] so toggling modes preserves each side's
 * preferred direction.
 */
enum class FilesSortMode { NAME, EDITED }

/**
 * Per-pane backing view-model. Mirrors the active [Document]'s content
 * and adds cursor, selection, zoom, file navigation, undo/redo, and
 * per-pane fold state on top.
 *
 * ### Callers
 * - Created per pane by the platform layer (web `AppShell.ensurePaneViewModel`)
 *   and wrapped in a thin platform-specific `MainViewModel` facade.
 * - Tests exercise the intents directly without going through a
 *   platform VM.
 *
 * @param registry The shared [DocumentRegistry] used to acquire/release
 *   [Document]s as the pane navigates between files.
 * @param scope Coroutine scope owning the inner collectors that mirror
 *   the active document's state and the registry's vault listings into
 *   this pane's state flow.
 * @param initialFileRel Vault-relative path of the file this pane should
 *   open with. Typically the configured root.
 */
class PaneBackingViewModel(
    private val registry: DocumentRegistry,
    private val scope: CoroutineScope,
    initialFileRel: String,
) {
    /** Mirrors [DocumentRegistry.rootFileName] for the view layer. */
    val rootFileName: String get() = registry.rootFileName

    /** App-scoped outline index, exposed for the Insert Link modal's search. */
    val vaultIndex: VaultIndex get() = registry.vaultIndex

    /**
     * Lists vault image files (vault-relative paths under `Images/`).
     * Used by the `Insert Image` palette flavour. Suspending because it
     * crosses the FileSystem expect/actual boundary.
     */
    suspend fun listImageFiles(): List<String> = registry.listImageFiles()

    /**
     * Immutable snapshot of one pane's state.
     *
     * @property activeFileRel Vault-relative path of the file this pane
     *   is currently viewing. Distinct per pane — two panes at the same
     *   file share a [Document] but each pane owns its own copy of
     *   [activeFileRel].
     * @property documentState Mirror of the active [Document]'s latest
     *   state, or `null` before the first emission. Kept in sync by
     *   the inner collector and by [patch] after every edit, so every
     *   emission is a consistent snapshot of document + pane state.
     * @property vaultListings Mirror of the registry's shared vault-tree
     *   directory cache. Mirrored here so view code can read pane state
     *   alone without a separate registry handle.
     * @property cursorRow Row of the caret, in absolute document coords.
     * @property cursorCol Column of the caret on [cursorRow].
     * @property anchorRow If non-null, together with [anchorCol] defines
     *   the other end of the active selection. `null` means "no
     *   selection — the caret is just a point".
     * @property anchorCol Column companion to [anchorRow].
     * @property zoomedLineId If non-null, the [LineId] of the bullet
     *   whose subtree is being shown. `null` means "at root".
     * @property zoomHistory Browser-style back stack of previous zoom
     *   targets within the active file. Capped at [NAV_HISTORY_CAP].
     * @property zoomForward Browser-style forward stack populated by
     *   [zoomBack] and consumed by [zoomForward].
     * @property fileHistory Browser-style back stack of `(fileRel,
     *   zoomedLineId)` pairs the user has switched away from. A single
     *   Back chord walks zoom-back first, then file-back.
     * @property fileForward Mirror of [fileHistory] for forward
     *   navigation.
     * @property collapsedIds Within-file fold state.
     * @property expandedRefIdsLocal Per-pane intent: which promoted-ref
     *   ids THIS pane wants expanded. Drives chevron direction and
     *   visibility for refs without coupling to other panes' choices.
     *   Distinct from `Document.State.expandedRefIds`, which is the
     *   shared "currently spliced into lines" set; the document
     *   refcounts these per-pane intents to decide when to evict
     *   children from `lines`.
     * @property pendingLeafZoomChild When non-null, the [LineId] of an
     *   empty placeholder bullet that was inserted by [zoomInto] when
     *   the user zoomed into a childless leaf so the zoom view would
     *   have something to type into. Cleared on the first edit (the
     *   user has committed to the placeholder). On any zoom navigation
     *   away or file switch, if the placeholder is still empty, the row
     *   is deleted again — so "zoom in, look around, go back" never
     *   leaves a stray empty bullet behind. [NoteRepository.save] also
     *   strips any trailing empty bullet that survives in memory before
     *   it reaches disk.
     * @property seenLineIds Internal: the set of [LineId]s the
     *   default-collapse pass has already processed.
     * @property isVaultFooterExpanded Master toggle for the editor's
     *   filesystem-tree footer.
     * @property expandedVaultPaths Per-pane open folders in the
     *   filesystem-tree footer.
     * @property filesSortMode Active sort mode for the vault footer's
     *   "Files" listing. [FilesSortMode.NAME] sorts alphabetically by
     *   display name (case-insensitive); [FilesSortMode.EDITED] sorts by
     *   the file's last-modified timestamp. Directories always come
     *   first regardless of mode.
     * @property filesSortNameDescending Direction for [FilesSortMode.NAME].
     *   Remembered across mode switches so toggling Edited → Name returns
     *   to the user's last-used name direction. Defaults to ascending.
     * @property filesSortEditedDescending Direction for [FilesSortMode.EDITED].
     *   Defaults to descending — most-recent-edit first matches the usual
     *   "what did I touch last" intuition.
     * @property pendingInlineStyles Inline styles armed via Cmd-B / etc
     *   while the caret was collapsed.
     */
    data class State(
        val activeFileRel: String = "",
        val documentState: Document.State? = null,
        val vaultListings: Map<String, List<VaultEntry>> = emptyMap(),
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null,
        val zoomedLineId: LineId? = null,
        val zoomHistory: List<LineId?> = emptyList(),
        val zoomForward: List<LineId?> = emptyList(),
        val fileHistory: List<String> = emptyList(),
        val fileForward: List<String> = emptyList(),
        val collapsedIds: Set<LineId> = emptySet(),
        val expandedRefIdsLocal: Set<LineId> = emptySet(),
        val pendingLeafZoomChild: LineId? = null,
        internal val seenLineIds: Set<LineId> = emptySet(),
        val isVaultFooterExpanded: Boolean = true,
        val expandedVaultPaths: Set<String> = emptySet(),
        val filesSortMode: FilesSortMode = FilesSortMode.NAME,
        val filesSortNameDescending: Boolean = false,
        val filesSortEditedDescending: Boolean = true,
        val pendingInlineStyles: Set<InlineStyle> = emptySet(),
    ) {
        /** `true` once the document has loaded from disk at least once. */
        val isLoaded: Boolean get() = documentState?.isLoaded == true

        /**
         * `true` while the document is mid-save on a tick that
         * promotes or demotes a subtree across the per-file boundary
         * — see [Document.State.isRestructuring].
         */
        val isRestructuring: Boolean get() = documentState?.isRestructuring == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")

        /**
         * `true` when the editor is currently displaying the configured
         * root file (so the vault-tree footer should render).
         */
        fun isAtRootFile(rootFileName: String): Boolean =
            activeFileRel == rootFileName

        /**
         * The vault-relative directory that [activeFileRel] is the
         * "anchor" for, or `null` when the active file is not a
         * directory anchor.
         *
         * A file is the anchor for a directory in two cases:
         *
         *  - The active file is the configured root file — it anchors
         *    the vault root (returns `""`).
         *  - The active file's path has the doubled-name shape
         *    `<dir>/<basename>.md` where the last directory segment
         *    equals the file's basename without `.md` (returns
         *    `<dir>` — the full directory path from the vault root).
         *
         * Used by the filesystem-tree footer to decide whether to
         * render — and, when rendering, which directory to scope the
         * listing to. The root case is special-cased so we don't have
         * to teach every caller about it.
         *
         * @param rootFileName Vault-relative path of the configured
         *   root file (typically `Home.md`).
         */
        fun anchoredDirectoryOf(rootFileName: String): String? =
            anchoredDirectoryFor(activeFileRel, rootFileName)

        /**
         * Same anchor logic as [anchoredDirectoryOf], but resolved against
         * an arbitrary [fileRel] instead of [activeFileRel]. Used by the
         * footer when the pane is zoomed into a promoted-ref bullet whose
         * subtree *is* a doubled-name anchor file — in that case the
         * footer should render the child file's directory listing, not
         * the active file's.
         *
         * @param fileRel Vault-relative path of the candidate anchor file.
         * @param rootFileName Vault-relative path of the configured root.
         */
        fun anchoredDirectoryFor(fileRel: String, rootFileName: String): String? {
            if (fileRel == rootFileName) return ""
            if (!fileRel.endsWith(".md")) return null
            val basename = fileRel.substringAfterLast('/').removeSuffix(".md")
            if (basename.isEmpty()) return null
            val parentDir = fileRel.substringBeforeLast('/', missingDelimiterValue = "")
            if (parentDir.isEmpty()) return null
            val lastSegment = parentDir.substringAfterLast('/')
            if (!lastSegment.equals(basename, ignoreCase = false)) return null
            return parentDir
        }
    }

    /**
     * A normalized selection range (start ≤ end, by row-then-column),
     * or absent when the caret is not extending any selection. Produced
     * by `selectionOf` from the raw anchor/cursor pair.
     */
    data class Selection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    /**
     * Resolved zoom geometry. Computed on demand by `zoomInfoOf` from
     * the current zoom id and document state — never stored, so it
     * cannot go stale.
     */
    /**
     * Resolved zoom geometry plus the leaf node's text and style.
     *
     * @property titleText the bullet's display text with leading indent,
     *   the `"* "` bullet marker, AND any line-level markdown prefix
     *   (e.g. `# `, `> `) stripped. Inline markers are left intact;
     *   consumers that need a flat label run this through
     *   [se.soderbjorn.notegrow.data.InlineMarkdownTokenizer].
     * @property style the line-level style detected on the zoomed bullet
     *   (heading level or quote), or `null` for plain text. Renderers use
     *   this to apply heading/quote visual styling to the zoom headline.
     */
    data class ZoomInfo(
        val zoomRow: Int,
        val zoomIndent: Int,
        val startRow: Int,
        val endRowInclusive: Int,
        val titleText: String,
        val style: LineStyle? = null,
    ) {
        /** `true` when the zoom target has at least one descendant bullet. */
        val hasVisibleRows: Boolean get() = startRow <= endRowInclusive
    }

    private val _stateFlow = MutableStateFlow(State(activeFileRel = initialFileRel))

    /**
     * Observable stream of pane states. Platform `MainViewModel`s
     * collect this and re-emit through their own envelope.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The [Document] the pane is currently viewing. Swapped by
     * [switchActiveFile] on cross-file navigation. Slice classes read
     * the live document via [currentDocument]; passing them this lambda
     * keeps them oblivious to swaps.
     */
    private var document: Document? = null

    private fun currentDocument(): Document = document
        ?: error("PaneBackingViewModel: no document acquired yet")

    /**
     * Job mirroring [Document.stateFlow] into [State.documentState].
     * Cancelled and re-launched whenever [switchActiveFile] swaps the
     * active document.
     */
    private var documentCollectorJob: Job? = null

    private val textEditing = TextEditingViewModel(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        applyState = { _stateFlow.value = it },
        mutate = { transform -> mutate(transform) },
        patch = { transform -> patch(transform) },
        revealAncestors = { row -> revealAncestors(row) },
    )

    private val zoomNavigation = ZoomNavigation(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        scope = scope,
        cleanupEmptyPlaceholder = { cleanupEmptyPlaceholderIfAny() },
    )

    private val markdownStyle = MarkdownStyleViewModel(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        selectWord = { row, col -> textEditing.selectWord(row, col) },
    )

    init {
        // Mirror the registry's shared vault-listings cache into pane
        // state so view code can read both document content and folder
        // tree from a single state snapshot.
        scope.launch {
            registry.vaultListingsFlow.collect { listings ->
                _stateFlow.value = _stateFlow.value.copy(vaultListings = listings)
            }
        }
        // Acquire the initial document and start mirroring it.
        scope.launch {
            val doc = registry.acquire(initialFileRel)
            document = doc
            startDocumentCollector(doc)
        }
    }

    /**
     * Subscribes to [doc]'s state flow and mirrors each emission into
     * [State.documentState], applying the default-collapse pass and
     * reconciling. The returned job is stored in [documentCollectorJob]
     * so [switchActiveFile] can cancel it before swapping.
     */
    private fun startDocumentCollector(doc: Document) {
        documentCollectorJob = scope.launch {
            doc.stateFlow.collect { docState ->
                val merged = _stateFlow.value.copy(documentState = docState)
                val withDefaults = applyDefaultCollapseIfNeeded(merged)
                _stateFlow.value = reconcile(withDefaults)
            }
        }
    }

    /**
     * Acquires the [Document] for [fileRel], cancels the inner
     * collector for the outgoing document, swaps to the new document,
     * starts a new inner collector, and releases the outgoing one.
     * Idempotent on the same `fileRel`.
     *
     * Resets per-pane state that no longer applies in the new file:
     * cursor, anchor, zoom target + history, fold state. File history
     * bookkeeping is not touched here — callers ([navigateToVaultFile],
     * [fileBack], [fileForward]) push / pop those stacks themselves.
     */
    private suspend fun switchActiveFile(fileRel: String) {
        if (_stateFlow.value.activeFileRel == fileRel && document != null) return
        // Strip any throwaway placeholder before swapping the active
        // document — otherwise an empty placeholder bullet inserted by a
        // leaf-zoom on the outgoing file would persist into the next
        // autosave tick on that file.
        cleanupEmptyPlaceholderIfAny()
        val outgoing = document
        val outgoingFile = _stateFlow.value.activeFileRel
        val outgoingExpansions = _stateFlow.value.expandedRefIdsLocal
        val incoming = registry.acquire(fileRel)
        documentCollectorJob?.cancelAndJoin()
        documentCollectorJob = null
        document = incoming
        // Hand back this pane's per-id expansion intents on the outgoing
        // document BEFORE releasing it, so the refcount can hit zero and
        // the shared document evicts spliced child files no pane wants
        // anymore.
        if (outgoing != null) {
            for (id in outgoingExpansions) outgoing.releaseExpansion(id)
        }
        // Reset per-pane state tied to the outgoing file.
        _stateFlow.value = _stateFlow.value.copy(
            activeFileRel = fileRel,
            documentState = null,
            cursorRow = 0,
            cursorCol = 0,
            anchorRow = null,
            anchorCol = null,
            zoomedLineId = null,
            zoomHistory = emptyList(),
            zoomForward = emptyList(),
            collapsedIds = emptySet(),
            expandedRefIdsLocal = emptySet(),
            pendingLeafZoomChild = null,
            seenLineIds = emptySet(),
            pendingInlineStyles = emptySet(),
        )
        // Captured snapshots in undo/redo refer to the outgoing
        // document; they would corrupt the new one if applied.
        undoStack.clear()
        redoStack.clear()
        startDocumentCollector(incoming)
        if (outgoing != null && outgoingFile.isNotEmpty() && outgoingFile != fileRel) {
            registry.release(outgoingFile)
        }
    }

    /**
     * Releases the active document. Called by the platform layer when
     * the pane itself is being torn down (e.g. the user closes the
     * pane). Safe to call once; subsequent calls are no-ops.
     */
    suspend fun release() {
        // Strip any throwaway placeholder before tearing down — a pane
        // closed mid-leaf-zoom should not persist its empty placeholder
        // through [Document.shutdown]'s final save.
        cleanupEmptyPlaceholderIfAny()
        documentCollectorJob?.cancelAndJoin()
        documentCollectorJob = null
        val outgoing = document
        val outgoingFile = _stateFlow.value.activeFileRel
        val outgoingExpansions = _stateFlow.value.expandedRefIdsLocal
        document = null
        if (outgoing != null) {
            for (id in outgoingExpansions) outgoing.releaseExpansion(id)
        }
        if (outgoing != null && outgoingFile.isNotEmpty()) {
            registry.release(outgoingFile)
        }
    }

    /**
     * Adds newly observed parent-bullet and promoted-ref [LineId]s to
     * [State.collapsedIds] so each subtree starts folded the first time
     * we see it. The pass runs on `lineIds - seenLineIds` so it does
     * not re-collapse ids the user has explicitly expanded.
     */
    private fun applyDefaultCollapseIfNeeded(state: State): State {
        val docState = state.documentState ?: return state
        if (!docState.isLoaded) return state
        val seen = state.seenLineIds
        val currentIds = docState.lineIds
        if (seen.size == currentIds.size && seen.containsAll(currentIds)) return state

        val newCollapsed = HashSet<LineId>()
        val doc = document
        for ((idx, id) in currentIds.withIndex()) {
            if (id in seen) continue
            if (idx !in docState.lines.indices) continue
            val line = docState.lines[idx]
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0) continue
            val isParent = DocumentLayout.hasChildren(docState.lines, idx, indent)
            val isRef = doc?.isPromotedRef(id) == true
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
     * `true` if [lineId] is a file-boundary reference whose children
     * live in a separate `.md` file. The paint loop uses this to
     * decide whether to render a chevron on a bullet that has no
     * children currently in `lines`.
     */
    fun isPromotedRef(lineId: LineId): Boolean = document?.isPromotedRef(lineId) == true

    /**
     * Toggle the fold state of [lineId] for THIS pane. For file-boundary
     * references the call also drives `Document.acquireExpansion` /
     * `releaseExpansion` so the shared document refcounts pane-local
     * intents — children are spliced in on the first acquire across all
     * panes, and only evicted when every pane has released.
     */
    fun toggleCollapse(lineId: LineId) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val doc = document ?: return
        val isRef = doc.isPromotedRef(lineId)
        if (isRef) {
            val isExpandedInPane = lineId in current.expandedRefIdsLocal
            if (isExpandedInPane) {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal - lineId,
                        collapsedIds = it.collapsedIds + lineId,
                    )
                }
                scope.launch { doc.releaseExpansion(lineId) }
            } else {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal + lineId,
                        collapsedIds = it.collapsedIds - lineId,
                    )
                }
                scope.launch { doc.acquireExpansion(lineId) }
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

    /**
     * Clears [State.pendingLeafZoomChild] if set. Called at the start of
     * every text-edit intent: once the user starts typing into the
     * placeholder, it is no longer "throwaway" — subsequent navigation
     * should leave it alone instead of cleaning it up.
     */
    private fun commitPlaceholderIfAny() {
        if (_stateFlow.value.pendingLeafZoomChild != null) {
            patch { it.copy(pendingLeafZoomChild = null) }
        }
    }

    /**
     * If [State.pendingLeafZoomChild] points at a row that is still an
     * empty bullet, deletes that row from the document (merging the
     * placeholder line back into the parent's tail) and clears the
     * pending flag. Used by every zoom-changing intent and by
     * [switchActiveFile] so "zoom into a childless bullet, look around,
     * go back" never leaves the placeholder behind.
     *
     * Safe to call when no placeholder is pending — it's a no-op in
     * that case.
     */
    private fun cleanupEmptyPlaceholderIfAny() {
        val s = _stateFlow.value
        val pending = s.pendingLeafZoomChild ?: return
        val docState = s.documentState
        val doc = document
        if (docState == null || doc == null) {
            patch { it.copy(pendingLeafZoomChild = null) }
            return
        }
        val row = docState.lineIds.indexOf(pending)
        if (row < 0 || row !in docState.lines.indices) {
            patch { it.copy(pendingLeafZoomChild = null) }
            return
        }
        val line = docState.lines[row]
        if (!DocumentLayout.isEmptyBulletLine(line)) {
            // User has put text in the placeholder by some path that
            // didn't go through commit (rare). Just clear the flag and
            // leave the content alone.
            patch { it.copy(pendingLeafZoomChild = null) }
            return
        }
        if (row == 0) {
            // Defensive: we always insert the placeholder *after* the
            // parent row, so the placeholder can never be at row 0. If
            // that invariant is broken, drop the row by replacing it
            // with an empty line instead of merging upward.
            patch { it.copy(pendingLeafZoomChild = null) }
            return
        }
        val prevLineLen = docState.lines[row - 1].length
        doc.delete(row - 1, prevLineLen, row, line.length)
        patch { it.copy(pendingLeafZoomChild = null) }
    }

    /** See [TextEditingViewModel.insertChar]. */
    fun insertChar(char: Char) {
        recordEdit(FrameKind.TYPING) {
            commitPlaceholderIfAny()
            textEditing.insertChar(char)
        }
    }

    /** See [TextEditingViewModel.insertNewline]. */
    fun insertNewline() {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertNewline()
            revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.insertText]. */
    fun insertText(text: String) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertText(text)
            if ('\n' in text || '\r' in text) revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.insertLiteralText]. */
    fun insertLiteralText(text: String) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertLiteralText(text)
            if ('\n' in text || '\r' in text) revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.backspace]. */
    fun backspace() {
        recordEdit(FrameKind.BACKSPACE) {
            commitPlaceholderIfAny()
            textEditing.backspace()
        }
    }

    /** See [TextEditingViewModel.indentLine]. */
    fun indentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.indentLine(amount)
            revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.outdentLine]. */
    fun outdentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.outdentLine(amount)
        }
    }

    /** See [TextEditingViewModel.isBulletLine]. */
    fun isBulletLine(): Boolean = textEditing.isBulletLine()

    /**
     * Resolves the absolute row range owned by the bullet at [row]:
     * the bullet itself plus its descendants up to (but not including)
     * the next sibling-or-shallower row.
     */
    fun subtreeRange(row: Int): IntRange? {
        val doc = document ?: return null
        val docState = doc.stateFlow.value
        if (!docState.isLoaded) return null
        val lines = docState.lines
        if (row !in lines.indices) return null
        val indent = DocumentLayout.bulletAsteriskColumn(lines[row])
        if (indent < 0) return null
        return row..DocumentLayout.subtreeEnd(lines, row, indent)
    }

    /**
     * Move the contiguous row range `[fromStartRow..fromEndRow]` to land
     * before [insertBeforeRow] with [targetIndent] applied to the top
     * of the moved block. See the v1 indent / disallow-cases / id
     * semantics rules from the original docstring; preserved verbatim.
     */
    fun moveLineRange(
        fromStartRow: Int,
        fromEndRow: Int,
        insertBeforeRow: Int,
        targetIndent: Int,
    ) = recordEdit(FrameKind.OTHER) {
        val doc = document ?: return@recordEdit
        val docStart = doc.stateFlow.value
        if (!docStart.isLoaded) return@recordEdit
        val lines0 = docStart.lines
        if (lines0.isEmpty()) return@recordEdit

        val src0 = fromStartRow.coerceIn(0, lines0.lastIndex)
        val src1 = fromEndRow.coerceIn(src0, lines0.lastIndex)
        val drop = insertBeforeRow.coerceIn(0, lines0.size)

        if (drop in (src0 + 1)..src1) return@recordEdit
        if (drop == src0 || drop == src1 + 1) return@recordEdit
        if (src0 == 0 && src1 == lines0.lastIndex) return@recordEdit

        val deletedRowCount = src1 - src0 + 1
        val sourceTopIndent = leadingSpaceCount(lines0[src0])
        val shift = targetIndent - sourceTopIndent
        val movedText = (src0..src1).joinToString("\n") { reindentLine(lines0[it], shift) }

        if (src0 > 0) {
            val above = lines0[src0 - 1]
            val tailRow = src1
            doc.delete(src0 - 1, above.length, tailRow, lines0[tailRow].length)
        } else {
            doc.delete(0, 0, src1 + 1, 0)
        }

        val postDeleteInsertRow = if (drop <= src0) drop else drop - deletedRowCount

        val newLines = doc.stateFlow.value.lines
        val landedFirst: Int
        val landedLast: Int
        if (postDeleteInsertRow > newLines.lastIndex) {
            val lastIdx = newLines.lastIndex
            doc.insertText(lastIdx, newLines[lastIdx].length, "\n" + movedText)
            landedFirst = lastIdx + 1
            landedLast = landedFirst + deletedRowCount - 1
        } else {
            doc.insertText(postDeleteInsertRow, 0, movedText + "\n")
            landedFirst = postDeleteInsertRow
            landedLast = landedFirst + deletedRowCount - 1
        }

        val finalLines = doc.stateFlow.value.lines
        val safeFirst = landedFirst.coerceIn(0, finalLines.lastIndex)
        val caretCol = DocumentLayout.caretStartCol(finalLines[safeFirst])
        patch {
            it.copy(
                anchorRow = null,
                anchorCol = null,
                cursorRow = safeFirst,
                cursorCol = caretCol,
            )
        }
        // Suppress an unused-value warning on landedLast: it's purely
        // for documentation that the logic computed the correct range.
        @Suppress("UNUSED_EXPRESSION") landedLast
    }

    private fun leadingSpaceCount(line: String): Int {
        val nonWs = line.indexOfFirst { !it.isWhitespace() }
        return if (nonWs < 0) line.length else nonWs
    }

    private fun reindentLine(line: String, delta: Int): String {
        val nonWs = leadingSpaceCount(line)
        val newIndent = (nonWs + delta).coerceAtLeast(0)
        return " ".repeat(newIndent) + line.substring(nonWs)
    }

    // ------------------------------------------------------------------ zoom

    /** See [ZoomNavigation.zoomInto]. */
    fun zoomInto(row: Int) = zoomNavigation.zoomInto(row)

    /** See [ZoomNavigation.zoomOut]. */
    fun zoomOut() = zoomNavigation.zoomOut()

    /** See [ZoomNavigation.zoomTo]. */
    fun zoomTo(lineId: LineId?) = zoomNavigation.zoomTo(lineId)

    /**
     * Walk one step back in the unified navigation history. Pops the
     * per-file zoom-history stack first; when that's empty, falls
     * through to the cross-file [State.fileHistory] stack and switches
     * to the previous document.
     */
    fun zoomBack() {
        val s = _stateFlow.value
        if (s.zoomHistory.isNotEmpty()) {
            zoomNavigation.zoomBack()
            return
        }
        if (s.fileHistory.isNotEmpty()) {
            scope.launch { fileBack() }
        }
    }

    /** Mirror of [zoomBack] for the forward direction. */
    fun zoomForward() {
        val s = _stateFlow.value
        if (s.zoomForward.isNotEmpty()) {
            zoomNavigation.zoomForward()
            return
        }
        if (s.fileForward.isNotEmpty()) {
            scope.launch { fileForward() }
        }
    }

    /** `true` when there's somewhere to go back to (zoom or file). */
    fun canZoomBack(state: State = _stateFlow.value): Boolean =
        state.zoomHistory.isNotEmpty() || state.fileHistory.isNotEmpty()

    /** `true` when there's somewhere to go forward to (zoom or file). */
    fun canZoomForward(state: State = _stateFlow.value): Boolean =
        state.zoomForward.isNotEmpty() || state.fileForward.isNotEmpty()

    private suspend fun fileBack() {
        val previous = _stateFlow.value.fileHistory.lastOrNull() ?: return
        val currentFile = _stateFlow.value.activeFileRel
        val priorHistory = _stateFlow.value.fileHistory.dropLast(1)
        val priorForward = _stateFlow.value.fileForward
        switchActiveFile(previous)
        patch {
            it.copy(
                fileHistory = priorHistory,
                fileForward = (priorForward + currentFile).takeLast(NAV_HISTORY_CAP),
            )
        }
    }

    private suspend fun fileForward() {
        val next = _stateFlow.value.fileForward.lastOrNull() ?: return
        val currentFile = _stateFlow.value.activeFileRel
        val priorHistory = _stateFlow.value.fileHistory
        val priorForward = _stateFlow.value.fileForward.dropLast(1)
        switchActiveFile(next)
        patch {
            it.copy(
                fileHistory = (priorHistory + currentFile).takeLast(NAV_HISTORY_CAP),
                fileForward = priorForward,
            )
        }
    }

    /** See [ZoomNavigation.zoomInfo]. */
    fun zoomInfo(state: State = _stateFlow.value): ZoomInfo? = zoomNavigation.zoomInfo(state)

    /**
     * When this pane is zoomed into a bullet that is a promoted-ref
     * (i.e. its subtree's content lives in another file), returns that
     * child file's vault-relative path. Returns `null` when there is no
     * zoom, the zoomed bullet is a plain inline bullet, or the zoomed
     * row can't be resolved in the current document.
     *
     * The footer uses this to decide whether the visible zoom region
     * "really belongs to" a child anchor file — if so, the footer
     * renders that child's directory listing instead of staying hidden.
     */
    fun zoomedPromotedRefFileRel(state: State = _stateFlow.value): String? {
        val zoomedId = state.zoomedLineId ?: return null
        val doc = document ?: return null
        if (!doc.isPromotedRef(zoomedId)) return null
        val docState = doc.stateFlow.value
        val row = docState.lineIds.indexOf(zoomedId)
        if (row < 0) return null
        return doc.promotedByRow()[row]?.fileRel
    }

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

    /**
     * Pushes a logical selection into the pane. Used by platforms whose
     * native text surface owns the live caret (web `contenteditable`,
     * Compose `BasicTextField`, UIKit `UITextView`).
     */
    fun setSelection(anchorRow: Int, anchorCol: Int, cursorRow: Int, cursorCol: Int) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val collapsed = anchorRow == cursorRow && anchorCol == cursorCol
        val moved = current.cursorRow != cursorRow || current.cursorCol != cursorCol ||
            current.anchorRow != (if (collapsed) null else anchorRow) ||
            current.anchorCol != (if (collapsed) null else anchorCol)
        val pending = if (moved) emptySet() else current.pendingInlineStyles
        // Any explicit caret reposition (mouse click, IME) means the
        // user has navigated past the "I just zoomed into a leaf" hint;
        // drop the pending placeholder so a later edit on this caret
        // doesn't accidentally insert a child under the old zoom target.
        val pendingLeaf = if (moved) null else current.pendingLeafZoomChild
        _stateFlow.value = reconcile(
            current.copy(
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                anchorRow = if (collapsed) null else anchorRow,
                anchorCol = if (collapsed) null else anchorCol,
                pendingInlineStyles = pending,
                pendingLeafZoomChild = pendingLeaf,
            )
        )
    }

    /** See [TextEditingViewModel.deleteSelectionIfAny]. */
    fun deleteSelectionIfAny(): Boolean {
        var result = false
        recordEdit(FrameKind.OTHER) { result = textEditing.deleteSelectionIfAny() }
        return result
    }

    /** See [TextEditingViewModel.getSelectedText]. */
    fun getSelectedText(): String? = textEditing.getSelectedText()

    /** See [TextEditingViewModel.onCutRequested]. */
    fun onCutRequested(): String? {
        var text: String? = null
        recordEdit(FrameKind.OTHER) { text = textEditing.onCutRequested() }
        return text
    }

    // ------------------------------------------------------- markdown styles

    /** See [MarkdownStyleViewModel.applyInlineStyle]. */
    fun applyInlineStyle(style: InlineStyle) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            markdownStyle.applyInlineStyle(style)
        }
    }

    /** See [MarkdownStyleViewModel.applyLineStyle]. */
    fun applyLineStyle(style: LineStyle) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            markdownStyle.applyLineStyle(style)
        }
    }

    /** See [MarkdownStyleViewModel.activeInlineStyles]. */
    fun activeInlineStyles(): Set<InlineStyle> = markdownStyle.activeInlineStyles()

    /** See [MarkdownStyleViewModel.activeLineStyle]. */
    fun activeLineStyle(): LineStyle? = markdownStyle.activeLineStyle()

    // ----------------------------------------------------------- vault footer

    /** Flips [State.isVaultFooterExpanded]. */
    fun toggleVaultFooter() {
        patch { it.copy(isVaultFooterExpanded = !it.isVaultFooterExpanded) }
    }

    /**
     * Toggles the vault footer's file-list sort. Clicking the icon for
     * the currently-active [mode] flips that mode's direction; clicking
     * the icon for the inactive mode switches to it (keeping that mode's
     * remembered direction). The per-mode direction memory means the
     * UI feels like "two sticky toggles" rather than a single carousel.
     */
    fun cycleFilesSort(mode: FilesSortMode) {
        patch {
            if (it.filesSortMode != mode) {
                it.copy(filesSortMode = mode)
            } else when (mode) {
                FilesSortMode.NAME ->
                    it.copy(filesSortNameDescending = !it.filesSortNameDescending)
                FilesSortMode.EDITED ->
                    it.copy(filesSortEditedDescending = !it.filesSortEditedDescending)
            }
        }
    }

    /**
     * Materialises the doubled-name anchor file for a folder picked
     * from the Insert Link modal's folder-stub results. Delegates to
     * [DocumentRegistry.ensureFolderStub].
     *
     * Suspends so the Insert Link pick handler can `await` the file
     * creation before computing the link URL via
     * [se.soderbjorn.notegrow.data.VaultIndex.shortestUrlFor] — the
     * resolver only sees the new anchor once it exists on disk.
     */
    suspend fun ensureFolderStub(fileRel: String) {
        registry.ensureFolderStub(fileRel)
    }

    /**
     * Fire-and-forget request that the registry populate
     * [DocumentRegistry.vaultListingsFlow] with the entries under
     * [dirRel] if they are not already cached. Used by the
     * filesystem-tree footer when the active file is a directory
     * anchor whose folder hasn't yet been visited via
     * [toggleVaultFolder] (so the lazy expand never fired). The
     * registry's own [DocumentRegistry.ensureVaultListing] is a no-op
     * when the entry is already present, so calling this repeatedly
     * on every repaint is safe.
     */
    fun ensureVaultListing(dirRel: String) {
        if (_stateFlow.value.vaultListings[dirRel] != null) return
        scope.launch { registry.ensureVaultListing(dirRel) }
    }

    /**
     * Toggles whether the folder at [dirRel] is open in the
     * filesystem-tree footer. Adding a path also kicks off
     * `DocumentRegistry.ensureVaultListing(dirRel)` so the folder's
     * direct children are fetched on first expand.
     */
    fun toggleVaultFolder(dirRel: String) {
        val current = _stateFlow.value
        val isOpening = dirRel !in current.expandedVaultPaths
        patch {
            val next = if (dirRel in it.expandedVaultPaths) {
                it.expandedVaultPaths - dirRel
            } else {
                it.expandedVaultPaths + dirRel
            }
            it.copy(expandedVaultPaths = next)
        }
        if (isOpening) {
            scope.launch { registry.ensureVaultListing(dirRel) }
        }
    }

    /**
     * Switches this pane to the markdown file at [pathRel]. Releases
     * the current file (last pane on it triggers final flush + Document
     * shutdown), acquires the new file (first pane on it triggers a
     * fresh load), and resets per-pane state.
     *
     * Other panes are unaffected — this is genuinely pane-local now.
     * Two panes pointed at the same file share one [Document] so their
     * edits show up in each other live.
     */
    fun navigateToVaultFile(pathRel: String) {
        if (!_stateFlow.value.isLoaded) return
        val currentFile = _stateFlow.value.activeFileRel
        if (currentFile == pathRel) return
        scope.launch {
            switchActiveFile(pathRel)
            patch {
                it.copy(
                    fileHistory = (it.fileHistory + currentFile).takeLast(NAV_HISTORY_CAP),
                    fileForward = emptyList(),
                )
            }
        }
    }

    // ----------------------------------------------------------------- links

    /**
     * The title path inside the active file from the file's top down
     * to the bullet currently containing the cursor. Empty when the
     * cursor is on a non-bullet row or the document hasn't loaded.
     *
     * Used by the Insert Link modal to scope its relative-URL math:
     * combined with [activeFileRel] and [VaultIndex.fullPathFor] this
     * yields the cursor's full vault title path.
     */
    fun currentInFileTitlePath(): List<String> {
        val state = _stateFlow.value
        val docState = state.documentState ?: return emptyList()
        if (!docState.isLoaded) return emptyList()
        val lines = docState.lines
        val row = state.cursorRow
        if (row !in lines.indices) return emptyList()
        val rowIndent = DocumentLayout.bulletAsteriskColumn(lines[row])
        if (rowIndent < 0) return emptyList()
        val baseline = topLevelBulletIndent(lines)
        if (baseline < 0) return emptyList()
        val stack = ArrayDeque<String>()
        // Include the bullet itself, then walk up the ancestor chain by
        // strictly-shallower indent.
        stack.addFirst(SubtreeCodec.titleOf(lines[row]))
        var lookingFor = rowIndent - 1
        var r = row - 1
        while (r >= 0 && lookingFor >= baseline) {
            val ind = DocumentLayout.bulletAsteriskColumn(lines[r])
            if (ind in baseline..lookingFor) {
                stack.addFirst(SubtreeCodec.titleOf(lines[r]))
                lookingFor = ind - 1
            }
            r--
        }
        return stack.toList()
    }

    private fun topLevelBulletIndent(lines: List<String>): Int {
        var min = Int.MAX_VALUE
        for (line in lines) {
            val c = DocumentLayout.bulletAsteriskColumn(line)
            if (c >= 0 && c < min) min = c
        }
        return if (min == Int.MAX_VALUE) -1 else min
    }

    /**
     * Inserts `[label](url)` at the cursor — replacing any active
     * selection — and leaves the caret immediately after the closing
     * `)`. Reuses the standard text-insert pipeline so the edit is
     * undoable, autosaves with the file, and respects pane state
     * (placeholder cleanup, ancestor reveal, etc).
     *
     * @param label Display text inside the brackets. Special markdown
     *   characters (`\`, `[`, `]`, `(`, `)`) are backslash-escaped so
     *   the label round-trips through CommonMark.
     * @param url The URL to put inside the parentheses. Wrapped in
     *   `<…>` automatically when it contains a space, paren, or angle
     *   bracket.
     */
    fun insertMarkdownLink(label: String, url: String) {
        val markdown = "[" + SubtreeCodec.escapeLabel(label) + "](" +
            SubtreeCodec.formatLinkUrlForLabel(url) + ")"
        // Use the literal-insert path so an armed `pendingInlineStyles`
        // (e.g. inline code from Cmd+E) doesn't wrap the markdown link
        // in marker pairs and silently turn it into an inline code span
        // — the link carries its own structural syntax and must reach
        // the document verbatim.
        insertLiteralText(markdown)
    }

    /**
     * Inserts an inline image reference `![alt](vaultRelPath)` at the
     * cursor. Mirrors [insertMarkdownLink] — same selection-replacing
     * literal-insert path so an armed `pendingInlineStyles` doesn't
     * wrap the image syntax in marker pairs.
     *
     * @param vaultRelPath Path to the image relative to the vault root
     *   (e.g. `Images/foo.png`). Wrapped in `<…>` automatically when it
     *   contains spaces / parens / angle brackets (CommonMark rule).
     * @param alt Optional alt text. CommonMark specials are escaped so
     *   the alt round-trips cleanly.
     * @param widthPx Optional display width in CSS pixels. When set, the
     *   width is appended to the alt as `|<digits>` (Obsidian convention)
     *   so it survives the file → display → file round-trip. Standard
     *   CommonMark viewers treat the whole `alt|width` as alt text.
     */
    fun insertImageRef(vaultRelPath: String, alt: String = "", widthPx: Int? = null) {
        val sizedAlt = if (widthPx != null && widthPx > 0) "$alt|$widthPx" else alt
        val markdown = "![" + SubtreeCodec.escapeLabel(sizedAlt) + "](" +
            SubtreeCodec.formatLinkUrlForLabel(vaultRelPath) + ")"
        insertLiteralText(markdown)
    }

    /**
     * Handles a pasted image. Writes [bytes] into the vault's `Images/`
     * folder under [suggestedName] (with `-2`, `-3`, … suffix on
     * collision) and inserts a `![](Images/<final-name>)` reference at
     * the current cursor. No-op when the active document hasn't
     * finished loading — the cursor isn't trustworthy yet.
     *
     * Suspends across the disk write. The caller is expected to launch
     * this on the pane's scope so paste latency doesn't block the UI
     * thread; the markdown insert that follows the write goes through
     * the standard undoable path, so an undo after paste removes the
     * `![…]` (the file on disk is left as an orphan — a Phase-5 reaper
     * task; deleting eagerly would surprise users who paste the same
     * screenshot into multiple notes).
     *
     * @param suggestedName Filename including extension. Generated at
     *   the platform layer (where `Date.now()` / equivalents live) so
     *   commonMain stays clock-agnostic.
     * @param bytes Raw image data from the clipboard.
     */
    suspend fun onImagePasted(suggestedName: String, bytes: ByteArray) {
        if (!_stateFlow.value.isLoaded) return
        val rel = registry.saveImageBytes(suggestedName, bytes)
        insertImageRef(rel)
    }

    /**
     * Rewrites the inline image at [row] whose source path equals
     * [imageSrc] so it carries [widthPx] as its sizing suffix
     * (`alt|widthPx`). Passing `null` strips the existing suffix.
     *
     * The scan locates the image by tokenizing the row and finding the
     * single image run with a matching `imageSrc`; if no such run
     * exists (the user must have edited the line between the click and
     * the apply) the call is a no-op. The resulting edit is undoable
     * via the standard edit pipeline.
     */
    fun setImageWidth(row: Int, imageSrc: String, widthPx: Int?) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val lines = state.documentState?.lines ?: return
        if (row !in lines.indices) return
        val line = lines[row]
        val tokenized = InlineMarkdownTokenizer.tokenize(line)
        // Image runs have `text == ""` and a span that's entirely in
        // markerCols. We locate the run via imageSrc + recover the
        // source span by scanning markerCols outward from modelStart.
        val targetRun = tokenized.runs.firstOrNull {
            it.imageSrc == imageSrc
        } ?: return
        val syntaxStart = targetRun.modelStart  // position of `!`
        // The closing `)` is the last contiguous marker char after
        // modelStart. Walk forward through markerCols.
        var syntaxEnd = syntaxStart
        while (syntaxEnd < line.length && syntaxEnd in tokenized.markerCols) syntaxEnd++
        if (syntaxEnd <= syntaxStart) return
        // Rebuild the markdown with the new width.
        val newAlt = if (widthPx != null && widthPx > 0)
            "${targetRun.imageAlt.orEmpty()}|$widthPx"
        else
            (targetRun.imageAlt.orEmpty())
        val newMarkdown = "![" + SubtreeCodec.escapeLabel(newAlt) + "](" +
            SubtreeCodec.formatLinkUrlForLabel(imageSrc) + ")"
        recordEdit(FrameKind.OTHER) {
            val d = currentDocument()
            d.delete(row, syntaxStart, row, syntaxEnd)
            d.insertText(row, syntaxStart, newMarkdown)
        }
    }

    /**
     * Resolves [url] against the cursor's position and navigates this
     * pane to the target. No-op when the URL is unparseable or the
     * resolver returns [VaultIndex.Resolution.NotFound] — the link
     * text stays in the document for the user to fix manually.
     *
     * Wires together:
     *  1. [LinkUrl.parse] (pure codec).
     *  2. [VaultIndex.resolve] (deterministic walk).
     *  3. [navigateToVaultFile] (if the target lives in another file).
     *  4. [zoomTo] (if the target is a specific bullet).
     */
    fun navigateToLink(url: String, onComplete: () -> Unit = {}) {
        val parsed = LinkUrl.parse(url)
        val state = _stateFlow.value
        if (parsed == null || !state.isLoaded) {
            onComplete()
            return
        }
        val activeFileRel = state.activeFileRel
        val inFilePath = currentInFileTitlePath()
        scope.launch {
            try {
                val cursorFullPath =
                    vaultIndex.fullPathFor(activeFileRel, inFilePath) ?: emptyList()
                val resolution = vaultIndex.resolve(parsed, cursorFullPath)
                if (resolution !is VaultIndex.Resolution.Found) {
                    println("[notegrow] link target not found: $url (cursor at $cursorFullPath)")
                    return@launch
                }
                val isCrossFile = resolution.fileRel != _stateFlow.value.activeFileRel
                if (isCrossFile) {
                    val priorFile = _stateFlow.value.activeFileRel
                    switchActiveFile(resolution.fileRel)
                    patch {
                        it.copy(
                            fileHistory = (it.fileHistory + priorFile).takeLast(NAV_HISTORY_CAP),
                            fileForward = emptyList(),
                        )
                    }
                }
                if (resolution.titlePathInFile.isNotEmpty()) {
                    val targetId = awaitLineIdForTitlePath(resolution.titlePathInFile)
                    if (targetId != null) {
                        if (isCrossFile) {
                            // The link click is this pane's entry point into
                            // the new file — no prior zoom in this file to
                            // remember. Set [zoomedLineId] directly without
                            // pushing to zoomHistory so the unified Back
                            // chord falls through to fileBack (returning to
                            // where the user came from) instead of unzooming
                            // inside the just-arrived file.
                            patch { it.copy(zoomedLineId = targetId) }
                        } else {
                            // Same-file navigation: keep the normal
                            // push-to-history semantics so Back undoes the
                            // zoom in place.
                            zoomTo(targetId)
                        }
                        placeCursorOn(targetId)
                    }
                } else if (isCrossFile) {
                    // File-root navigation (no specific bullet, e.g. user
                    // picked "Framna" in the modal). Park the caret on
                    // row 0 so the contenteditable has a valid DOM
                    // selection to extend from — without this, focusing
                    // the editor leaves the caret unset and chords like
                    // Cmd-Shift-Left have nothing to anchor on.
                    awaitFirstLoadedLineId()?.let { placeCursorOn(it) }
                }
            } finally {
                onComplete()
            }
        }
    }

    /**
     * Awaits the active document's first loaded emission and returns
     * its row-0 [LineId]. Used by [navigateToLink] for file-root
     * navigation where there is no specific bullet path to walk.
     */
    private suspend fun awaitFirstLoadedLineId(): LineId? {
        val doc = document ?: return null
        val loadedState = doc.stateFlow.first { it.isLoaded }
        return loadedState.lineIds.firstOrNull()
    }

    /**
     * Moves the caret onto the row that owns [lineId], collapsing any
     * active selection. No-op when the row is gone (race with an
     * in-flight edit) or the document hasn't loaded.
     *
     * Used by [navigateToLink] so a Cmd-O / link-click navigation
     * lands the caret *on* the target bullet rather than wherever the
     * caret happened to be in the now-zoomed view.
     */
    private fun placeCursorOn(lineId: LineId) {
        patch {
            val docState = it.documentState ?: return@patch it
            val row = docState.lineIds.indexOf(lineId)
            if (row < 0 || row !in docState.lines.indices) return@patch it
            val caretCol = DocumentLayout.caretStartCol(docState.lines[row])
            it.copy(
                cursorRow = row,
                cursorCol = caretCol,
                anchorRow = null,
                anchorCol = null,
            )
        }
    }

    /**
     * Awaits the active document's first loaded emission and tries
     * to resolve [titlePath] inside it. Used by [navigateToLink] so
     * the lookup runs on the freshly-acquired document's content even
     * when the click happens before the initial disk read finishes.
     *
     * Returns `null` when the active document is gone or the path
     * doesn't match any bullet in the loaded content.
     */
    private suspend fun awaitLineIdForTitlePath(titlePath: List<String>): LineId? {
        val doc = document ?: return null
        val loadedState = doc.stateFlow.first { it.isLoaded }
        return findLineIdByTitlePathIn(loadedState.lines, loadedState.lineIds, titlePath)
    }

    /**
     * Walks [titlePath] through the active document's bullets and
     * returns the [LineId] of the matching row, or `null` when no
     * walk succeeds. Each segment is matched against direct-child
     * bullets (deeper indent) of the previously matched row.
     */
    fun findLineIdByTitlePath(titlePath: List<String>): LineId? {
        val docState = _stateFlow.value.documentState ?: return null
        return findLineIdByTitlePathIn(docState.lines, docState.lineIds, titlePath)
    }

    private fun findLineIdByTitlePathIn(
        lines: List<String>,
        lineIds: List<LineId>,
        titlePath: List<String>,
    ): LineId? {
        if (titlePath.isEmpty()) return null
        val baseline = topLevelBulletIndent(lines)
        if (baseline < 0) return null
        var startRow = 0
        var endRowExclusive = lines.size
        var parentIndent = baseline - 1
        var matchedRow = -1
        for (segIdx in titlePath.indices) {
            val target = titlePath[segIdx].lowercase()
            var found = false
            var i = startRow
            var childIndent = -1
            while (i < endRowExclusive) {
                val line = lines[i]
                val ind = DocumentLayout.bulletAsteriskColumn(line)
                if (ind < 0) { i++; continue }
                if (ind <= parentIndent) break
                if (childIndent < 0) childIndent = ind
                if (ind == childIndent &&
                    SubtreeCodec.titleOf(line).lowercase() == target
                ) {
                    matchedRow = i
                    found = true
                    val end = DocumentLayout.subtreeEnd(lines, i, ind)
                    startRow = i + 1
                    endRowExclusive = end + 1
                    parentIndent = ind
                    break
                }
                i++
            }
            if (!found) return null
        }
        return if (matchedRow >= 0 && matchedRow in lineIds.indices) lineIds[matchedRow]
               else null
    }

    // ------------------------------------------------------------------ helpers

    private inline fun mutate(transform: (State) -> State) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        _stateFlow.value = reconcile(transform(current))
    }

    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        val patched = transform(current).copy(
            documentState = document?.stateFlow?.value ?: current.documentState
        )
        _stateFlow.value = reconcile(patched)
    }

    private fun reconcile(state: State): State {
        val docState = state.documentState
        if (docState?.isLoaded != true) return state
        val lines = state.lines
        val lastRow = lines.lastIndex
        val baseRow = state.cursorRow.coerceIn(0, lastRow)
        val baseLineMin = DocumentLayout.caretStartCol(lines[baseRow])
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
                val zLineMin = DocumentLayout.caretStartCol(lines[zRow])
                val zCol = clamped.cursorCol.coerceIn(zLineMin, lines[zRow].length)
                val zAr = clamped.anchorRow?.coerceIn(zoom.startRow, zoom.endRowInclusive)
                val zAc = if (zAr != null) clamped.anchorCol?.coerceIn(0, lines[zAr].length) else null
                clamped = clamped.copy(cursorRow = zRow, cursorCol = zCol, anchorRow = zAr, anchorCol = zAc)
            }
        }
        return clampToVisible(clamped)
    }

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
        var target = state.cursorRow
        while (target > 0 && target !in visibleSet) target--
        if (target !in visibleSet) target = visible.first()
        val targetLine = docState.lines[target]
        val safeCol = state.cursorCol.coerceIn(
            DocumentLayout.caretStartCol(targetLine), targetLine.length
        )
        return state.copy(
            cursorRow = target,
            cursorCol = safeCol,
            anchorRow = null,
            anchorCol = null,
        )
    }

    // ------------------------------------------------------------------ undo / redo

    private data class Snapshot(
        val lines: List<String>,
        val lineIds: List<LineId>,
        val expandedRefIds: Set<LineId>,
        val cursorRow: Int,
        val cursorCol: Int,
        val anchorRow: Int?,
        val anchorCol: Int?,
    )

    private enum class FrameKind { TYPING, BACKSPACE, OTHER }

    private data class UndoFrame(
        val before: Snapshot,
        val after: Snapshot,
        val kind: FrameKind,
        val timestampMs: Long,
    )

    private val undoStack: ArrayDeque<UndoFrame> = ArrayDeque()
    private val redoStack: ArrayDeque<UndoFrame> = ArrayDeque()

    private val timeOrigin = TimeSource.Monotonic.markNow()

    private var recordingDepth: Int = 0

    private fun snapshotNow(): Snapshot {
        val view = _stateFlow.value
        val doc = view.documentState ?: document?.stateFlow?.value ?: Document.State()
        return Snapshot(
            lines = doc.lines,
            lineIds = doc.lineIds,
            expandedRefIds = doc.expandedRefIds,
            cursorRow = view.cursorRow,
            cursorCol = view.cursorCol,
            anchorRow = view.anchorRow,
            anchorCol = view.anchorCol,
        )
    }

    private fun nowMs(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    private inline fun recordEdit(kind: FrameKind, block: () -> Unit) {
        if (recordingDepth > 0) {
            block()
            return
        }
        val before = snapshotNow()
        recordingDepth++
        try {
            block()
        } finally {
            recordingDepth--
        }
        val after = snapshotNow()
        if (before == after) return
        pushUndoFrame(UndoFrame(before, after, kind, nowMs()))
        redoStack.clear()
    }

    private fun pushUndoFrame(frame: UndoFrame) {
        val top = undoStack.lastOrNull()
        if (top != null && shouldCoalesce(top, frame)) {
            undoStack.removeLast()
            undoStack.addLast(top.copy(after = frame.after, timestampMs = frame.timestampMs))
        } else {
            undoStack.addLast(frame)
        }
        while (undoStack.size > MAX_UNDO_FRAMES) undoStack.removeFirst()
    }

    private fun shouldCoalesce(prev: UndoFrame, next: UndoFrame): Boolean {
        if (prev.kind != next.kind) return false
        if (prev.kind != FrameKind.TYPING && prev.kind != FrameKind.BACKSPACE) return false
        if (prev.before.anchorRow != null || next.before.anchorRow != null) return false
        if (prev.after.anchorRow != null || next.after.anchorRow != null) return false
        if (next.timestampMs - prev.timestampMs > COALESCE_WINDOW_MS) return false
        if (prev.after.cursorRow != next.before.cursorRow) return false
        if (prev.after.cursorCol != next.before.cursorCol) return false
        if (prev.kind == FrameKind.TYPING) {
            val r = next.before.cursorRow
            if (r !in next.before.lines.indices || r !in next.after.lines.indices) return false
            val beforeLine = next.before.lines[r]
            val afterLine = next.after.lines[r]
            if (afterLine.length != beforeLine.length + 1) return false
            val col = next.before.cursorCol
            if (col !in afterLine.indices) return false
            val inserted = afterLine[col]
            if (inserted.isWhitespace()) return false
        }
        return true
    }

    fun undo() {
        if (!_stateFlow.value.isLoaded) return
        val frame = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(frame)
        while (redoStack.size > MAX_UNDO_FRAMES) redoStack.removeFirst()
        restoreSnapshot(frame.before)
    }

    fun redo() {
        if (!_stateFlow.value.isLoaded) return
        val frame = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(frame)
        while (undoStack.size > MAX_UNDO_FRAMES) undoStack.removeFirst()
        restoreSnapshot(frame.after)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private fun restoreSnapshot(snap: Snapshot) {
        val doc = document ?: return
        doc.replaceContent(snap.lines, snap.lineIds, snap.expandedRefIds)
        patch {
            it.copy(
                cursorRow = snap.cursorRow,
                cursorCol = snap.cursorCol,
                anchorRow = snap.anchorRow,
                anchorCol = snap.anchorCol,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    companion object {
        /** Standard indent step across the app (two spaces). */
        const val TAB_SIZE: Int = 2

        /** Maximum number of frames retained on either undo stack. */
        private const val MAX_UNDO_FRAMES: Int = 500

        /** Maximum gap between two coalescing-eligible frames, in ms. */
        private const val COALESCE_WINDOW_MS: Long = 1_000L

        /** Cap on entries in [State.fileHistory] / [State.fileForward]. */
        const val NAV_HISTORY_CAP: Int = 50

        /** Normalizes an anchor + cursor pair into a [Selection]. */
        fun selectionOf(state: State): Selection? = se.soderbjorn.notegrow.main.selectionOf(state)
    }
}
