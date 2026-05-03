/*
 * StarredModal.kt (jsMain)
 * ------------------------
 * Read-only popup that displays the contents of `Starred.md` — the user's
 * bookmark file — using the same outline renderer the live editor uses.
 *
 * One instance per pane (created lazily by [AppShell.openStarredModal]).
 * The modal:
 *
 * 1. Reads `Starred.md` through a *private* document-VM trio whose
 *    `rootFileName` is `Starred.md`. The trio is rebuilt on every open so
 *    a fresh disk snapshot is shown.
 * 2. Paints the file via [paint] inside a non-`contenteditable` host so
 *    the user cannot edit it from inside the modal.
 * 3. Intercepts clicks on the rendered bullet rows in the *capture* phase
 *    so the bullet's own zoom-into-modal handler never fires; instead the
 *    modal closes and the *parent pane*'s [MainViewModel] is asked to
 *    navigate to the bookmark target.
 * 4. Provides an "Add to starred" toggle that snapshots the parent pane's
 *    current navigation target — file alone, or `(file, zoomedRow)` — and
 *    appends a markdown-link bullet to `Starred.md` via
 *    [NoteRepository.appendStarredEntry].
 *
 * The modal is appended to `document.body` with `position: fixed`. ESC,
 * outside-click on the backdrop, and the close button all dismiss it.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.notegrow.data.NoteRepository
import se.soderbjorn.notegrow.data.SubtreeCodec
import se.soderbjorn.notegrow.platform.FileSystem

/**
 * Per-pane Starred bookmarks modal.
 *
 * @param parentScope App-scoped coroutine scope. The modal launches its
 *   own per-open [SupervisorJob] underneath this scope so cancelling the
 *   modal cancels its document-VM autosave loop and state collector
 *   without disturbing anything else. [dispose] cancels everything.
 * @param fileSystem Platform filesystem, used to construct the modal's
 *   private [NoteRepository] (rooted at `Starred.md`).
 * @param noteRepository Shared repository used to append new bookmarks
 *   via [NoteRepository.appendStarredEntry]. Distinct from the modal's
 *   private repository so writes go through the same instance the rest
 *   of the app uses (keeping the on-disk file consistent with autosave's
 *   view of the world).
 * @param activePaneVmProvider Returns the parent pane's current
 *   [MainViewModel], or `null` if the pane has been torn down. Looked up
 *   on every interaction so the modal always targets the latest VM if
 *   the pane was rebuilt.
 */
internal class StarredModal(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
) {
    private val style = EditorStyle()

    /**
     * Stateless platform filesystem instance used for both the modal's
     * read-only document VM and bookmark writes. Constructed locally so
     * the modal does not need to be threaded through DI — `FileSystem`
     * holds no state across calls (every method delegates straight to
     * `window.noteApi`).
     */
    private val fileSystem = FileSystem()

    /**
     * Repository used by the "Add to starred" handler to append new
     * bookmark entries to `Starred.md`. Distinct instance from the rest
     * of the app's repository, but safe: writes go through `FileSystem`
     * directly with no in-memory cache that could diverge.
     */
    private val noteRepository = NoteRepository(fileSystem = fileSystem)

    /** Private repo whose `rootFileName` is `Starred.md`, so the modal's
     *  document VM boots straight into the bookmark file with no extra
     *  switchTo dance. Constructed once, reused across opens. */
    private val starredRepo = NoteRepository(
        fileSystem = fileSystem,
        rootFileName = NoteRepository.STARRED_FILE_NAME,
    )

    // ----- per-open state (null when the modal is closed) -----------------
    private var openJob: Job? = null
    private var openScope: CoroutineScope? = null
    private var modalDocBackingVm: DocumentBackingViewModel? = null
    private var modalDocViewVm: DocumentViewBackingViewModel? = null
    private var modalMainVm: MainViewModel? = null
    private var latestState: DocumentViewBackingViewModel.State? = null

    // ----- DOM refs -------------------------------------------------------
    private var backdropEl: HTMLElement? = null
    private var bodyEl: HTMLElement? = null
    private var addStarBtn: HTMLElement? = null
    private var addStarBtnLabelEl: HTMLElement? = null
    /** True when the parent pane's current target is already in
     *  `Starred.md`, so the toggle button acts as "Remove" rather than
     *  "Add". Kept in sync by [refreshAddStarButtonState]. */
    private var addStarBtnIsActive: Boolean = false
    private var documentKeyDownHandler: ((Event) -> Unit)? = null

    /**
     * Open the modal. If already open, closes the previous instance first
     * so we get a fresh snapshot of `Starred.md` and a fresh look at the
     * parent pane's current navigation target.
     */
    fun open() {
        if (backdropEl != null) closeInternal()

        val backdrop = buildBackdrop()
        val panel = buildPanel()
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)
        backdropEl = backdrop

        attachEscDismiss()
        startModalVms()
        refreshAddStarButtonState()
    }

    /**
     * Close the modal. Idempotent. Does NOT tear down the per-pane
     * registration in [AppShell.starredModals]; call [dispose] for that.
     */
    fun close() {
        closeInternal()
    }

    /**
     * Permanently dispose this modal — closes it and cancels the parent
     * registration's hold on this instance. Called from
     * [AppShell.closePane] when a pane is removed.
     */
    fun dispose() {
        closeInternal()
    }

    // ---------------------------------------------------------------- DOM

    private fun buildBackdrop(): HTMLElement {
        val b = document.createElement("div") as HTMLElement
        b.className = "notegrow-starred-backdrop"
        b.addEventListener("mousedown", { e ->
            // Only dismiss when the click originates on the backdrop
            // itself, not when it bubbles up from the panel.
            if ((e as MouseEvent).target === b) {
                e.preventDefault()
                e.stopPropagation()
                closeInternal()
            }
        })
        return b
    }

    private fun buildPanel(): HTMLElement {
        val panel = document.createElement("div") as HTMLElement
        panel.className = "notegrow-starred-panel"
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")
        panel.setAttribute("aria-label", "Starred")

        panel.appendChild(buildHeader())

        val body = document.createElement("div") as HTMLElement
        body.className = "notegrow-starred-body notegrow-editor"
        body.style.apply {
            fontFamily = style.fontFamily
            fontSize = "${style.fontSize}px"
            setProperty("line-height", "${style.lineHeightPx}px")
            paddingTop = "${style.editorPaddingTopPx}px"
            paddingBottom = "${style.editorPaddingBottomPx}px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            setProperty("overflow-y", "auto")
        }
        // Capture-phase click handler: intercept bullet/row clicks BEFORE
        // OutlinePaintLoop's per-bullet zoom handler fires, so the modal
        // closes and the parent pane navigates instead.
        body.addEventListener("click", { e ->
            handleBodyClick(e as MouseEvent)
        }, /* useCapture = */ true)
        bodyEl = body
        panel.appendChild(body)

        return panel
    }

    private fun buildHeader(): HTMLElement {
        val header = document.createElement("div") as HTMLElement
        header.className = "notegrow-starred-header"

        val title = document.createElement("div") as HTMLElement
        title.className = "notegrow-starred-title"
        title.textContent = "Starred"
        header.appendChild(title)

        val addBtn = document.createElement("button") as HTMLElement
        addBtn.className = "notegrow-starred-add"
        addBtn.setAttribute("type", "button")
        addBtn.title = "Add the active pane's current location to your starred list"
        val iconSpan = document.createElement("span") as HTMLElement
        iconSpan.className = "notegrow-starred-add-icon"
        iconSpan.innerHTML = AppShell.ICON_STAR
        addBtn.appendChild(iconSpan)
        val labelSpan = document.createElement("span") as HTMLElement
        labelSpan.className = "notegrow-starred-add-label"
        labelSpan.textContent = "Add to starred"
        addBtn.appendChild(labelSpan)
        addBtn.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault()
            e.stopPropagation()
            handleToggleStarred()
        })
        addStarBtn = addBtn
        addStarBtnLabelEl = labelSpan
        header.appendChild(addBtn)

        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "notegrow-starred-close"
        closeBtn.setAttribute("type", "button")
        closeBtn.title = "Close"
        closeBtn.setAttribute("aria-label", "Close")
        closeBtn.innerHTML = "&times;"
        closeBtn.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault()
            e.stopPropagation()
            closeInternal()
        })
        header.appendChild(closeBtn)

        return header
    }

    // ---------------------------------------------------------------- VMs

    /**
     * Build a fresh per-open document-VM trio rooted at `Starred.md`,
     * launch a state collector that paints the body on every emission,
     * and remember the latest state so the click handler can resolve
     * row → markdown link without re-querying.
     */
    private fun startModalVms() {
        val parentJob = parentScope.coroutineContext[Job]
        val job = SupervisorJob(parentJob)
        val scope = parentScope + job
        openJob = job
        openScope = scope

        val docBackingVm = DocumentBackingViewModel(starredRepo, scope)
        val docViewVm = DocumentViewBackingViewModel(docBackingVm, scope)
        val mainVm = MainViewModel(scope, docViewVm)
        modalDocBackingVm = docBackingVm
        modalDocViewVm = docViewVm
        modalMainVm = mainVm

        scope.launch {
            mainVm.stateFlow.collect { envelope ->
                val st = envelope.backingState ?: return@collect
                latestState = st
                val body = bodyEl ?: return@collect
                paint(body, st, mainVm, style)
                refreshAddStarButtonState()
            }
        }
    }

    // -------------------------------------------------------- interactions

    /**
     * Snapshot the parent pane's current navigation target — file alone,
     * or `(file, currentZoomedRow)` if the pane is zoomed — and either
     * append a bookmark line to `Starred.md` (when the target is not yet
     * starred) or remove every matching bookmark (when it already is).
     * The branch is taken from [addStarBtnIsActive], which is kept up to
     * date by [refreshAddStarButtonState] each time the modal repaints.
     * After the write completes, restart the modal's VMs so the change
     * shows up in the body.
     */
    private fun handleToggleStarred() {
        val parentVm = activePaneVmProvider() ?: return
        val parentState = parentVm.stateFlow.value.backingState ?: return
        val docState = parentState.documentState ?: return
        if (!docState.isLoaded) return

        val file = docState.activeFileRel
        if (file.isEmpty()) return
        if (file == NoteRepository.STARRED_FILE_NAME) {
            // Don't bookmark the bookmark file.
            return
        }

        val zoomedId = parentState.zoomedLineId
        val targetRow: Int? = if (zoomedId != null) {
            val idx = docState.lineIds.indexOf(zoomedId)
            if (idx >= 0) idx else null
        } else null

        val scope = openScope ?: return
        if (addStarBtnIsActive) {
            scope.launch {
                noteRepository.removeStarredEntry(
                    targetPathRel = file,
                    targetRow = targetRow,
                )
                restartModalVms()
            }
            return
        }

        val title = if (targetRow != null) {
            val raw = docState.lines.getOrNull(targetRow).orEmpty()
            SubtreeCodec.titleOf(raw).ifBlank { defaultFileTitle(file) }
        } else {
            defaultFileTitle(file)
        }

        scope.launch {
            noteRepository.appendStarredEntry(
                title = title,
                targetPathRel = file,
                targetRow = targetRow,
            )
            // Rebuild the modal VMs so the freshly-written file is
            // re-read from disk and re-painted.
            restartModalVms()
        }
    }

    /**
     * Cancel the current document-VM trio and start a new one. Used after
     * "Add to starred" so the modal body reflects the just-written entry.
     */
    private fun restartModalVms() {
        openJob?.cancel()
        openJob = null
        openScope = null
        modalDocBackingVm = null
        modalDocViewVm = null
        modalMainVm = null
        // Clear the body so the next paint does not stack on top of stale
        // rows during the brief reload window.
        bodyEl?.innerHTML = ""
        startModalVms()
    }

    /**
     * Capture-phase click on the modal body. If the click originated on a
     * row that is a markdown-link bullet, close the modal and route the
     * navigation to the parent pane.
     */
    private fun handleBodyClick(e: MouseEvent) {
        val rowDiv = closestRowDiv(e.target as? Node) ?: return
        val rowAttr = rowDiv.getAttribute("data-row") ?: return
        val rowIdx = rowAttr.toIntOrNull() ?: return
        val state = latestState ?: return
        val rawLine = state.lines.getOrNull(rowIdx) ?: return
        val link = parseLinkBullet(rawLine) ?: return

        e.preventDefault()
        e.stopPropagation()
        // stopImmediatePropagation prevents OutlinePaintLoop's per-bullet
        // and per-chevron handlers (registered on inner elements during
        // the bubble phase) from firing.
        e.stopImmediatePropagation()

        navigateParentTo(link)
        closeInternal()
    }

    private fun closestRowDiv(start: Node?): HTMLElement? {
        var n: Node? = start
        while (n != null) {
            if (n is HTMLElement && n.hasAttribute("data-row")) return n
            n = n.parentNode
            if (n === bodyEl) return null
        }
        return null
    }

    /**
     * Issue navigation against the parent pane. If the pane is already on
     * the target file, just zoom; otherwise switch files and wait for the
     * shared document VM's load to settle before zooming.
     */
    private fun navigateParentTo(target: ParsedLink) {
        val parentVm = activePaneVmProvider() ?: return
        val parentState = parentVm.stateFlow.value.backingState
        val currentFile = parentState?.documentState?.activeFileRel
        if (currentFile == target.path) {
            val docState = parentState.documentState ?: return
            val lineId = target.row?.let { docState.lineIds.getOrNull(it) }
            parentVm.zoomTo(lineId)
            return
        }
        parentVm.navigateToVaultFile(target.path)
        if (target.row == null) return
        val scope = parentScope
        scope.launch {
            // Wait for the shared document VM (the one the pane mirrors)
            // to actually reach the new file in a loaded state. We can't
            // peek through MainViewModel because the wait happens after
            // the modal has closed and the per-open scope is gone.
            val pollVm = activePaneVmProvider() ?: return@launch
            val settled = pollVm.stateFlow
                .first { env ->
                    val ds = env.backingState?.documentState
                    ds?.isLoaded == true && ds.activeFileRel == target.path
                }
                .backingState!!
                .documentState!!
            val lineId = settled.lineIds.getOrNull(target.row)
            if (lineId != null) activePaneVmProvider()?.zoomTo(lineId)
        }
    }

    /**
     * Update the toggle button's active styling, label, and tooltip based
     * on whether the parent pane's current target is already in the
     * starred list. Uses [latestState] so it follows reloads. The result
     * is mirrored into [addStarBtnIsActive] so [handleToggleStarred] can
     * branch without re-deriving it.
     */
    private fun refreshAddStarButtonState() {
        val btn = addStarBtn ?: return
        val parentVm = activePaneVmProvider()
        val parentState = parentVm?.stateFlow?.value?.backingState
        val docState = parentState?.documentState
        val file = docState?.activeFileRel
        if (file.isNullOrEmpty()) {
            applyAddStarBtnState(btn, active = false)
            return
        }
        val zoomedId = parentState.zoomedLineId
        val row: Int? = if (zoomedId != null) {
            docState.lineIds.indexOf(zoomedId).takeIf { it >= 0 }
        } else null
        val href = if (row != null) "$file#r=$row" else file
        val state = latestState ?: run {
            applyAddStarBtnState(btn, active = false)
            return
        }
        val active = state.lines.any { line ->
            parseLinkBullet(line)?.let { it.path == file && it.row == row } == true ||
                parseLinkBullet(line)?.rawHref == href
        }
        applyAddStarBtnState(btn, active = active)
    }

    private fun applyAddStarBtnState(btn: HTMLElement, active: Boolean) {
        addStarBtnIsActive = active
        if (active) {
            btn.classList.add("is-active")
            btn.title = "Remove the active pane's current location from your starred list"
            addStarBtnLabelEl?.textContent = "Remove from starred"
        } else {
            btn.classList.remove("is-active")
            btn.title = "Add the active pane's current location to your starred list"
            addStarBtnLabelEl?.textContent = "Add to starred"
        }
    }

    // ----------------------------------------------------------- lifecycle

    private fun closeInternal() {
        backdropEl?.parentNode?.removeChild(backdropEl!!)
        backdropEl = null
        bodyEl = null
        addStarBtn = null
        addStarBtnLabelEl = null
        addStarBtnIsActive = false
        latestState = null
        openJob?.cancel()
        openJob = null
        openScope = null
        modalDocBackingVm = null
        modalDocViewVm = null
        modalMainVm = null
        detachEscDismiss()
    }

    private fun attachEscDismiss() {
        val handler: (Event) -> Unit = lambda@ { e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                e.preventDefault()
                e.stopPropagation()
                closeInternal()
            }
        }
        documentKeyDownHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachEscDismiss() {
        documentKeyDownHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyDownHandler = null
    }

    // -------------------------------------------------------- link parser

    private data class ParsedLink(
        val path: String,
        val row: Int?,
        val rawHref: String,
    )

    /**
     * Parses a bullet line of the form `* [Label](href)` (with optional
     * leading indent and `<…>`-wrapped href). Returns null if [line] is
     * not a markdown-link bullet.
     *
     * Recognises `#r=<n>` as a row anchor; ignores any other fragment
     * (treats it as part of the path).
     */
    private fun parseLinkBullet(line: String): ParsedLink? {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        if (i + 2 > line.length) return null
        if (line[i] != '*' || line[i + 1] != ' ') return null
        i += 2
        if (i >= line.length || line[i] != '[') return null
        // Find the closing ']' that starts an inline link "](".
        val labelStart = i + 1
        var j = labelStart
        var depth = 1
        while (j < line.length) {
            val c = line[j]
            if (c == '\\' && j + 1 < line.length) { j += 2; continue }
            if (c == '[') depth++
            else if (c == ']') {
                depth--
                if (depth == 0) break
            }
            j++
        }
        if (j >= line.length || depth != 0) return null
        if (j + 1 >= line.length || line[j + 1] != '(') return null
        var k = j + 2
        var rawHref = ""
        if (k < line.length && line[k] == '<') {
            // Angle-bracketed URL.
            val urlStart = k + 1
            val urlEnd = line.indexOf('>', urlStart)
            if (urlEnd < 0) return null
            rawHref = line.substring(urlStart, urlEnd)
            k = urlEnd + 1
        } else {
            val urlEnd = line.indexOf(')', k)
            if (urlEnd < 0) return null
            rawHref = line.substring(k, urlEnd)
            k = urlEnd
        }
        if (k >= line.length || line[k] != ')') return null

        // Split off `#r=<n>` if present; everything else stays in the path
        // so foreign fragments on user-curated entries round-trip.
        val rowMarker = "#r="
        val hashIdx = rawHref.indexOf(rowMarker)
        val (path, row) = if (hashIdx >= 0) {
            val tail = rawHref.substring(hashIdx + rowMarker.length)
            val n = tail.toIntOrNull()
            if (n != null) rawHref.substring(0, hashIdx) to n
            else rawHref to null
        } else {
            rawHref to null
        }
        return ParsedLink(path = path, row = row, rawHref = rawHref)
    }

    private fun defaultFileTitle(fileRel: String): String {
        val basename = fileRel.substringAfterLast('/')
        return basename.removeSuffix(".md").ifBlank { fileRel }
    }
}
