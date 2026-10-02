/*
 * MainViewModel.kt (jsMain)
 * -------------------------
 * Web-platform facade over `PaneBackingViewModel`. Its only jobs are
 * to wrap the backing state in a platform-specific envelope (`State`) that
 * `MainScreen` consumes, and to delegate every user intent to the backing VM
 * with a one-liner.
 *
 * All editor logic lives one layer down. Keep this class boring — the only
 * code that belongs here is platform-specific glue that cannot exist in
 * commonMain: on web, [MainViewModel.openInDefaultApp], which hands a file
 * to the Electron main process to open in the system's default app.
 * Android and iOS will have their own `MainViewModel` implementations with
 * the same shape.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.demo.demoFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.browser.window
import kotlinx.coroutines.launch
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.data.VaultEntry
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.VaultIndex

/**
 * Thin web-platform ViewModel that `MainScreen` collects from. Receives the
 * shared `PaneBackingViewModel` via DI and re-emits its state
 * through a stable envelope so each platform can tack on its own fields
 * (scroll offset, focus flags, …) later without touching commonMain.
 *
 * ### Callers
 * - Instantiated by `JsAppGraph` (`@Provides mainViewModel(...)`).
 * - Collected by `MainScreen.render` for paint loops.
 * - Invoked by `MainScreen`'s DOM event handlers (keydown, mousedown, …).
 *
 * @param scope Coroutine scope owning the collector that mirrors the
 *   backing VM's flow. Typically the app-scoped `GlobalScope`.
 * @param paneBackingViewModel The common `PaneBackingViewModel` this
 *   platform layer delegates to.
 */
class MainViewModel(
    scope: CoroutineScope,
    private val paneBackingViewModel: PaneBackingViewModel
) {
    /**
     * Envelope state exposed to the web view.
     *
     * @property backingState Latest snapshot from `PaneBackingViewModel`,
     *   or `null` before the first emission (which causes the view to show
     *   a "Loading…" placeholder).
     */
    data class State(val backingState: PaneBackingViewModel.State? = null)

    private val _stateFlow = MutableStateFlow(State())

    /** Observable stream of envelope states consumed by `MainScreen`. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Synchronous accessor for the latest backing state. Use this from
     * input handlers (`beforeinput`, `keydown`) where the envelope flow
     * may still be one coroutine hop behind a freshly-applied edit —
     * stale envelope reads have caused caret-snap bugs where a sync
     * pass right after an insert reports the pre-insert cursor and
     * silently drops state like `pendingInlineStyles`.
     */
    val currentBackingState: PaneBackingViewModel.State
        get() = paneBackingViewModel.stateFlow.value

    init {
        scope.launch {
            paneBackingViewModel.stateFlow.collect { backing ->
                _stateFlow.value = State(backingState = backing)
            }
        }
    }

    // ---- editing intents ------------------------------------------------

    /** See `PaneBackingViewModel.insertChar`. */
    fun insertChar(char: Char) = paneBackingViewModel.insertChar(char)

    /** See `PaneBackingViewModel.insertNewline`. */
    fun insertNewline() = paneBackingViewModel.insertNewline()

    /** See `PaneBackingViewModel.insertText`. */
    fun insertText(text: String) = paneBackingViewModel.insertText(text)

    /** See `PaneBackingViewModel.backspace`. */
    fun backspace() = paneBackingViewModel.backspace()

    /** See `PaneBackingViewModel.undo`. */
    fun undo() = paneBackingViewModel.undo()

    /** See `PaneBackingViewModel.redo`. */
    fun redo() = paneBackingViewModel.redo()

    /** See `PaneBackingViewModel.canUndo`. */
    fun canUndo(): Boolean = paneBackingViewModel.canUndo()

    /** See `PaneBackingViewModel.canRedo`. */
    fun canRedo(): Boolean = paneBackingViewModel.canRedo()

    // ---- movement intents -----------------------------------------------

    /** See `PaneBackingViewModel.moveLeft`. */
    fun moveLeft(extend: Boolean = false) = paneBackingViewModel.moveLeft(extend)

    /** See `PaneBackingViewModel.moveRight`. */
    fun moveRight(extend: Boolean = false) = paneBackingViewModel.moveRight(extend)

    /** See `PaneBackingViewModel.moveUp`. */
    fun moveUp(extend: Boolean = false) = paneBackingViewModel.moveUp(extend)

    /** See `PaneBackingViewModel.moveDown`. */
    fun moveDown(extend: Boolean = false) = paneBackingViewModel.moveDown(extend)

    /** See `PaneBackingViewModel.moveTo`. */
    fun moveTo(row: Int, col: Int, extend: Boolean = false) = paneBackingViewModel.moveTo(row, col, extend)

    /** See `PaneBackingViewModel.moveLineStart`. */
    fun moveLineStart(extend: Boolean = false) = paneBackingViewModel.moveLineStart(extend)

    /** See `PaneBackingViewModel.moveLineEnd`. */
    fun moveLineEnd(extend: Boolean = false) = paneBackingViewModel.moveLineEnd(extend)

    /** See `PaneBackingViewModel.moveWordLeft`. */
    fun moveWordLeft(extend: Boolean = false) = paneBackingViewModel.moveWordLeft(extend)

    /** See `PaneBackingViewModel.moveWordRight`. */
    fun moveWordRight(extend: Boolean = false) = paneBackingViewModel.moveWordRight(extend)

    /** See `PaneBackingViewModel.moveDocStart`. */
    fun moveDocStart(extend: Boolean = false) = paneBackingViewModel.moveDocStart(extend)

    /** See `PaneBackingViewModel.moveDocEnd`. */
    fun moveDocEnd(extend: Boolean = false) = paneBackingViewModel.moveDocEnd(extend)

    // ---- structural intents ---------------------------------------------

    /** See `PaneBackingViewModel.indentLine`. */
    fun indentLine(amount: Int = 2) = paneBackingViewModel.indentLine(amount)

    /** See `PaneBackingViewModel.outdentLine`. */
    fun outdentLine(amount: Int = 2) = paneBackingViewModel.outdentLine(amount)

    /** See `PaneBackingViewModel.isBulletLine`. */
    fun isBulletLine(): Boolean = paneBackingViewModel.isBulletLine()

    // ---- block intents (TRF-5) --------------------------------------------

    /** See `PaneBackingViewModel.isBlockLine`. */
    fun isBlockLine(): Boolean = paneBackingViewModel.isBlockLine()

    /** See `PaneBackingViewModel.insertBlock`. */
    fun insertBlock() = paneBackingViewModel.insertBlock()

    /** See `PaneBackingViewModel.insertMarkdownAsBlock`. */
    fun insertMarkdownAsBlock(markdownText: String) = paneBackingViewModel.insertMarkdownAsBlock(markdownText)

    /** See `PaneBackingViewModel.deleteBlock`. */
    fun deleteBlock(lineId: LineId) = paneBackingViewModel.deleteBlock(lineId)

    /** See `PaneBackingViewModel.deleteBlockAtCursor`. */
    fun deleteBlockAtCursor() = paneBackingViewModel.deleteBlockAtCursor()

    /** See `PaneBackingViewModel.convertBlockToNodes`. */
    fun convertBlockToNodes() = paneBackingViewModel.convertBlockToNodes()

    /** See `PaneBackingViewModel.hiddenBlockRows`. */
    fun hiddenBlockRows(state: PaneBackingViewModel.State, block: IntRange): Int? =
        paneBackingViewModel.hiddenBlockRows(state, block)

    /** See `PaneBackingViewModel.toggleBlockExpanded`. */
    fun toggleBlockExpanded(lineId: LineId) = paneBackingViewModel.toggleBlockExpanded(lineId)

    /** See `PaneBackingViewModel.visibleRows`. */
    fun visibleRows(state: PaneBackingViewModel.State, startRow: Int, endRowInclusive: Int): List<Int> =
        paneBackingViewModel.visibleRows(state, startRow, endRowInclusive)

    /** See `PaneBackingViewModel.openSearch`. */
    fun openSearch() = paneBackingViewModel.openSearch()

    /** See `PaneBackingViewModel.setSearchQuery`. */
    fun setSearchQuery(query: String) = paneBackingViewModel.setSearchQuery(query)

    /** See `PaneBackingViewModel.tagSuggestions`. */
    fun tagSuggestions(prefix: String): List<se.soderbjorn.lunarbor.data.TagCount> =
        paneBackingViewModel.tagSuggestions(prefix)

    /** See `PaneBackingViewModel.insertSearchNode`. */
    fun insertSearchNode() = paneBackingViewModel.insertSearchNode()

    /** See `PaneBackingViewModel.setSearchReversed`. */
    fun setSearchReversed(reversed: Boolean) = paneBackingViewModel.setSearchReversed(reversed)

    /** See `PaneBackingViewModel.noteScroll`. */
    fun noteScroll(top: Double) = paneBackingViewModel.noteScroll(top)

    /** See `PaneBackingViewModel.restoreScroll`. */
    fun restoreScroll(top: Double, location: PaneBackingViewModel.FileHistoryEntry) =
        paneBackingViewModel.restoreScroll(top, location)

    /** See `PaneBackingViewModel.restoreCaret`. */
    fun restoreCaret(caret: PaneBackingViewModel.Caret, location: PaneBackingViewModel.FileHistoryEntry) =
        paneBackingViewModel.restoreCaret(caret, location)

    /** See `PaneBackingViewModel.currentCaret`. */
    fun currentCaret(): PaneBackingViewModel.Caret? = paneBackingViewModel.currentCaret()

    /** See `PaneBackingViewModel.closeSearch`. */
    fun closeSearch() = paneBackingViewModel.closeSearch()

    /** See `PaneBackingViewModel.searchHitCrumbs`. */
    fun searchHitCrumbs(hit: se.soderbjorn.lunarbor.data.TextHit, under: String? = null): List<String> =
        paneBackingViewModel.searchHitCrumbs(hit, under)

    /** See `PaneBackingViewModel.searchNodeOf`. */
    fun searchNodeOf(state: PaneBackingViewModel.State, row: Int): PaneBackingViewModel.SearchNodeView? =
        paneBackingViewModel.searchNodeOf(state, row)


    /**
     * Platform glue: opens a search result in a new window. Set by
     * `MainScreen` (which gets it from `AppShell`) so the paint loop's
     * search-node rows can offer it; `null` until then.
     */
    var openSearchHitInNewWindow: ((se.soderbjorn.lunarbor.data.TextHit) -> Unit)? = null

    /** See `PaneBackingViewModel.navigateToSearchHit`. */
    fun navigateToSearchHit(hit: se.soderbjorn.lunarbor.data.TextHit) = paneBackingViewModel.navigateToSearchHit(hit)

    /** See `PaneBackingViewModel.openSearchHit`. */
    fun openSearchHit(hit: se.soderbjorn.lunarbor.data.TextHit) = paneBackingViewModel.openSearchHit(hit)

    /** See `PaneBackingViewModel.exitBlock`. */
    fun exitBlock() = paneBackingViewModel.exitBlock()

    /** See `PaneBackingViewModel.moveDownOutOfBlock`. */
    fun moveDownOutOfBlock(): Boolean = paneBackingViewModel.moveDownOutOfBlock()

    /** See `PaneBackingViewModel.moveUpOutOfBlock`. */
    fun moveUpOutOfBlock(): Boolean = paneBackingViewModel.moveUpOutOfBlock()

    /** See `PaneBackingViewModel.exitBlockAbove`. */
    fun exitBlockAbove() = paneBackingViewModel.exitBlockAbove()

    // ---- drag intents ---------------------------------------------------

    /** See `PaneBackingViewModel.subtreeRange`. */
    fun subtreeRange(row: Int): IntRange? = paneBackingViewModel.subtreeRange(row)

    /** See `PaneBackingViewModel.moveLineRange`. */
    /** See `PaneBackingViewModel.dropTarget`. */
    fun dropTarget(sourceStart: Int, sourceEnd: Int, hoverRow: Int, insertAbove: Boolean, levelDelta: Int) =
        paneBackingViewModel.dropTarget(sourceStart, sourceEnd, hoverRow, insertAbove, levelDelta)

    fun moveLineRange(
        fromStartRow: Int,
        fromEndRow: Int,
        insertBeforeRow: Int,
        targetIndent: Int,
    ) = paneBackingViewModel.moveLineRange(fromStartRow, fromEndRow, insertBeforeRow, targetIndent)

    // ---- zoom intents ---------------------------------------------------

    /** See `PaneBackingViewModel.zoomInto`. */
    fun zoomInto(row: Int) = paneBackingViewModel.zoomInto(row)

    /** See `PaneBackingViewModel.zoomOut`. */
    fun zoomOut() = paneBackingViewModel.zoomOut()

    /** See `PaneBackingViewModel.navigateUp`. */
    fun navigateUp() = paneBackingViewModel.navigateUp()

    /** See `PaneBackingViewModel.navigateHome`. */
    fun navigateHome() = paneBackingViewModel.navigateHome()

    /** See `PaneBackingViewModel.canNavigateUp`. */
    fun canNavigateUp(state: PaneBackingViewModel.State) = paneBackingViewModel.canNavigateUp(state)

    /** See `PaneBackingViewModel.parentFileOf`. */
    fun parentFileOf(fileRel: String) = paneBackingViewModel.parentFileOf(fileRel)

    /** See `PaneBackingViewModel.zoomTo`. */
    fun zoomTo(lineId: LineId?) = paneBackingViewModel.zoomTo(lineId)

    /** See `PaneBackingViewModel.zoomBack`. */
    fun zoomBack() = paneBackingViewModel.zoomBack()

    /** See `PaneBackingViewModel.zoomForward`. */
    fun zoomForward() = paneBackingViewModel.zoomForward()

    /** See `PaneBackingViewModel.canZoomBack`. */
    fun canZoomBack(state: PaneBackingViewModel.State): Boolean =
        paneBackingViewModel.canZoomBack(state)

    /** See `PaneBackingViewModel.canZoomForward`. */
    fun canZoomForward(state: PaneBackingViewModel.State): Boolean =
        paneBackingViewModel.canZoomForward(state)

    /** See `PaneBackingViewModel.zoomInfo`. */
    fun zoomInfo(state: PaneBackingViewModel.State) = paneBackingViewModel.zoomInfo(state)

    /** See `PaneBackingViewModel.bulletAncestors`. */
    fun bulletAncestors(state: PaneBackingViewModel.State) =
        paneBackingViewModel.bulletAncestors(state)

    /** See `PaneBackingViewModel.zoomPathSegments`. */
    fun zoomPathSegments(state: PaneBackingViewModel.State): List<String> =
        paneBackingViewModel.zoomPathSegments(state)

    /** See `PaneBackingViewModel.fileBreadcrumb`. */
    fun fileBreadcrumb(state: PaneBackingViewModel.State) =
        paneBackingViewModel.fileBreadcrumb(state)

    // ---- collapse intents -----------------------------------------------

    /** See `PaneBackingViewModel.toggleCollapse`. */
    fun toggleCollapse(lineId: LineId) = paneBackingViewModel.toggleCollapse(lineId)

    /** See `PaneBackingViewModel.setCaretItemFolded`. */
    fun setCaretItemFolded(folded: Boolean) = paneBackingViewModel.setCaretItemFolded(folded)

    /** See `PaneBackingViewModel.locationOfRow`. */
    fun locationOfRow(row: Int) = paneBackingViewModel.locationOfRow(row)

    /** See `PaneBackingViewModel.setAllChildrenFolded`. */
    fun setAllChildrenFolded(folded: Boolean) = paneBackingViewModel.setAllChildrenFolded(folded)

    /** See `PaneBackingViewModel.pageNodeTitle`. */
    fun pageNodeTitle(state: PaneBackingViewModel.State) = paneBackingViewModel.pageNodeTitle(state)

    /** See `PaneBackingViewModel.deletePageNode`. */
    fun deletePageNode() = paneBackingViewModel.deletePageNode()

    /** See `PaneBackingViewModel.pageFile`. */
    fun pageFile(state: PaneBackingViewModel.State) = paneBackingViewModel.pageFile(state)

    /** See `PaneBackingViewModel.trashPageFile`. */
    suspend fun trashPageFile(): String? = paneBackingViewModel.trashPageFile()

    /** See `PaneBackingViewModel.sortChildrenByName`. */
    fun sortChildrenByName(reverse: Boolean = false) = paneBackingViewModel.sortChildrenByName(reverse)

    /** See `PaneBackingViewModel.isPromotedRef`. */
    fun isPromotedRef(lineId: LineId): Boolean = paneBackingViewModel.isPromotedRef(lineId)

    // ---- selection intents ----------------------------------------------

    /** See `PaneBackingViewModel.selectAll`. */
    fun selectAll() = paneBackingViewModel.selectAll()

    /** See `PaneBackingViewModel.selectWord`. */
    fun selectWord(row: Int, col: Int) = paneBackingViewModel.selectWord(row, col)

    /** See `PaneBackingViewModel.selectLine`. */
    fun selectLine(row: Int) = paneBackingViewModel.selectLine(row)

    /** See `PaneBackingViewModel.clearSelection`. */
    fun clearSelection() = paneBackingViewModel.clearSelection()

    /** See `PaneBackingViewModel.setSelection`. */
    fun setSelection(anchorRow: Int, anchorCol: Int, cursorRow: Int, cursorCol: Int) =
        paneBackingViewModel.setSelection(anchorRow, anchorCol, cursorRow, cursorCol)

    /** See `PaneBackingViewModel.deleteSelectionIfAny`. */
    fun deleteSelectionIfAny() = paneBackingViewModel.deleteSelectionIfAny()

    /** See `PaneBackingViewModel.getSelectedText`. */
    fun getSelectedText(): String? = paneBackingViewModel.getSelectedText()

    /** See `PaneBackingViewModel.onCutRequested`. */
    fun onCutRequested(): String? = paneBackingViewModel.onCutRequested()

    // ---- markdown style intents -----------------------------------------

    /** See `PaneBackingViewModel.applyInlineStyle`. */
    fun applyInlineStyle(style: InlineStyle) = paneBackingViewModel.applyInlineStyle(style)

    /** See `PaneBackingViewModel.applyLineStyle`. */
    fun applyLineStyle(style: LineStyle) = paneBackingViewModel.applyLineStyle(style)

    /** See `PaneBackingViewModel.activeInlineStyles`. */
    fun activeInlineStyles(): Set<InlineStyle> = paneBackingViewModel.activeInlineStyles()

    /** See `PaneBackingViewModel.activeLineStyle`. */
    fun activeLineStyle(): LineStyle? = paneBackingViewModel.activeLineStyle()

    /** See `PaneBackingViewModel.linkPreviewOf`. */
    fun linkPreviewOf(state: PaneBackingViewModel.State, row: Int) =
        paneBackingViewModel.linkPreviewOf(state, row)

    /** See `PaneBackingViewModel.zoomLinkPreviewOf`. */
    fun zoomLinkPreviewOf(state: PaneBackingViewModel.State) =
        paneBackingViewModel.zoomLinkPreviewOf(state)

    /** See `PaneBackingViewModel.toggleLinkPreview`. */
    fun toggleLinkPreview(lineId: LineId) = paneBackingViewModel.toggleLinkPreview(lineId)

    /** See `PaneBackingViewModel.codeBlockState`. */
    fun codeBlockState(): Boolean? = paneBackingViewModel.codeBlockState()

    // ---- folder contents intents ----------------------------------------

    /** Vault-relative path of the root file. Forwarded from the document VM. */
    val rootFileName: String get() = paneBackingViewModel.rootFileName

    /** See `PaneBackingViewModel.navigateToVaultFile`. */
    fun navigateToVaultFile(pathRel: String) = paneBackingViewModel.navigateToVaultFile(pathRel)

    /** See `PaneBackingViewModel.currentNodeFolder`. */
    fun currentNodeFolder(state: PaneBackingViewModel.State): String? =
        paneBackingViewModel.currentNodeFolder(state)

    /** See `PaneBackingViewModel.folderContentsOf`. */
    fun folderContentsOf(state: PaneBackingViewModel.State, dirRel: String): List<VaultEntry>? =
        paneBackingViewModel.folderContentsOf(state, dirRel)

    /** See `PaneBackingViewModel.folderContentsOfBullet`. */
    fun folderContentsOfBullet(state: PaneBackingViewModel.State, lineId: LineId): List<VaultEntry>? =
        paneBackingViewModel.folderContentsOfBullet(state, lineId)

    /** See `PaneBackingViewModel.openFolderAsNode`. */
    fun openFolderAsNode(dirRel: String) = paneBackingViewModel.openFolderAsNode(dirRel)

    /** See `PaneBackingViewModel.newMarkdownFile`. */
    fun newMarkdownFile() = paneBackingViewModel.newMarkdownFile()

    /** See `PaneBackingViewModel.newDrawingFile`. */
    fun newDrawingFile() = paneBackingViewModel.newDrawingFile()

    /** See `PaneBackingViewModel.loadDrawing`. */
    suspend fun loadDrawing(fileRel: String): String? = paneBackingViewModel.loadDrawing(fileRel)

    /** See `PaneBackingViewModel.onDrawingChanged`. */
    fun onDrawingChanged(fileRel: String, text: String) = paneBackingViewModel.onDrawingChanged(fileRel, text)

    /** See `PaneBackingViewModel.currentLocation`. */
    fun currentLocation(): PaneBackingViewModel.FileHistoryEntry = paneBackingViewModel.currentLocation()

    /** See `PaneBackingViewModel.openLocation`. */
    fun openLocation(location: PaneBackingViewModel.FileHistoryEntry): kotlinx.coroutines.Job = paneBackingViewModel.openLocation(location)

    /** See `PaneBackingViewModel.renameActiveFile`. */
    fun renameActiveFile(title: String) = paneBackingViewModel.renameActiveFile(title)

    /** See `PaneBackingViewModel.renamedTo`. */
    fun renamedTo(fromRel: String): String? = paneBackingViewModel.renamedTo(fromRel)

    /** See `PaneBackingViewModel.refreshCurrentFolderListing`. */
    fun refreshCurrentFolderListing() = paneBackingViewModel.refreshCurrentFolderListing()

    /**
     * Opens the vault file [pathRel] in the system's default app for its
     * type (TRF-7: an "other file" row in the folder contents list).
     * Platform glue with no commonMain counterpart: forwards to the
     * Electron main process (`noteApi.openPath` → `shell.openPath`), which
     * resolves the path against the vault root and refuses anything
     * outside it. Does not touch pane state or file history — the file
     * opens outside Lunarbor. In the browser demo it opens in a new tab.
     *
     * @param pathRel Vault-relative path of the file.
     */
    fun openInDefaultApp(pathRel: String) {
        // The browser demo has no default apps: the file opens in a new
        // browser tab, served from memory.
        if (demoFileSystem != null) {
            window.open(lunarborAssetUrl(pathRel), "_blank")
            return
        }
        val bridge = window.asDynamic().noteApi
        if (bridge == null || bridge.openPath == null) {
            console.warn("[lunarbor] noteApi.openPath unavailable; cannot open $pathRel")
            return
        }
        bridge.openPath(pathRel)
    }

    /**
     * Shows the vault entry [pathRel] selected in its folder in Finder
     * ("Reveal in Finder" in a folder-entry menu). Platform glue like
     * [openInDefaultApp]: forwards to `noteApi.revealPath` →
     * `shell.showItemInFolder`, confined to the vault by the main process.
     *
     * @param pathRel Vault-relative path of the file or folder.
     */
    fun revealInFinder(pathRel: String) {
        val bridge = window.asDynamic().noteApi
        if (bridge == null || bridge.revealPath == null) {
            console.warn("[lunarbor] noteApi.revealPath unavailable; cannot reveal $pathRel")
            return
        }
        bridge.revealPath(pathRel)
    }

    /** See `PaneBackingViewModel.convertNoteToNode`. */
    suspend fun convertNoteToNode(noteRel: String): LineId? = paneBackingViewModel.convertNoteToNode(noteRel)

    /** See `PaneBackingViewModel.trashConvertedNote`. */
    suspend fun trashConvertedNote(noteRel: String, nodeId: LineId): String? =
        paneBackingViewModel.trashConvertedNote(noteRel, nodeId)

    /** See `PaneBackingViewModel.convertFolderToNode`. */
    suspend fun convertFolderToNode(folderRel: String, recursive: Boolean): PaneBackingViewModel.FolderConversion? =
        paneBackingViewModel.convertFolderToNode(folderRel, recursive)

    /** See `PaneBackingViewModel.trashFile`. */
    suspend fun trashFile(fileRel: String): String? = paneBackingViewModel.trashFile(fileRel)

    /** See `PaneBackingViewModel.trashConvertedNotes`. */
    suspend fun trashConvertedNotes(notes: Map<String, String>): List<String> =
        paneBackingViewModel.trashConvertedNotes(notes)

    // ---- link intents ---------------------------------------------------

    /** App-scoped link index; the link modals search it (`VaultIndex.search`). */
    val vaultIndex: VaultIndex get() = paneBackingViewModel.vaultIndex

    /** See `PaneBackingViewModel.prepareLinkSearch`. */
    suspend fun prepareLinkSearch() = paneBackingViewModel.prepareLinkSearch()

    /** See `PaneBackingViewModel.insertLinkTo`. */
    fun insertLinkTo(target: LinkTarget, label: String = "") = paneBackingViewModel.insertLinkTo(target, label)

    /** See `PaneBackingViewModel.retargetLinkAt`. */
    fun retargetLinkAt(row: Int, col: Int, target: LinkTarget) = paneBackingViewModel.retargetLinkAt(row, col, target)

    /** See `PaneBackingViewModel.removeLinkAt`. */
    fun removeLinkAt(row: Int, col: Int) = paneBackingViewModel.removeLinkAt(row, col)

    /** See `PaneBackingViewModel.editLinkTextAt`. */
    fun editLinkTextAt(row: Int, col: Int) = paneBackingViewModel.editLinkTextAt(row, col)

    /** See `PaneBackingViewModel.wikiLinkHref`. */
    fun wikiLinkHref(state: PaneBackingViewModel.State, name: String): String? =
        paneBackingViewModel.wikiLinkHref(state, name)

    /** See `PaneBackingViewModel.isLinkBroken`. */
    fun isLinkBroken(state: PaneBackingViewModel.State, url: String): Boolean =
        paneBackingViewModel.isLinkBroken(state, url)

    /** See `PaneBackingViewModel.currentLocationPath`. */
    suspend fun currentLocationPath(): String? = paneBackingViewModel.currentLocationPath()

    /** See `PaneBackingViewModel.toggleStarred`. */
    suspend fun toggleStarred(starred: Boolean) = paneBackingViewModel.toggleStarred(starred)

    /** See `PaneBackingViewModel.insertMarkdownLink`. */
    fun insertMarkdownLink(label: String, url: String) =
        paneBackingViewModel.insertMarkdownLink(label, url)

    /** See `PaneBackingViewModel.insertImageRef`. */
    fun insertImageRef(src: String, alt: String = "", widthPx: Int? = null) =
        paneBackingViewModel.insertImageRef(src, alt, widthPx)

    /** See `PaneBackingViewModel.insertVaultImage`. */
    fun insertVaultImage(vaultRelPath: String) = paneBackingViewModel.insertVaultImage(vaultRelPath)

    /** See `PaneBackingViewModel.resolveImageSrc`. */
    fun resolveImageSrc(row: Int, src: String): String? = paneBackingViewModel.resolveImageSrc(row, src)

    /** See `PaneBackingViewModel.listImageFiles`. */
    suspend fun listImageFiles(): List<String> = paneBackingViewModel.listImageFiles()

    /** See `PaneBackingViewModel.onImagePasted`. */
    suspend fun onImagePasted(suggestedName: String, bytes: ByteArray) =
        paneBackingViewModel.onImagePasted(suggestedName, bytes)

    /** See `PaneBackingViewModel.setImageWidth`. */
    fun setImageWidth(row: Int, imageSrc: String, widthPx: Int?) =
        paneBackingViewModel.setImageWidth(row, imageSrc, widthPx)

    /**
     * See `PaneBackingViewModel.navigateToLink`. A link to a file that is
     * neither a note nor an image opens in the system's default app
     * ([openInDefaultApp]), the one platform-specific part.
     */
    fun navigateToLink(url: String, onComplete: () -> Unit = {}) =
        paneBackingViewModel.navigateToLink(url, onComplete, openExternally = ::openInDefaultApp)

    /**
     * Releases the underlying [Document] back to the registry. Call
     * from `AppShell.closeFloatingPane` so the registry can drop the doc when
     * its last pane goes away.
     */
    suspend fun release() = paneBackingViewModel.release()
}
