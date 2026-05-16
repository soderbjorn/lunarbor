/*
 * LinkSearchModal.kt (jsMain)
 * ---------------------------
 * Single modal class shared by the "Insert Link" command and the
 * "Navigate to" command (Cmd-O). Same DOM, same keyboard handling,
 * same search-as-you-type backed by `VaultIndex.search`. The two
 * commands differ only in:
 *
 *  - the placeholder text shown in the input,
 *  - what to do when the user picks a hit (insert a markdown link at
 *    the cursor, vs navigate this pane to the target).
 *
 * They're expressed as two factory functions on the companion. Adding
 * a third "go to" flavour later (e.g. "Open in new pane") is a one-line
 * factory that hands a different [Action] callback.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.notegrow.data.LinkUrl
import se.soderbjorn.notegrow.data.VaultIndex

/**
 * Per-pane search-and-pick modal for vault outline nodes.
 *
 * @param parentScope App-scoped coroutine scope for suspend-y vault
 *   queries triggered by typing.
 * @param activePaneVmProvider Returns the focused pane's
 *   [MainViewModel] when the modal opens. Same lookup pattern as
 *   `StarredModal`.
 * @param placeholder The text shown in the empty input.
 * @param action What to do once the user picks a hit. Receives the
 *   captured-at-open [OpenContext] so flavours that care about the
 *   cursor (Insert Link uses cursor position to compute a relative
 *   URL) can read it without re-querying mid-flight.
 */
internal class LinkSearchModal private constructor(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
    private val placeholder: String,
    private val action: Action,
) {

    /**
     * State captured when the modal opens, frozen so the action's
     * computations reference *where the user was* when they invoked
     * the command, not whatever the focus state ends up being while
     * they type.
     */
    data class OpenContext(
        val activeFileRel: String,
        val cursorInFilePath: List<String>,
        val selectedText: String,
    )

    /**
     * Lambda invoked when the user selects a hit. The modal closes
     * itself before invoking the action so the action can freely
     * launch its own coroutines / popovers.
     */
    fun interface Action {
        fun perform(vm: MainViewModel, hit: VaultIndex.SearchHit, context: OpenContext)
    }

    private var backdropEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var listEl: HTMLElement? = null

    private var matches: List<VaultIndex.SearchHit> = emptyList()
    private var highlightedIndex: Int = 0

    private var pinnedVm: MainViewModel? = null
    private var pinnedContext: OpenContext = OpenContext("", emptyList(), "")
    private var pendingSearchJob: Job? = null

    private var focusToRestore: HTMLElement? = null
    private var documentKeyHandler: ((Event) -> Unit)? = null

    fun open() {
        val vm = activePaneVmProvider() ?: return
        if (backdropEl != null) {
            inputEl?.focus()
            return
        }
        pinnedVm = vm
        val backing = vm.currentBackingState
        pinnedContext = OpenContext(
            activeFileRel = backing.activeFileRel,
            cursorInFilePath = vm.currentInFileTitlePath(),
            selectedText = vm.getSelectedText().orEmpty(),
        )
        focusToRestore = document.activeElement as? HTMLElement
        buildDom()
        rebuildList(query = "")
        attachDocumentKeyHandler()
        inputEl?.focus()
    }

    fun close() {
        pendingSearchJob?.cancel()
        pendingSearchJob = null
        backdropEl?.let { it.parentNode?.removeChild(it) }
        backdropEl = null
        inputEl = null
        listEl = null
        matches = emptyList()
        highlightedIndex = 0
        pinnedVm = null
        pinnedContext = OpenContext("", emptyList(), "")
        detachDocumentKeyHandler()
        focusToRestore?.focus()
        focusToRestore = null
    }

    private fun buildDom() {
        val backdrop = document.createElement("div") as HTMLElement
        backdrop.className = "notegrow-palette-backdrop"
        backdrop.addEventListener("mousedown", { e ->
            if (e.target === backdrop) close()
        })

        val panel = document.createElement("div") as HTMLElement
        panel.className = "notegrow-palette-panel"
        panel.addEventListener("mousedown", { e ->
            (e as MouseEvent).stopPropagation()
        })

        val input = document.createElement("input") as HTMLInputElement
        input.type = "text"
        input.className = "notegrow-palette-input"
        input.placeholder = placeholder
        input.autocomplete = "off"
        input.spellcheck = false
        input.addEventListener("input", { _ -> rebuildList(input.value) })
        input.addEventListener("keydown", { e ->
            handleInputKey(e as KeyboardEvent)
        })

        val list = document.createElement("div") as HTMLElement
        list.className = "notegrow-palette-list"

        panel.appendChild(input)
        panel.appendChild(list)
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)

        backdropEl = backdrop
        inputEl = input
        listEl = list
    }

    private fun handleInputKey(ke: KeyboardEvent) {
        when (ke.key) {
            "Escape" -> { ke.preventDefault(); close() }
            "Enter" -> { ke.preventDefault(); pickHighlighted() }
            "ArrowDown" -> {
                ke.preventDefault()
                if (matches.isNotEmpty()) {
                    highlightedIndex = (highlightedIndex + 1).coerceAtMost(matches.lastIndex)
                    repaintHighlight()
                }
            }
            "ArrowUp" -> {
                ke.preventDefault()
                if (matches.isNotEmpty()) {
                    highlightedIndex = (highlightedIndex - 1).coerceAtLeast(0)
                    repaintHighlight()
                }
            }
        }
    }

    private fun attachDocumentKeyHandler() {
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                ke.preventDefault()
                close()
            }
        }
        documentKeyHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachDocumentKeyHandler() {
        documentKeyHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyHandler = null
    }

    private fun rebuildList(query: String) {
        val list = listEl ?: return
        val vm = pinnedVm ?: return
        pendingSearchJob?.cancel()
        if (query.isBlank()) {
            renderRows(emptyList())
            return
        }
        pendingSearchJob = parentScope.launch {
            val hits = vm.vaultIndex.search(query, max = 50)
            if (listEl == null) return@launch
            renderRows(hits)
        }
    }

    private fun renderRows(hits: List<VaultIndex.SearchHit>) {
        val list = listEl ?: return
        matches = hits
        highlightedIndex = if (hits.isEmpty()) 0 else 0
        while (list.firstChild != null) list.removeChild(list.firstChild!!)
        if (hits.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "notegrow-palette-empty"
            empty.textContent = "No matches"
            list.appendChild(empty)
            return
        }
        for ((index, hit) in hits.withIndex()) {
            val row = document.createElement("button") as HTMLElement
            row.className = "notegrow-palette-item notegrow-link-item" +
                if (index == highlightedIndex) " is-active" else ""
            row.setAttribute("type", "button")

            val titleEl = document.createElement("div") as HTMLElement
            titleEl.className = "notegrow-link-item-title"
            titleEl.textContent = hit.title
            row.appendChild(titleEl)

            val breadcrumb = hit.titlePathFromRoot.dropLast(1)
            if (breadcrumb.isNotEmpty()) {
                val crumbEl = document.createElement("div") as HTMLElement
                crumbEl.className = "notegrow-link-item-crumb"
                crumbEl.textContent = breadcrumb.joinToString(" › ")
                row.appendChild(crumbEl)
            }
            val pathEl = document.createElement("div") as HTMLElement
            pathEl.className = "notegrow-link-item-path"
            // Folder stubs name a directory whose anchor file does not
            // exist yet — make that obvious so picking the row isn't
            // surprising when it creates a new file on disk.
            pathEl.textContent = if (hit.isFolderStub) "${hit.fileRel} (new folder page)"
                                 else hit.fileRel
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
                pickHighlighted()
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
            row.className = "notegrow-palette-item notegrow-link-item" +
                if (active) " is-active" else ""
            if (active) row.scrollIntoView(js("({block:'nearest'})"))
        }
    }

    private fun pickHighlighted() {
        val hit = matches.getOrNull(highlightedIndex) ?: return
        val vm = pinnedVm ?: return
        val context = pinnedContext
        close()
        action.perform(vm, hit, context)
    }

    companion object {
        /**
         * "Insert Link" flavour: at pick time, compute the shortest
         * URL from the cursor's position and emit a markdown link via
         * `MainViewModel.insertMarkdownLink`. If the user had an
         * editor selection at open time, that text becomes the link's
         * label; otherwise the hit's title is used.
         *
         * @param onAfterPick Optional follow-up — typically the host
         *   focuses the pane's editor here so the caret lands inside
         *   the contenteditable after the modal closes.
         */
        fun forInsertLink(
            parentScope: CoroutineScope,
            activePaneVmProvider: () -> MainViewModel?,
            onAfterPick: () -> Unit = {},
        ): LinkSearchModal = LinkSearchModal(
            parentScope = parentScope,
            activePaneVmProvider = activePaneVmProvider,
            placeholder = "Find a note or bullet to link…",
            action = Action { vm, hit, ctx ->
                parentScope.launch {
                    // A folder-stub hit names a directory that has no
                    // anchor file yet — materialise it before the
                    // resolver tries to walk to it. Subsequent calls
                    // are no-ops once the file exists.
                    if (hit.isFolderStub) {
                        vm.ensureFolderStub(hit.fileRel)
                    }
                    val cursorFullPath =
                        vm.vaultIndex.fullPathFor(ctx.activeFileRel, ctx.cursorInFilePath)
                            ?: emptyList()
                    val url = vm.vaultIndex.shortestUrlFor(hit, cursorFullPath)
                    val label = ctx.selectedText.takeIf { it.isNotEmpty() } ?: hit.title
                    vm.insertMarkdownLink(label, LinkUrl.format(url.segments, url.isAbsolute))
                    onAfterPick()
                }
            },
        )

        /**
         * "Navigate to" flavour: at pick time, build an absolute-form
         * URL from the hit's full title path and route through
         * `MainViewModel.navigateToLink` so the file-switch +
         * zoom-to-bullet semantics match a real link click.
         *
         * @param onAfterPick Optional follow-up — typically the host
         *   focuses the pane's editor so the caret lands inside the
         *   target document after navigation. Without this the focus
         *   is restored to whatever element held it before the modal
         *   opened (the command palette button, say), which leaves
         *   the user one click away from typing.
         */
        fun forNavigateTo(
            parentScope: CoroutineScope,
            activePaneVmProvider: () -> MainViewModel?,
            onAfterPick: () -> Unit = {},
        ): LinkSearchModal = LinkSearchModal(
            parentScope = parentScope,
            activePaneVmProvider = activePaneVmProvider,
            placeholder = "Navigate to a note or bullet…",
            action = Action { vm, hit, _ ->
                // Defer onAfterPick until navigation actually completes —
                // navigateToLink's work is async (file switch, document
                // load, cursor placement), so calling onAfterPick before
                // the coroutine finishes would focus an editor that's
                // about to be reconciled with new content, losing focus.
                val targetUrl = LinkUrl.format(hit.titlePathFromRoot, isAbsolute = true)
                if (hit.isFolderStub) {
                    // The anchor file doesn't exist yet — materialise it
                    // first so the resolver can walk to a real file root
                    // when navigateToLink runs.
                    parentScope.launch {
                        vm.ensureFolderStub(hit.fileRel)
                        vm.navigateToLink(targetUrl, onComplete = onAfterPick)
                    }
                } else {
                    vm.navigateToLink(targetUrl, onComplete = onAfterPick)
                }
            },
        )
    }
}
