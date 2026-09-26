/*
 * StarredModal.kt (jsMain)
 * ------------------------
 * Browse-only popup that lists every bookmark stored in `Starred.md`.
 * Inspired by the Cmd-O navigation modal ([LinkSearchModal]) so users
 * get the same arrow-key + Enter affordances they're used to —
 * minus the type-to-filter input, which doesn't apply here because
 * the bookmark file is the user's own curated list.
 *
 * One instance per pane (lazily created by [AppShell.openStarredModal]).
 * The modal:
 *
 * 1. Reads `Starred.md` through a *private* document-VM trio rooted at
 *    `Starred.md`. Rebuilt on every open so a fresh disk snapshot is
 *    shown.
 * 2. Renders each `* [Label](tf:/…)` bullet as a row in the palette-list
 *    shape (title + path). Entries are the same `tf:` paths as links
 *    (TRF-8), so a save that renames or moves the target rewrites them;
 *    an entry whose target is gone shows "not found".
 * 3. Supports keyboard navigation:
 *    - `ArrowUp` / `ArrowDown` — move highlight.
 *    - `Enter` — open the highlighted bookmark in the parent pane.
 *    - `Escape` — close.
 *    - `Cmd+D` / `Ctrl+D` — toggle the parent pane's current location
 *      in/out of the starred list (same as the Add button).
 * 4. Mouse: row click → open; row hover → highlight; Add button → toggle.
 *
 * Opening an entry follows its link in the parent pane
 * (`MainViewModel.navigateToLink`); adding and removing go through the
 * parent pane's VM (`MainViewModel.toggleStarred`) and so through the
 * app's `DocumentRegistry`, whose link index must see every entry.
 *
 * The modal is appended to `document.body` with `position: fixed`. ESC,
 * outside-click on the backdrop, and the close button all dismiss it.
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.data.SubtreeCodec
import se.soderbjorn.treefacts.data.TfLink
import se.soderbjorn.treefacts.platform.PlatformFileSystem

/**
 * Per-pane Starred bookmarks modal.
 *
 * @param parentScope App-scoped coroutine scope. The modal launches its
 *   own per-open [SupervisorJob] underneath this scope so cancelling the
 *   modal cancels its document-VM autosave loop and state collector
 *   without disturbing anything else. [dispose] cancels everything.
 * @param activePaneVmProvider Returns the parent pane's current
 *   [MainViewModel], or `null` if the pane has been torn down. Looked up
 *   on every interaction so the modal always targets the latest VM if
 *   the pane was rebuilt.
 * @param vaultRoot Absolute vault root this run uses — the app-wide
 *   [DocumentRegistry.rootDirectory], resolved by the Electron main process
 *   from `TREEFACTS_VAULT` / `TREEFACTS_LOCAL_DATA`. Both private
 *   repositories below are rooted here so the modal always reads the same
 *   vault as the panes.
 */
internal class StarredModal(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
    private val vaultRoot: String,
) {
    private val fileSystem = PlatformFileSystem()

    /** Private repo whose `rootFileName` is `Starred.md`, so the modal's
     *  document VM boots straight into the bookmark file with no extra
     *  switchTo dance. Constructed once, reused across opens. */
    private val starredRepo = NoteRepository(
        fileSystem = fileSystem,
        rootDirectory = vaultRoot,
        rootFileName = NoteRepository.STARRED_FILE_NAME,
    )

    // ----- per-open state (null when the modal is closed) -----------------
    private var openJob: Job? = null
    private var openScope: CoroutineScope? = null
    private var modalRegistry: DocumentRegistry? = null
    private var modalPaneBackingVm: PaneBackingViewModel? = null
    private var modalMainVm: MainViewModel? = null
    private var latestState: PaneBackingViewModel.State? = null

    // ----- DOM refs -------------------------------------------------------
    private var backdropEl: HTMLElement? = null
    private var listEl: HTMLElement? = null
    private var addStarBtn: HTMLElement? = null
    private var addStarBtnLabelEl: HTMLElement? = null
    /** True when the parent pane's current target is already in
     *  `Starred.md`, so the toggle button acts as "Remove" rather than
     *  "Add". Kept in sync by [refreshAddStarButtonState]. */
    private var addStarBtnIsActive: Boolean = false
    private var documentKeyDownHandler: ((Event) -> Unit)? = null

    // ----- list state -----------------------------------------------------
    private var entries: List<BookmarkEntry> = emptyList()
    private var highlightedIndex: Int = 0

    /**
     * The parent pane's location as a `tf:` path
     * (`MainViewModel.currentLocationPath`), read when the modal opens and
     * after each toggle; `null` until known. Drives the Add / Remove
     * button.
     */
    private var parentLocation: String? = null

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

        attachDocumentKeyHandler()
        startModalVms()
        refreshParentLocation()
    }

    /** Re-reads [parentLocation] and repaints the Add / Remove button. */
    private fun refreshParentLocation() {
        val vm = activePaneVmProvider() ?: return
        parentScope.launch {
            parentLocation = vm.currentLocationPath()
            refreshAddStarButtonState()
        }
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
     * [AppShell.closeFloatingPane] when a pane is removed.
     */
    fun dispose() {
        closeInternal()
    }

    // ---------------------------------------------------------------- DOM

    private fun buildBackdrop(): HTMLElement {
        val b = document.createElement("div") as HTMLElement
        b.className = "treefacts-palette-backdrop"
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
        panel.className = "treefacts-palette-panel treefacts-starred-panel"
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")
        panel.setAttribute("aria-label", "Starred")
        panel.addEventListener("mousedown", { e ->
            (e as MouseEvent).stopPropagation()
        })

        panel.appendChild(buildHeader())

        val list = document.createElement("div") as HTMLElement
        list.className = "treefacts-palette-list"
        listEl = list
        panel.appendChild(list)

        return panel
    }

    private fun buildHeader(): HTMLElement {
        // Slim header row mirroring the cmd-O input slot's height/border,
        // but without an input — instead it shows the modal title plus
        // the Add and Close affordances aligned right.
        val header = document.createElement("div") as HTMLElement
        header.className = "treefacts-starred-header"

        val title = document.createElement("div") as HTMLElement
        title.className = "treefacts-starred-title"
        title.textContent = "Starred"
        header.appendChild(title)

        val addBtn = document.createElement("button") as HTMLElement
        addBtn.className = "treefacts-starred-add"
        addBtn.setAttribute("type", "button")
        addBtn.title = "Add the active pane's current location to your starred list (⌘D)"
        val iconSpan = document.createElement("span") as HTMLElement
        iconSpan.className = "treefacts-starred-add-icon"
        iconSpan.innerHTML = AppShell.ICON_STAR
        addBtn.appendChild(iconSpan)
        val labelSpan = document.createElement("span") as HTMLElement
        labelSpan.className = "treefacts-starred-add-label"
        labelSpan.textContent = "Add to starred"
        addBtn.appendChild(labelSpan)
        addBtn.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault()
            e.stopPropagation()
            handleToggleStarred()
        })
        // Don't allow keyboard tab-focus to land on the button; the
        // panel's own keydown handler maps Cmd/Ctrl+D to the same
        // action, and tab-into-button would otherwise eat arrow keys.
        addBtn.tabIndex = -1
        addStarBtn = addBtn
        addStarBtnLabelEl = labelSpan
        header.appendChild(addBtn)

        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "treefacts-starred-close"
        closeBtn.setAttribute("type", "button")
        closeBtn.title = "Close"
        closeBtn.setAttribute("aria-label", "Close")
        closeBtn.innerHTML = "&times;"
        closeBtn.tabIndex = -1
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
     * launch a state collector that re-renders the list on every
     * emission, and remember the latest state so toggle-star can
     * resolve "is this target already starred?" without re-querying.
     */
    private fun startModalVms() {
        val parentJob = parentScope.coroutineContext[Job]
        val job = SupervisorJob(parentJob)
        val scope = parentScope + job
        openJob = job
        openScope = scope

        val registry = DocumentRegistry(starredRepo, scope)
        val paneVm = PaneBackingViewModel(
            registry = registry,
            scope = scope,
            initialFileRel = NoteRepository.STARRED_FILE_NAME,
        )
        val mainVm = MainViewModel(scope, paneVm)
        modalRegistry = registry
        modalPaneBackingVm = paneVm
        modalMainVm = mainVm

        scope.launch {
            mainVm.stateFlow.collect { envelope ->
                val st = envelope.backingState ?: return@collect
                latestState = st
                renderEntries(buildEntries(st))
                refreshAddStarButtonState()
            }
        }
    }

    /**
     * The bookmark rows of the loaded `Starred.md`: every
     * `* [Label](tf:/…)` bullet, with whether its target is missing
     * (asked through the parent pane, whose registry checks the disk).
     * Other lines are ignored.
     */
    private fun buildEntries(state: PaneBackingViewModel.State): List<BookmarkEntry> {
        val docState = state.documentState ?: return emptyList()
        if (!docState.isLoaded) return emptyList()
        val parentVm = activePaneVmProvider()
        val parentState = parentVm?.stateFlow?.value?.backingState
        val out = ArrayList<BookmarkEntry>(state.lines.size)
        for (line in state.lines) {
            val link = SubtreeCodec.parseAnyLinkBullet(line) ?: continue
            val path = TfLink.parse(link.url) ?: continue
            val label = link.bulletText.substring(link.indent + 2)
            val broken = parentVm != null && parentState != null && parentVm.isLinkBroken(parentState, link.url)
            out.add(
                BookmarkEntry(
                    label = label.ifBlank { if (path.isEmpty()) NoteRepository.ROOT_DISPLAY_NAME else path.substringAfterLast('/') },
                    path = path,
                    rawHref = link.url,
                    broken = broken,
                ),
            )
        }
        return out
    }

    private fun renderEntries(newEntries: List<BookmarkEntry>) {
        val list = listEl ?: return
        // Preserve highlight if it still maps to a bookmark with the
        // same href; otherwise reset to the top.
        val priorHref = entries.getOrNull(highlightedIndex)?.rawHref
        entries = newEntries
        highlightedIndex = if (newEntries.isEmpty()) 0
        else newEntries.indexOfFirst { it.rawHref == priorHref }.takeIf { it >= 0 } ?: 0

        while (list.firstChild != null) list.removeChild(list.firstChild!!)
        if (newEntries.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "treefacts-palette-empty"
            empty.textContent =
                "No bookmarks yet. Press ⌘D inside this dialog (or click ★ in any pane) to add one."
            list.appendChild(empty)
            return
        }
        for ((index, entry) in newEntries.withIndex()) {
            val row = document.createElement("button") as HTMLElement
            row.className = "treefacts-palette-item treefacts-link-item" +
                if (index == highlightedIndex) " is-active" else ""
            row.setAttribute("type", "button")
            row.tabIndex = -1

            val titleEl = document.createElement("div") as HTMLElement
            titleEl.className = "treefacts-link-item-title"
            titleEl.textContent = entry.label
            row.appendChild(titleEl)

            val pathEl = document.createElement("div") as HTMLElement
            pathEl.className = "treefacts-link-item-path"
            pathEl.textContent = "/" + entry.path + if (entry.broken) " — not found" else ""
            row.appendChild(pathEl)

            row.addEventListener("mousemove", { _ ->
                if (highlightedIndex != index) {
                    highlightedIndex = index
                    repaintHighlight()
                }
            })
            row.addEventListener("mousedown", { e ->
                (e as MouseEvent).preventDefault()
            })
            row.addEventListener("click", { _ ->
                highlightedIndex = index
                openHighlighted()
            })
            list.appendChild(row)
        }
    }

    private fun repaintHighlight() {
        val list = listEl ?: return
        val rows = list.children
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val active = i == highlightedIndex
            row.className = "treefacts-palette-item treefacts-link-item" +
                if (active) " is-active" else ""
            if (active) row.scrollIntoView(js("({block:'nearest'})"))
        }
    }

    private fun openHighlighted() {
        val entry = entries.getOrNull(highlightedIndex) ?: return
        activePaneVmProvider()?.navigateToLink(entry.rawHref)
        closeInternal()
    }

    // -------------------------------------------------------- interactions

    /**
     * Stars or un-stars the parent pane's current location
     * (`MainViewModel.toggleStarred`) — remove when [addStarBtnIsActive],
     * add otherwise — then restarts the modal's VMs so the body shows the
     * rewritten `Starred.md`.
     */
    private fun handleToggleStarred() {
        val parentVm = activePaneVmProvider() ?: return
        if (parentVm.stateFlow.value.backingState?.activeFileRel == NoteRepository.STARRED_FILE_NAME) {
            // Don't bookmark the bookmark file.
            return
        }
        val starred = addStarBtnIsActive
        parentScope.launch {
            parentVm.toggleStarred(starred)
            parentLocation = parentVm.currentLocationPath()
            if (backdropEl != null) restartModalVms()
        }
    }

    /**
     * Cancel the current document-VM trio and start a new one. Used after
     * "Add to starred" so the modal body reflects the just-written entry.
     */
    private fun restartModalVms() {
        val pane = modalPaneBackingVm
        openJob?.cancel()
        openJob = null
        openScope = null
        modalRegistry = null
        modalPaneBackingVm = null
        modalMainVm = null
        if (pane != null) parentScope.launch { pane.release() }
        listEl?.let { while (it.firstChild != null) it.removeChild(it.firstChild!!) }
        entries = emptyList()
        highlightedIndex = 0
        startModalVms()
    }

    /**
     * Update the toggle button's active styling, label, and tooltip based
     * on whether [parentLocation] is already in the starred list. Uses
     * [latestState] so it follows reloads. The result is mirrored into
     * [addStarBtnIsActive] so [handleToggleStarred] can branch without
     * re-deriving it.
     */
    private fun refreshAddStarButtonState() {
        val btn = addStarBtn ?: return
        val location = parentLocation
        val state = latestState
        if (location == null || state == null) {
            applyAddStarBtnState(btn, active = false)
            return
        }
        val active = state.lines.any { line ->
            SubtreeCodec.parseAnyLinkBullet(line)?.let { TfLink.parse(it.url) } == location
        }
        applyAddStarBtnState(btn, active = active)
    }

    private fun applyAddStarBtnState(btn: HTMLElement, active: Boolean) {
        addStarBtnIsActive = active
        if (active) {
            btn.classList.add("is-active")
            btn.title = "Remove the active pane's current location from your starred list (⌘D)"
            addStarBtnLabelEl?.textContent = "Remove from starred"
        } else {
            btn.classList.remove("is-active")
            btn.title = "Add the active pane's current location to your starred list (⌘D)"
            addStarBtnLabelEl?.textContent = "Add to starred"
        }
    }

    // ----------------------------------------------------------- lifecycle

    private fun closeInternal() {
        backdropEl?.parentNode?.removeChild(backdropEl!!)
        backdropEl = null
        listEl = null
        addStarBtn = null
        addStarBtnLabelEl = null
        addStarBtnIsActive = false
        parentLocation = null
        latestState = null
        entries = emptyList()
        highlightedIndex = 0
        val pane = modalPaneBackingVm
        openJob?.cancel()
        openJob = null
        openScope = null
        modalRegistry = null
        modalPaneBackingVm = null
        modalMainVm = null
        if (pane != null) parentScope.launch { pane.release() }
        detachDocumentKeyHandler()
    }

    private fun attachDocumentKeyHandler() {
        val handler: (Event) -> Unit = lambda@ { e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            // Cmd/Ctrl+D toggles starred for the parent pane's current
            // target — same effect as clicking the Add button. Honoured
            // before the fall-through arrow/Enter handling so the
            // shortcut works regardless of which row is highlighted.
            if ((ke.metaKey || ke.ctrlKey) && ke.key.equals("d", ignoreCase = true)) {
                ke.preventDefault()
                ke.stopPropagation()
                handleToggleStarred()
                return@lambda
            }
            when (ke.key) {
                "Escape" -> {
                    ke.preventDefault()
                    ke.stopPropagation()
                    closeInternal()
                }
                "ArrowDown" -> {
                    if (entries.isNotEmpty()) {
                        ke.preventDefault()
                        ke.stopPropagation()
                        highlightedIndex = (highlightedIndex + 1).coerceAtMost(entries.lastIndex)
                        repaintHighlight()
                    }
                }
                "ArrowUp" -> {
                    if (entries.isNotEmpty()) {
                        ke.preventDefault()
                        ke.stopPropagation()
                        highlightedIndex = (highlightedIndex - 1).coerceAtLeast(0)
                        repaintHighlight()
                    }
                }
                "Home" -> {
                    if (entries.isNotEmpty()) {
                        ke.preventDefault()
                        ke.stopPropagation()
                        highlightedIndex = 0
                        repaintHighlight()
                    }
                }
                "End" -> {
                    if (entries.isNotEmpty()) {
                        ke.preventDefault()
                        ke.stopPropagation()
                        highlightedIndex = entries.lastIndex
                        repaintHighlight()
                    }
                }
                "Enter" -> {
                    if (entries.isNotEmpty()) {
                        ke.preventDefault()
                        ke.stopPropagation()
                        openHighlighted()
                    }
                }
            }
        }
        documentKeyDownHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachDocumentKeyHandler() {
        documentKeyDownHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyDownHandler = null
    }

    // -------------------------------------------------------- entries

    /**
     * One row of the list.
     *
     * @property label The entry's label.
     * @property path Vault-relative target path.
     * @property rawHref The entry's `tf:` link, as written.
     * @property broken `true` when the target is known to be missing.
     */
    private data class BookmarkEntry(
        val label: String,
        val path: String,
        val rawHref: String,
        val broken: Boolean,
    )
}
