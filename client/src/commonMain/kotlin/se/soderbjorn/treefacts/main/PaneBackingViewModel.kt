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
 * ### Markdown mode (TRF-7)
 * A pane viewing a file that is not a `.treefacts` outline — a `.md`
 * note — is in Markdown mode ([State.isMarkdownMode]): the same editor,
 * fully editable, but with no bullet behaviour. Nothing folds, nothing
 * zooms, rows are not dragged, and the document saves the text exactly
 * as written ([Document.bulletsOnly] is `false`, so no promotion).
 *
 * ### Links (TRF-8)
 * Links are `tf:` paths to folders and files ([TfLink]). Clicking one
 * ([navigateToLink]) zooms to a folder — into its bullet when it is a
 * node's folder — or opens a file as the folder contents list would. The
 * link search runs over the whole vault ([VaultIndex.search]); a link
 * whose target is gone is reported by [isLinkBroken] for the view to
 * strike through. Starred entries are the same `tf:` paths
 * ([currentLocationPath], [toggleStarred]).
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
import kotlin.time.TimeSource
import se.soderbjorn.treefacts.data.ImagePaths
import se.soderbjorn.treefacts.data.InlineMarkdownTokenizer
import se.soderbjorn.treefacts.data.InlineStyle
import se.soderbjorn.treefacts.data.LineStyle
import se.soderbjorn.treefacts.data.FolderName
import se.soderbjorn.treefacts.data.LinkTarget
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.data.SubtreeCodec
import se.soderbjorn.treefacts.data.TfLink
import se.soderbjorn.treefacts.data.VaultEntry
import se.soderbjorn.treefacts.data.VaultEntryKind
import se.soderbjorn.treefacts.data.VaultIndex

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

    /** App-scoped link index, exposed for the link modals' search ([VaultIndex.search]). */
    val vaultIndex: VaultIndex get() = registry.vaultIndex

    /**
     * Lists every image in the vault (vault-relative paths). Used by the
     * `Insert Image` palette flavour. Suspending because it crosses the
     * FileSystem boundary.
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
     * @property vaultListings Mirror of the registry's shared folder
     *   listings cache ([DocumentRegistry.vaultListingsFlow]), raw and
     *   unfiltered. Mirrored here so view code can read pane state alone
     *   without a separate registry handle; read it through
     *   [folderContentsOf] / [folderContentsOfBullet], which apply
     *   [FolderContents.visible].
     * @property linkStatus Mirror of [DocumentRegistry.linkStatusFlow]:
     *   link target path → whether it exists. Mirrored so a target found
     *   missing (or back) repaints the pane; read through [isLinkBroken].
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
     * @property fileHistory Browser-style back stack of the places the
     *   user has switched away from — nodes, `.md` notes and images alike
     *   (TRF-7). Each [FileHistoryEntry] carries the zoom the pane had in
     *   that file, so Back from a note opened in a zoomed node returns to
     *   that node. A single Back chord walks zoom-back first, then
     *   file-back.
     * @property fileForward Mirror of [fileHistory] for forward
     *   navigation.
     * @property collapsedIds Within-file fold state.
     * @property expandedRefIdsLocal Per-pane intent: which promoted-ref
     *   ids THIS pane wants expanded. Drives chevron direction and
     *   visibility for refs without coupling to other panes' choices.
     *   Distinct from `Document.State.unloadedRefIds`, the shared
     *   "children on disk only" set; the document
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
     * @property pendingInlineStyles Inline styles armed via Cmd-B / etc
     *   while the caret was collapsed.
     */
    data class State(
        val activeFileRel: String = "",
        val documentState: Document.State? = null,
        val vaultListings: Map<String, List<VaultEntry>> = emptyMap(),
        val linkStatus: Map<String, Boolean> = emptyMap(),
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null,
        val zoomedLineId: LineId? = null,
        val zoomHistory: List<LineId?> = emptyList(),
        val zoomForward: List<LineId?> = emptyList(),
        val fileHistory: List<FileHistoryEntry> = emptyList(),
        val fileForward: List<FileHistoryEntry> = emptyList(),
        val collapsedIds: Set<LineId> = emptySet(),
        val expandedRefIdsLocal: Set<LineId> = emptySet(),
        val pendingLeafZoomChild: LineId? = null,
        internal val seenLineIds: Set<LineId> = emptySet(),
        val pendingInlineStyles: Set<InlineStyle> = emptySet(),
    ) {
        /** `true` once the document has loaded from disk at least once. */
        val isLoaded: Boolean get() = documentState?.isLoaded == true

        /**
         * `true` when the pane's active location is an image rather than a
         * markdown document. The pane VM treats image paths as a "special
         * case of document view": the navigation primitives (fileHistory,
         * fileBack, fileForward, parent/root chrome) work as usual; the
         * editor surface in the view layer swaps to a read-only image
         * viewer and `documentState` stays `null` for the duration.
         */
        val isImageView: Boolean get() = NoteRepository.isImagePath(activeFileRel)

        /**
         * `true` when the pane shows a file that is not a node outline —
         * a `.md` note (TRF-7). The editor stays fully editable but drops
         * every bullet behaviour: no folding, no zoom, no row dragging, no
         * promotion to folders. `false` for outlines and for the image
         * view.
         */
        val isMarkdownMode: Boolean
            get() = activeFileRel.isNotEmpty() && !isImageView && !NoteRepository.isOutlineFile(activeFileRel)

        /**
         * `true` while the document is mid-save on a tick that
         * turns a bullet into a folder or a folder back into a bullet
         * — see [Document.State.isRestructuring].
         */
        val isRestructuring: Boolean get() = documentState?.isRestructuring == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")
    }

    /**
     * One entry of [State.fileHistory] / [State.fileForward].
     *
     * @property fileRel The file the pane showed: a node outline, a `.md`
     *   note or an image.
     * @property zoomTitlePath Titles from the file's top-level bullet down
     *   to the bullet the pane was zoomed into, or empty when it was not
     *   zoomed. Stored as titles rather than a [LineId] because line ids
     *   do not survive the document being closed and reloaded; restored
     *   by [restoreZoom] on the way back.
     */
    data class FileHistoryEntry(val fileRel: String, val zoomTitlePath: List<String> = emptyList())

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
     *   [se.soderbjorn.treefacts.data.InlineMarkdownTokenizer].
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
        scope.launch {
            registry.linkStatusFlow.collect { status ->
                _stateFlow.value = _stateFlow.value.copy(linkStatus = status)
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
        val current = _stateFlow.value
        if (current.activeFileRel == fileRel &&
            (document != null || NoteRepository.isImagePath(fileRel))
        ) return
        // Strip any throwaway placeholder before swapping the active
        // document — otherwise an empty placeholder bullet inserted by a
        // leaf-zoom on the outgoing file would persist into the next
        // autosave tick on that file.
        cleanupEmptyPlaceholderIfAny()
        val outgoing = document
        val outgoingFile = current.activeFileRel
        val outgoingExpansions = current.expandedRefIdsLocal
        val targetIsImage = NoteRepository.isImagePath(fileRel)
        // Acquire the incoming document up front so the registry refcount
        // is bumped before we release the outgoing one — keeps a shared
        // [Document] alive across a same-file navigation without an
        // extra disk round-trip. For image targets we skip the registry
        // entirely; the pane simply has no document for the duration.
        val incoming = if (targetIsImage) null else registry.acquire(fileRel)
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
        _stateFlow.value = current.copy(
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
        if (incoming != null) startDocumentCollector(incoming)
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
        // Markdown mode never folds.
        if (state.isMarkdownMode) return state
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
     *
     * A no-op in Markdown mode, which never folds.
     */
    fun toggleCollapse(lineId: LineId) {
        val current = _stateFlow.value
        if (!current.isLoaded || current.isMarkdownMode) return
        val doc = document ?: return
        val isRef = doc.isPromotedRef(lineId)
        // A folder-backed bullet whose children are already in `lines`
        // without this pane holding an expansion — it was just promoted by
        // a save, or another pane expanded it — folds like an ordinary
        // parent: nothing to load, nothing to release.
        val materializedWithoutHold = isRef && lineId !in current.expandedRefIdsLocal &&
            lineId !in doc.stateFlow.value.unloadedRefIds && lineId !in current.collapsedIds
        if (isRef && !materializedWithoutHold) {
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
        // A block row nests by its marker column, like a bullet by its `*`.
        val rowIndent = BlockLayout.markerColumn(docState.lines[row]).takeIf { it >= 0 }
            ?: DocumentLayout.bulletAsteriskColumn(docState.lines[row])
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

    /** See [TextEditingViewModel.backspace].
     *
     *  Adds one image-specific behavior: when the caret sits immediately
     *  after a markdown image `![…](…)` (no selection, same row), a
     *  single backspace deletes the entire image syntax atomically.
     *  Otherwise it falls through to the standard character-by-character
     *  deletion path. Without this special case the user would have to
     *  press backspace once for every char of `![alt](path)` because
     *  the syntax has no visible characters — the first press would
     *  silently delete `)` and leave the image visually intact but
     *  syntactically broken. */
    fun backspace() {
        if (tryBackspaceImage()) return
        recordEdit(FrameKind.BACKSPACE) {
            commitPlaceholderIfAny()
            textEditing.backspace()
        }
    }

    /** Returns `true` and performs an atomic image-syntax deletion when
     *  the caret is positioned at the source-end of an inline image on
     *  the current row with no active selection. */
    private fun tryBackspaceImage(): Boolean {
        val state = _stateFlow.value
        if (state.anchorRow != null) return false
        val docState = state.documentState ?: return false
        if (!docState.isLoaded) return false
        val row = state.cursorRow
        if (row !in docState.lines.indices) return false
        val col = state.cursorCol
        if (col <= 0) return false
        val line = docState.lines[row]
        // Walk through every `![` in the line — for each, check whether
        // its parsed source-end equals the cursor column. Cheap because
        // most lines have at most one image; the tokenizer would be
        // overkill compared to a direct `imageEndAt` probe.
        var probe = line.indexOf("![")
        while (probe >= 0) {
            val end = InlineMarkdownTokenizer.imageEndAt(line, probe)
            if (end != null && end == col) {
                recordEdit(FrameKind.OTHER) {
                    commitPlaceholderIfAny()
                    val d = currentDocument()
                    d.delete(row, probe, row, end)
                    patch { it.copy(cursorCol = probe, anchorRow = null, anchorCol = null) }
                }
                return true
            }
            probe = line.indexOf("![", startIndex = probe + 2)
        }
        return false
    }

    /**
     * See [TextEditingViewModel.indentLine]. Ancestor reveal (un-collapsing
     * the bullet the row lands under) happens inside the slice, atomically
     * with the cursor move — see `TextEditingViewModel.ancestorIdsAt`.
     */
    fun indentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.indentLine(amount)
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

    // ------------------------------------------------------------------ blocks

    /** See [TextEditingViewModel.isBlockLine]. */
    fun isBlockLine(): Boolean = textEditing.isBlockLine()

    /** See [TextEditingViewModel.insertBlock]. Undoable. */
    fun insertBlock() {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertBlock()
        }
    }

    /**
     * Deletes the block whose row carries [lineId] — any row of the block
     * will do. Called by the view's hover delete control, which knows the
     * block by the stable id of its first row. Undoable. See
     * [TextEditingViewModel.deleteBlockAt].
     */
    fun deleteBlock(lineId: LineId) {
        val row = _stateFlow.value.documentState?.lineIds?.indexOf(lineId) ?: return
        if (row < 0) return
        recordEdit(FrameKind.OTHER) { textEditing.deleteBlockAt(row) }
    }

    /**
     * Deletes the block the caret is in; a no-op elsewhere. The "Delete
     * block" palette command. Undoable. See [TextEditingViewModel.deleteBlockAt].
     */
    fun deleteBlockAtCursor() {
        recordEdit(FrameKind.OTHER) { textEditing.deleteBlockAt(_stateFlow.value.cursorRow) }
    }

    /** See [TextEditingViewModel.exitBlock]. Undoable. */
    fun exitBlock() {
        recordEdit(FrameKind.OTHER) { textEditing.exitBlock() }
    }

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
     *
     * A no-op in Markdown mode, where rows are text, not movable bullets.
     */
    fun moveLineRange(
        fromStartRow: Int,
        fromEndRow: Int,
        insertBeforeRow: Int,
        targetIndent: Int,
    ) = recordEdit(FrameKind.OTHER) {
        if (_stateFlow.value.isMarkdownMode) return@recordEdit
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

        val sourceTopIndent = leadingSpaceCount(lines0[src0])
        val shift = targetIndent - sourceTopIndent
        val movedText = (src0..src1).map { reindentLine(lines0[it], shift) }

        // Move the rows with their ids, so a folder-backed bullet keeps its
        // folder (the next save moves it on disk) instead of reading as a
        // delete plus a new bullet.
        val landedFirst = doc.moveRows(src0, src1, drop, movedText)

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

    /** See [ZoomNavigation.zoomInto]. A no-op in Markdown mode, which never zooms. */
    fun zoomInto(row: Int) {
        if (_stateFlow.value.isMarkdownMode) return
        zoomNavigation.zoomInto(row)
    }

    /** See [ZoomNavigation.zoomOut]. */
    fun zoomOut() = zoomNavigation.zoomOut()

    /** See [ZoomNavigation.zoomTo]. A no-op in Markdown mode, which never zooms. */
    fun zoomTo(lineId: LineId?) {
        if (_stateFlow.value.isMarkdownMode) return
        zoomNavigation.zoomTo(lineId)
    }

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
        val current = currentHistoryEntry()
        val priorHistory = _stateFlow.value.fileHistory.dropLast(1)
        val priorForward = _stateFlow.value.fileForward
        switchActiveFile(previous.fileRel)
        patch {
            it.copy(
                fileHistory = priorHistory,
                fileForward = (priorForward + current).takeLast(NAV_HISTORY_CAP),
            )
        }
        restoreZoom(previous.zoomTitlePath)
    }

    private suspend fun fileForward() {
        val next = _stateFlow.value.fileForward.lastOrNull() ?: return
        val current = currentHistoryEntry()
        val priorHistory = _stateFlow.value.fileHistory
        val priorForward = _stateFlow.value.fileForward.dropLast(1)
        switchActiveFile(next.fileRel)
        patch {
            it.copy(
                fileHistory = (priorHistory + current).takeLast(NAV_HISTORY_CAP),
                fileForward = priorForward,
            )
        }
        restoreZoom(next.zoomTitlePath)
    }

    /**
     * The [FileHistoryEntry] for where this pane is right now: the active
     * file plus the title path of the zoom target, if any. Pushed onto
     * the history by every cross-file navigation.
     */
    private fun currentHistoryEntry(): FileHistoryEntry {
        val s = _stateFlow.value
        val docState = s.documentState
        val zoomed = s.zoomedLineId
        val path = if (zoomed != null && docState != null && docState.isLoaded) {
            val row = docState.lineIds.indexOf(zoomed)
            if (row >= 0) titlePathOfRow(docState.lines, row) else emptyList()
        } else {
            emptyList()
        }
        return FileHistoryEntry(s.activeFileRel, path)
    }

    /**
     * After a history step landed on a file, zooms back into the bullet
     * at [titlePath] (see [FileHistoryEntry.zoomTitlePath]). Folded
     * folder-backed bullets on the way are expanded for this pane, as a
     * click on their chevron would. The zoom goes through
     * [ZoomNavigation.zoomInto], so Back from there returns to the
     * file's root view. Stops quietly at the first title it cannot find
     * (the bullet was renamed or deleted meanwhile).
     */
    private suspend fun restoreZoom(titlePath: List<String>) {
        if (titlePath.isEmpty()) return
        val doc = document ?: return
        doc.stateFlow.first { it.isLoaded }
        var targetId: LineId? = null
        for (depth in 1..titlePath.size) {
            val id = findLineIdByTitlePathIn(
                doc.stateFlow.value.lines, doc.stateFlow.value.lineIds, titlePath.take(depth)
            ) ?: return
            targetId = id
            val isLast = depth == titlePath.size
            if (!isLast && doc.isPromotedRef(id) && id !in _stateFlow.value.expandedRefIdsLocal) {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal + id,
                        collapsedIds = it.collapsedIds - id,
                    )
                }
                doc.acquireExpansion(id)
            }
        }
        val id = targetId ?: return
        if (document !== doc) return
        val row = doc.stateFlow.value.lineIds.indexOf(id)
        if (row >= 0) zoomNavigation.zoomInto(row)
    }

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

    // ------------------------------------------------------ folder contents

    /**
     * The vault-relative folder of the node this pane is showing — the
     * folder whose contents list is drawn under the bullets and where
     * "New Markdown file" creates its note — or `null` when there is none:
     *
     * - Zoomed into a folder-backed bullet: that bullet's folder.
     * - Zoomed into a leaf bullet (no folder yet): `null`.
     * - Not zoomed, viewing a node outline: the outline's folder (`""`
     *   for the vault root's `.treefacts`).
     * - Viewing an image: the folder the image is in, so the list still
     *   leads to its siblings.
     * - Viewing a `.md` note: `null`.
     *
     * A folder in the trash (its bullet was just deleted) also gives
     * `null`.
     *
     * Called by the web `FolderContentsList`, [newMarkdownFile] and
     * [refreshCurrentFolderListing].
     *
     * @param state The pane state to resolve against; defaults to the
     *   latest.
     */
    fun currentNodeFolder(state: State = _stateFlow.value): String? {
        val file = state.activeFileRel
        if (NoteRepository.isImagePath(file)) return file.substringBeforeLast('/', missingDelimiterValue = "")
        val zoomed = state.zoomedLineId
        val folder = when {
            zoomed != null -> document?.folderOf(zoomed)
            NoteRepository.isOutlineFile(file) -> NoteRepository.folderOfOutline(file)
            else -> null
        } ?: return null
        return if (NoteRepository.isInTrash(folder)) null else folder
    }

    /**
     * The visible contents of the folder [dirRel] ([FolderContents.visible]
     * over the cached listing), or `null` while the listing has not been
     * read yet — in which case the read is started, so the next emission
     * carries it.
     *
     * Called by the web `FolderContentsList` for the current node's
     * folder.
     */
    fun folderContentsOf(state: State, dirRel: String): List<VaultEntry>? {
        val raw = state.vaultListings[dirRel]
        if (raw == null) {
            ensureVaultListing(dirRel)
            return null
        }
        return FolderContents.visible(raw)
    }

    /**
     * The visible contents of the folder backing the bullet [lineId], for
     * its count badge; `null` when the bullet is not folder-backed or its
     * folder's listing is still being read (the read is then started).
     *
     * Called by the web paint loop for every expanded folder-backed
     * bullet; the badge wording is [FolderContents.badgeLabel].
     */
    fun folderContentsOfBullet(state: State, lineId: LineId): List<VaultEntry>? {
        val folder = document?.folderOf(lineId) ?: return null
        if (NoteRepository.isInTrash(folder)) return null
        return folderContentsOf(state, folder)
    }

    /**
     * Opens the folder [dirRel] as a node: navigates this pane to its
     * outline file, `<dirRel>/.treefacts`. Works for foreign folders and
     * for TreeFacts folders no bullet references — a folder without an
     * outline file opens as an empty node, and its outline file is only
     * written once the user types a bullet. Pushes file history like any
     * other file navigation.
     *
     * Called when the user clicks a folder row in the contents list.
     */
    fun openFolderAsNode(dirRel: String) {
        navigateToVaultFile(NoteRepository.outlineFileOf(dirRel))
    }

    /**
     * "New Markdown file": creates `Untitled.md` (then `Untitled 2.md`,
     * …) in [currentNodeFolder] and opens it in this pane. Saves the
     * document first, so a bullet that just got its first child (and is
     * zoomed into) has its folder on disk before the note goes in.
     * No-op when the pane has no current folder (a zoom into a leaf
     * bullet, a `.md` note).
     *
     * Called from the command palette.
     */
    fun newMarkdownFile() {
        scope.launch {
            document?.flush()
            val folder = currentNodeFolder() ?: return@launch
            val rel = registry.createMarkdownFile(folder)
            navigateToVaultFile(rel)
        }
    }

    /**
     * Re-reads the listing of [currentNodeFolder], so what the contents
     * list shows reflects the disk even when nothing was saved since the
     * last read (a file dropped in from Finder). Fire-and-forget.
     *
     * Called by the web view whenever the pane lands on a new node (file
     * or zoom navigation).
     */
    fun refreshCurrentFolderListing() {
        val folder = currentNodeFolder() ?: return
        scope.launch { registry.refreshVaultListing(folder) }
    }

    /**
     * Fire-and-forget request that the registry populate
     * [DocumentRegistry.vaultListingsFlow] with the entries under
     * [dirRel] if they are not already cached. Used by
     * [folderContentsOf] the first time a folder is looked at. The
     * registry's own [DocumentRegistry.ensureVaultListing] is a no-op
     * when the entry is already present, so calling this on every
     * repaint is safe.
     */
    fun ensureVaultListing(dirRel: String) {
        if (_stateFlow.value.vaultListings[dirRel] != null) return
        scope.launch { registry.ensureVaultListing(dirRel) }
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
     *
     * The leading guard accepts a pane that has either a loaded document
     * or is currently in image-view (in which case [State.documentState]
     * is intentionally `null`). Without the image-view branch the
     * parent / home toolbar buttons would silently no-op while an image
     * is on screen, even though `fileHistory` and the AppShell handlers
     * are perfectly happy to navigate away from one.
     */
    fun navigateToVaultFile(pathRel: String) {
        val current = _stateFlow.value
        if (!current.isLoaded && !current.isImageView) return
        if (current.activeFileRel == pathRel) return
        val here = currentHistoryEntry()
        scope.launch {
            switchActiveFile(pathRel)
            patch {
                it.copy(
                    fileHistory = (it.fileHistory + here).takeLast(NAV_HISTORY_CAP),
                    fileForward = emptyList(),
                )
            }
        }
    }

    // ----------------------------------------------------------------- links

    /**
     * Inserts a link to [target] at the cursor: `[label](tf:/…)`, labelled
     * with [label] or, when that is blank, the target's title. See
     * [insertMarkdownLink]. Called by the Insert Link / "Link to node…"
     * modal once the user picks a target.
     */
    fun insertLinkTo(target: LinkTarget, label: String = "") {
        insertMarkdownLink(label.ifBlank { target.title }, TfLink.format(target.pathRel))
    }

    /**
     * Gets the link search ready to reflect the latest edits: saves every
     * open document (so a bullet that just got its first child already has
     * a folder, and a renamed one its new name) and drops the cached
     * target list. Called when a link modal opens.
     */
    suspend fun prepareLinkSearch() {
        registry.flushAll()
        registry.vaultIndex.invalidateTargets()
    }

    /**
     * `true` when [url] is a `tf:` link whose target is known to be
     * missing — moved or trashed outside the app, or a node deleted here.
     * While the target's status is unknown, starts a check (see
     * [DocumentRegistry.requestLinkStatus]) and answers `false`; the
     * result arrives as a new [State.linkStatus], which repaints.
     *
     * Called by the web paint loop for every link it draws. Links with
     * any other URL are never broken.
     */
    fun isLinkBroken(state: State, url: String): Boolean {
        val path = TfLink.parse(url) ?: return TfLink.isTfLink(url)
        state.linkStatus[path]?.let { return !it }
        return registry.requestLinkStatus(path) == false
    }

    /**
     * Where this pane is, as the `tf:` path a Starred entry stores (TRF-8):
     *
     * - An image or a `.md` note: the file itself.
     * - Zoomed into a folder-backed bullet: its folder.
     * - Zoomed into a leaf bullet: the folder the leaf is stored in (leaf
     *   bullets cannot be linked).
     * - Otherwise: the outline's folder (`""` for the vault root).
     *
     * Saves the document first, so a bullet that just got children has its
     * folder. `null` while nothing is loaded.
     */
    suspend fun currentLocationPath(): String? {
        val s0 = _stateFlow.value
        if (s0.isImageView) return s0.activeFileRel
        val doc = document ?: return null
        if (!s0.isLoaded) return null
        doc.flush()
        val s = _stateFlow.value
        if (s.isMarkdownMode) return s.activeFileRel
        val zoomed = s.zoomedLineId ?: return doc.folderRel
        doc.folderOf(zoomed)?.takeIf { !NoteRepository.isInTrash(it) }?.let { return it }
        val row = s.documentState?.lineIds?.indexOf(zoomed) ?: -1
        return if (row >= 0) doc.storageFolderOf(row) else doc.folderRel
    }

    /**
     * Label for a Starred entry of [pathRel]: the zoomed bullet's plain
     * title when the pane is zoomed into the bullet backed by [pathRel],
     * the path's display name otherwise.
     */
    private fun starLabelFor(pathRel: String): String {
        val s = _stateFlow.value
        val zoomed = s.zoomedLineId
        val docState = s.documentState
        if (zoomed != null && docState != null && document?.folderOf(zoomed) == pathRel) {
            val row = docState.lineIds.indexOf(zoomed)
            if (row >= 0) FolderName.plainTextOf(SubtreeCodec.titleOf(docState.lines[row])).takeIf { it.isNotBlank() }?.let { return it }
        }
        if (pathRel.isEmpty()) return NoteRepository.ROOT_DISPLAY_NAME
        val name = pathRel.substringAfterLast('/')
        return if (NoteRepository.isImagePath(name) || name.endsWith(NoteRepository.NOTE_EXTENSION)) {
            name.removeSuffix(NoteRepository.NOTE_EXTENSION)
        } else {
            FolderName.decode(name)
        }
    }

    /**
     * Stars or un-stars this pane's [currentLocationPath]: adds a
     * `* [label](tf:/…)` entry to `Starred.md` when [starred] is `false`,
     * removes every entry for the location when it is `true`. Goes through
     * the registry so the link index sees the change.
     *
     * Called by the web Starred modal (the Add / Remove button and ⌘D).
     *
     * @param starred Whether the location is currently starred, as the
     *   modal shows it.
     */
    suspend fun toggleStarred(starred: Boolean) {
        val path = currentLocationPath() ?: return
        if (starred) registry.removeStarred(path)
        else registry.addStarred(starLabelFor(path), path)
    }

    /**
     * Titles from the top-level bullet of [lines] down to the bullet at
     * [row], inclusive; empty when [row] is not a bullet. The inverse of
     * [findLineIdByTitlePathIn].
     */
    private fun titlePathOfRow(lines: List<String>, row: Int): List<String> {
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
     * Inserts an inline image reference `![alt](src)` at the cursor.
     * Mirrors [insertMarkdownLink] — same selection-replacing
     * literal-insert path so an armed `pendingInlineStyles` doesn't
     * wrap the image syntax in marker pairs.
     *
     * @param src The image destination exactly as it should be written:
     *   a bare file name for an image in the row's own folder, a
     *   vault-rooted `/…` path otherwise ([ImagePaths]). Wrapped in `<…>`
     *   automatically when it contains spaces / parens / angle brackets
     *   (CommonMark rule).
     * @param alt Optional alt text. CommonMark specials are escaped so
     *   the alt round-trips cleanly.
     * @param widthPx Optional display width in CSS pixels. When set, the
     *   width is appended to the alt as `|<digits>` (Obsidian convention)
     *   so it survives the file → display → file round-trip. Standard
     *   CommonMark viewers treat the whole `alt|width` as alt text.
     */
    fun insertImageRef(src: String, alt: String = "", widthPx: Int? = null) {
        val sizedAlt = if (widthPx != null && widthPx > 0) "$alt|$widthPx" else alt
        val markdown = "![" + SubtreeCodec.escapeLabel(sizedAlt) + "](" +
            SubtreeCodec.formatLinkUrlForLabel(src) + ")"
        insertLiteralText(markdown)
    }

    /**
     * Inserts a reference to the existing vault image [vaultRelPath], as
     * picked in the Insert Image palette. An image in the cursor row's
     * own folder is referenced by bare file name (so it follows the row);
     * any other is referenced vault-rooted (`/Images/logo.png`), which
     * resolves the same wherever the row goes.
     */
    fun insertVaultImage(vaultRelPath: String) {
        val folder = imageFolderOf(_stateFlow.value.cursorRow)
        val parent = vaultRelPath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = vaultRelPath.substringAfterLast('/')
        insertImageRef(if (folder != null && parent == folder) name else ImagePaths.vaultRooted(vaultRelPath))
        if (folder != null && parent == folder) document?.noteImageHomeAt(_stateFlow.value.cursorRow)
    }

    /**
     * Handles a pasted (or dropped) image. Writes [bytes] under
     * [suggestedName] (with `-2`, `-3`, … on collision) into the folder
     * of the node being edited — the folder the cursor row is stored in
     * ([Document.storageFolderOf]), or the `.md` note's folder — and
     * inserts `![](<name>)` at the cursor. The image then shows in that
     * folder's contents list, and travels with the node's folder when it
     * is renamed or moved. No-op when the active document hasn't
     * finished loading — the cursor isn't trustworthy yet.
     *
     * Saves the document first, so a bullet that just got its first
     * child already has its folder and the image lands in it.
     *
     * Suspends across the disk writes. The caller is expected to launch
     * this on the pane's scope so paste latency doesn't block the UI
     * thread; the markdown insert that follows goes through the standard
     * undoable path, so an undo after paste removes the `![…]` (the file
     * on disk is kept).
     *
     * @param suggestedName Filename including extension. Generated at
     *   the platform layer (where `Date.now()` / equivalents live) so
     *   commonMain stays clock-agnostic.
     * @param bytes Raw image data from the clipboard.
     */
    suspend fun onImagePasted(suggestedName: String, bytes: ByteArray) {
        if (!_stateFlow.value.isLoaded) return
        val doc = document ?: return
        doc.flush()
        if (document !== doc) return
        val folder = doc.storageFolderOf(_stateFlow.value.cursorRow)
        val rel = registry.saveImageBytes(folder, suggestedName, bytes)
        if (document !== doc) return
        insertImageRef(rel.substringAfterLast('/'))
        doc.noteImageHomeAt(_stateFlow.value.cursorRow)
    }

    /**
     * Vault-relative folder the images on [row] resolve against — see
     * [Document.storageFolderOf] — or `null` when no document is loaded.
     */
    fun imageFolderOf(row: Int): String? = document?.storageFolderOf(row)

    /**
     * The vault-relative file the image [src] on [row] points at, or
     * `null` for an external URL ([ImagePaths.resolve]). Called by the web
     * paint loop and the zoom headline for every inline image they draw.
     */
    fun resolveImageSrc(row: Int, src: String): String? =
        ImagePaths.resolve(imageFolderOf(row) ?: "", src)

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
     * Follows the `tf:` link [url] (TRF-8):
     *
     * - **A folder** zooms there ([zoomToFolder]).
     * - **A `.md` note or an image** opens in this pane, with file history,
     *   as a click in the folder contents list does.
     * - **Any other file** is handed to [openExternally] (on the web, the
     *   system's default app).
     * - **A missing target** does nothing; the view already draws the
     *   link as broken, and its text stays as it is.
     *
     * [onComplete] runs once the navigation has settled (or failed), so
     * the view can focus the editor. No-op for other URLs.
     *
     * Called when a link is clicked, when a Starred entry or a
     * Navigate-to hit is picked, and for the new pane a shift-click opens.
     */
    fun navigateToLink(url: String, onComplete: () -> Unit = {}, openExternally: (String) -> Unit = {}) {
        val path = TfLink.parse(url)
        if (path == null) {
            onComplete()
            return
        }
        scope.launch {
            try {
                when (registry.kindOf(path)) {
                    null -> {
                        println("[treefacts] link target not found: $url")
                        registry.requestLinkStatus(path)
                        registry.refreshLinkStatuses()
                    }
                    VaultEntryKind.FOLDER -> zoomToFolder(path)
                    VaultEntryKind.MARKDOWN, VaultEntryKind.IMAGE -> {
                        if (NoteRepository.isOutlineFile(path)) zoomToFolder(NoteRepository.folderOfOutline(path))
                        else openFileWithHistory(path)
                    }
                    VaultEntryKind.FILE -> {
                        if (NoteRepository.isOutlineFile(path)) zoomToFolder(NoteRepository.folderOfOutline(path))
                        else openExternally(path)
                    }
                }
            } finally {
                onComplete()
            }
        }
    }

    /** Switches this pane to [fileRel], pushing file history, and waits for it to load. */
    private suspend fun openFileWithHistory(fileRel: String) {
        if (_stateFlow.value.activeFileRel == fileRel) return
        val here = currentHistoryEntry()
        switchActiveFile(fileRel)
        patch {
            it.copy(
                fileHistory = (it.fileHistory + here).takeLast(NAV_HISTORY_CAP),
                fileForward = emptyList(),
            )
        }
        if (!NoteRepository.isImagePath(fileRel)) awaitFirstLoadedLineId()?.let { placeCursorOn(it) }
    }

    /**
     * Shows the folder [folderRel] as a zoomed node:
     *
     * 1. **Inside the open outline** — the folder is the outline's own
     *    folder or lies below it: expand the folder-backed bullets on the
     *    way (as a chevron click would, for this pane only) and zoom into
     *    the bullet backed by [folderRel], with zoom history; the outline's
     *    own folder clears the zoom.
     * 2. **Elsewhere, a node's folder** — open the parent node's outline
     *    and zoom into the bullet; Back returns to where the link was.
     * 3. **Elsewhere, any other folder** (foreign, or one no bullet names)
     *    — open it as a node of its own, like a folder row in the contents
     *    list.
     */
    private suspend fun zoomToFolder(folderRel: String) {
        if (zoomWithinCurrentOutline(folderRel)) return
        if (folderRel.isNotEmpty() && registry.isBulletFolder(folderRel)) {
            val parentOutline = NoteRepository.outlineFileOf(folderRel.substringBeforeLast('/', missingDelimiterValue = ""))
            openFileWithHistory(parentOutline)
            val doc = document ?: return
            doc.stateFlow.first { it.isLoaded }
            val id = doc.lineIdForFolder(folderRel)
            if (id != null && document === doc) {
                expandForPane(doc, id)
                // Entry point into the file: no zoom history, so Back
                // returns to where the link was clicked.
                patch { it.copy(zoomedLineId = id) }
                placeCursorOn(id)
                return
            }
        }
        openFileWithHistory(NoteRepository.outlineFileOf(folderRel))
    }

    /**
     * Case 1 of [zoomToFolder]. Returns `false` when the pane is not on
     * an outline, the folder is not at or under the outline's folder, or a
     * bullet on the way cannot be found (bullets unfolded before that stay
     * unfolded; the caller then navigates elsewhere anyway).
     */
    private suspend fun zoomWithinCurrentOutline(folderRel: String): Boolean {
        val doc = document ?: return false
        val s = _stateFlow.value
        if (!s.isLoaded || s.isMarkdownMode || s.isImageView) return false
        val base = doc.folderRel
        if (folderRel == base) {
            if (s.zoomedLineId != null) zoomTo(null)
            return true
        }
        val rest = when {
            base.isEmpty() -> folderRel
            folderRel.startsWith("$base/") -> folderRel.substring(base.length + 1)
            else -> return false
        }
        val segments = rest.split('/')
        var prefix = base
        var targetId: LineId? = null
        for (seg in segments) {
            prefix = if (prefix.isEmpty()) seg else "$prefix/$seg"
            val id = doc.lineIdForFolder(prefix) ?: return false
            targetId = id
            // Expand every bullet on the way, the target too, so the zoom
            // shows its children.
            expandForPane(doc, id)
        }
        val id = targetId ?: return false
        if (document !== doc) return false
        zoomTo(id)
        placeCursorOn(id)
        return true
    }

    /**
     * Unfolds the folder-backed bullet [id] for this pane, as a chevron
     * click would: records the pane's expansion intent, loads its
     * children if they are on disk only, and clears its fold.
     */
    private suspend fun expandForPane(doc: Document, id: LineId) {
        val needsExpansion = doc.isPromotedRef(id) && id !in _stateFlow.value.expandedRefIdsLocal
        patch {
            it.copy(
                expandedRefIdsLocal = if (needsExpansion) it.expandedRefIdsLocal + id else it.expandedRefIdsLocal,
                collapsedIds = it.collapsedIds - id,
            )
        }
        if (needsExpansion) doc.acquireExpansion(id)
    }

    /**
     * Awaits the active document's first loaded emission and returns
     * its row-0 [LineId]. Used when a link opens a file, so the caret has
     * a row to sit on.
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
     * Walks [titlePath] through the bullets of [lines] and returns the
     * [LineId] of the matching row, or `null` when no walk succeeds. Each
     * segment is matched against direct-child bullets (deeper indent) of
     * the previously matched row. Used by [restoreZoom].
     */
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
        val unloadedRefIds: Set<LineId>,
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
            unloadedRefIds = doc.unloadedRefIds,
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
        doc.replaceContent(snap.lines, snap.lineIds, snap.unloadedRefIds)
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
        fun selectionOf(state: State): Selection? = se.soderbjorn.treefacts.main.selectionOf(state)
    }
}
