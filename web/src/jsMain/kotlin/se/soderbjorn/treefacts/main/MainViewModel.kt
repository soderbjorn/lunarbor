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

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.browser.window
import kotlinx.coroutines.launch
import se.soderbjorn.treefacts.data.InlineStyle
import se.soderbjorn.treefacts.data.LineStyle
import se.soderbjorn.treefacts.data.VaultEntry
import se.soderbjorn.treefacts.data.LinkTarget
import se.soderbjorn.treefacts.data.VaultIndex

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

    /** See `PaneBackingViewModel.deleteBlock`. */
    fun deleteBlock(lineId: LineId) = paneBackingViewModel.deleteBlock(lineId)

    /** See `PaneBackingViewModel.deleteBlockAtCursor`. */
    fun deleteBlockAtCursor() = paneBackingViewModel.deleteBlockAtCursor()

    /** See `PaneBackingViewModel.exitBlock`. */
    fun exitBlock() = paneBackingViewModel.exitBlock()

    // ---- drag intents ---------------------------------------------------

    /** See `PaneBackingViewModel.subtreeRange`. */
    fun subtreeRange(row: Int): IntRange? = paneBackingViewModel.subtreeRange(row)

    /** See `PaneBackingViewModel.moveLineRange`. */
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

    // ---- collapse intents -----------------------------------------------

    /** See `PaneBackingViewModel.toggleCollapse`. */
    fun toggleCollapse(lineId: LineId) = paneBackingViewModel.toggleCollapse(lineId)

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

    /** See `PaneBackingViewModel.refreshCurrentFolderListing`. */
    fun refreshCurrentFolderListing() = paneBackingViewModel.refreshCurrentFolderListing()

    /**
     * Opens the vault file [pathRel] in the system's default app for its
     * type (TRF-7: an "other file" row in the folder contents list).
     * Platform glue with no commonMain counterpart: forwards to the
     * Electron main process (`noteApi.openPath` → `shell.openPath`), which
     * resolves the path against the vault root and refuses anything
     * outside it. Does not touch pane state or file history — the file
     * opens outside TreeFacts.
     *
     * @param pathRel Vault-relative path of the file.
     */
    fun openInDefaultApp(pathRel: String) {
        val bridge = window.asDynamic().noteApi
        if (bridge == null || bridge.openPath == null) {
            console.warn("[treefacts] noteApi.openPath unavailable; cannot open $pathRel")
            return
        }
        bridge.openPath(pathRel)
    }

    // ---- link intents ---------------------------------------------------

    /** App-scoped link index; the link modals search it (`VaultIndex.search`). */
    val vaultIndex: VaultIndex get() = paneBackingViewModel.vaultIndex

    /** See `PaneBackingViewModel.prepareLinkSearch`. */
    suspend fun prepareLinkSearch() = paneBackingViewModel.prepareLinkSearch()

    /** See `PaneBackingViewModel.insertLinkTo`. */
    fun insertLinkTo(target: LinkTarget, label: String = "") = paneBackingViewModel.insertLinkTo(target, label)

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
     * from `AppShell.closePane` so the registry can drop the doc when
     * its last pane goes away.
     */
    suspend fun release() = paneBackingViewModel.release()
}
