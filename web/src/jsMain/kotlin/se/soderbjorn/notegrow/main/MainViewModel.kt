/*
 * MainViewModel.kt (jsMain)
 * -------------------------
 * Web-platform facade over `DocumentViewBackingViewModel`. Its only jobs are
 * to wrap the backing state in a platform-specific envelope (`State`) that
 * `MainScreen` consumes, and to delegate every user intent to the backing VM
 * with a one-liner.
 *
 * All editor logic lives one layer down. Keep this class boring — the only
 * code that belongs here is platform-specific glue that cannot exist in
 * commonMain (there is none today on web). Android and iOS will have their
 * own `MainViewModel` implementations with the same shape.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineStyle

/**
 * Thin web-platform ViewModel that `MainScreen` collects from. Receives the
 * shared `DocumentViewBackingViewModel` via DI and re-emits its state
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
 * @param backingViewModel The common `DocumentViewBackingViewModel` this
 *   platform layer delegates to.
 */
class MainViewModel(
    scope: CoroutineScope,
    private val backingViewModel: DocumentViewBackingViewModel
) {
    /**
     * Envelope state exposed to the web view.
     *
     * @property backingState Latest snapshot from `DocumentViewBackingViewModel`,
     *   or `null` before the first emission (which causes the view to show
     *   a "Loading…" placeholder).
     */
    data class State(val backingState: DocumentViewBackingViewModel.State? = null)

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
    val currentBackingState: DocumentViewBackingViewModel.State
        get() = backingViewModel.stateFlow.value

    init {
        scope.launch {
            backingViewModel.stateFlow.collect { backing ->
                _stateFlow.value = State(backingState = backing)
            }
        }
    }

    // ---- editing intents ------------------------------------------------

    /** See `DocumentViewBackingViewModel.insertChar`. */
    fun insertChar(char: Char) = backingViewModel.insertChar(char)

    /** See `DocumentViewBackingViewModel.insertNewline`. */
    fun insertNewline() = backingViewModel.insertNewline()

    /** See `DocumentViewBackingViewModel.insertText`. */
    fun insertText(text: String) = backingViewModel.insertText(text)

    /** See `DocumentViewBackingViewModel.backspace`. */
    fun backspace() = backingViewModel.backspace()

    // ---- movement intents -----------------------------------------------

    /** See `DocumentViewBackingViewModel.moveLeft`. */
    fun moveLeft(extend: Boolean = false) = backingViewModel.moveLeft(extend)

    /** See `DocumentViewBackingViewModel.moveRight`. */
    fun moveRight(extend: Boolean = false) = backingViewModel.moveRight(extend)

    /** See `DocumentViewBackingViewModel.moveUp`. */
    fun moveUp(extend: Boolean = false) = backingViewModel.moveUp(extend)

    /** See `DocumentViewBackingViewModel.moveDown`. */
    fun moveDown(extend: Boolean = false) = backingViewModel.moveDown(extend)

    /** See `DocumentViewBackingViewModel.moveTo`. */
    fun moveTo(row: Int, col: Int, extend: Boolean = false) = backingViewModel.moveTo(row, col, extend)

    /** See `DocumentViewBackingViewModel.moveLineStart`. */
    fun moveLineStart(extend: Boolean = false) = backingViewModel.moveLineStart(extend)

    /** See `DocumentViewBackingViewModel.moveLineEnd`. */
    fun moveLineEnd(extend: Boolean = false) = backingViewModel.moveLineEnd(extend)

    /** See `DocumentViewBackingViewModel.moveWordLeft`. */
    fun moveWordLeft(extend: Boolean = false) = backingViewModel.moveWordLeft(extend)

    /** See `DocumentViewBackingViewModel.moveWordRight`. */
    fun moveWordRight(extend: Boolean = false) = backingViewModel.moveWordRight(extend)

    /** See `DocumentViewBackingViewModel.moveDocStart`. */
    fun moveDocStart(extend: Boolean = false) = backingViewModel.moveDocStart(extend)

    /** See `DocumentViewBackingViewModel.moveDocEnd`. */
    fun moveDocEnd(extend: Boolean = false) = backingViewModel.moveDocEnd(extend)

    // ---- structural intents ---------------------------------------------

    /** See `DocumentViewBackingViewModel.indentLine`. */
    fun indentLine(amount: Int = 2) = backingViewModel.indentLine(amount)

    /** See `DocumentViewBackingViewModel.outdentLine`. */
    fun outdentLine(amount: Int = 2) = backingViewModel.outdentLine(amount)

    /** See `DocumentViewBackingViewModel.isBulletLine`. */
    fun isBulletLine(): Boolean = backingViewModel.isBulletLine()

    // ---- drag intents ---------------------------------------------------

    /** See `DocumentViewBackingViewModel.subtreeRange`. */
    fun subtreeRange(row: Int): IntRange? = backingViewModel.subtreeRange(row)

    /** See `DocumentViewBackingViewModel.moveLineRange`. */
    fun moveLineRange(
        fromStartRow: Int,
        fromEndRow: Int,
        insertBeforeRow: Int,
        targetIndent: Int,
    ) = backingViewModel.moveLineRange(fromStartRow, fromEndRow, insertBeforeRow, targetIndent)

    // ---- zoom intents ---------------------------------------------------

    /** See `DocumentViewBackingViewModel.zoomInto`. */
    fun zoomInto(row: Int) = backingViewModel.zoomInto(row)

    /** See `DocumentViewBackingViewModel.zoomOut`. */
    fun zoomOut() = backingViewModel.zoomOut()

    /** See `DocumentViewBackingViewModel.zoomTo`. */
    fun zoomTo(lineId: LineId?) = backingViewModel.zoomTo(lineId)

    /** See `DocumentViewBackingViewModel.zoomBack`. */
    fun zoomBack() = backingViewModel.zoomBack()

    /** See `DocumentViewBackingViewModel.zoomForward`. */
    fun zoomForward() = backingViewModel.zoomForward()

    /** See `DocumentViewBackingViewModel.canZoomBack`. */
    fun canZoomBack(state: DocumentViewBackingViewModel.State): Boolean =
        backingViewModel.canZoomBack(state)

    /** See `DocumentViewBackingViewModel.canZoomForward`. */
    fun canZoomForward(state: DocumentViewBackingViewModel.State): Boolean =
        backingViewModel.canZoomForward(state)

    /** See `DocumentViewBackingViewModel.zoomInfo`. */
    fun zoomInfo(state: DocumentViewBackingViewModel.State) = backingViewModel.zoomInfo(state)

    /** See `DocumentViewBackingViewModel.bulletAncestors`. */
    fun bulletAncestors(state: DocumentViewBackingViewModel.State) =
        backingViewModel.bulletAncestors(state)

    /** See `DocumentViewBackingViewModel.zoomPathSegments`. */
    fun zoomPathSegments(state: DocumentViewBackingViewModel.State): List<String> =
        backingViewModel.zoomPathSegments(state)

    // ---- collapse intents -----------------------------------------------

    /** See `DocumentViewBackingViewModel.toggleCollapse`. */
    fun toggleCollapse(lineId: LineId) = backingViewModel.toggleCollapse(lineId)

    /** See `DocumentViewBackingViewModel.isPromotedRef`. */
    fun isPromotedRef(lineId: LineId): Boolean = backingViewModel.isPromotedRef(lineId)

    // ---- selection intents ----------------------------------------------

    /** See `DocumentViewBackingViewModel.selectAll`. */
    fun selectAll() = backingViewModel.selectAll()

    /** See `DocumentViewBackingViewModel.selectWord`. */
    fun selectWord(row: Int, col: Int) = backingViewModel.selectWord(row, col)

    /** See `DocumentViewBackingViewModel.selectLine`. */
    fun selectLine(row: Int) = backingViewModel.selectLine(row)

    /** See `DocumentViewBackingViewModel.clearSelection`. */
    fun clearSelection() = backingViewModel.clearSelection()

    /** See `DocumentViewBackingViewModel.setSelection`. */
    fun setSelection(anchorRow: Int, anchorCol: Int, cursorRow: Int, cursorCol: Int) =
        backingViewModel.setSelection(anchorRow, anchorCol, cursorRow, cursorCol)

    /** See `DocumentViewBackingViewModel.deleteSelectionIfAny`. */
    fun deleteSelectionIfAny() = backingViewModel.deleteSelectionIfAny()

    /** See `DocumentViewBackingViewModel.getSelectedText`. */
    fun getSelectedText(): String? = backingViewModel.getSelectedText()

    /** See `DocumentViewBackingViewModel.onCutRequested`. */
    fun onCutRequested(): String? = backingViewModel.onCutRequested()

    // ---- markdown style intents -----------------------------------------

    /** See `DocumentViewBackingViewModel.applyInlineStyle`. */
    fun applyInlineStyle(style: InlineStyle) = backingViewModel.applyInlineStyle(style)

    /** See `DocumentViewBackingViewModel.applyLineStyle`. */
    fun applyLineStyle(style: LineStyle) = backingViewModel.applyLineStyle(style)

    /** See `DocumentViewBackingViewModel.activeInlineStyles`. */
    fun activeInlineStyles(): Set<InlineStyle> = backingViewModel.activeInlineStyles()

    /** See `DocumentViewBackingViewModel.activeLineStyle`. */
    fun activeLineStyle(): LineStyle? = backingViewModel.activeLineStyle()

    // ---- vault-footer intents -------------------------------------------

    /** Vault-relative path of the root file. Forwarded from the document VM. */
    val rootFileName: String get() = backingViewModel.rootFileName

    /** See `DocumentViewBackingViewModel.toggleVaultFooter`. */
    fun toggleVaultFooter() = backingViewModel.toggleVaultFooter()

    /** See `DocumentViewBackingViewModel.toggleVaultFolder`. */
    fun toggleVaultFolder(dirRel: String) = backingViewModel.toggleVaultFolder(dirRel)

    /** See `DocumentViewBackingViewModel.navigateToVaultFile`. */
    fun navigateToVaultFile(pathRel: String) = backingViewModel.navigateToVaultFile(pathRel)
}
