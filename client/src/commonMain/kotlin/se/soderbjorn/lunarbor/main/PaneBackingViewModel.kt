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
 * A pane viewing a file that is not a `_node.md` outline — a `.md`
 * note — is in Markdown mode ([State.isMarkdownMode]): the same editor,
 * fully editable, but with no bullet behaviour. Nothing folds, nothing
 * zooms, rows are not dragged, and the document saves the text exactly
 * as written ([Document.bulletsOnly] is `false`, so no promotion).
 *
 * ### Links (TRF-8)
 * Links are vault paths to folders and files ([LunarborLink]). Clicking one
 * ([navigateToLink]) zooms to a folder — into its bullet when it is a
 * node's folder — or opens a file as the folder contents list would. The
 * link search runs over the whole vault ([VaultIndex.search]); a link
 * whose target is gone is reported by [isLinkBroken] for the view to
 * strike through. Starred entries are the same vault paths
 * ([currentLocationPath], [toggleStarred]).
 *
 * ### Privacy modes (LBR-10)
 * The app's privacy mode ([State.privacy], mirrored from
 * [DocumentRegistry.privacyFlow]) hides tagged items with their subtrees.
 * Their rows stay in the document but are never on screen
 * ([visibleRowsIn]); every recorded edit is checked afterwards
 * ([recordEdit], [PrivacyLayout.keepsHiddenRows]) and undone when it would
 * have changed, deleted or re-parented a hidden row, or moved a visible
 * one under a hidden item. A zoom into a hidden item falls back to its
 * nearest visible ancestor ([reconcile]); a pane on a hidden file or
 * folder moves up to the nearest visible node ([leaveHiddenLocation]).
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import se.soderbjorn.lunarbor.data.ImagePaths
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.data.DoneState
import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.NodeLine
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.data.PrivacyFilter
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.data.bareUrlEndAt
import se.soderbjorn.lunarbor.data.TagCount
import se.soderbjorn.lunarbor.data.TextHit
import se.soderbjorn.lunarbor.data.SearchNode
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.TextIndex
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.data.TextSearchResult
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.VaultEntry
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.data.LinkSource
import se.soderbjorn.lunarbor.data.VaultIndex
import se.soderbjorn.lunarbor.data.WikiLink
import se.soderbjorn.lunarbor.platform.toNfc

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
     * Lists every image and drawing in the vault (vault-relative paths).
     * Used by the
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
     * @property wikiLinks Mirror of [DocumentRegistry.wikiLinksFlow]: wiki
     *   link name key → the path it resolves to (`null`: no single
     *   match). Mirrored so a name that starts or stops resolving
     *   repaints the pane; read through [wikiLinkHref].
     * @property linkPreviews Mirror of [DocumentRegistry.linkPreviewsFlow]:
     *   node folder → its bullets, so a listing that arrives repaints the
     *   3D view's pages ([spacePageOf]).
     * @property searchNodeResults Mirror of
     *   [DocumentRegistry.searchNodeResultsFlow]: each search node's
     *   results, refreshed a few seconds after the vault changes. Read
     *   through [searchNodeOf].
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
     * @property zoomUnfoldedIds Items that were folded until a zoom
     *   unfolded them — the zoom target itself, or the folded ancestors a
     *   link or history step opened on the way to it. Each folds again as
     *   soon as the zoom target leaves its subtree
     *   ([ZoomNavigation.refoldLeftBehind]), so zooming into a folded
     *   bullet and going back leaves it folded. Toggling an item's fold by
     *   hand drops it from the set.
     * @property expandedBlockIds Large blocks ([BlockLayout.isLarge]) this
     *   pane shows whole, by their first row's id; every other large block
     *   shows only its first rows ([toggleBlockExpanded], [visibleRowsIn]).
     * @property pendingLeafZoomChild When non-null, the [LineId] of an
     *   empty placeholder bullet that was inserted by [zoomInto] when
     *   the user zoomed into a childless leaf so the zoom view would
     *   have something to type into — or by leaving a block onto a new
     *   bullet ([exitBlock], Arrow Down off a block's last row). Moving
     *   the caret off it removes it while still empty
     *   ([dropAbandonedPlaceholder]). Cleared on the first edit (the
     *   user has committed to the placeholder). On any zoom navigation
     *   away or file switch, if the placeholder is still empty, the row
     *   is deleted again — so "zoom in, look around, go back" never
     *   leaves a stray empty bullet behind. [NoteRepository.save] also
     *   strips any trailing empty bullet that survives in memory before
     *   it reaches disk.
     * @property pendingRowsGroup When non-null, the group of pending rows
     *   ([Document.insertPendingRows]) this pane holds: the
     *   `Journal › year › week › day` items and the empty placeholder — or
     *   the daily template's copied rows (LBR-21) — the Today command
     *   ([navigateToToday]) prepared and nobody has edited yet. Those
     *   rows are on screen in every pane but never saved. The first edit
     *   that changes one of them commits the whole group (they
     *   save like any row from then on — [recordEdit]); a zoom change, file
     *   switch or closing the pane lets go of it
     *   ([cleanupEmptyPlaceholderIfAny] → [Document.releasePendingRows]),
     *   and once no pane holds it the rows still pending are removed — so
     *   an untouched day leaves nothing behind, on screen or on disk.
     *   Unlike [pendingLeafZoomChild] (a row that *is* saved, stripped
     *   later as a trailing empty bullet), the group lives in the shared
     *   [Document], so two panes on the same prepared day share it.
     * @property dailyTemplateFolder Mirror of [DocumentRegistry.dailyTemplate]:
     *   the vault folder of the node new journal days copy (LBR-21), or
     *   `null` for none. Read through [isDailyTemplatePage].
     * @property seenLineIds Internal: the set of [LineId]s the
     *   default-collapse pass has already processed.
     * @property pendingInlineStyles Inline styles armed via Cmd-B / etc
     *   while the caret was collapsed.
     * @property searchQuery The pane's search text; `null` while the
     *   search field is closed, `""` while it is open and empty. See
     *   [setSearchQuery].
     * @property searchHits The matching lines under the pane's page
     *   ([searchScope]), from the vault's text index; empty for a blank
     *   query. The view lists them instead of the page while
     *   [isSearchActive].
     * @property searchTotal How many lines match in all; [searchHits]
     *   holds at most the first few hundred.
     * @property isSearching `true` while a search runs that has to build
     *   the text index first (the first search after launch).
     * @property searchReversed `true` when this pane lists its search
     *   results in reverse order ([setSearchReversed], the field's ⇅
     *   button); a query's own `order:reverse` reverses them too.
     * @property scrollRestore A scroll position the view should apply once
     *   (it remembers the last [ScrollRestore.seq] it applied): the
     *   remembered scroll of a page the pane just came back to
     *   ([recallPage]), or a persisted one ([restoreScroll]).
     * @property drawingRevision While the pane shows a drawing
     *   ([isDrawingView]): the registry's change counter for it
     *   ([DocumentRegistry.drawingRevisionsFlow]). The drawing editor
     *   re-reads the drawing ([loadDrawing]) when it changes. `0` otherwise.
     * @property privacy What the app's privacy mode hides (mirror of
     *   [DocumentRegistry.PrivacyView.filter]); [PrivacyFilter.NONE] for
     *   "No privacy". Rows it hides are never on screen ([visibleRowsIn]).
     * @property backlinks Mirror of [DocumentRegistry.backlinksFlow]: page
     *   path → the lines linking to it. Read through [backlinksOf].
     * @property backlinksCollapsed `true` when this pane has folded its
     *   "Linked from" section ([toggleBacklinksCollapsed]); pane state, kept
     *   across pages.
     * @property hideDone `true` while this pane hides done items (LBR-24,
     *   palette "Hide done items" — [setHideDone]): every done item on the
     *   page ([DoneLayout.hiddenRows]) is left off the screen with its
     *   subtree, like a folded subtree that never shows ([visibleRowsIn]).
     *   Only a view filter: unlike the privacy mode it never refuses an
     *   edit. Pane state, like folds; persisted with the pane's location.
     * @property hitDoneToast The last Toggle done this pane made on a search
     *   result (LBR-22, [toggleDoneOnHit]) and the serial of that toggle,
     *   for the view's "Marked done · Undo" toast: the view shows a toast
     *   whenever the serial changes, and its Undo calls [undoHitDoneToggle].
     *   `null` when there is nothing to undo.
     * @property privacyRevision Mirror of [DocumentRegistry.PrivacyView.revision]:
     *   changes whenever what is hidden may have changed, so the view repaints
     *   (folder contents, links) even when [privacy] did not.
     */
    data class State(
        val activeFileRel: String = "",
        val documentState: Document.State? = null,
        val vaultListings: Map<String, List<VaultEntry>> = emptyMap(),
        val linkStatus: Map<String, Boolean> = emptyMap(),
        val wikiLinks: Map<String, String?> = emptyMap(),
        val linkPreviews: Map<String, List<LinkPreviewItem>> = emptyMap(),
        val searchNodeResults: Map<DocumentRegistry.SearchNodeKey, TextSearchResult> = emptyMap(),
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
        val zoomUnfoldedIds: Set<LineId> = emptySet(),
        val expandedBlockIds: Set<LineId> = emptySet(),
        val pendingLeafZoomChild: LineId? = null,
        val pendingRowsGroup: Long? = null,
        val dailyTemplateFolder: String? = null,
        internal val seenLineIds: Set<LineId> = emptySet(),
        val pendingInlineStyles: Set<InlineStyle> = emptySet(),
        val searchQuery: String? = null,
        val searchHits: List<TextHit> = emptyList(),
        val searchTotal: Int = 0,
        val isSearching: Boolean = false,
        val searchReversed: Boolean = false,
        val scrollRestore: ScrollRestore? = null,
        val drawingRevision: Int = 0,
        val privacy: PrivacyFilter = PrivacyFilter.NONE,
        val privacyRevision: Int = 0,
        val backlinks: Map<String, List<TextHit>> = emptyMap(),
        val backlinksCollapsed: Boolean = false,
        val hideDone: Boolean = false,
        val hitDoneToast: HitDoneToast? = null,
    ) {
        /**
         * `true` while the search field holds at least one word: the view
         * shows the result list ([searchHits]) in place of the page.
         */
        val isSearchActive: Boolean get() = !SearchQuery.parse(searchQuery).isEmpty

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
         * `true` when the pane's active location is an Excalidraw drawing
         * ([NoteRepository.isDrawingPath]). Like the image view there is no
         * [Document] ([documentState] stays `null`); the view layer swaps
         * the editor surface for the Excalidraw editor, which loads through
         * [loadDrawing] and saves through [onDrawingChanged].
         */
        val isDrawingView: Boolean get() = NoteRepository.isDrawingPath(activeFileRel)

        /**
         * `true` when the pane's active location is an HTML page
         * ([NoteRepository.isHtmlPath]). Like the image view there is no
         * [Document]; the view layer shows the page in a sandboxed frame
         * in place of the editor surface.
         */
        val isHtmlView: Boolean get() = NoteRepository.isHtmlPath(activeFileRel)

        /**
         * `true` when the pane shows a file without a [Document] — an
         * image, a drawing or an HTML page ([NoteRepository.isFileViewPath]).
         * Such a page is "ready" as soon as [activeFileRel] points at it;
         * no load to wait for.
         */
        val isFileView: Boolean get() = isImageView || isDrawingView || isHtmlView

        /**
         * `true` when the pane shows a file that is not a node outline —
         * a `.md` note (TRF-7). The editor stays fully editable but drops
         * every bullet behaviour: no folding, no zoom, no row dragging, no
         * promotion to folders. `false` for outlines and for the image
         * view.
         */
        val isMarkdownMode: Boolean
            get() = activeFileRel.isNotEmpty() && !isFileView && !NoteRepository.isOutlineFile(activeFileRel)

        /**
         * `true` while the document is mid-save on a tick that
         * turns a bullet into a folder or a folder back into a bullet
         * — see [Document.State.isRestructuring].
         */
        val isRestructuring: Boolean get() = documentState?.isRestructuring == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")

        /**
         * `true` when the page title above the editor can be edited to
         * rename the file ([renameActiveFile]): a loaded `.md` note other
         * than `Starred.md`, an image or a drawing ([isFileView]). Outlines
         * and app files keep a read-only title.
         */
        val canRenameFromTitle: Boolean
            get() = isFileView || (isMarkdownMode && isLoaded && !NoteRepository.isAppFile(activeFileRel))

        /**
         * `true` while the pane is zoomed into a search node or a link
         * bullet ([ZoomInfo.isReadOnly]): the page shows its results or the
         * linked node's preview, which are not its own, so nothing on it
         * can be edited — no typing, no new bullets, no moves, no undo
         * ([recordEdit]). The bullet itself is edited from the parent.
         */
        val isReadOnlyPage: Boolean get() = zoomInfoOf(this)?.isReadOnly == true

        /**
         * `true` when a note's first line is an `# H1` saying the same as
         * its file name — the page title already shows it, so the editor
         * hides that row instead of drawing the title twice. Only while
         * something follows it (the caret needs a visible row), and only
         * while it matches: edit the file elsewhere so it differs and it
         * shows again. [renameActiveFile] rewrites it with the name.
         */
        val hidesTitleHeading: Boolean
            get() {
                if (!isMarkdownMode || !isLoaded) return false
                val lines = lines
                if (lines.size < 2) return false
                return isTitleHeading(lines[0], NoteRepository.displayNameOf(activeFileRel))
            }

        /**
         * First row the editor shows when not zoomed: past the hidden title
         * heading ([hidesTitleHeading]) and the blank lines right after it
         * (the usual `# Title` + empty line opening, which would otherwise
         * leave a gap under the page title), but never past the last row, so
         * the caret always has a row. `0` when nothing is hidden. Every
         * "which rows are on screen" computation starts here instead of `0`.
         */
        val firstEditableRow: Int
            get() {
                if (!hidesTitleHeading) return 0
                val lines = lines
                var row = 1
                while (row < lines.lastIndex && lines[row].isBlank()) row++
                return row
            }
    }

    /**
     * A scroll position for the view to apply once.
     *
     * @property top The scroll offset, in CSS pixels.
     * @property seq Increases with every request, so the same offset can be
     *   asked for twice and the view can tell a new request from an old one.
     */
    data class ScrollRestore(val top: Double, val seq: Int)

    /**
     * What a page (a file and zoom) looked like when the pane left it,
     * kept by [rememberPage] and brought back by [recallPage].
     *
     * @property scrollTop The view's scroll offset there, or `null` when
     *   it never reported one for the page.
     * @property searchQuery The search field's text, or `null` (closed).
     * @property searchReversed Whether the results were reversed.
     * @property caret Where the caret was, or `null`.
     */
    data class PageView(
        val scrollTop: Double?,
        val searchQuery: String?,
        val searchReversed: Boolean,
        val caret: Caret? = null,
    )

    /**
     * A remembered caret: [row] and [col], and the text of its line, so it
     * finds the same line again when rows have shifted (a reload, another
     * folder expanded) — see [rowOfCaret].
     */
    data class Caret(val row: Int, val col: Int, val lineText: String)

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
     * @property zoomBack The pane's zoom history in this file
     *   ([State.zoomHistory]) when it left, each entry as a title path
     *   (empty: the file's root view), so Back after a return keeps
     *   climbing the zooms that led here instead of jumping to the root.
     *   Restored by [restoreZoomStacks]; only on the history stacks.
     * @property zoomForward Likewise [State.zoomForward].
     */
    data class FileHistoryEntry(
        val fileRel: String,
        val zoomTitlePath: List<String> = emptyList(),
        val zoomBack: List<List<String>> = emptyList(),
        val zoomForward: List<List<String>> = emptyList(),
    )

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
     *   [se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer].
     * @property style the line-level style detected on the zoomed bullet
     *   (heading level or quote), or `null` for plain text. Renderers use
     *   this to apply heading/quote visual styling to the zoom headline.
     * @property isSearchNode `true` when the zoomed bullet is a search node
     *   ([SearchNode]): the page lists its results and is read-only
     *   ([State.isReadOnlyPage]).
     */
    data class ZoomInfo(
        val zoomRow: Int,
        val zoomIndent: Int,
        val startRow: Int,
        val endRowInclusive: Int,
        val titleText: String,
        val style: LineStyle? = null,
        val isSearchNode: Boolean = false,
    ) {
        /** `true` when the page is read-only ([State.isReadOnlyPage]). */
        val isReadOnly: Boolean get() = isSearchNode

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

    /**
     * Open state of each folder-backed item of the active document as
     * last written to [DocumentRegistry.foldMemory] by THIS pane
     * ([recordFolds]); only changes are written, so two panes on one file
     * with different folds do not overwrite each other on every edit.
     * Cleared by [switchActiveFile].
     */
    private val recordedOpen = HashMap<LineId, Boolean>()

    private val textEditing = TextEditingViewModel(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        applyState = { _stateFlow.value = it },
        mutate = { transform -> mutate(transform) },
        patch = { transform -> patch(transform) },
        isProtectedRow = { state, row -> isProtectedRow(state, row) },
    )

    /**
     * `true` when the visible item at [row] must not be deleted while the
     * app's privacy mode is on, because content it hides lives under it:
     * hidden rows in its subtree, or — for a folder-backed item — hidden
     * content in its folder ([DocumentRegistry.hasHiddenUnder]), which the
     * delete would send to the trash. `false` with no mode on.
     *
     * Called by [TextEditingViewModel.deleteSelectionIfAny] (such rows are
     * kept) and by [recordEdit]'s check.
     */
    private fun isProtectedRow(state: State, row: Int): Boolean {
        if (!state.privacy.isActive) return false
        val docState = state.documentState ?: return false
        if (PrivacyLayout.hasHiddenDescendants(docState.lines, hiddenRowsIn(state), row)) return true
        val id = docState.lineIds.getOrNull(row) ?: return false
        val folder = document?.folderOf(id) ?: return false
        return !NoteRepository.isInTrash(folder) && registry.hasHiddenUnder(folder)
    }

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

    /**
     * Follows a file rename ([DocumentRegistry.renameFile]) — by this pane
     * or another one on the same file — in [State.activeFileRel] and the
     * file history, so Back / Forward and the eventual release use the
     * new path. Called synchronously by the registry.
     */
    private val renameListener: (PathMove) -> Unit = { move ->
        fun List<FileHistoryEntry>.moved() = map { if (it.fileRel == move.from) it.copy(fileRel = move.to) else it }
        val s = _stateFlow.value
        _stateFlow.value = s.copy(
            activeFileRel = if (s.activeFileRel == move.from) move.to else s.activeFileRel,
            fileHistory = s.fileHistory.moved(),
            fileForward = s.fileForward.moved(),
        )
    }

    init {
        registry.addRenameListener(renameListener)
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
        scope.launch {
            registry.wikiLinksFlow.collect { wiki ->
                _stateFlow.value = _stateFlow.value.copy(wikiLinks = wiki)
            }
        }
        scope.launch {
            registry.linkPreviewsFlow.collect { previews ->
                _stateFlow.value = _stateFlow.value.copy(linkPreviews = previews)
            }
        }
        scope.launch {
            registry.dailyTemplate.folderFlow.collect { folder ->
                _stateFlow.value = _stateFlow.value.copy(dailyTemplateFolder = folder)
            }
        }
        scope.launch {
            registry.backlinksFlow.collect { backlinks ->
                _stateFlow.value = _stateFlow.value.copy(backlinks = backlinks)
            }
        }
        scope.launch {
            registry.searchNodeResultsFlow.collect { results ->
                _stateFlow.value = _stateFlow.value.copy(searchNodeResults = results)
            }
        }
        // The active drawing's change counter, so the drawing editor
        // re-reads it when another pane wrote it or it changed on disk.
        scope.launch {
            combine(registry.drawingRevisionsFlow, _stateFlow.map { it.activeFileRel }.distinctUntilChanged()) { revs, file ->
                if (NoteRepository.isDrawingPath(file)) revs[file] ?: 0 else 0
            }.distinctUntilChanged().collect { rev ->
                _stateFlow.value = _stateFlow.value.copy(drawingRevision = rev)
            }
        }
        // The app's privacy mode: hidden rows leave the screen at once, and
        // a pane on something now hidden moves to the nearest visible node.
        scope.launch {
            registry.privacyFlow.collect { view ->
                val before = _stateFlow.value
                if (before.privacy == view.filter && before.privacyRevision == view.revision) return@collect
                if (before.privacy != view.filter && before.documentState != null) {
                    // Snapshots from under another mode could bring back or
                    // drop rows this mode hides.
                    undoStack.clear()
                    redoStack.clear()
                }
                _stateFlow.value = reconcile(before.copy(privacy = view.filter, privacyRevision = view.revision))
                if (before.privacy != view.filter) before.searchQuery?.let(::setSearchQuery)
                ensureVisibleRow()
                leaveHiddenLocation()
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
     * `true` when the app's privacy mode hides the vault path [pathRel]
     * ([DocumentRegistry.isPathHidden]). Called by the web view for the
     * folder contents list, Starred and links, and by this pane for its
     * own location and history.
     */
    fun isPathHidden(pathRel: String): Boolean = registry.isPathHidden(pathRel)

    /**
     * `true` when the item at [row] has children the pane can show: rows
     * in its subtree that are on screen once unfolded, or — a folded
     * folder-backed item, its children on disk only — items in its folder.
     * The app's privacy mode counts: an item whose children are all hidden
     * has none, so it gets no fold control; so do done children while the
     * pane hides them ([State.hideDone]).
     *
     * Called by the web paint loop for every bullet and block item.
     */
    fun hasChildrenOnScreen(state: State, row: Int): Boolean {
        val docState = state.documentState ?: return false
        val lines = docState.lines
        val col = DocumentLayout.itemColumn(lines, row)
        if (col < 0) return false
        val id = docState.lineIds.getOrNull(row)
        val foldedRef = id != null && isPromotedRef(id) && id !in state.expandedRefIdsLocal
        val hidden = hiddenOnScreenIn(state)
        if (hidden == null) return foldedRef || DocumentLayout.hasChildren(lines, row, col)
        val end = DocumentLayout.subtreeEnd(lines, row, col)
        if ((DocumentLayout.itemLastRow(lines, row) + 1..end).any { !hidden[it] }) return true
        if (!foldedRef) return false
        if (!state.privacy.isActive) return true
        val folder = document?.folderOf(id!!) ?: return true
        return registry.textIndex.hasVisibleItems(folder, state.privacy)
    }

    /**
     * The page whose backlinks the pane shows (LBR-7): the link target this
     * page is — the zoomed item's folder, the open outline's folder, or the
     * open note, image or other file. `null` where nothing can link here:
     * zoomed into a leaf (it has no folder) or into an item in the trash.
     */
    fun backlinksTarget(state: State = _stateFlow.value): String? {
        val file = state.activeFileRel
        if (file.isEmpty()) return null
        if (state.isFileView || state.isMarkdownMode) return file
        if (!NoteRepository.isOutlineFile(file)) return null
        val zoomed = state.zoomedLineId?.takeIf { zoomInfoOf(state) != null } ?: return NoteRepository.folderOfOutline(file)
        return document?.folderOf(zoomed)?.takeUnless { NoteRepository.isInTrash(it) }
    }

    /**
     * The lines linking to this pane's page ([backlinksTarget]) — vault
     * links and `[[wiki]]` links resolving to it, none from inside the page
     * or hidden by the privacy mode — or `null` while they are being found
     * ([DocumentRegistry.requestBacklinks]; the result arrives as a new
     * [State.backlinks], which repaints) or when nothing can link here.
     *
     * Called by the web view for the "Linked from" section under the page.
     */
    fun backlinksOf(state: State): List<TextHit>? {
        val target = backlinksTarget(state) ?: return null
        return state.backlinks[target] ?: registry.requestBacklinks(target)
    }

    /**
     * Turns this pane's "Hide done items" on or off ([State.hideDone],
     * LBR-24). A caret on a row that goes off screen moves to the nearest
     * visible row above; a page left with nothing on screen gets a
     * throwaway empty bullet ([ensureVisibleRow]). A no-op in Markdown mode.
     *
     * Called by the web palette ("Hide done items" / "Show done items") and
     * by `AppShell` when it restores a pane's persisted setting.
     */
    fun setHideDone(on: Boolean) {
        val s = _stateFlow.value
        if (s.hideDone == on) return
        _stateFlow.value = reconcile(s.copy(hideDone = on))
        ensureVisibleRow()
    }

    /**
     * `true` when the row [row] of [state]'s outline is done (LBR-24): it
     * belongs to an item whose whole title is struck through, or to
     * anything under one ([DoneLayout.doneRows]). Always `false` in
     * Markdown mode. Called by the web paint loop to dim done rows.
     */
    fun isRowDone(state: State, row: Int): Boolean {
        if (state.isMarkdownMode) return false
        val docState = state.documentState ?: return false
        return DoneLayout.isMarked(DoneLayout.doneRows(docState.lines), row)
    }

    /**
     * `true` when Toggle done ([toggleDone]) can act here: a loaded outline
     * page that is not read-only. Not in Markdown mode (`.md` notes) or on
     * file views. Called by the web palette to offer the command.
     */
    fun canToggleDone(state: State = _stateFlow.value): Boolean =
        state.isLoaded && !state.isMarkdownMode && !state.isFileView && !state.isReadOnlyPage

    /**
     * Toggle done (LBR-24): wraps or unwraps the **whole title** of the
     * caret's item in `~~` ([DoneState.withDoneRow]; tags and a search
     * node's query stay outside the markers), or of every item a multi-row
     * selection touches — a block by its first row. With several items it
     * marks them all done unless all already are, then it unmarks them all.
     * An item done only because something above it is done is not done by
     * its own title, so it is struck on its own (its own title alone; the
     * parent is left alone). Rows the privacy mode hides and code rows are
     * skipped.
     *
     * One undoable edit, composed from [Document.delete] /
     * [Document.insertText] per changed row; the caret and the selection
     * stay on their rows, shifted by the markers added or removed. A no-op
     * when [canToggleDone] is `false`.
     *
     * Called by the web palette ("Toggle done") and the editor's ⌃↩
     * (Alt-Enter off the Mac).
     */
    fun toggleDone() = recordEdit(FrameKind.OTHER) {
        val s = _stateFlow.value
        if (!canToggleDone(s)) return@recordEdit
        val doc = document ?: return@recordEdit
        val docState = doc.stateFlow.value
        val lines = docState.lines
        val sel = selectionOf(s)
        val first = (sel?.startRow ?: s.cursorRow).coerceIn(0, lines.lastIndex)
        val last = (sel?.endRow ?: s.cursorRow).coerceIn(first, lines.lastIndex)
        val hidden = hiddenRowsIn(s.copy(documentState = docState))
        val itemRows = LinkedHashSet<Int>()
        for (r in first..last) {
            val itemRow = BlockLayout.rangeAt(lines, r)?.first ?: r
            if (DocumentLayout.itemColumn(lines, itemRow) < 0) continue
            if (PrivacyLayout.isHidden(hidden, itemRow) || BlockLayout.isCodeLine(lines[itemRow])) continue
            itemRows += itemRow
        }
        if (itemRows.isEmpty()) return@recordEdit
        val done = !itemRows.all { DoneState.isDoneRow(lines[it]) }
        commitPlaceholderIfAny()
        // Per changed row: the unchanged head, the old end of the changed
        // middle and the change in length. A column in the head stays, one
        // after the middle moves by the change, one inside the middle (the
        // title) moves by the opening marker added or removed.
        val shifts = HashMap<Int, Triple<Int, Int, Int>>()
        for (row in itemRows) {
            val before = lines[row]
            val after = DoneState.withDoneRow(before, done)
            if (after == before) continue
            var head = 0
            while (head < before.length && head < after.length && before[head] == after[head]) head++
            var tail = 0
            while (tail < before.length - head && tail < after.length - head &&
                before[before.length - 1 - tail] == after[after.length - 1 - tail]
            ) tail++
            doc.delete(row, head, row, before.length - tail)
            doc.insertText(row, head, after.substring(head, after.length - tail))
            shifts[row] = Triple(head, before.length - tail, after.length - before.length)
        }
        if (shifts.isEmpty()) return@recordEdit
        fun shifted(row: Int, col: Int): Int {
            val (head, middleEnd, delta) = shifts[row] ?: return col
            val newLen = doc.stateFlow.value.lines.getOrNull(row)?.length ?: return col
            val moved = when {
                col <= head -> col
                col >= middleEnd -> col + delta
                else -> (col + if (done) 2 else -2).coerceIn(head, middleEnd + delta)
            }
            return moved.coerceIn(0, newLen)
        }
        patch {
            it.copy(
                cursorCol = shifted(it.cursorRow, it.cursorCol),
                anchorCol = it.anchorRow?.let { ar -> it.anchorCol?.let { ac -> shifted(ar, ac) } },
            )
        }
    }

    /**
     * Toggle done on a search result (LBR-22): LBR-24's Toggle done on the
     * line [hit] names, where it is stored — another file, a folder-backed
     * item's folder, or this pane's own document — without opening it
     * ([DocumentRegistry.toggleDoneOnHit]). Allowed on a read-only page (a
     * zoomed search node): the edit is the result's own line elsewhere,
     * not the page. Not a pane edit: it never enters this pane's undo
     * stack and never commits a prepared day; the toast's Undo
     * ([undoHitDoneToggle]) takes it back instead. Afterwards an open pane
     * search re-runs its query, so a row leaves an `is:open` list.
     *
     * Called by the web view: the ✓ circle on a result row (pane search
     * and search nodes), and the palette's "Toggle done" / ⌃↩ on the pane
     * search's highlighted result.
     *
     * @return The job doing it; complete once saved and re-searched.
     */
    fun toggleDoneOnHit(hit: TextHit): Job = scope.launch {
        if (!hit.canToggleDone) return@launch
        val toggle = registry.toggleDoneOnHit(hit) ?: return@launch
        hitDoneSerial++
        patch { it.copy(hitDoneToast = HitDoneToast(toggle, hitDoneSerial)) }
        rerunSearch()
    }

    /**
     * Undoes the last Toggle done on a search result ([State.hitDoneToast],
     * [DocumentRegistry.undoDoneOnHit]) and clears the toast. Called by the
     * toast's Undo.
     *
     * @return The job doing it.
     */
    fun undoHitDoneToggle(): Job = scope.launch {
        val toast = _stateFlow.value.hitDoneToast ?: return@launch
        patch { it.copy(hitDoneToast = null) }
        registry.undoDoneOnHit(toast.toggle)
        rerunSearch()
    }

    /** Forgets the toast's undo ([State.hitDoneToast]). Called by the view when the toast times out. */
    fun dismissHitDoneToast() {
        if (_stateFlow.value.hitDoneToast != null) patch { it.copy(hitDoneToast = null) }
    }

    /** Serial of the last [toggleDoneOnHit], for [HitDoneToast.serial]. */
    private var hitDoneSerial = 0

    /** Runs the open pane search's query again ([setSearchQuery]), so its hits follow the index. */
    private fun rerunSearch() {
        val s = _stateFlow.value
        if (s.isSearchActive) s.searchQuery?.let(::setSearchQuery)
    }

    /** Folds or unfolds this pane's "Linked from" section. Called by its header. */
    fun toggleBacklinksCollapsed() {
        _stateFlow.value = _stateFlow.value.copy(backlinksCollapsed = !_stateFlow.value.backlinksCollapsed)
    }

    /**
     * Gives the page a row to type in when the app's privacy mode hides
     * every row it has — an outline, or a zoom, whose items all carry a
     * hidden tag, or (with [State.hideDone]) are all done: an empty bullet after them (at the page's top level, so
     * it is no hidden item's child), with the caret on it. Like a leaf
     * zoom's placeholder ([State.pendingLeafZoomChild]) it goes again when
     * left empty. Not an undoable edit. A no-op when anything is visible.
     *
     * Called after every document emission, mode change and zoom change.
     */
    private fun ensureVisibleRow() {
        val s = _stateFlow.value
        if (!s.isLoaded || !(s.privacy.isActive || s.hideDone) || s.isMarkdownMode || s.isReadOnlyPage) return
        val hidden = hiddenOnScreenIn(s) ?: return
        val doc = document ?: return
        val zoom = zoomInfoOf(s)
        val start = zoom?.startRow ?: s.firstEditableRow
        val end = zoom?.endRowInclusive ?: s.lines.lastIndex
        if (start > end || (start..end).any { !hidden[it] }) return
        val indent = zoom?.let { it.zoomIndent + TAB_SIZE } ?: 0
        val at = end + 1
        doc.insertLine(at, " ".repeat(indent) + "* ")
        val id = doc.stateFlow.value.lineIds.getOrNull(at) ?: return
        patch {
            it.copy(cursorRow = at, cursorCol = indent + 2, anchorRow = null, anchorCol = null, pendingLeafZoomChild = id)
        }
    }

    /**
     * Moves this pane off a file or folder the app's privacy mode hides:
     * to the nearest node above it that is visible (the root at worst),
     * without recording history. A zoom into a hidden item is handled by
     * [reconcile] instead. A no-op when nothing the pane shows is hidden.
     *
     * Called whenever the mode, or what it hides, changes.
     */
    private fun leaveHiddenLocation() {
        val s = _stateFlow.value
        if (!s.privacy.isActive) return
        val file = s.activeFileRel
        if (file.isEmpty() || !registry.isPathHidden(file)) return
        val dest = visibleFileFor(file)
        scope.launch {
            if (_stateFlow.value.activeFileRel != file) return@launch
            switchActiveFile(dest)
            recallAfterLoad(dest)
        }
    }

    /**
     * [fileRel], or — when the app's privacy mode hides it — the outline of
     * the nearest node above it that is visible (the root at worst). Every
     * file switch ([switchActiveFile]) goes through it, so no navigation —
     * a link, Back, a restored location — ever lands on hidden content.
     */
    private fun visibleFileFor(fileRel: String): String {
        if (fileRel.isEmpty() || !registry.isPathHidden(fileRel)) return fileRel
        var target = parentFileOf(fileRel)
        while (target != null && registry.isPathHidden(target)) target = parentFileOf(target)
        return target ?: rootFileName
    }

    /**
     * `true` when the history entry [entry] points at a file or folder the
     * app's privacy mode hides: Back / Forward skip it.
     */
    private fun isEntryHidden(entry: FileHistoryEntry): Boolean = registry.isPathHidden(entry.fileRel)

    /**
     * `true` when the item [id] of the open document is hidden by the
     * app's privacy mode (its row, or a row above it, carries a hidden tag).
     */
    private fun isRowIdHidden(state: State, id: LineId): Boolean {
        val hidden = hiddenRowsIn(state) ?: return false
        val row = state.documentState?.lineIds?.indexOf(id) ?: return false
        return PrivacyLayout.isHidden(hidden, row)
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
                val previous = _stateFlow.value.documentState
                val base = if (previous != null && docState.reloadCount != previous.reloadCount) {
                    afterExternalReload(_stateFlow.value, previous, docState)
                } else _stateFlow.value
                val merged = base.copy(documentState = docState)
                val withDefaults = applyDefaultCollapseIfNeeded(merged)
                _stateFlow.value = reconcile(withDefaults)
                recordFolds(_stateFlow.value)
                ensureVisibleRow()
                // Folder-backed items remembered open load their children
                // (each splice is seen here again, so deeper levels follow).
                // Inline, so switchActiveFile's cancelAndJoin settles every
                // acquire before it releases the pane's expansions.
                for (id in withDefaults.expandedRefIdsLocal - merged.expandedRefIdsLocal) {
                    doc.acquireExpansion(id)
                }
            }
        }
    }

    /**
     * Pane state after the document was reloaded because its file changed
     * outside the app ([Document.reloadFromDisk]): the caret follows its
     * line by id (or stays at its row when the line is gone), the
     * selection is dropped, and undo / redo are cleared — their snapshots
     * would bring back the content the disk replaced.
     *
     * Called by [startDocumentCollector] when [Document.State.reloadCount]
     * changes.
     */
    private fun afterExternalReload(state: State, before: Document.State, after: Document.State): State {
        undoStack.clear()
        redoStack.clear()
        val id = before.lineIds.getOrNull(state.cursorRow)
        val row = id?.let { after.lineIds.indexOf(it) }?.takeIf { it >= 0 }
        return state.copy(
            cursorRow = row ?: state.cursorRow,
            anchorRow = null,
            anchorCol = null,
            pendingLeafZoomChild = state.pendingLeafZoomChild?.takeIf { it in after.lineIds },
        )
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
     *
     * A [requested] file the app's privacy mode hides opens the nearest
     * visible node above it instead ([visibleFileFor]).
     */
    private suspend fun switchActiveFile(requested: String) {
        val fileRel = visibleFileFor(requested)
        val current = _stateFlow.value
        if (current.activeFileRel == fileRel &&
            (document != null || NoteRepository.isFileViewPath(fileRel))
        ) return
        rememberPage(current)
        // Strip any throwaway placeholder before swapping the active
        // document — otherwise an empty placeholder bullet inserted by a
        // leaf-zoom on the outgoing file would persist into the next
        // autosave tick on that file.
        cleanupEmptyPlaceholderIfAny()
        val outgoing = document
        val outgoingFile = current.activeFileRel
        val outgoingExpansions = current.expandedRefIdsLocal
        val targetIsFileView = NoteRepository.isFileViewPath(fileRel)
        // A drawing being left writes its last change now, so the next
        // pane (or app) to open it reads it as it was left.
        if (NoteRepository.isDrawingPath(outgoingFile) && outgoingFile != fileRel) registry.flushDrawings(outgoingFile)
        // Acquire the incoming document up front so the registry refcount
        // is bumped before we release the outgoing one — keeps a shared
        // [Document] alive across a same-file navigation without an
        // extra disk round-trip. For image and drawing targets we skip the
        // registry's documents entirely; the pane simply has no document
        // for the duration.
        val incoming = if (targetIsFileView) null else registry.acquire(fileRel)
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
        recordedOpen.clear()
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
            zoomUnfoldedIds = emptySet(),
            expandedBlockIds = emptySet(),
            pendingLeafZoomChild = null,
            pendingRowsGroup = null,
            seenLineIds = emptySet(),
            pendingInlineStyles = emptySet(),
            searchQuery = null,
            searchHits = emptyList(),
            searchTotal = 0,
            isSearching = false,
            searchReversed = false,
        )
        searchJob?.cancel()
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
     * After a switch to [fileRel]: once the page has loaded, brings back
     * its caret, scroll and search ([recallPage]); a zoom that follows
     * recalls the zoomed page's. Called by each navigation after its own
     * history bookkeeping (so Back / Forward never wait on a load for
     * their stacks) — and not by one that places the caret itself (a
     * search result), which memory must not override.
     */
    private suspend fun recallAfterLoad(fileRel: String): Boolean {
        val loaded = _stateFlow.first { it.activeFileRel != fileRel || it.isLoaded || it.isFileView }
        return loaded.activeFileRel == fileRel && recallPage()
    }

    /**
     * Releases the active document. Called by the platform layer when
     * the pane itself is being torn down (e.g. the user closes the
     * pane). Safe to call once; subsequent calls are no-ops.
     */
    suspend fun release() {
        registry.removeRenameListener(renameListener)
        _stateFlow.value.activeFileRel.takeIf { NoteRepository.isDrawingPath(it) }?.let { registry.flushDrawings(it) }
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
     * not re-collapse ids the user has explicitly expanded. A
     * folder-backed item remembered open ([FoldMemory]) is left open and
     * added to [State.expandedRefIdsLocal] instead; the collector then
     * acquires its expansion.
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
        val restored = HashSet<LineId>()
        val doc = document
        for ((idx, id) in currentIds.withIndex()) {
            if (id in seen) continue
            if (idx !in docState.lines.indices) continue
            val indent = DocumentLayout.itemColumn(docState.lines, idx)
            if (indent < 0) continue
            // An item this or another pane last left open opens again; its
            // children load in the collector ([startDocumentCollector]).
            val folder = doc?.folderOf(id)
            if (folder != null && registry.foldMemory.isExpanded(folder)) {
                restored += id
                continue
            }
            val isParent = DocumentLayout.hasChildren(docState.lines, idx, indent)
            val isRef = doc?.isPromotedRef(id) == true
            if (isParent || isRef) newCollapsed += id
        }
        val nextSeen = seen + currentIds
        if (newCollapsed.isEmpty() && restored.isEmpty() && nextSeen.size == seen.size) return state
        return state.copy(
            collapsedIds = state.collapsedIds + newCollapsed,
            expandedRefIdsLocal = state.expandedRefIdsLocal + restored,
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
     * `true` when the privacy [filter] hides the preview item [item] of the
     * linked node [nodeFolder]: it carries a hidden tag, or its folder is
     * hidden.
     */
    private fun previewItemHidden(nodeFolder: String, item: LinkPreviewItem, filter: PrivacyFilter): Boolean =
        filter.hides(item.tagKeys) || item.pathRel?.let { registry.isPathHidden(it, filter) } == true

    /**
     * `true` when [lineId] is a mirror ([Document.isMirror]): a link bullet
     * that folds open onto another node's own, editable items. Called by
     * the web paint loop to mark its dot.
     */
    fun isMirror(lineId: LineId): Boolean = document?.isMirror(lineId) == true

    /**
     * What a search node at [row] shows ([SearchNode]).
     *
     * @property query The expression, as written.
     * @property result Its results, without the node's own line; `null`
     *   while the first search runs.
     * @property folded `true` when this pane has folded the node (its fold
     *   state, [State.collapsedIds], like any parent's).
     * @property scopeFolder The folder it searches (its own, or the
     *   query's `in:`); its results name where they live from there
     *   ([searchHitCrumbs]).
     */
    data class SearchNodeView(
        val query: String,
        val result: TextSearchResult?,
        val folded: Boolean,
        val scopeFolder: String = "",
    )

    /**
     * The search node at [row], or `null`: a bullet of an outline whose
     * text holds `{{search: …}}`. It searches the tree its line is stored
     * in ([Document.storageFolderOf] and below) unless the query names
     * another with `in:`; results come from [State.searchNodeResults], and
     * an unknown query starts its search
     * ([DocumentRegistry.requestSearchNode]) — the result arrives as a new
     * state, which repaints.
     *
     * Called by the web paint loop for every bullet row.
     */
    fun searchNodeOf(state: State, row: Int): SearchNodeView? {
        if (state.isMarkdownMode) return null
        val docState = state.documentState ?: return null
        val line = docState.lines.getOrNull(row) ?: return null
        if (DocumentLayout.bulletAsteriskColumn(line) < 0) return null
        val title = SubtreeCodec.titleOf(line)
        val query = SearchNode.queryOf(title) ?: return null
        val id = docState.lineIds.getOrNull(row) ?: return null
        val folded = id in state.collapsedIds
        val parsed = SearchQuery.parse(query)
        if (parsed.isEmpty) return SearchNodeView(query, TextSearchResult(emptyList(), 0), folded)
        val doc = document ?: return null
        val home = doc.storageFolderOf(row)
        val scopeFolder = parsed.scopePath ?: home
        val key = DocumentRegistry.SearchNodeKey(TextScope.Tree(scopeFolder), query)
        val result = state.searchNodeResults[key] ?: registry.requestSearchNode(key)
        // The node itself is no result of its own search.
        val homeFile = NoteRepository.outlineFileOf(home)
        val selfText = FolderName.plainTextOf(title).trim()
        val shown = result?.let { r ->
            val hits = r.hits.filterNot { it.fileRel == homeFile && it.text == selfText }
            TextSearchResult(hits, r.total - (r.hits.size - hits.size))
        }
        return SearchNodeView(query, shown, folded, scopeFolder)
    }


    /**
     * Rows of the block [block] this pane hides: past the preview of a
     * large block it has not expanded. `null` when the block is not large
     * (it never folds), `0` when it is large and shown whole.
     *
     * Called by the web paint loop for a block's first row (the expand /
     * collapse control) and its last shown row (the "more lines" label).
     */
    fun hiddenBlockRows(state: State, block: IntRange): Int? {
        if (!BlockLayout.isLarge(block)) return null
        val id = state.documentState?.lineIds?.getOrNull(block.first) ?: return null
        if (id in state.expandedBlockIds || id == state.zoomedLineId) return 0
        return block.last - block.first + 1 - BlockLayout.PREVIEW_ROWS
    }

    /**
     * Shows the large block whose first row carries [lineId] whole, or
     * cuts it back to its preview. Pane state only. Collapsing with the
     * caret in the rows it hides moves the caret to the end of the last
     * row still shown.
     *
     * Called by the web view's expand / collapse control on the block and
     * its "more lines" label.
     */
    fun toggleBlockExpanded(lineId: LineId) {
        patch { s ->
            if (lineId in s.expandedBlockIds) {
                val docState = s.documentState
                val first = docState?.lineIds?.indexOf(lineId) ?: -1
                val lastShown = first + BlockLayout.PREVIEW_ROWS - 1
                val block = if (first >= 0) BlockLayout.rangeAt(docState!!.lines, first) else null
                val caretHidden = block != null && s.cursorRow in (lastShown + 1)..block.last
                val collapsed = s.copy(expandedBlockIds = s.expandedBlockIds - lineId)
                if (caretHidden) {
                    collapsed.copy(
                        cursorRow = lastShown, cursorCol = docState!!.lines[lastShown].length,
                        anchorRow = null, anchorCol = null,
                    )
                } else collapsed
            } else {
                s.copy(expandedBlockIds = s.expandedBlockIds + lineId)
            }
        }
    }

    /**
     * Rows [startRow]..[endRowInclusive] of [state] on screen — see
     * [visibleRowsIn]. Called by the web paint loop.
     */
    fun visibleRows(state: State, startRow: Int, endRowInclusive: Int): List<Int> =
        visibleRowsIn(state, startRow, endRowInclusive)

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
            if (isExpandedInPane && lineId in current.collapsedIds) {
                // Folded again after a zoom ([State.zoomUnfoldedIds]) while
                // this pane still holds its children: just show them.
                patch {
                    it.copy(
                        collapsedIds = it.collapsedIds - lineId,
                        zoomUnfoldedIds = it.zoomUnfoldedIds - lineId,
                    )
                }
            } else if (isExpandedInPane) {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal - lineId,
                        collapsedIds = it.collapsedIds + lineId,
                        zoomUnfoldedIds = it.zoomUnfoldedIds - lineId,
                    )
                }
                scope.launch { doc.releaseExpansion(lineId) }
            } else {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal + lineId,
                        collapsedIds = it.collapsedIds - lineId,
                        zoomUnfoldedIds = it.zoomUnfoldedIds - lineId,
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
                it.copy(collapsedIds = next, zoomUnfoldedIds = it.zoomUnfoldedIds - lineId)
            }
        }
    }

    /**
     * Folds ([folded] `true`) or unfolds the item on the caret's row —
     * the bullet, or the block the caret is in. A no-op when that item has
     * no children, is already in the requested state, or in Markdown mode.
     * Unfolding a folded folder-backed bullet loads its children, as a click
     * on its −/+ control does ([toggleCollapse]).
     *
     * Called by the web view for Cmd-Down (unfold) / Cmd-Up (fold).
     */
    fun setCaretItemFolded(folded: Boolean) {
        val s = _stateFlow.value
        if (!s.isLoaded || s.isMarkdownMode) return
        val doc = document ?: return
        val docState = s.documentState ?: return
        val lines = docState.lines
        val row = BlockLayout.rangeAt(lines, s.cursorRow)?.first ?: s.cursorRow
        val id = docState.lineIds.getOrNull(row) ?: return
        val foldedRef = doc.isPromotedRef(id) && id !in s.expandedRefIdsLocal
        val hasChildren = foldedRef ||
            DocumentLayout.hasChildren(lines, row, DocumentLayout.itemColumn(lines, row))
        if (!hasChildren) return
        val isFolded = foldedRef || id in s.collapsedIds
        if (isFolded != folded) toggleCollapse(id)
    }

    /**
     * Folds ([folded] `true`) or unfolds every item under the node the
     * pane shows — the zoom target's subtree, or the whole outline — at
     * every depth. The node itself is left alone, and so is each large
     * block's preview ([State.expandedBlockIds]): only folds change.
     * A no-op in Markdown mode.
     *
     * Unfolding loads folded folder-backed items' children
     * ([Document.acquireExpansion]) level by level until nothing below is
     * left on disk only; rows that arrive are marked seen so the
     * default-collapse pass does not fold them again. Stops early if the
     * pane switches file meanwhile. Folding only marks items folded — the
     * pane keeps its folder expansions, as a zoom's refold does, so
     * unfolding again is instant.
     *
     * Called by the web command palette ("Expand all children" /
     * "Collapse all children").
     */
    fun setAllChildrenFolded(folded: Boolean) {
        val s = _stateFlow.value
        if (!s.isLoaded || s.isMarkdownMode) return
        val doc = document ?: return
        if (folded) {
            val items = foldableItemsUnderPage()
            patch {
                it.copy(collapsedIds = it.collapsedIds + items, zoomUnfoldedIds = it.zoomUnfoldedIds - items)
            }
            return
        }
        scope.launch {
            while (document === doc) {
                val cur = _stateFlow.value
                val unloaded = doc.stateFlow.value.unloadedRefIds
                val items = foldableItemsUnderPage()
                // As toggleCollapse: a folder-backed item this pane does
                // not hold is loaded, unless its children are already in
                // `lines` and showing.
                val toAcquire = items.filter { id ->
                    doc.isPromotedRef(id) && id !in cur.expandedRefIdsLocal &&
                        (id in unloaded || id in cur.collapsedIds)
                }
                patch {
                    it.copy(
                        collapsedIds = it.collapsedIds - items,
                        zoomUnfoldedIds = it.zoomUnfoldedIds - items,
                        expandedRefIdsLocal = it.expandedRefIdsLocal + toAcquire,
                        seenLineIds = it.seenLineIds + (it.documentState?.lineIds ?: emptyList()),
                    )
                }
                if (toAcquire.isEmpty()) break
                for (id in toAcquire) doc.acquireExpansion(id)
            }
        }
    }

    /**
     * Ids of the items with children (or folder-backed) under the node
     * the pane shows — see [setAllChildrenFolded]. The zoom target itself
     * is not included.
     */
    private fun foldableItemsUnderPage(): Set<LineId> {
        val s = _stateFlow.value
        val doc = document ?: return emptySet()
        val docState = doc.stateFlow.value
        val lines = docState.lines
        if (lines.isEmpty()) return emptySet()
        val zoom = zoomInfoOf(s.copy(documentState = docState))
        val start = zoom?.let { it.zoomRow + 1 } ?: 0
        val end = zoom?.endRowInclusive ?: lines.lastIndex
        val out = HashSet<LineId>()
        val hidden = hiddenRowsIn(s.copy(documentState = docState))
        for (row in start..end) {
            // Hidden items are neither folded nor unfolded (nor loaded).
            if (PrivacyLayout.isHidden(hidden, row)) continue
            val col = DocumentLayout.itemColumn(lines, row)
            if (col < 0) continue
            val id = docState.lineIds.getOrNull(row) ?: continue
            if (doc.isPromotedRef(id) || DocumentLayout.hasChildren(lines, row, col)) out += id
        }
        return out
    }

    /**
     * The title of the node the pane's page is, when [deletePageNode] can
     * delete it, else `null`: the zoom target, or — unzoomed on a node's
     * own outline other than the root — that node. Plain text (inline
     * Markdown collapsed). `null` at the root, on a `.md` note or image,
     * and in Markdown mode.
     *
     * Also `null` while the app's privacy mode hides something inside the
     * node: deleting it would take that along to the trash.
     *
     * Called by the web command palette to offer "Delete this node" and
     * to name it in the confirmation.
     */
    fun pageNodeTitle(state: State = _stateFlow.value): String? {
        if (!state.isLoaded || state.isMarkdownMode) return null
        zoomInfoOf(state)?.let { zoom ->
            if (isProtectedRow(state, zoom.zoomRow)) return null
            return FolderName.withoutTags(InlineMarkdownTokenizer.tokenize(SearchNode.stripQuery(zoom.titleText))).trim()
        }
        if (!NoteRepository.isOutlineFile(state.activeFileRel) || parentFileOf(state.activeFileRel) == null) return null
        if (registry.hasHiddenUnder(NoteRepository.folderOfOutline(state.activeFileRel))) return null
        return NoteRepository.displayNameOf(state.activeFileRel)
    }

    /**
     * Deletes the node the pane's page is ([pageNodeTitle]) with
     * everything under it: the pane first goes up a level (zoomed, as
     * [navigateUp]; on a node's outline, to its parent's, recording no
     * history for the page going away),
     * then the node's item is deleted there — the zoom target's rows, or
     * the bullet backing the open outline's folder, in its parent's
     * outline. A folder-backed item's folder goes to the vault trash on
     * the next save, like any deleted bullet; ⌘Z (on the parent page)
     * brings it all back. Back / Forward entries inside the deleted
     * node's folder are dropped, so they cannot reopen it.
     *
     * Called by the web command palette ("Delete this node"), after the
     * user confirmed.
     */
    fun deletePageNode(): Job = scope.launch {
        val s = _stateFlow.value
        if (pageNodeTitle(s) == null) return@launch
        val zoomed = s.zoomedLineId?.takeIf { zoomInfoOf(s) != null }
        val id: LineId
        if (zoomed != null) {
            id = zoomed
            navigateUp()
        } else {
            val folder = NoteRepository.folderOfOutline(s.activeFileRel)
            val parent = parentFileOf(s.activeFileRel) ?: return@launch
            // No history entry for a page about to be deleted.
            switchActiveFile(parent)
            val parentDoc = document ?: return@launch
            val there = parentDoc.stateFlow.first { it.isLoaded }
            id = there.lineIds.firstOrNull { parentDoc.folderOf(it) == folder } ?: return@launch
        }
        deleteItem(id)
    }

    /**
     * The file the pane shows, when [trashPageFile] can move it to the
     * trash, else `null`: a `.md` note (not an app file such as
     * `Starred.md`), an image, a drawing or another viewed file — never a
     * node outline ([pageNodeTitle] covers those).
     *
     * Called by the web command palette to offer "Delete this file" and
     * to name it in the confirmation.
     */
    fun pageFile(state: State = _stateFlow.value): String? {
        val file = state.activeFileRel
        val shown = (state.isMarkdownMode && state.isLoaded) || state.isFileView
        if (!shown || file.isEmpty() || NoteRepository.isAppFile(file)) return null
        return file
    }

    /**
     * Moves the file the pane shows ([pageFile]) to the vault's trash, as
     * the folder contents list's "Move to Trash" does
     * ([DocumentRegistry.trashNote]): the pane first goes to the node the
     * file is in ([parentFileOf]), recording no history for the page
     * going away, and Back / Forward entries for the file are dropped.
     * Not undoable; the file stays in `.trash`.
     *
     * Called by the web command palette ("Delete this file"), after the
     * user confirmed.
     *
     * @return `null` on success (or nothing to do), else why the file was
     *   kept — e.g. another window has it open.
     */
    suspend fun trashPageFile(): String? {
        val s = _stateFlow.value
        val file = pageFile(s) ?: return null
        val parent = parentFileOf(file) ?: rootFileName
        switchActiveFile(parent)
        patch {
            it.copy(
                fileHistory = it.fileHistory.filterNot { e -> e.fileRel == file },
                fileForward = it.fileForward.filterNot { e -> e.fileRel == file },
            )
        }
        return registry.trashNote(file, null)
    }

    /**
     * Deletes the item [id] with its subtree in one undoable edit, the
     * caret on the row above where it was. See [deletePageNode].
     */
    private fun deleteItem(id: LineId) = recordEdit(FrameKind.OTHER) {
        val doc = document ?: return@recordEdit
        val st = doc.stateFlow.value
        val row = st.lineIds.indexOf(id)
        if (row < 0) return@recordEdit
        val col = DocumentLayout.itemColumn(st.lines, row)
        if (col < 0) return@recordEdit
        val folder = doc.folderOf(id)
        doc.deleteRows(row, DocumentLayout.subtreeEnd(st.lines, row, col))
        val lines = doc.stateFlow.value.lines
        val r = (row - 1).coerceIn(0, lines.lastIndex)
        patch {
            fun List<FileHistoryEntry>.outside() =
                if (folder == null) this else filterNot { e -> e.fileRel.startsWith("$folder/") }
            it.copy(
                cursorRow = r, cursorCol = lines[r].length, anchorRow = null, anchorCol = null,
                fileHistory = it.fileHistory.outside(), fileForward = it.fileForward.outside(),
            )
        }
    }

    /**
     * Sorts the direct children of the node the pane shows — the zoom
     * target's, or the outline's top-level items — by name: their visible
     * title ([FolderName.plainTextOf]) in [FolderContents.naturalCompare]
     * order (case-insensitive, `Note 2` before `Note 10`), or the reverse
     * of that order when [reverse] is set; untitled items go last either
     * way. Not recursive: each child moves with its whole subtree, whose
     * own order is kept. Rows keep their ids, so folder-backed items keep
     * their folders and the next save only rewrites the outline's order.
     *
     * One undoable edit; the caret stays on its line. A no-op in Markdown
     * mode and when the children are already in order.
     *
     * Called by the web command palette ("Sort children by name" /
     * "Sort children by name, reversed").
     *
     * @param reverse `true` for Z–A order instead of A–Z.
     */
    fun sortChildrenByName(reverse: Boolean = false) = recordEdit(FrameKind.OTHER) {
        val s = _stateFlow.value
        if (!s.isLoaded || s.isMarkdownMode) return@recordEdit
        val doc = document ?: return@recordEdit
        val docState = doc.stateFlow.value
        val lines = docState.lines
        if (lines.isEmpty()) return@recordEdit
        val zoom = zoomInfoOf(s.copy(documentState = docState))
        val start = zoom?.let { DocumentLayout.itemLastRow(lines, it.zoomRow) + 1 } ?: 0
        val end = zoom?.endRowInclusive ?: lines.lastIndex
        if (start >= end) return@recordEdit

        // Each child item with its subtree, in document order.
        val children = ArrayList<IntRange>()
        var row = start
        while (row <= end) {
            val col = DocumentLayout.itemColumn(lines, row)
            if (col < 0) return@recordEdit
            val last = minOf(DocumentLayout.subtreeEnd(lines, row, col), end)
            children += row..last
            row = last + 1
        }
        val keyOf = { range: IntRange ->
            FolderName.plainTextOf(SubtreeCodec.itemTitleOf(lines, range.first)).trim()
        }
        // Children the privacy mode hides stay where they are; the visible
        // ones are sorted into the remaining places.
        val hidden = hiddenRowsIn(s.copy(documentState = docState))
        val isHiddenChild = { range: IntRange -> PrivacyLayout.isHidden(hidden, range.first) }
        val sortedVisible = children.filterNot(isHiddenChild).sortedWith { a, b ->
            val ka = keyOf(a)
            val kb = keyOf(b)
            when {
                ka.isEmpty() != kb.isEmpty() -> if (ka.isEmpty()) 1 else -1
                reverse -> FolderContents.naturalCompare(kb, ka)
                else -> FolderContents.naturalCompare(ka, kb)
            }
        }.iterator()
        val sorted = children.map { if (isHiddenChild(it)) it else sortedVisible.next() }
        if (sorted == children) return@recordEdit

        val order = (0 until start) + sorted.flatMap { it.toList() } + ((end + 1)..lines.lastIndex)
        val caretId = docState.lineIds.getOrNull(s.cursorRow)
        doc.replaceContent(order.map { lines[it] }, order.map { docState.lineIds[it] }, docState.unloadedRefIds)
        val newIds = doc.stateFlow.value.lineIds
        val caretRow = caretId?.let { newIds.indexOf(it) }?.takeIf { it >= 0 } ?: s.cursorRow
        patch { it.copy(anchorRow = null, anchorCol = null, cursorRow = caretRow) }
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
            val col = DocumentLayout.itemColumn(docState.lines, r)
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
     *
     * First lets go of the pending rows the pane holds
     * ([releasePendingRowsIfAny]), so every way of leaving a page also
     * leaves an untouched Today preparation.
     */
    private fun cleanupEmptyPlaceholderIfAny() {
        releasePendingRowsIfAny()
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
            // A bullet opened above a block at the top of the document
            // (`exitBlockAbove`): nothing to merge into — drop the row.
            if (docState.lines.size > 1) doc.deleteRows(0, 0)
            patch { it.copy(pendingLeafZoomChild = null) }
            return
        }
        val prevLineLen = docState.lines[row - 1].length
        doc.delete(row - 1, prevLineLen, row, line.length)
        patch { it.copy(pendingLeafZoomChild = null) }
    }

    /**
     * Lets go of [State.pendingRowsGroup], if any: the document removes
     * the group's rows once no pane holds it and nobody typed in them
     * ([Document.releasePendingRows]). The caret keeps pointing at the
     * same text: rows below the removed ones move up with it.
     *
     * Called by [cleanupEmptyPlaceholderIfAny], i.e. on every zoom change,
     * file switch and pane close.
     */
    private fun releasePendingRowsIfAny() {
        val s = _stateFlow.value
        val group = s.pendingRowsGroup ?: return
        val doc = document
        if (doc == null) {
            patch { it.copy(pendingRowsGroup = null) }
            return
        }
        val caretId = s.documentState?.lineIds?.getOrNull(s.cursorRow)
        doc.releasePendingRows(group)
        patch {
            val ids = it.documentState?.lineIds ?: return@patch it.copy(pendingRowsGroup = null)
            val row = caretId?.let { id -> ids.indexOf(id) }?.takeIf { r -> r >= 0 }
            it.copy(
                pendingRowsGroup = null,
                cursorRow = row ?: it.cursorRow.coerceAtMost(ids.lastIndex),
                anchorRow = null,
                anchorCol = null,
            )
        }
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
     *  single backspace deletes the entire image syntax atomically; the
     *  same goes for an HTML character reference such as `&amp;`, which
     *  displays as one character.
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
        // An HTML character reference (`&amp;`) shows as one character;
        // delete all of it rather than leave a half reference behind.
        val entityStart = InlineMarkdownTokenizer.entityStartBefore(line, col)
        if (entityStart != null && entityStart >= DocumentLayout.caretStartCol(line)) {
            recordEdit(FrameKind.OTHER) {
                commitPlaceholderIfAny()
                currentDocument().delete(row, entityStart, row, col)
                patch { it.copy(cursorCol = entityStart, anchorRow = null, anchorCol = null) }
            }
            return true
        }
        return false
    }

    /**
     * See [TextEditingViewModel.indentLine]. Ancestor reveal (un-collapsing
     * the bullet the row lands under) happens inside the slice, atomically
     * with the cursor move — see `TextEditingViewModel.ancestorIdsAt`.
     *
     * When the new parent is a folded folder-backed bullet
     * ([TextEditingViewModel.foldedRefIndentParent]) it is unfolded first,
     * as a chevron click would, loading its children; the caret and
     * selection are then re-found by line id and the rows indented, so
     * they land as its last children. Only the indent is undoable.
     */
    fun indentLine(amount: Int = TAB_SIZE) {
        val doc = document
        val parent = textEditing.foldedRefIndentParent(amount)
        if (parent == null || doc == null) {
            recordEdit(FrameKind.OTHER) {
                commitPlaceholderIfAny()
                textEditing.indentLine(amount)
            }
            return
        }
        val s = _stateFlow.value
        val ids = s.documentState?.lineIds ?: return
        val cursorId = ids.getOrNull(s.cursorRow) ?: return
        val anchorId = s.anchorRow?.let { ids.getOrNull(it) }
        val cursorCol = s.cursorCol
        val anchorCol = s.anchorCol
        scope.launch {
            expandForPane(doc, parent)
            val now = doc.stateFlow.value.lineIds
            val row = now.indexOf(cursorId)
            if (row < 0) return@launch
            val anchorRow = anchorId?.let { now.indexOf(it) }?.takeIf { it >= 0 }
            patch {
                it.copy(
                    cursorRow = row, cursorCol = cursorCol,
                    anchorRow = anchorRow, anchorCol = if (anchorRow != null) anchorCol else null,
                )
            }
            recordEdit(FrameKind.OTHER) {
                commitPlaceholderIfAny()
                textEditing.indentLine(amount)
            }
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

    /** See [TextEditingViewModel.insertSearchNode]. Undoable. */
    fun insertSearchNode() {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertSearchNode()
        }
    }

    /** See [TextEditingViewModel.insertBlock]. Undoable. */
    fun insertBlock() {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertBlock()
        }
    }

    /**
     * "Insert Markdown file as block": a block holding [markdownText] —
     * the text of a `.md` file the user picked — placed like
     * [insertBlock] places an empty one ([NoteConversion.blockRowContentsOf]:
     * verbatim, code fences made code rows, leading / trailing blank lines
     * dropped). The file itself is not touched or linked. A no-op in
     * Markdown mode. Undoable.
     *
     * Called by the web view's palette command after its file chooser.
     *
     * @param markdownText The file's raw text.
     */
    fun insertMarkdownAsBlock(markdownText: String) {
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            textEditing.insertBlock(NoteConversion.blockRowContentsOf(markdownText))
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

    /**
     * "Convert block to nodes" (palette): every block in the page's whole
     * tree becomes bullets, one per line
     * ([TextEditingViewModel.convertBlocksIn]). See [editBlocksUnderPage].
     */
    fun convertBlockToNodes() = editBlocksUnderPage { start, end, skip ->
        textEditing.convertBlocksIn(start, end, skip)
    }

    /**
     * TEMPORARY ("Clean up blocks (temporary)", palette): every block in
     * the page's whole tree loses the imported-note frame — `---` lines
     * at the top, `---` and the `![[…]]` embed at the bottom — and stays
     * a block ([TextEditingViewModel.cleanUpBlocksIn]). See
     * [editBlocksUnderPage].
     */
    fun cleanUpBlocks() = editBlocksUnderPage { start, end, skip ->
        textEditing.cleanUpBlocksIn(start, end, skip)
    }

    /**
     * Loads every folder-backed item under the page (the zoom target's
     * subtree, or the whole outline) level by level — so those items end
     * up unfolded — then runs [edit] on the page's rows (first row, last
     * row, and which rows to skip: those the privacy mode hides) as one
     * undoable edit. Mirrors are not followed, so mirrored nodes
     * elsewhere are left alone. Stops if the pane switches file while
     * loading; a no-op in Markdown mode and on read-only pages.
     *
     * Called by [convertBlockToNodes] and [cleanUpBlocks].
     */
    private fun editBlocksUnderPage(edit: (Int, Int, (Int) -> Boolean) -> Unit) {
        val s0 = _stateFlow.value
        if (!s0.isLoaded || s0.isMarkdownMode || s0.isReadOnlyPage) return
        val doc = document ?: return
        scope.launch {
            while (document === doc) {
                val cur = _stateFlow.value
                val unloaded = doc.stateFlow.value.unloadedRefIds
                val items = foldableItemsUnderPage()
                val toAcquire = items.filter { id ->
                    doc.isPromotedRef(id) && !doc.isMirror(id) && id !in cur.expandedRefIdsLocal &&
                        (id in unloaded || id in cur.collapsedIds)
                }
                val toOpen = items.filter { !doc.isMirror(it) }.toSet()
                patch {
                    it.copy(
                        collapsedIds = it.collapsedIds - toOpen,
                        zoomUnfoldedIds = it.zoomUnfoldedIds - toOpen,
                        expandedRefIdsLocal = it.expandedRefIdsLocal + toAcquire,
                        seenLineIds = it.seenLineIds + (it.documentState?.lineIds ?: emptyList()),
                    )
                }
                if (toAcquire.isEmpty()) break
                for (id in toAcquire) doc.acquireExpansion(id)
            }
            if (document !== doc) return@launch
            patch { it }
            val s = _stateFlow.value
            val lines = s.documentState?.lines ?: return@launch
            if (lines.isEmpty()) return@launch
            val zoom = zoomInfoOf(s)
            val start = zoom?.startRow ?: 0
            val end = zoom?.endRowInclusive ?: lines.lastIndex
            val hidden = hiddenRowsIn(s)
            recordEdit(FrameKind.OTHER) { edit(start, end) { PrivacyLayout.isHidden(hidden, it) } }
        }
    }

    /**
     * "Convert to node" on the Markdown note [noteRel] in the folder
     * contents list: appends a bullet titled with the note's name, holding
     * one block with the note's Markdown ([NoteConversion.nodeRowsFor]),
     * as the last child of the node the list belongs to — the zoom target,
     * or the outline's root level. The note's folder is that node's folder,
     * so the note's content stays where it was. One undoable edit; the
     * caret lands on the new bullet. The note itself is untouched (see
     * [trashConvertedNote]).
     *
     * Called by the web view's folder-entry menu.
     *
     * @return The new bullet's id, or `null` when nothing was inserted
     *   (Markdown mode, not loaded, the note is gone).
     */
    suspend fun convertNoteToNode(noteRel: String): LineId? {
        if (!noteRel.endsWith(NoteRepository.NOTE_EXTENSION)) return null
        val text = registry.readNoteText(noteRel) ?: return null
        val s = _stateFlow.value
        val doc = document ?: return null
        if (!s.isLoaded || s.isMarkdownMode || !doc.bulletsOnly) return null
        var at = -1
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            val (row, indent) = lastChildSlot(_stateFlow.value)
            at = row
            val rows = NoteConversion.nodeRowsFor(noteRel, text, indent)
            rows.forEachIndexed { i, line -> doc.insertLine(row + i, line) }
            placeCaretAtEndOf(row, rows.first())
        }
        return if (at < 0) null else doc.stateFlow.value.lineIds.getOrNull(at)
    }

    /**
     * What [convertFolderToNode] did.
     *
     * @property nodeId The new bullet.
     * @property convertedNotes Each `.md` note a recursive conversion copied
     *   into a node → that node's folder (empty otherwise). The notes stay
     *   in place until [trashConvertedNotes].
     */
    data class FolderConversion(val nodeId: LineId, val convertedNotes: Map<String, String>)

    /**
     * "Convert to node" on the folder [folderRel] in the folder contents
     * list: appends a bullet titled with the folder's name as the last
     * child of the node the list belongs to (like [convertNoteToNode]) and
     * saves, which makes the folder that bullet's node — a bullet named
     * like a folder that holds something adopts it (see
     * [NoteRepository.save]). The folder's files then show in the node's
     * own folder contents list, and an outline already in the folder
     * becomes its children.
     *
     * With [recursive], everything under the folder is first turned into
     * nodes on disk — subfolders and `.md` notes, all the way down
     * ([DocumentRegistry.convertFolderTree]).
     *
     * The bullet is one undoable edit; the recursive disk writes are not
     * undone with it. The caret lands on the new bullet.
     *
     * Called by the web view's folder-entry menu.
     *
     * @param folderRel A folder listed in this pane's folder contents list.
     * @return What was done, or `null` when nothing was inserted (Markdown
     *   mode, not loaded, the pane moved to another file meanwhile).
     */
    suspend fun convertFolderToNode(folderRel: String, recursive: Boolean): FolderConversion? {
        val doc = document ?: return null
        if (!_stateFlow.value.isLoaded || _stateFlow.value.isMarkdownMode || !doc.bulletsOnly) return null
        val notes = if (recursive) registry.convertFolderTree(folderRel) else emptyMap()
        if (document !== doc || !_stateFlow.value.isLoaded) return null
        val title = FolderName.decode(folderRel.substringAfterLast('/')).toNfc()
        var at = -1
        recordEdit(FrameKind.OTHER) {
            commitPlaceholderIfAny()
            val (row, indent) = lastChildSlot(_stateFlow.value)
            at = row
            val line = " ".repeat(indent) + "* " + title
            doc.insertLine(row, line)
            placeCaretAtEndOf(row, line)
        }
        if (at < 0) return null
        val id = doc.stateFlow.value.lineIds.getOrNull(at) ?: return null
        doc.flush()
        return FolderConversion(id, notes)
    }

    /**
     * Moves the notes a recursive [convertFolderToNode] copied into nodes
     * to the trash, pointing links to each at its node's folder
     * ([DocumentRegistry.trashNote]).
     *
     * Called by the web view when the user confirms deleting the originals.
     *
     * @param notes [FolderConversion.convertedNotes].
     * @return One line per note that was kept, saying why; empty when all
     *   went to the trash.
     */
    suspend fun trashConvertedNotes(notes: Map<String, String>): List<String> {
        registry.flushAll()
        return notes.mapNotNull { (noteRel, folder) ->
            registry.trashNote(noteRel, folder)?.let { "${NoteRepository.displayNameOf(noteRel)}: $it" }
        }
    }

    /**
     * Where a new last child of the node this pane's folder contents list
     * belongs to goes: the row to insert at and its item column — after
     * the zoom target's subtree, or at the end of the outline.
     */
    private fun lastChildSlot(state: State): Pair<Int, Int> {
        val zoom = zoomInfo(state) ?: return state.lines.size to 0
        return DocumentLayout.subtreeEnd(state.lines, zoom.zoomRow, zoom.zoomIndent) + 1 to
            zoom.zoomIndent + TAB_SIZE
    }

    /** Puts the caret at the end of the just-inserted [line] on [row], with no selection. */
    private fun placeCaretAtEndOf(row: Int, line: String) {
        patch {
            it.copy(
                cursorRow = row, cursorCol = line.length,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Moves the note [noteRel] to the trash after [convertNoteToNode] made
     * [nodeId] from it: saves first (so the node has its folder), then
     * [DocumentRegistry.trashNote] points links at that folder.
     *
     * Called by the web view when the user confirms deleting the original.
     *
     * @return `null` on success, else why the note was kept.
     */
    suspend fun trashConvertedNote(noteRel: String, nodeId: LineId): String? {
        val doc = document ?: return "The outline is no longer open."
        doc.flush()
        return registry.trashNote(noteRel, doc.folderOf(nodeId))
    }

    /**
     * Moves the file [fileRel] — a note, image, drawing or any other file
     * in the folder contents list — to the trash
     * ([DocumentRegistry.trashNote]); links to it are kept and show as
     * broken. Not undoable here; the file stays in `.trash`.
     *
     * Called by the web view's folder contents row menu ("Move to Trash")
     * once the user confirms.
     *
     * @return `null` on success, else why the file was kept.
     */
    suspend fun trashFile(fileRel: String): String? = registry.trashNote(fileRel, null)

    /** See [TextEditingViewModel.exitBlock]. Undoable. */
    fun exitBlock() {
        recordEdit(FrameKind.OTHER) { textEditing.exitBlock() }
    }

    /**
     * See [TextEditingViewModel.moveDownOutOfBlock]. Undoable when it
     * leaves the block.
     *
     * @return `true` when it consumed the key.
     */
    fun moveDownOutOfBlock(): Boolean {
        var consumed = false
        recordEdit(FrameKind.OTHER) { consumed = textEditing.moveDownOutOfBlock() }
        return consumed
    }

    /**
     * See [TextEditingViewModel.moveUpOutOfBlock]. Undoable when it
     * leaves the block.
     *
     * @return `true` when it consumed the key.
     */
    fun moveUpOutOfBlock(): Boolean {
        var consumed = false
        recordEdit(FrameKind.OTHER) { consumed = textEditing.moveUpOutOfBlock() }
        return consumed
    }

    /**
     * Leaves the block the caret is in onto a new bullet above it
     * (Shift-Cmd-Enter). Undoable. See [TextEditingViewModel.exitBlockAbove].
     */
    fun exitBlockAbove() {
        recordEdit(FrameKind.OTHER) { textEditing.exitBlockAbove() }
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
        val indent = DocumentLayout.itemColumn(lines, row)
        if (indent < 0) return null
        return row..DocumentLayout.subtreeEnd(lines, row, indent)
    }

    /**
     * Where a drag of rows [sourceStart]..[sourceEnd] would land when the
     * pointer is over [hoverRow] — in its upper half when [insertAbove] —
     * having moved [levelDelta] indent levels sideways since the press.
     *
     * - The drop never splits a block (it snaps to the block's edge) and
     *   never lands inside a folded item's hidden subtree (it goes after
     *   it). Zoomed in, it stays inside the zoom region, below a zoomed
     *   block's own rows. Rows a privacy mode hides after the item above
     *   are passed over: the drop goes before them.
     * - The level is any from that of the row below the drop point — so
     *   the rows below never become the moved item's children — down to
     *   one level under the visible item above it (that item's own level
     *   when it is folded). [levelDelta] picks from that range, starting
     *   at the moved rows' current level: drag right to nest deeper,
     *   left to move out.
     *
     * Called by the web view on every pointer move of a drag, to draw the
     * drop line, and on release, to pass the result to [moveLineRange].
     *
     * @return `null` when there is no valid drop there (inside the dragged
     *   rows, outside the zoom).
     */
    fun dropTarget(
        sourceStart: Int,
        sourceEnd: Int,
        hoverRow: Int,
        insertAbove: Boolean,
        levelDelta: Int,
    ): DropTarget? {
        val s = _stateFlow.value
        val docState = s.documentState ?: return null
        val doc = document ?: return null
        val lines = docState.lines
        val ids = docState.lineIds
        if (hoverRow !in lines.indices || sourceStart !in lines.indices) return null
        val zoom = zoomInfoOf(s)
        val zoomBody = zoom?.let { z -> BlockLayout.rangeAt(lines, z.zoomRow)?.takeIf { it.first == z.zoomRow } }
        val regionStart = zoomBody?.let { it.last + 1 } ?: zoom?.startRow ?: 0
        val regionEnd = zoom?.endRowInclusive ?: lines.lastIndex
        val floor = zoom?.let { it.zoomIndent + TAB_SIZE } ?: 0

        var drop = if (insertAbove) hoverRow else hoverRow + 1
        BlockLayout.rangeAt(lines, hoverRow)?.let { b -> drop = if (insertAbove) b.first else b.last + 1 }
        // Over the dragged rows themselves: stay put, change level only.
        if (hoverRow in sourceStart..sourceEnd) drop = sourceStart
        drop = drop.coerceIn(regionStart, regionEnd + 1)
        val visible = visibleRowsIn(s, regionStart, regionEnd)
            .filter { it !in sourceStart..sourceEnd }
        // The item above the drop point, and whether its children show.
        val aboveRow = visible.lastOrNull { it < drop }
        val aboveItem = aboveRow?.let { BlockLayout.rangeAt(lines, it)?.first ?: it }
        var maxCol = floor
        if (aboveItem != null) {
            val col = DocumentLayout.itemColumn(lines, aboveItem)
            // Rows the privacy mode hides between the item above and the
            // drop point are not its own: drop before them, so the moved
            // rows never land under (or above) an item they cannot see.
            hiddenRowsIn(s)?.let { hidden ->
                val aboveEnd = DocumentLayout.subtreeEnd(lines, aboveItem, col)
                if (drop > aboveEnd + 1 && ((aboveEnd + 1) until drop).any { hidden[it] }) drop = aboveEnd + 1
            }
            val id = ids.getOrNull(aboveItem)
            val folded = id != null && (id in s.collapsedIds ||
                (doc.isPromotedRef(id) && id !in s.expandedRefIdsLocal))
            if (folded) drop = maxOf(drop, DocumentLayout.subtreeEnd(lines, aboveItem, col) + 1)
            maxCol = if (folded) col else col + TAB_SIZE
        }
        if (drop in (sourceStart + 1)..sourceEnd) return null
        val nextRow = (drop..regionEnd).firstOrNull { it !in sourceStart..sourceEnd }
        val minCol = maxOf(floor, nextRow?.let { DocumentLayout.indentOf(lines[it]) } ?: floor)
            .coerceAtMost(maxOf(maxCol, floor))
        val sourceCol = DocumentLayout.indentOf(lines[sourceStart])
        val col = (sourceCol + levelDelta * TAB_SIZE).coerceIn(minCol, maxOf(minCol, maxCol))
        return DropTarget(drop, col)
    }

    /**
     * Result of [dropTarget].
     *
     * @property insertBeforeRow Row the moved rows are inserted before.
     * @property indent Column the moved rows' first row gets.
     */
    data class DropTarget(val insertBeforeRow: Int, val indent: Int)

    /**
     * Move the contiguous row range `[fromStartRow..fromEndRow]` to land
     * before [insertBeforeRow] with [targetIndent] applied to the top
     * of the moved block (the rest shift with it). Dropped where the rows
     * already are, only their level changes. [dropTarget] computes a
     * valid [insertBeforeRow] / [targetIndent] pair for a drag.
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
        val sourceTopIndent = leadingSpaceCount(lines0[src0])
        val shift = targetIndent - sourceTopIndent
        if (drop == src0 || drop == src1 + 1) {
            // Dropped where it already is: only the level can change.
            if (shift == 0) return@recordEdit
            for (r in src0..src1) {
                if (shift > 0) doc.insertText(r, 0, " ".repeat(shift))
                else doc.delete(r, 0, r, minOf(-shift, leadingSpaceCount(lines0[r])))
            }
            patch { it.copy(anchorRow = null, anchorCol = null, cursorRow = src0,
                cursorCol = DocumentLayout.caretStartCol(doc.stateFlow.value.lines[src0])) }
            return@recordEdit
        }
        if (src0 == 0 && src1 == lines0.lastIndex) return@recordEdit

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

    /**
     * "Up": one step up the hierarchy from what the pane shows. Zoomed
     * in, it zooms to the nearest ancestor (or clears the zoom at a
     * top-level item); otherwise it opens the parent node's outline
     * ([parentFileOf]) — the pane may be showing a node's own file, a
     * `.md` note or an image. Nothing at the root outline.
     *
     * Called by the web view for Ctrl-Cmd-Up. (The pane header's
     * breadcrumb offers the same step as its parent segment.)
     */
    fun navigateUp() {
        val s = _stateFlow.value
        if (s.zoomedLineId != null && zoomInfoOf(s) != null) {
            zoomTo(bulletAncestors(s).lastOrNull()?.lineId)
            return
        }
        val parent = parentFileOf(s.activeFileRel) ?: return
        navigateToVaultFile(parent)
    }

    /**
     * "Home": back to the root outline, unzoomed. Clears the zoom and,
     * when the pane shows another file, opens [rootFileName]; both push
     * history, so Back returns. Nothing when already there.
     *
     * Called by the web view for Shift-Ctrl-Cmd-Up. (The pane header
     * breadcrumb's `Home` segment has the same effect.)
     */
    fun navigateHome() {
        val s = _stateFlow.value
        if (s.zoomedLineId != null) zoomTo(null)
        if (s.activeFileRel.isNotEmpty() && s.activeFileRel != rootFileName) navigateToVaultFile(rootFileName)
    }

    /** `true` when [navigateUp] would go anywhere from [state]. */
    fun canNavigateUp(state: State = _stateFlow.value): Boolean =
        (state.zoomedLineId != null && zoomInfoOf(state) != null) || parentFileOf(state.activeFileRel) != null

    /**
     * The node outline one level up from [fileRel], following the
     * folder-per-bullet layout, or `null` for the root outline (and an
     * empty path):
     *
     *  - `Recipes/Pasta/_node.md` → `Recipes/_node.md`
     *  - `Recipes/_node.md`       → [rootFileName]
     *  - `Recipes/notes.md`, `Recipes/pic.png` → `Recipes/_node.md`
     *  - `notes.md` (at the vault root) → [rootFileName]
     *
     * Path-based; does not check that the parent outline exists (a
     * folder without one is still a node, just with no bullets yet).
     */
    fun parentFileOf(fileRel: String): String? {
        if (fileRel.isEmpty() || fileRel == rootFileName) return null
        val folder = if (NoteRepository.isOutlineFile(fileRel)) {
            NoteRepository.folderOfOutline(fileRel).substringBeforeLast('/', "")
        } else {
            fileRel.substringBeforeLast('/', "")
        }
        return if (folder.isEmpty()) rootFileName else NoteRepository.outlineFileOf(folder)
    }

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
        dropHiddenHistory()
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
        dropHiddenHistory()
        val s = _stateFlow.value
        if (s.zoomForward.isNotEmpty()) {
            zoomNavigation.zoomForward()
            return
        }
        if (s.fileForward.isNotEmpty()) {
            scope.launch { fileForward() }
        }
    }

    /**
     * Drops the Back / Forward entries that point into what the app's
     * privacy mode hides — zooms into hidden items, hidden files — so a
     * step skips them. Called before every history step.
     */
    private fun dropHiddenHistory() {
        val s = _stateFlow.value
        if (!s.privacy.isActive) return
        fun zooms(stack: List<LineId?>) = stack.filter { it == null || !isRowIdHidden(s, it) }
        val zoomBack = zooms(s.zoomHistory)
        val zoomFwd = zooms(s.zoomForward)
        val fileBack = s.fileHistory.filterNot(::isEntryHidden)
        val fileFwd = s.fileForward.filterNot(::isEntryHidden)
        if (zoomBack.size != s.zoomHistory.size || zoomFwd.size != s.zoomForward.size ||
            fileBack.size != s.fileHistory.size || fileFwd.size != s.fileForward.size
        ) {
            _stateFlow.value = s.copy(zoomHistory = zoomBack, zoomForward = zoomFwd, fileHistory = fileBack, fileForward = fileFwd)
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
        recallAfterLoad(previous.fileRel)
        restoreZoom(previous.zoomTitlePath)
        restoreZoomStacks(previous)
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
        recallAfterLoad(next.fileRel)
        restoreZoom(next.zoomTitlePath)
        restoreZoomStacks(next)
    }

    /**
     * The [FileHistoryEntry] for where this pane is right now: the active
     * file plus the title path of the zoom target, if any, and the zoom
     * history that led there. Pushed onto the history by every
     * cross-file navigation.
     */
    private fun currentHistoryEntry(): FileHistoryEntry {
        val s = _stateFlow.value
        val lines = s.documentState?.takeIf { it.isLoaded }?.lines
        val ids = s.documentState?.lineIds
        fun pathsOf(stack: List<LineId?>): List<List<String>> {
            if (lines == null || ids == null) return emptyList()
            return stack.mapNotNull { id ->
                if (id == null) emptyList() else ids.indexOf(id).takeIf { it >= 0 }?.let { titlePathOfRow(lines, it) }
            }
        }
        return historyEntryOf(s).copy(zoomBack = pathsOf(s.zoomHistory), zoomForward = pathsOf(s.zoomForward))
    }

    /**
     * After a history step landed on [entry] (and [restoreZoom] zoomed
     * where it was), puts back the zoom history and forward stacks it was
     * left with ([FileHistoryEntry.zoomBack] / [FileHistoryEntry.zoomForward]),
     * each title path resolved in the document as it is now; one that no
     * longer resolves (renamed, deleted, inside a node not loaded) is left
     * out.
     */
    private fun restoreZoomStacks(entry: FileHistoryEntry) {
        if (entry.zoomBack.isEmpty() && entry.zoomForward.isEmpty()) return
        val doc = document ?: return
        val docState = doc.stateFlow.value
        if (!docState.isLoaded || !NoteRepository.isOutlineFile(_stateFlow.value.activeFileRel)) return
        fun idsOf(paths: List<List<String>>): List<LineId?> {
            val out = mutableListOf<LineId?>()
            for (path in paths) {
                if (path.isEmpty()) out += null
                else findLineIdByTitlePathIn(docState.lines, docState.lineIds, path)?.let { out += it }
            }
            return out
        }
        patch { it.copy(zoomHistory = idsOf(entry.zoomBack), zoomForward = idsOf(entry.zoomForward)) }
    }

    /** The location of [s]: its file and zoom title path. See [currentHistoryEntry]. */
    private fun historyEntryOf(s: State): FileHistoryEntry {
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
                        zoomUnfoldedIds = it.zoomUnfoldedIds + id,
                    )
                }
                doc.acquireExpansion(id)
            } else if (!isLast) {
                // Held already but maybe still loading (a fold remembered
                // open): the next level is found among its children.
                doc.awaitChildrenLoaded(id)
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

    /**
     * The file part of this pane's breadcrumb: the vault root, each node
     * folder down to the open file, then the file itself — see
     * [fileBreadcrumbOf]. The web pane header follows it with
     * [bulletAncestors] and the zoom target.
     */
    fun fileBreadcrumb(state: State = _stateFlow.value): List<FileCrumb> =
        fileBreadcrumbOf(state.activeFileRel, rootFileName)

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
        _stateFlow.value = reconcile(
            current.copy(
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                anchorRow = if (collapsed) null else anchorRow,
                anchorCol = if (collapsed) null else anchorCol,
                pendingInlineStyles = pending,
            )
        )
        // A click or arrow off an untouched placeholder removes it.
        dropAbandonedPlaceholder()
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

    /** See [MarkdownStyleViewModel.codeBlockState]. */
    fun codeBlockState(): Boolean? = markdownStyle.codeBlockState()

    // ------------------------------------------------------ folder contents

    /**
     * The vault-relative folder of the node this pane is showing — the
     * folder whose contents list is drawn under the bullets and where
     * "New Markdown file" creates its note — or `null` when there is none:
     *
     * - Zoomed into a folder-backed bullet: that bullet's folder.
     * - Zoomed into a leaf bullet (no folder yet): `null`.
     * - Not zoomed, viewing a node outline: the outline's folder (`""`
     *   for the vault root's `_node.md`).
     * - Viewing a `.md` note or an image: `null` — the pane is on a file,
     *   not a node, so its siblings are not listed.
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
        val zoomed = state.zoomedLineId
        val folder = when {
            zoomed != null -> document?.folderOf(zoomed)
            NoteRepository.isOutlineFile(file) -> NoteRepository.folderOfOutline(file)
            else -> null
        } ?: return null
        return if (NoteRepository.isInTrash(folder)) null else folder
    }

    /**
     * The page this pane is on in 3D mode's page space, with the pages
     * around it ([SpacePage]): its child nodes and their children, read
     * from the open outline's rows ([PageSpaceModel.childrenOf]) — so an
     * indent that gives a bullet its first child shows a new page at once —
     * and from cached node listings ([DocumentRegistry.requestLinkPreview])
     * for folders that are not loaded, which this asks for (their pages
     * fill in once [DocumentRegistry.linkPreviewsFlow] has them).
     *
     * The page's key is its node folder ([currentNodeFolder]), so a node is
     * the same page whether the pane zoomed into it or opened its outline;
     * a zoomed leaf is keyed by its row, a note, image, drawing or web page
     * by its file, and those have no child pages.
     *
     * Leaves out everything the pane's privacy mode hides ([State.privacy]):
     * hidden rows ([hiddenRowsIn]) and hidden listing items, as link
     * previews do.
     *
     * Called by the web `PageSpaceView` on every state emission while 3D
     * mode is on; read-only.
     *
     * @return `null` while the pane is loading.
     */
    fun spacePageOf(state: State = _stateFlow.value): SpacePage? {
        val file = state.activeFileRel
        if (state.isFileView || (state.isLoaded && state.isMarkdownMode)) {
            return SpacePage(
                key = PageSpaceKeys.ofFile(file),
                title = NoteRepository.displayNameOf(file),
                parentKey = PageSpaceKeys.ofFolder(file.substringBeforeLast('/', "")),
                children = emptyList(),
            )
        }
        val docState = state.documentState?.takeIf { it.isLoaded } ?: return null
        val doc = document?.takeIf { it.fileRel == file } ?: return null
        val zoom = zoomInfoOf(state)
        val zoomedId = state.zoomedLineId
        val folder = currentNodeFolder(state)
        val key = folder?.let(PageSpaceKeys::ofFolder)
            ?: if (zoom != null && zoomedId != null) PageSpaceKeys.ofLine(file, zoomedId) else PageSpaceKeys.ofFile(file)
        val parentKey = when {
            folder != null -> if (folder.isEmpty()) null else PageSpaceKeys.ofFolder(folder.substringBeforeLast('/', ""))
            zoom != null -> PageSpaceKeys.ofFolder(doc.storageFolderOf(zoom.zoomRow))
            else -> null
        }
        val lines = docState.lines
        val start = zoom?.let { DocumentLayout.itemLastRow(lines, it.zoomRow) + 1 } ?: 0
        val end = zoom?.endRowInclusive ?: lines.lastIndex
        // What the privacy mode hides is neither a page nor a preview row:
        // hidden rows of this outline, and hidden items of node listings.
        val hidden = hiddenRowsIn(state)
        val filter = state.privacy
        val children = PageSpaceModel.childrenOf(
            lines = lines,
            lineIds = docState.lineIds,
            startRow = start,
            endRow = end,
            fileRel = file,
            folderOf = { doc.folderOf(it) },
            unloaded = docState.unloadedRefIds,
            previewOf = { folder ->
                val all = registry.requestLinkPreview(folder)
                if (all == null || !filter.isActive) all else all.filterNot { previewItemHidden(folder, it, filter) }
            },
            hidden = hidden,
        )
        val title = when {
            zoom != null -> PageSpaceModel.plainTitle(zoom.titleText)
            else -> NoteRepository.displayNameOf(file)
        }
        val items = PageSpaceModel.itemsOf(lines, docState.lineIds, start, end, { doc.folderOf(it) }, hidden)
        return SpacePage(key, title, parentKey, children, items)
    }

    // ------------------------------------------------------- page memory

    /** Per-page view memory ([PageView]), by [locationKeyOf]; the oldest go past [PAGE_MEMORY_CAP]. */
    private val pageViews = LinkedHashMap<String, PageView>()

    /** Set while an intent places the caret itself, so [recallPage] stays out. */
    private var suppressRecall = false

    /**
     * The row [caret] is on in [lines]: its old row when that still holds
     * its line's text, else the nearest row that does, else the old row
     * (clamped).
     */
    private fun rowOfCaret(lines: List<String>, caret: Caret): Int {
        val old = caret.row.coerceIn(0, lines.lastIndex)
        if (lines.getOrNull(caret.row) == caret.lineText) return caret.row
        for (d in 1..lines.size) {
            if (lines.getOrNull(caret.row - d) == caret.lineText) return caret.row - d
            if (lines.getOrNull(caret.row + d) == caret.lineText) return caret.row + d
        }
        return old
    }

    /** The view's last reported scroll offset, and the page it was on. */
    private var lastScroll: Pair<String, Double>? = null

    /** Counter for [ScrollRestore.seq]. */
    private var scrollSeq = 0

    /** A page's key: its file and zoom title path. */
    private fun locationKeyOf(state: State): String = locationKeyOf(historyEntryOf(state))

    private fun locationKeyOf(location: FileHistoryEntry): String =
        location.fileRel + "\u0000" + location.zoomTitlePath.joinToString("\u0000")

    /**
     * Records how [state]'s page looks — its search and, when the view
     * reported one there, its scroll — before the pane leaves it. Called
     * on every file switch and zoom change.
     */
    private fun rememberPage(state: State) {
        if (!state.isLoaded && !state.isFileView) return
        val key = locationKeyOf(state)
        val scroll = lastScroll?.takeIf { it.first == key }?.second ?: pageViews[key]?.scrollTop
        val caret = state.lines.getOrNull(state.cursorRow)?.let { Caret(state.cursorRow, state.cursorCol, it) }
        pageViews.remove(key)
        pageViews[key] = PageView(scroll, state.searchQuery, state.searchReversed, caret)
        while (pageViews.size > PAGE_MEMORY_CAP) pageViews.remove(pageViews.keys.first())
    }

    /**
     * After arriving at a page: brings back its search (field text and
     * order) and asks the view to scroll where it was ([State.scrollRestore]).
     * A page never seen before keeps its search closed and starts at the
     * top, rather than at the offset the page before was scrolled to.
     *
     * @return `true` when it put the caret back (the page had one).
     */
    private fun recallPage(): Boolean {
        if (suppressRecall) return false
        val s = _stateFlow.value
        val view = pageViews[locationKeyOf(s)]
        view?.caret?.let { c ->
            val row = rowOfCaret(s.lines, c)
            patch {
                it.copy(
                    cursorRow = row, cursorCol = c.col.coerceIn(0, s.lines[row].length),
                    anchorRow = null, anchorCol = null, pendingInlineStyles = emptySet(),
                )
            }
        }
        val top = view?.scrollTop ?: 0.0
        patch { it.copy(scrollRestore = ScrollRestore(top, ++scrollSeq)) }
        if (view == null) return false
        val query = view.searchQuery ?: return view.caret != null
        if (view.searchReversed) patch { it.copy(searchReversed = true) }
        setSearchQuery(query)
        return view.caret != null
    }

    /**
     * The view's scroll offset on the current page, reported as the user
     * scrolls; kept for [rememberPage]. Not state: scrolling never emits.
     * Called by the web view's scroll listener.
     */
    fun noteScroll(top: Double) {
        lastScroll = locationKeyOf(_stateFlow.value) to top
    }

    /**
     * Puts back a persisted scroll offset [top] for the page [location]
     * after a restart: filed in the page memory, so arriving there scrolls
     * to it — a zoom being restored can land after this call (a folded
     * node loads first) — and, when the pane is there already, asked of
     * the view now ([State.scrollRestore]). Called by the web shell.
     */
    fun restoreScroll(top: Double, location: FileHistoryEntry) {
        val key = locationKeyOf(location)
        val s = _stateFlow.value
        pageViews[key] = pageViews[key]?.copy(scrollTop = top) ?: PageView(top, s.searchQuery, s.searchReversed)
        if (locationKeyOf(s) == key) patch { it.copy(scrollRestore = ScrollRestore(top, ++scrollSeq)) }
    }

    /**
     * Puts back a persisted caret for the page [location] after a restart,
     * like [restoreScroll]: filed in the page memory, and placed now when
     * the pane is there. Called by the web shell.
     */
    fun restoreCaret(caret: Caret, location: FileHistoryEntry) {
        val key = locationKeyOf(location)
        val s = _stateFlow.value
        pageViews[key] = pageViews[key]?.copy(caret = caret) ?: PageView(null, s.searchQuery, s.searchReversed, caret)
        if (locationKeyOf(s) == key && s.isLoaded) {
            val row = rowOfCaret(s.lines, caret)
            patch {
                it.copy(cursorRow = row, cursorCol = caret.col.coerceIn(0, s.lines[row].length), anchorRow = null, anchorCol = null)
            }
        }
    }

    /**
     * The caret to persist for this pane's page: row, column and its line's
     * text, or `null` before the page has loaded. Called by the web shell.
     */
    fun currentCaret(): Caret? {
        val s = _stateFlow.value
        if (!s.isLoaded) return null
        return s.lines.getOrNull(s.cursorRow)?.let { Caret(s.cursorRow, s.cursorCol, it) }
    }

    // ------------------------------------------------------------ search

    /** The running search of [setSearchQuery], if any. */
    private var searchJob: Job? = null

    /**
     * What a search from this pane covers — the tree on screen and never
     * anything above it: the zoomed node's folder, or the open outline's
     * folder ([currentNodeFolder]), each with everything under it; on a
     * `.md` note, only that note. `null` — nothing to search — when the
     * pane is zoomed into an item with no folder (a leaf: it has no tree
     * under it) or shows an image.
     */
    fun searchScope(state: State = _stateFlow.value): TextScope? {
        currentNodeFolder(state)?.let { return TextScope.Tree(it) }
        return if (state.isMarkdownMode) TextScope.File(state.activeFileRel) else null
    }

    /**
     * Opens the pane's search field, empty; a no-op when it is open.
     * Called by the web view's search button, Cmd-F and the palette.
     */
    fun openSearch() {
        if (_stateFlow.value.searchQuery != null) return
        patch { it.copy(searchQuery = "") }
        // Build the text index now (once per launch), so the first query
        // and the tag autocomplete don't wait on a vault scan.
        scope.launch { registry.textIndex.ensureBuilt() }
    }

    /**
     * Tags under this pane's tree ([searchScope]) starting with [prefix]
     * (`#` optional; `""` lists all), most used first, at most [max] — the
     * search field's autocomplete ([TextIndex.tags]). Empty while the
     * text index is still being built (it starts when the search opens).
     *
     * Called by the web search field as a `#tag` is typed.
     */
    fun tagSuggestions(prefix: String, max: Int = 8): List<TagCount> {
        val query = SearchQuery.parse(_stateFlow.value.searchQuery)
        val where = query.scopePath?.let { TextScope.Tree(it) } ?: searchScope() ?: return emptyList()
        return if (registry.textIndex.isBuilt) registry.textIndex.tags(where, prefix, max, registry.privacyFilter) else emptyList()
    }

    /**
     * Sets the search text and, after a short pause in typing, finds the
     * lines meeting it — a [SearchQuery] expression: words, `#tags`
     * (inherited from parent items), `AND` / `OR` / `NOT`, parentheses —
     * in the tree on screen ([searchScope]) or the one its `in:` names, in
     * the vault's text index ([DocumentRegistry.searchText]). The results land
     * in [State.searchHits]; a newer query cancels an older search. Opens
     * the search when closed. A no-op in the image view.
     *
     * Called by the web view on every keystroke in the search field, and
     * by the web shell to restore a pane's persisted search.
     */
    fun setSearchQuery(query: String) {
        if (_stateFlow.value.isFileView) return
        searchJob?.cancel()
        val parsed = SearchQuery.parse(query)
        patch { it.copy(searchQuery = query, isSearching = false).let { s -> if (parsed.isEmpty) s.copy(searchHits = emptyList(), searchTotal = 0) else s } }
        if (parsed.isEmpty) return
        val where = parsed.scopePath?.let { TextScope.Tree(it) } ?: searchScope()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (!registry.textIndex.isBuilt) patch { it.copy(isSearching = true) }
            val reversed = _stateFlow.value.searchReversed || parsed.reversed
            val result = if (where == null) TextSearchResult(emptyList(), 0) else registry.searchText(where, parsed.expr, reversed, tagSort = parsed.tagSort)
            if (_stateFlow.value.searchQuery != query) return@launch
            patch { it.copy(searchHits = result.hits, searchTotal = result.total, isSearching = false) }
        }
    }

    /** Closes the search field; the page shows again. Called by the web view (Escape, close button). */
    fun closeSearch() {
        if (_stateFlow.value.searchQuery == null) return
        searchJob?.cancel()
        patch {
            it.copy(searchQuery = null, searchHits = emptyList(), searchTotal = 0, isSearching = false, searchReversed = false)
        }
    }

    /**
     * Lists this pane's search results in reverse order ([reversed]) or
     * the normal one, and searches again. Pane state; reset when the search
     * closes. Called by the search field's ⇅ button, and by the web shell
     * to restore a pane's persisted search.
     */
    fun setSearchReversed(reversed: Boolean) {
        val s = _stateFlow.value
        if (s.searchReversed == reversed) return
        patch { it.copy(searchReversed = reversed) }
        s.searchQuery?.let(::setSearchQuery)
    }

    /**
     * Where a result lives, for the result list: the labels of the file
     * breadcrumb of [hit]'s file (`Home`, then each folder; a note's name
     * last). With [under] (a folder the hit is in, such as a search
     * node's [SearchNodeView.scopeFolder]), only the part below it — empty
     * for a hit in that folder's own outline.
     */
    fun searchHitCrumbs(hit: TextHit, under: String? = null): List<String> {
        val crumbs = fileBreadcrumbOf(hit.fileRel, rootFileName).map { it.label }
        if (under == null) return crumbs
        // `Home`, then one crumb per folder of [under].
        val above = 1 + if (under.isEmpty()) 0 else under.count { it == '/' } + 1
        return crumbs.drop(above)
    }

    /**
     * Moves this pane to the line of [hit]: opens its file (no zoom) and
     * puts the caret at the end of the line — found by item, so children
     * another pane has unfolded in the shared document don't throw it
     * off. Waits for the pane's first document to load, so it can be
     * called on a pane that was just constructed.
     *
     * Called by the web shell when a search result is opened in a new
     * window.
     *
     * @return The job doing the move; complete once the caret is there.
     */
    fun openSearchHit(hit: TextHit): Job =
        scope.launch {
            _stateFlow.first { it.isLoaded || it.isFileView }
            switchActiveFile(hit.fileRel)
            placeCaretAtHit(hit)
        }

    /**
     * Navigates this pane to the line of [hit] — the search result the
     * user clicked: opens the hit's file pushing the pane's file history
     * (so Back returns — to the search as it was), or, when it is the file
     * on screen, closes the search and stays; then puts the caret at the
     * end of the line. A zoom that would hide the line is zoomed out of,
     * on the zoom history, so Back returns to it.
     *
     * Called by the web result list (a click or Enter on a result) and by
     * a search node's result rows.
     */
    fun navigateToSearchHit(hit: TextHit): Job =
        scope.launch {
            val s = _stateFlow.value
            if (!s.isLoaded && !s.isFileView) return@launch
            // Another file: the search stays with the page left (Back
            // brings it back, see [rememberPage]); the same file: close it.
            if (s.activeFileRel == hit.fileRel) closeSearch()
            if (s.activeFileRel != hit.fileRel) {
                val here = currentHistoryEntry()
                switchActiveFile(hit.fileRel)
                patch {
                    it.copy(
                        fileHistory = (it.fileHistory + here).takeLast(NAV_HISTORY_CAP),
                        fileForward = emptyList(),
                    )
                }
            }
            placeCaretAtHit(hit)
        }

    /**
     * Puts the caret at the end of [hit]'s line in the open document —
     * found by item index, so children another pane has unfolded in the
     * shared document don't throw it off — zooming out of a zoom that does
     * not contain it the way [ZoomNavigation.zoomOut] does (pushing the
     * zoom history, so Back returns there, and dropping a leaf zoom's
     * empty placeholder). Waits for the document to load.
     */
    private suspend fun placeCaretAtHit(hit: TextHit) {
        val doc = document ?: return
        doc.stateFlow.first { it.isLoaded }
        var row = rowOfHit(doc.stateFlow.value.lines, hit)
        if (row < 0 || document !== doc) return
        // A zoom this clears is an arrival too: keep its recall from moving
        // the caret off the hit.
        suppressRecall = true
        try {
            val zoom = zoomInfoOf(_stateFlow.value)
            if (zoom != null && row !in zoom.startRow..zoom.endRowInclusive) {
                zoomNavigation.zoomOut()
                // Dropping the placeholder may have shifted the rows.
                row = rowOfHit(doc.stateFlow.value.lines, hit)
                if (row < 0) return
            }
            val lines = doc.stateFlow.value.lines
            patch { it.copy(cursorRow = row, cursorCol = lines[row].length, anchorRow = null, anchorCol = null) }
        } finally {
            suppressRecall = false
        }
    }

    /** The row of [hit] in [lines] (see [placeCaretAtHit]), or -1. */
    private fun rowOfHit(lines: List<String>, hit: TextHit): Int {
        if (!NoteRepository.isOutlineFile(hit.fileRel)) return hit.itemIndex.coerceAtMost(lines.lastIndex)
        var item = -1
        for (r in lines.indices) {
            if (DocumentLayout.itemColumn(lines, r) != 0 || (BlockLayout.isBlockLine(lines[r]) && !BlockLayout.startsBlock(lines, r))) continue
            item++
            if (item == hit.itemIndex) return minOf(r + hit.rowOffset, DocumentLayout.itemLastRow(lines, r))
        }
        return -1
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
        val shown = FolderContents.visible(raw)
        // What the privacy mode hides: notes carrying a hidden tag, and
        // folders under hidden items (never "unreferenced" folders).
        return if (state.privacy.isActive) shown.filterNot { registry.isPathHidden(it.pathRel) } else shown
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
     * outline file, `<dirRel>/_node.md`. Works for foreign folders and
     * for Lunarbor folders no bullet references — a folder without an
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
     * Creates `Untitled.excalidraw` (or `Untitled 2.excalidraw`, …) in the
     * folder of the node on screen ([currentNodeFolder]) and opens it in
     * the drawing editor, pushing file history. No-op where there is no
     * such folder (a zoomed leaf, a note, an image).
     *
     * Called by the "New Excalidraw drawing" palette command.
     */
    fun newDrawingFile() {
        scope.launch {
            document?.flush()
            val folder = currentNodeFolder() ?: return@launch
            val rel = registry.createDrawingFile(folder)
            navigateToVaultFile(rel)
        }
    }

    /**
     * The newest text (Excalidraw JSON) of the drawing [fileRel], or `null`
     * when it does not exist. See [DocumentRegistry.drawingText].
     *
     * Called by the web drawing editor when it opens a drawing and when
     * [State.drawingRevision] changes.
     */
    suspend fun loadDrawing(fileRel: String): String? = registry.drawingText(fileRel)

    /**
     * Takes a changed drawing from the pane's drawing editor; the registry
     * writes it after the autosave pause ([DocumentRegistry.saveDrawing]).
     * Ignored for anything but a drawing path.
     *
     * Called by the web drawing editor whenever its scene changed.
     *
     * @param fileRel The drawing the editor shows — passed explicitly, so a
     *   change reported just as the pane navigates away lands in the right file.
     * @param text The whole scene, serialized as an `.excalidraw` file.
     */
    fun onDrawingChanged(fileRel: String, text: String) {
        if (!NoteRepository.isDrawingPath(fileRel)) return
        registry.saveDrawing(fileRel, text)
    }

    /**
     * Renames the file this pane shows — a `.md` note, an image or a
     * drawing — to [title]: the user edited the page title. When a note's
     * first line is the hidden `# H1` title ([State.hidesTitleHeading]),
     * that line is rewritten to `# title` first, so it stays in step with
     * the name and stays hidden. Then the registry saves, moves the file,
     * re-keys the document and rewrites links to it
     * ([DocumentRegistry.renameFile]); every pane on the file follows
     * through its rename listener. An image or drawing keeps its extension
     * ([NoteRepository.renameTargetOf]).
     *
     * No-op for a blank or unchanged title and where the title is not
     * editable ([State.canRenameFromTitle]). A name already taken in the
     * folder gets ` (2)`, ` (3)`, …
     *
     * Called by the web view when the title's inline edit is committed.
     */
    fun renameActiveFile(title: String) {
        val s = _stateFlow.value
        val trimmed = title.trim()
        if (!s.canRenameFromTitle || trimmed.isEmpty()) return
        if (trimmed == NoteRepository.displayNameOf(s.activeFileRel)) return
        if (!s.isFileView) {
            val doc = document ?: return
            if (s.hidesTitleHeading) {
                val heading = s.lines[0]
                val marker = LineStyle.HEADING_1.marker
                doc.delete(0, marker.length, 0, heading.length)
                doc.insertText(0, marker.length, trimmed)
            }
        }
        scope.launch { registry.renameFile(s.activeFileRel, trimmed) }
    }

    /**
     * Where this pane is: its file plus the zoom target as a title path
     * (a [FileHistoryEntry], as Back / Forward store it). Title paths
     * survive being opened by another pane, whose row ids may differ.
     *
     * Called by the web shell's "New window" to open a pane at the same
     * place ([openLocation]).
     */
    fun currentLocation(): FileHistoryEntry = historyEntryOf(_stateFlow.value)

    /**
     * The location of the item at [row] of the open outline: this file,
     * zoomed into that item ([FileHistoryEntry.zoomTitlePath]). `null` when
     * [row] starts no item, or in Markdown mode (nothing zooms there).
     *
     * Called by the web view when a bullet's dot is right-clicked, to open
     * the item in a new window ([openLocation] on the new pane).
     */
    fun locationOfRow(row: Int): FileHistoryEntry? {
        val s = _stateFlow.value
        val docState = s.documentState ?: return null
        if (!s.isLoaded || s.isMarkdownMode) return null
        val path = titlePathOfRow(docState.lines, row)
        if (path.isEmpty()) return null
        return FileHistoryEntry(s.activeFileRel, path)
    }

    /**
     * Moves this pane to [location] — its file, then its zoom (expanding
     * folded bullets on the way) — without pushing file history: the pane
     * starts there. Waits for the pane's first document to load, so it can
     * be called on a pane that was just constructed.
     *
     * Called by the web shell right after creating a "New window" pane
     * from another pane's [currentLocation], and when a pane reopens at its
     * persisted location after a restart or a vault change.
     *
     * @return The job doing the move; complete once the pane is there.
     */
    fun openLocation(location: FileHistoryEntry): Job =
        scope.launch {
            _stateFlow.first { it.isLoaded || it.isFileView }
            switchActiveFile(location.fileRel)
            recallAfterLoad(location.fileRel)
            restoreZoom(location.zoomTitlePath)
        }

    /**
     * Where the note [fromRel] was renamed to this session, or `null` —
     * see [DocumentRegistry.renamedTo]. Lets the web view treat a rename
     * as staying put rather than as navigating to another file.
     */
    fun renamedTo(fromRel: String): String? = registry.renamedTo(fromRel)

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
        if (!current.isLoaded && !current.isFileView) return
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
            recallAfterLoad(pathRel)
        }
    }

    // ----------------------------------------------------------------- links

    /**
     * Inserts a link to [target] at the cursor, labelled with [label] or,
     * when that is blank, the target's title: a relative Markdown link
     * from the caret row's folder ([linkBaseOf]), a node named by its
     * `_node.md` ([LunarborLink.relative]). See [insertMarkdownLink].
     * Called by the Insert Link / "Insert Mirror…" modal once the user
     * picks a target.
     */
    fun insertLinkTo(target: LinkTarget, label: String = "") {
        val base = linkBaseOf(_stateFlow.value.cursorRow)
        val dest = LunarborLink.relative(target.pathRel, target.kind == VaultEntryKind.FOLDER, base)
        insertMarkdownLink(label.ifBlank { target.title }, dest)
    }

    /**
     * The folder the links in row [row] are written relative to
     * ([Document.linkBaseOf]); the open file's folder outside an outline.
     * Called to write a link into the row (Insert Link, the Edit link
     * dialog) and to read one.
     */
    fun linkBaseOf(row: Int): String =
        document?.linkBaseOf(row) ?: LunarborLink.baseOfFile(_stateFlow.value.activeFileRel)

    /**
     * What a link [url] written in row [row] points at, as the app passes
     * links around: `/<path>` for a place in the vault (read
     * relative to the row's folder, [LunarborLink.resolve]), else [url]
     * unchanged (a web link, `mailto:`, …). Called by the web paint loop
     * for every link it draws, so clicks, hover cards and broken-link
     * marks all see the vault path.
     */
    fun linkHrefOf(row: Int, url: String): String =
        LunarborLink.resolve(url, linkBaseOf(row))?.let { LunarborLink.rooted(it) } ?: url

    /**
     * The link at [row] / [col] ([LinkSource.at]), or `null`. Called by the
     * web Edit link dialog to fill in the link's text and URL.
     *
     * @param row Document row of the link.
     * @param col Any column inside the link's source span.
     */
    fun linkAt(row: Int, col: Int): LinkSource? = linkSourceAt(row, col)

    /**
     * Rewrites the link at [row] / [col] — a Markdown link, a `[[…]]` wiki
     * link or a bare URL — as `[text](url)`. [text] is plain text: its
     * `\ [ ] ( )` are escaped ([SubtreeCodec.escapeLabel]), other Markdown
     * stays. A blank [text] shows the URL; a URL shown as itself is written
     * bare; a blank [url] removes the link ([removeLinkAt]). One undoable
     * edit; nothing happens when the result equals what is there. Called by
     * the web Edit link dialog's Save.
     *
     * @param row Document row of the link.
     * @param col Any column inside the link's source span.
     * @param text The text the link should show.
     * @param url Where it should point: any URL, a `/…` path.
     */
    fun updateLinkAt(row: Int, col: Int, text: String, url: String) {
        val link = linkSourceAt(row, col) ?: return
        val target = url.trim()
        if (target.isEmpty()) {
            removeLinkAt(row, col)
            return
        }
        val shown = text.trim().ifEmpty { target }
        val replacement = if (shown == target && bareUrlEndAt(target, 0) == target.length) target
        else "[" + SubtreeCodec.escapeLabel(shown) + "](" + SubtreeCodec.formatLinkUrlForLabel(target) + ")"
        val line = _stateFlow.value.lines.getOrNull(row) ?: return
        if (line.substring(link.start, link.end) == replacement) return
        replaceLinkSource(row, link, replacement)
    }

    /**
     * Unlinks the link at [row] / [col], leaving the text it showed. One
     * undoable edit. Called by the web Edit link dialog's "Remove link".
     */
    fun removeLinkAt(row: Int, col: Int) {
        val link = linkSourceAt(row, col) ?: return
        replaceLinkSource(row, link, link.label)
    }

    private fun linkSourceAt(row: Int, col: Int): LinkSource? {
        val line = _stateFlow.value.lines.getOrNull(row) ?: return null
        return LinkSource.at(line, col)
    }

    /** Selects [link]'s source on [row] and types [replacement] over it. */
    private fun replaceLinkSource(row: Int, link: LinkSource, replacement: String) {
        moveTo(row, link.start)
        moveTo(row, link.end, extend = true)
        insertLiteralText(replacement)
    }

    /**
     * Gets the link search ready to reflect the latest edits: saves every
     * open document (so a bullet that just got its first child already has
     * a folder, and a renamed one its new name) and drops the cached
     * target list. Builds the link index too (once; it is kept current
     * afterwards), which gives every node's `updated` stamp to Navigate
     * to's recency order (LBR-16). Called when a link modal opens.
     */
    suspend fun prepareLinkSearch() {
        registry.flushAll()
        registry.vaultIndex.invalidateTargets()
        registry.vaultIndex.ensureLinkIndex()
    }

    /**
     * `true` when [url] is a vault link whose target is known to be
     * missing — moved or trashed outside the app, or a node deleted here.
     * While the target's status is unknown, starts a check (see
     * [DocumentRegistry.requestLinkStatus]) and answers `false`; the
     * result arrives as a new [State.linkStatus], which repaints.
     *
     * Called by the web paint loop for every link it draws. Links with
     * any other URL are never broken.
     */
    fun isLinkBroken(state: State, url: String): Boolean {
        val path = LunarborLink.parseRooted(url) ?: return LunarborLink.isRooted(url)
        // A target the privacy mode hides reads as missing.
        if (state.privacy.isActive && registry.isPathHidden(path)) return true
        state.linkStatus[path]?.let { return !it }
        return registry.requestLinkStatus(path) == false
    }

    /**
     * The vault link a wiki link `[[name]]` stands for — the one vault
     * target [name] matches ([WikiLink.resolve]) — or `null` when none or
     * several match, in which case the text is drawn plain. While the name
     * is unresolved, starts the resolution (see
     * [DocumentRegistry.requestWikiLink]) and answers `null`; the result
     * arrives as a new [State.wikiLinks], which repaints.
     *
     * Called by the web paint loop for every wiki link run it draws.
     *
     * @param name The run's target name ([StyledRun.wikiName]).
     */
    fun wikiLinkHref(state: State, name: String): String? {
        val key = WikiLink.keyOf(name)
        val path = if (key in state.wikiLinks) state.wikiLinks[key]
        else registry.requestWikiLink(name)?.getOrNull()
        return path?.let { LunarborLink.rooted(it) }
    }

    /**
     * Where this pane is, as the vault path a Starred entry stores (TRF-8):
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
        if (s0.isFileView) return s0.activeFileRel
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
            if (row >= 0) FolderName.nameTextOf(SubtreeCodec.titleOf(docState.lines[row])).takeIf { it.isNotBlank() }?.let { return it }
        }
        if (pathRel.isEmpty()) return NoteRepository.ROOT_DISPLAY_NAME
        val name = pathRel.substringAfterLast('/')
        return if (NoteRepository.isFileViewPath(name) || name.endsWith(NoteRepository.NOTE_EXTENSION)) {
            name.removeSuffix(NoteRepository.NOTE_EXTENSION)
        } else {
            FolderName.decode(name)
        }
    }

    /**
     * Stars or un-stars this pane's [currentLocationPath]: adds a
     * `* [label](…)` entry to `Starred.md` when [starred] is `false`,
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
     * Titles from the top-level item of [lines] down to the item (bullet
     * or block) at [row], inclusive; empty when [row] starts no item. The
     * inverse of [findLineIdByTitlePathIn].
     */
    private fun titlePathOfRow(lines: List<String>, row: Int): List<String> {
        if (row !in lines.indices) return emptyList()
        val rowIndent = DocumentLayout.itemColumn(lines, row)
        if (rowIndent < 0) return emptyList()
        val baseline = topLevelBulletIndent(lines)
        if (baseline < 0) return emptyList()
        val stack = ArrayDeque<String>()
        // Include the bullet itself, then walk up the ancestor chain by
        // strictly-shallower indent.
        stack.addFirst(SubtreeCodec.itemTitleOf(lines, row))
        var lookingFor = rowIndent - 1
        var r = row - 1
        while (r >= 0 && lookingFor >= baseline) {
            val ind = DocumentLayout.itemColumn(lines, r)
            if (ind in baseline..lookingFor) {
                stack.addFirst(SubtreeCodec.itemTitleOf(lines, r))
                lookingFor = ind - 1
            }
            r--
        }
        return stack.toList()
    }

    private fun topLevelBulletIndent(lines: List<String>): Int {
        var min = Int.MAX_VALUE
        for (row in lines.indices) {
            val c = DocumentLayout.itemColumn(lines, row)
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
     * Inserts a reference to the existing vault image or drawing
     * [vaultRelPath], as picked in the Insert Image palette. An image in the cursor row's
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
     * Follows the vault link [url] (TRF-8):
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
        val path = LunarborLink.parseRooted(url)
        if (path == null) {
            onComplete()
            return
        }
        scope.launch {
            try {
                // A target the privacy mode hides is not followed.
                if (registry.isPathHidden(path)) return@launch
                when (registry.kindOf(path)) {
                    null -> {
                        println("[lunarbor] link target not found: $url")
                        registry.requestLinkStatus(path)
                        registry.refreshLinkStatuses()
                    }
                    VaultEntryKind.FOLDER -> zoomToFolder(path)
                    VaultEntryKind.MARKDOWN, VaultEntryKind.IMAGE, VaultEntryKind.DRAWING, VaultEntryKind.HTML -> {
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

    // ------------------------------------------------------------ daily notes

    /**
     * What [navigateToToday] or [navigateToAdjacentDay] did, for the view
     * to react to.
     */
    enum class TodayOutcome {
        /** The pane went to the day item (found or prepared). */
        OPENED,

        /** The pane was on today already; nothing changed. */
        ALREADY_THERE,

        /**
         * The app's privacy mode hides `Journal` or a part of today's path:
         * nothing was revealed or created. The view shows a short notice.
         */
        HIDDEN,

        /** The pane is still loading, or the root outline could not be opened. */
        UNAVAILABLE,

        /**
         * [navigateToAdjacentDay] only: the pane is not on a journal day, or
         * there is no earlier (later) day to go to. Nothing changed.
         */
        NO_DAY,
    }

    /**
     * The Today command (LBR-19): takes the pane to today's journal item,
     * `Journal › <ISO week year> › Week <NN> › <YYYY-MM-DD Weekday>` in the
     * root outline ([DailyNotes.titlePath]), preparing what is missing.
     *
     * 1. Already there (zoomed into the day in the root outline, or on the
     *    day's own folder) → [TodayOutcome.ALREADY_THERE].
     * 2. A privacy mode hiding `Journal` or a part of the path →
     *    [TodayOutcome.HIDDEN] before the pane moves ([isTodayHidden]), and
     *    checked again row by row on the way down.
     * 3. Opens the root outline when the pane is elsewhere (file history,
     *    like following a link — works from notes and file views too), or
     *    lets go of the page's own throwaway rows first.
     * 4. Walks down the path, reusing existing items ([DailyNotes.findChild];
     *    `Journal` by name, case-insensitive) and unfolding each for this
     *    pane ([expandForPane], which loads a folder-backed item's children).
     * 5. Inserts the missing items, newest first — a new year first under
     *    `Journal`, a week first under its year, the day first under its
     *    week; a new `Journal` last at the root — plus an empty placeholder
     *    child under the day, as **pending rows**
     *    ([Document.insertPendingRows]): on screen, never saved until the
     *    first edit touches them ([commitTouchedPendingRows]), removed when
     *    the pane leaves untouched ([releasePendingRowsIfAny]). A new day's
     *    children are a copy of the daily template's items when one is set
     *    (LBR-21, [dailyTemplateRowsFor]) instead of the placeholder —
     *    pending in the same group, so the copy is a throwaway too. An
     *    existing day without children gets only the pending placeholder. Pending
     *    rows another pane prepared for the same day are joined
     *    ([Document.holdPendingRows]) or extended, never duplicated.
     * 6. Zooms into the day — with zoom history when the pane was on the
     *    root outline already, as the entry point of the file switch
     *    otherwise — so Back returns, the view morphs, and page memory
     *    applies; the caret goes to the day's first child unless the page
     *    remembered a caret inside it.
     *
     * Called by the web `AppShell` for the "Today" palette command and its
     * hotkey (through `MainViewModel.navigateToToday`).
     *
     * @param today The user's local date ("today" on their clock, not UTC),
     *   from the platform layer; a parameter so tests can pass any date.
     * @return What happened; [TodayOutcome.HIDDEN] asks the view for a notice.
     */
    suspend fun navigateToToday(today: CalendarDate): TodayOutcome =
        openJournalPath(DailyNotes.titlePath(today), prepare = true)

    /**
     * Takes the pane to the journal item named by [titles] (outermost
     * first, `Journal` › year › week › day) in the root outline — the
     * steps of [navigateToToday], which is this with today's path and
     * [prepare] on. "Previous day" / "Next day" ([navigateToAdjacentDay])
     * pass the path of a day that exists, with [prepare] off.
     *
     * @param titles The item titles from the root down.
     * @param prepare Whether missing path items are prepared as pending
     *   rows. When off, a path item that is not found ends the call with
     *   [TodayOutcome.UNAVAILABLE]; an existing day without children still
     *   gets its throwaway placeholder child, as a leaf zoom does.
     */
    private suspend fun openJournalPath(titles: List<String>, prepare: Boolean): TodayOutcome {
        val s0 = _stateFlow.value
        if (!s0.isLoaded && !s0.isFileView) return TodayOutcome.UNAVAILABLE
        if (isOnTitlePath(s0, titles)) return TodayOutcome.ALREADY_THERE
        if (isTodayHidden(titles)) return TodayOutcome.HIDDEN
        val switched = s0.activeFileRel != rootFileName
        val previousZoom = s0.zoomedLineId
        if (switched) {
            openFileWithHistory(rootFileName)
        } else {
            // Leave the page's own throwaway rows (an earlier day's
            // untouched preparation) before reading the rows.
            cleanupEmptyPlaceholderIfAny()
        }
        val doc = document ?: return TodayOutcome.UNAVAILABLE
        doc.stateFlow.first { it.isLoaded }
        if (_stateFlow.value.activeFileRel != rootFileName || document !== doc) return TodayOutcome.UNAVAILABLE

        // Walk down the existing part of the path.
        val chain = ArrayList<LineId>()
        var parentId: LineId? = null
        var level = 0
        while (level < titles.size) {
            val docState = doc.stateFlow.value
            val parentRow = parentId?.let { docState.lineIds.indexOf(it) } ?: -1
            if (parentId != null && parentRow < 0) return TodayOutcome.UNAVAILABLE
            val row = DailyNotes.findChild(docState.lines, parentRow, titles[level])
            if (row < 0) break
            if (PrivacyLayout.isHidden(PrivacyLayout.hiddenRows(docState.lines, _stateFlow.value.privacy), row)) {
                return TodayOutcome.HIDDEN
            }
            val id = docState.lineIds[row]
            holdPendingGroupOf(doc, id)
            expandForPane(doc, id)
            if (document !== doc) return TodayOutcome.UNAVAILABLE
            chain += id
            parentId = id
            level++
        }

        // Prepare what is missing (or only the day's placeholder child).
        val missing = titles.drop(level)
        if (!prepare && missing.isNotEmpty()) return TodayOutcome.UNAVAILABLE
        // A day that does not exist yet starts as a copy of the daily
        // template's items (LBR-21), pending like the rest.
        val templateRows = if (missing.isEmpty()) null else dailyTemplateRowsFor(doc, chain, missing)
        if (document !== doc) return TodayOutcome.UNAVAILABLE
        val docState = doc.stateFlow.value
        val parentRow = parentId?.let { docState.lineIds.indexOf(it) } ?: -1
        if (parentId != null && parentRow < 0) return TodayOutcome.UNAVAILABLE
        val needsRows = missing.isNotEmpty() ||
            DailyNotes.childItemRows(docState.lines, parentRow).isEmpty()
        var dayId = if (missing.isEmpty()) parentId else null
        if (needsRows) {
            val (at, indent) = if (parentRow < 0) {
                val top = DailyNotes.childItemRows(docState.lines, -1).firstOrNull()
                DailyNotes.lastTopLevelRow(docState.lines) to (top?.let { DocumentLayout.itemColumn(docState.lines, it) } ?: 0)
            } else {
                DailyNotes.firstChildRow(docState.lines, parentRow) to
                    DocumentLayout.itemColumn(docState.lines, parentRow) + TAB_SIZE
            }
            val contents = DailyNotes.preparedRows(missing, indent, templateRows)
            val heldGroup = _stateFlow.value.pendingRowsGroup
            val parentGroup = parentId?.let { doc.pendingGroupOf(it) }
            val prepared = if (parentGroup != null && parentGroup == heldGroup) {
                doc.extendPendingRows(parentGroup, at, contents)
            } else {
                doc.insertPendingRows(at, contents)?.also { pr -> patch { it.copy(pendingRowsGroup = pr.groupId) } }
            } ?: return TodayOutcome.UNAVAILABLE
            if (missing.isNotEmpty()) dayId = prepared.ids[missing.size - 1]
            // The path items open for the zoom; the day's children — the
            // placeholder, or the template's rows — start open too (seen,
            // so the default-collapse pass leaves them be).
            val pathCount = if (missing.isEmpty()) 0 else missing.size
            chain += prepared.ids.take(pathCount)
            patch { it.copy(seenLineIds = it.seenLineIds + prepared.ids.drop(pathCount)) }
        }
        val target = dayId ?: return TodayOutcome.UNAVAILABLE
        // Every item on the way is open for the zoom. One the pane had
        // folded — or not seen yet, which the default-collapse pass would
        // fold — folds again once the pane zooms away (if it is kept),
        // like any item a zoom opened ([ZoomNavigation.refoldLeftBehind]).
        patch { st ->
            val refold = chain.filter { it in st.collapsedIds || it !in st.seenLineIds }
            st.copy(
                collapsedIds = st.collapsedIds - chain.toSet(),
                seenLineIds = st.seenLineIds + chain,
                zoomUnfoldedIds = st.zoomUnfoldedIds + refold,
            )
        }

        // Zoom into the day.
        if (switched) {
            // Entry point into the file: no zoom history, so Back returns
            // to where the pane was (as following a link does).
            patch { it.copy(zoomedLineId = target, collapsedIds = it.collapsedIds - target) }
        } else {
            zoomNavigation.zoomToPrepared(target, previousZoom)
        }
        val after = _stateFlow.value
        val lines = after.lines
        val dayRow = after.documentState?.lineIds?.indexOf(target) ?: -1
        if (dayRow < 0) return TodayOutcome.UNAVAILABLE
        val firstChild = DailyNotes.firstChildRow(lines, dayRow)
        val end = DocumentLayout.subtreeEnd(lines, dayRow, DocumentLayout.itemColumn(lines, dayRow))
        if (needsRows || after.cursorRow !in firstChild..end) {
            after.documentState?.lineIds?.getOrNull(firstChild)?.let { placeCursorOn(it) }
        }
        return TodayOutcome.OPENED
    }

    /**
     * `true` when [state]'s page is the daily template (LBR-21): its node
     * folder ([currentNodeFolder] — the zoomed item's, or the open
     * outline's) is [State.dailyTemplateFolder]. The web view then shows a
     * "Daily template" label by the page title, and the palette offers
     * "Stop using as daily template".
     */
    fun isDailyTemplatePage(state: State = _stateFlow.value): Boolean {
        val template = state.dailyTemplateFolder ?: return false
        if (!state.isLoaded || state.isMarkdownMode || state.isFileView) return false
        return currentNodeFolder(state) == template
    }

    /**
     * `true` when [state]'s page can become the daily template (LBR-21):
     * a node page other than the root — the zoomed item's folder or an
     * open outline's folder ([currentNodeFolder]) — that is not the
     * template already. A zoomed leaf (no folder yet), a note or a file
     * view cannot.
     *
     * Called by the web `AppShell` to offer "Use as daily template".
     */
    fun canUseAsDailyTemplate(state: State = _stateFlow.value): Boolean {
        if (!state.isLoaded || state.isMarkdownMode || state.isFileView) return false
        val folder = currentNodeFolder(state) ?: return false
        return folder.isNotEmpty() && folder != state.dailyTemplateFolder
    }

    /**
     * "Use as daily template" ([use] `true`): makes this page's node
     * ([currentNodeFolder]) the daily template, replacing any earlier one.
     * "Stop using as daily template" ([use] `false`, on the template's
     * page): no template from now on. Days that exist are never changed;
     * only days prepared from now on start as a copy
     * ([DocumentRegistry.dailyTemplateRows]).
     *
     * Called by the web `AppShell` for the two palette commands (through
     * `MainViewModel.setDailyTemplate`). No-op when the page cannot be the
     * template ([canUseAsDailyTemplate]) or is not it.
     */
    fun setDailyTemplate(use: Boolean) {
        val s = _stateFlow.value
        if (use) {
            if (!canUseAsDailyTemplate(s)) return
            registry.dailyTemplate.set(currentNodeFolder(s))
        } else if (isDailyTemplatePage(s)) {
            registry.dailyTemplate.set(null)
        }
    }

    /**
     * The daily template's rows for a day [openJournalPath] is about to
     * prepare ([DocumentRegistry.dailyTemplateRows]; LBR-21), or `null`
     * for none — no template set, gone, hidden by the pane's privacy mode,
     * or holding the day itself.
     *
     * @param chain The existing path items found, outermost first (one per
     *   leading entry of the path's titles).
     * @param missing The titles still to prepare, the day last.
     */
    private suspend fun dailyTemplateRowsFor(doc: Document, chain: List<LineId>, missing: List<String>): List<String>? {
        if (registry.dailyTemplate.folder == null) return null
        // The day's folder as the save will name it: the deepest existing
        // item's folder, then the encoded titles below it.
        var base = ""
        var named = 0
        for ((i, id) in chain.withIndex()) {
            doc.folderOf(id)?.let {
                base = it
                named = i + 1
            }
        }
        val below = (chain.indices.drop(named).map { i -> titleOfItem(doc, chain[i]) } + missing).map { FolderName.forTitle(it) }
        val dayFolder = (listOf(base).filter { it.isNotEmpty() } + below).joinToString("/")
        return registry.dailyTemplateRows(dayFolder, _stateFlow.value.privacy)
    }

    /** The title of the item [id] in [doc] ([SubtreeCodec.itemTitleOf]), or `""` when it is gone. */
    private fun titleOfItem(doc: Document, id: LineId): String {
        val st = doc.stateFlow.value
        val row = st.lineIds.indexOf(id)
        return if (row < 0) "" else SubtreeCodec.itemTitleOf(st.lines, row)
    }

    /**
     * When [id] is a pending row of a group this pane does not hold yet
     * (another pane prepared today and nobody typed in it), holds that
     * group too, so it stays while this pane is on it. A pane holds at
     * most one group: one it held before is let go of first.
     */
    private fun holdPendingGroupOf(doc: Document, id: LineId) {
        val group = doc.pendingGroupOf(id) ?: return
        val held = _stateFlow.value.pendingRowsGroup
        if (held == group) return
        if (held != null) doc.releasePendingRows(held)
        if (doc.holdPendingRows(group)) patch { it.copy(pendingRowsGroup = group) }
    }

    /**
     * `true` when [state] is on the item named by [titles] (outermost
     * first): zoomed into it in the root outline, or on its folder
     * ([currentNodeFolder]) — by name text, case-insensitive
     * ([DailyNotes.matchesTitle]). Used by [navigateToToday].
     */
    private fun isOnTitlePath(state: State, titles: List<String>): Boolean {
        fun matches(path: List<String>) =
            path.size == titles.size && path.indices.all { DailyNotes.matchesTitle(path[it], titles[it]) }
        if (!state.isLoaded || state.isMarkdownMode) return false
        val zoomed = state.zoomedLineId
        if (state.activeFileRel == rootFileName && zoomed != null) {
            val row = state.documentState?.lineIds?.indexOf(zoomed) ?: -1
            if (row >= 0 && matches(titlePathOfRow(state.lines, row))) return true
        }
        val folder = currentNodeFolder(state) ?: return false
        return folder.isNotEmpty() && matches(folder.split('/').map { FolderName.decode(it) })
    }

    /**
     * `true` when the app's privacy mode hides a part of today's path
     * [titles]: the root's `Journal` item carries a hiding tag, or one of
     * the path's folders (as the save rules would name them,
     * [FolderName.forTitle]) is hidden ([DocumentRegistry.isPathHidden]).
     * Reads the root outline through the registry without moving the pane,
     * so a refusal leaves the pane where it was. `false` with no mode on.
     */
    private suspend fun isTodayHidden(titles: List<String>): Boolean {
        val filter = _stateFlow.value.privacy
        if (!filter.isActive) return false
        val root = registry.acquire(rootFileName)
        try {
            val docState = root.stateFlow.first { it.isLoaded }
            val row = DailyNotes.findChild(docState.lines, -1, titles.first())
            if (row < 0) return false
            if (PrivacyLayout.isHidden(PrivacyLayout.hiddenRows(docState.lines, filter), row)) return true
            var folder = root.folderOf(docState.lineIds[row]) ?: return false
            if (registry.isPathHidden(folder)) return true
            for (title in titles.drop(1)) {
                folder = "$folder/${FolderName.forTitle(title)}"
                if (registry.isPathHidden(folder)) return true
            }
            return false
        } finally {
            registry.release(rootFileName)
        }
    }

    /**
     * The journal day this pane is on, or anywhere inside (LBR-20): its
     * location's titles from the root down — the active file's folders
     * (decoded names), then the zoom path in that file — read by
     * [DailyNotes.dayOfTitlePath]. Covers the day zoomed into in the root
     * outline or any outline above it, the day's own folder, and anything
     * below it (a child zoomed into, a note or image in its folder).
     * `null` elsewhere, and while nothing is loaded.
     *
     * Called by the web `AppShell` to offer "Previous day" / "Next day"
     * only on a day, and by [navigateToAdjacentDay].
     *
     * @param state The pane state to read; defaults to the latest.
     */
    fun journalDayOf(state: State = _stateFlow.value): CalendarDate? {
        if (!state.isLoaded && !state.isFileView) return null
        val file = state.activeFileRel
        val dir = if (NoteRepository.isOutlineFile(file)) NoteRepository.folderOfOutline(file) else file.substringBeforeLast('/', "")
        val titles = dir.split('/').filter { it.isNotEmpty() }.mapTo(ArrayList()) { FolderName.decode(it) }
        val zoomed = state.zoomedLineId
        if (zoomed != null && !state.isFileView && !state.isMarkdownMode) {
            val row = state.documentState?.lineIds?.indexOf(zoomed) ?: -1
            titles += titlePathOfRow(state.lines, row)
        }
        return DailyNotes.dayOfTitlePath(titles)
    }

    /**
     * "Previous day" / "Next day" (LBR-20): from the journal day the pane
     * is on ([journalDayOf]) to the nearest existing day before or after
     * it ([DailyNotes.stepFrom] over [journalDays]) — gaps, weeks and
     * years skipped, days the privacy mode hides left out — through the
     * Today command's path ([openJournalPath]), so history, morph, page
     * memory and the throwaway placeholder of an empty day all work the
     * same. "Next day" reaching today goes through [navigateToToday]
     * itself (prepared when missing).
     *
     * Called by the web `AppShell` for the two palette commands (through
     * `MainViewModel.navigateToAdjacentDay`).
     *
     * @param forward `true` for "Next day", `false` for "Previous day".
     * @param today The user's local date, from the platform layer.
     * @return [TodayOutcome.NO_DAY] when the pane is not on a day or there
     *   is nowhere to go; otherwise what the navigation did.
     */
    suspend fun navigateToAdjacentDay(forward: Boolean, today: CalendarDate): TodayOutcome {
        val from = journalDayOf() ?: return TodayOutcome.NO_DAY
        return when (val step = DailyNotes.stepFrom(journalDays(), from, forward, today)) {
            null -> TodayOutcome.NO_DAY
            DailyNotes.DayStep.ToToday -> navigateToToday(today)
            is DailyNotes.DayStep.ToDay -> openJournalPath(step.day.titlePath, prepare = false)
        }
    }

    /**
     * Every day that exists in the journal, read from disk after saving
     * open documents ([DocumentRegistry.flushAll]; rows the Today command
     * prepared and nobody typed in are not saved, so they do not count):
     * the root's first item named `Journal` ([DailyNotes.matchesTitle]),
     * its year items ([DailyNotes.isYearTitle]), their `Week NN` items
     * ([DailyNotes.isWeekTitle]) and their day items
     * ([DailyNotes.dateOfDayTitle]). An item the pane's privacy mode hides
     * — a hiding tag in its title, or its folder hidden
     * ([DocumentRegistry.isPathHidden]) — is left out with everything
     * under it. One day per date: the first found.
     *
     * Called by [navigateToAdjacentDay].
     */
    private suspend fun journalDays(): List<DailyNotes.JournalDay> {
        registry.flushAll()
        val filter = _stateFlow.value.privacy
        fun hidden(title: String, folder: String?): Boolean = filter.isActive &&
            (filter.hides(TextIndex.tagKeysOfRow("* $title")) || (folder != null && registry.isPathHidden(folder)))
        fun join(parent: String, child: String) = if (parent.isEmpty()) child else "$parent/$child"

        val journal = registry.nodeItemsOf("").firstOrNull {
            (it is NodeLine.Leaf && DailyNotes.matchesTitle(it.title, DailyNotes.JOURNAL_TITLE)) ||
                (it is NodeLine.Folder && DailyNotes.matchesTitle(it.title, DailyNotes.JOURNAL_TITLE))
        } as? NodeLine.Folder ?: return emptyList()
        if (hidden(journal.title, journal.folder)) return emptyList()
        val out = LinkedHashMap<Long, DailyNotes.JournalDay>()
        for (year in registry.nodeItemsOf(journal.folder)) {
            if (year !is NodeLine.Folder || !DailyNotes.isYearTitle(year.title)) continue
            val yearFolder = join(journal.folder, year.folder)
            if (hidden(year.title, yearFolder)) continue
            for (week in registry.nodeItemsOf(yearFolder)) {
                if (week !is NodeLine.Folder || !DailyNotes.isWeekTitle(week.title)) continue
                val weekFolder = join(yearFolder, week.folder)
                if (hidden(week.title, weekFolder)) continue
                for (day in registry.nodeItemsOf(weekFolder)) {
                    val (title, folder) = when (day) {
                        is NodeLine.Leaf -> day.title to null
                        is NodeLine.Folder -> day.title to join(weekFolder, day.folder)
                        else -> continue
                    }
                    val date = DailyNotes.dateOfDayTitle(title) ?: continue
                    if (hidden(title, folder)) continue
                    out.getOrPut(date.epochDay) {
                        DailyNotes.JournalDay(date, listOf(journal.title, year.title, week.title, title))
                    }
                }
            }
        }
        return out.values.toList()
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
        // A page seen before keeps its caret; a new one starts on its first line.
        val recalled = recallAfterLoad(fileRel)
        if (!recalled && !NoteRepository.isFileViewPath(fileRel)) awaitFirstLoadedLineId()?.let { placeCursorOn(it) }
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
        if (!s.isLoaded || s.isMarkdownMode || s.isFileView) return false
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
     * children if they are on disk only, and clears its fold. A bullet
     * that was folded is recorded in [State.zoomUnfoldedIds], so it folds
     * again once the pane zooms away from it. When the pane holds the
     * expansion already (a fold remembered open), waits for a load still
     * under way ([Document.awaitChildrenLoaded]), since callers zoom next.
     */
    private suspend fun expandForPane(doc: Document, id: LineId) {
        val needsExpansion = doc.isPromotedRef(id) && id !in _stateFlow.value.expandedRefIdsLocal
        patch {
            val wasFolded = needsExpansion || id in it.collapsedIds
            it.copy(
                expandedRefIdsLocal = if (needsExpansion) it.expandedRefIdsLocal + id else it.expandedRefIdsLocal,
                collapsedIds = it.collapsedIds - id,
                zoomUnfoldedIds = if (wasFolded) it.zoomUnfoldedIds + id else it.zoomUnfoldedIds,
            )
        }
        if (needsExpansion) doc.acquireExpansion(id)
        // Held already (a fold remembered open) but maybe still loading:
        // the caller zooms next, which needs the children in.
        else doc.awaitChildrenLoaded(id)
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
                val ind = DocumentLayout.itemColumn(lines, i)
                if (ind < 0) { i++; continue }
                if (ind <= parentIndent) break
                if (childIndent < 0) childIndent = ind
                if (ind == childIndent &&
                    SubtreeCodec.itemTitleOf(lines, i).lowercase() == target
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
        dropAbandonedPlaceholder()
    }

    /**
     * Removes the pending placeholder bullet ([State.pendingLeafZoomChild])
     * once the caret and anchor are both off its row while it is still
     * empty — e.g. Arrow Up or a click back into the block it was made
     * from. The caret keeps pointing at the same text: rows below the
     * removed one move up by one. No-op while the caret is on it, and
     * when nothing is pending.
     *
     * Called after every caret move ([mutate], [setSelection]).
     */
    private fun dropAbandonedPlaceholder() {
        val s = _stateFlow.value
        val pendingId = s.pendingLeafZoomChild ?: return
        val r = s.documentState?.lineIds?.indexOf(pendingId) ?: return
        if (r < 0 || s.cursorRow == r || s.anchorRow == r) return
        val before = s.lines.size
        cleanupEmptyPlaceholderIfAny()
        if (_stateFlow.value.lines.size < before) {
            patch {
                it.copy(
                    cursorRow = if (s.cursorRow > r) s.cursorRow - 1 else s.cursorRow,
                    cursorCol = s.cursorCol,
                    anchorRow = s.anchorRow?.let { a -> if (a > r) a - 1 else a },
                    anchorCol = s.anchorCol,
                )
            }
        }
    }

    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        var patched = transform(current).copy(
            documentState = document?.stateFlow?.value ?: current.documentState
        )
        // Items a zoom unfolded fold again once the zoom leaves them.
        if (patched.zoomedLineId != current.zoomedLineId) {
            patched = ZoomNavigation.refoldLeftBehind(patched)
            // Another page: the search (scoped to the page) is kept with
            // the page left, and the new page's own comes back below.
            rememberPage(current)
            searchJob?.cancel()
            patched = patched.copy(
                searchQuery = null, searchHits = emptyList(), searchTotal = 0, isSearching = false, searchReversed = false,
            )
            _stateFlow.value = reconcile(patched)
            recordFolds(_stateFlow.value)
            recallPage()
            ensureVisibleRow()
            return
        }
        _stateFlow.value = reconcile(patched)
        recordFolds(_stateFlow.value)
    }

    /**
     * Writes the open state of every folder-backed item of [state] that
     * changed since this pane last wrote it to
     * [DocumentRegistry.foldMemory]. Open means not folded, not only open
     * for a zoom ([State.zoomUnfoldedIds], which folds again), and its
     * children loaded or wanted by this pane. Rows the default-collapse
     * pass has not seen yet are skipped — they have no fold state yet.
     *
     * Called after every [patch] and every document emission.
     */
    private fun recordFolds(state: State) {
        if (!state.isLoaded || state.isMarkdownMode) return
        val doc = document ?: return
        val docState = state.documentState ?: return
        for (id in docState.lineIds) {
            if (id !in state.seenLineIds) continue
            val folder = doc.folderOf(id) ?: continue
            val open = id !in state.collapsedIds && id !in state.zoomUnfoldedIds &&
                (id in state.expandedRefIdsLocal || id !in docState.unloadedRefIds)
            if (recordedOpen[id] == open) continue
            recordedOpen[id] = open
            registry.foldMemory.setExpanded(folder, open)
        }
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
        // A zoom into an item the privacy mode hides falls back to its
        // nearest visible ancestor (or no zoom).
        clamped.zoomedLineId?.let { zoomed ->
            if (isRowIdHidden(clamped, zoomed)) {
                val hidden = hiddenRowsIn(clamped)
                val ids = docState.lineIds
                val visibleAncestor = bulletAncestorsOf(clamped).lastOrNull { a ->
                    !PrivacyLayout.isHidden(hidden, ids.indexOf(a.lineId))
                }?.lineId
                clamped = clamped.copy(zoomedLineId = visibleAncestor)
            }
        }
        if (clamped.zoomedLineId != null) {
            val zoom = zoomInfoOf(clamped)
            if (zoom == null || (!zoom.hasVisibleRows && !zoom.isReadOnly)) {
                clamped = clamped.copy(zoomedLineId = null)
            } else if (!zoom.hasVisibleRows) {
                // A read-only page with no bullets of its own: the caret rests
                // on the node, which the page shows as its title.
                val zRow = zoom.zoomRow
                clamped = clamped.copy(cursorRow = zRow, cursorCol = lines[zRow].length, anchorRow = null, anchorCol = null)
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
        val startRow = zoom?.startRow ?: state.firstEditableRow
        val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
        if (endRowInclusive < startRow) return state
        // A row the caret reached inside a collapsed large block (Enter
        // or typing past its preview, a paste, undo) opens the block rather
        // than bouncing the caret out of it.
        BlockLayout.rangeAt(docState.lines, state.cursorRow)?.let { block ->
            val id = docState.lineIds.getOrNull(block.first)
            if (id != null && BlockLayout.isLarge(block) && id !in state.expandedBlockIds &&
                state.cursorRow - block.first >= BlockLayout.PREVIEW_ROWS
            ) {
                return state.copy(expandedBlockIds = state.expandedBlockIds + id)
            }
        }
        val visible = visibleRowsIn(state, startRow, endRowInclusive)
        if (visible.isEmpty()) return state
        val visibleSet = visible.toHashSet()
        if (state.cursorRow in visibleSet) return state
        var target = state.cursorRow
        while (target > 0 && target !in visibleSet) target--
        val aboveVisible = target !in visibleSet
        if (aboveVisible) target = visible.first()
        val targetLine = docState.lines[target]
        // Above everything shown (a hidden title heading, rows above a
        // zoom): start of the first visible row. The old column belongs to
        // another line — kept, it put a note's opening caret mid-word.
        val safeCol = if (aboveVisible) DocumentLayout.caretStartCol(targetLine)
        else state.cursorCol.coerceIn(DocumentLayout.caretStartCol(targetLine), targetLine.length)
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
        // A search node's page is read-only (State.isReadOnlyPage).
        if (_stateFlow.value.isReadOnlyPage) return
        val before = snapshotNow()
        recordingDepth++
        try {
            block()
        } finally {
            recordingDepth--
        }
        val after = snapshotNow()
        if (before == after) return
        if (!keepsHiddenContent(before, after)) {
            // The edit would have touched what the privacy mode hides:
            // put everything back, as if it never ran.
            restoreSnapshot(before)
            return
        }
        pushUndoFrame(UndoFrame(before, after, kind, nowMs()))
        redoStack.clear()
        commitTouchedPendingRows(before, after)
    }

    /**
     * After an edit that changed something: commits the pending rows the
     * edit itself changed ([Document.commitPendingRowsEditedBetween],
     * comparing [before] with [after]) — typing into today's placeholder
     * or a copied template row, indenting, deleting or dragging one, in
     * any pane, keeps the whole preparation, which then saves like any
     * row. An edit of rows outside the preparation (another day's line
     * ticked off from a search-node result, LBR-22) keeps it a throwaway,
     * even with the caret on it. Clears [State.pendingRowsGroup] when
     * this pane's group was the one committed. Called by [recordEdit].
     */
    private fun commitTouchedPendingRows(before: Snapshot, after: Snapshot) {
        val doc = document ?: return
        if (!doc.commitPendingRowsEditedBetween(before.lines, before.lineIds, after.lines, after.lineIds)) return
        val group = _stateFlow.value.pendingRowsGroup ?: return
        if (!doc.isPendingGroup(group)) patch { it.copy(pendingRowsGroup = null) }
    }

    /**
     * `true` when going from [before] to [after] leaves alone everything the
     * app's privacy mode hides: every hidden row is still there, with its
     * text, under its parent; no visible row moved under a hidden item
     * ([PrivacyLayout.keepsHiddenRows]); and no folder-backed item whose
     * folder holds hidden content was deleted. Always `true` with no mode on.
     */
    private fun keepsHiddenContent(before: Snapshot, after: Snapshot): Boolean {
        val s = _stateFlow.value
        if (!s.privacy.isActive || s.isMarkdownMode) return true
        if (!PrivacyLayout.keepsHiddenRows(before.lines, before.lineIds, after.lines, after.lineIds, s.privacy)) return false
        val doc = document ?: return true
        val kept = after.lineIds.toHashSet()
        for (id in before.lineIds) {
            if (id in kept) continue
            val folder = doc.folderOf(id) ?: continue
            if (!NoteRepository.isInTrash(folder) && registry.hasHiddenUnder(folder)) return false
        }
        return true
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
        if (!_stateFlow.value.isLoaded || _stateFlow.value.isReadOnlyPage) return
        val frame = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(frame)
        while (redoStack.size > MAX_UNDO_FRAMES) redoStack.removeFirst()
        restoreSnapshot(frame.before)
    }

    fun redo() {
        if (!_stateFlow.value.isLoaded || _stateFlow.value.isReadOnlyPage) return
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

        /**
         * Search-node results listed under the node in its parent; the
         * rest are on its own page (zoomed into), which lists them all.
         */
        const val SEARCH_NODE_INLINE: Int = 10

        /** Most pages a pane remembers the scroll and search of ([PageView]). */
        private const val PAGE_MEMORY_CAP: Int = 200

        /** Pause in typing before [setSearchQuery] searches, in ms. */
        private const val SEARCH_DEBOUNCE_MS: Long = 120

        /** Cap on entries in [State.fileHistory] / [State.fileForward]. */
        const val NAV_HISTORY_CAP: Int = 50

        /** Normalizes an anchor + cursor pair into a [Selection]. */
        fun selectionOf(state: State): Selection? = se.soderbjorn.lunarbor.main.selectionOf(state)
    }
}

/**
 * The toast a pane shows after a Toggle done on a search result (LBR-22,
 * [PaneBackingViewModel.State.hitDoneToast]).
 *
 * @property toggle What changed, for Undo.
 * @property serial Grows with every toggle the pane makes, so the view
 *   shows a fresh toast for each, even one equal to the last.
 */
data class HitDoneToast(val toggle: HitDoneToggle, val serial: Int)
