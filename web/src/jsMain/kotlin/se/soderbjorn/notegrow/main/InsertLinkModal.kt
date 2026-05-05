/*
 * InsertLinkModal.kt (jsMain)
 * ---------------------------
 * Modal that powers the "Insert Link" command. The user types a query;
 * the modal lists matching nodes from the active vault's outline tree
 * (file roots and bullets, transparently across promoted-ref boundaries)
 * with a breadcrumb so the user can pick the right one when titles
 * collide. Up/Down navigate, Enter inserts a markdown link at the
 * cursor in the focused pane, Esc closes.
 *
 * The modal does not own a [Document]; it reaches into the focused
 * pane's [MainViewModel] for both reading the cursor's position
 * (`currentInFileTitlePath`) and emitting the inserted link
 * (`insertMarkdownLink`). The shared `vaultIndex` accessed via the VM
 * is the data source for search.
 *
 * Visual style mirrors `CommandPalette.kt` — same `--t-*` toolkit CSS
 * variables, same backdrop / panel / input / list shape — so themes
 * apply uniformly.
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
 * Per-pane Insert Link modal.
 *
 * @param parentScope App-scoped coroutine scope used to launch the
 *   suspend-y vault-index queries triggered by typing.
 * @param activePaneVmProvider Returns the focused pane's [MainViewModel]
 *   when the modal is opened. The modal looks up the pane's cursor
 *   position, vault index, and insert intent through this lambda; if
 *   the lookup returns `null` the open is a no-op.
 */
internal class InsertLinkModal(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
) {

    private var backdropEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var listEl: HTMLElement? = null

    private var matches: List<VaultIndex.SearchHit> = emptyList()
    private var highlightedIndex: Int = 0

    /** VM captured at [open] time so a re-render mid-open targets the same pane. */
    private var pinnedVm: MainViewModel? = null

    /**
     * `(activeFileRel, currentInFileTitlePath)` snapshot, cached at
     * [open] time so [shortestUrlFor] can compute relative paths
     * deterministically against the cursor position when the modal
     * opened — even if the user clicks outside the editor first and
     * the cursor's pane state shifts during typing.
     */
    private var pinnedFileRel: String = ""
    private var pinnedInFilePath: List<String> = emptyList()

    /**
     * Initial selected text in the editor. When non-empty, used as the
     * link's display label; the user-typed query becomes the search
     * filter only.
     */
    private var pinnedSelection: String = ""

    private var pendingSearchJob: Job? = null

    /** Element that held focus before [open] was called; restored on close. */
    private var focusToRestore: HTMLElement? = null
    private var documentKeyHandler: ((Event) -> Unit)? = null

    /**
     * Show the modal. Captures the pane's cursor position so
     * relative-URL math always references where the user *was* when
     * they triggered Insert Link, not where the focus ends up after
     * the modal opens.
     */
    fun open() {
        val vm = activePaneVmProvider() ?: return
        if (backdropEl != null) {
            inputEl?.focus()
            return
        }
        pinnedVm = vm
        val backing = vm.currentBackingState
        pinnedFileRel = backing.activeFileRel
        pinnedInFilePath = vm.currentInFileTitlePath()
        pinnedSelection = vm.getSelectedText().orEmpty()
        focusToRestore = document.activeElement as? HTMLElement
        buildDom()
        rebuildList(query = "")
        attachDocumentKeyHandler()
        inputEl?.focus()
    }

    /** Hide the modal. Idempotent. */
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
        pinnedFileRel = ""
        pinnedInFilePath = emptyList()
        pinnedSelection = ""
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
        input.placeholder = "Find a note or bullet to link…"
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
            "Enter" -> { ke.preventDefault(); insertHighlighted() }
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
        // Cancel any in-flight search; the user has typed more.
        pendingSearchJob?.cancel()
        if (query.isBlank()) {
            renderRows(emptyList())
            return
        }
        pendingSearchJob = parentScope.launch {
            val hits = vm.vaultIndex.search(query, max = 50)
            // Caller may have closed the modal while the search ran.
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

            // Breadcrumb shows everything in the path *except* the leaf
            // (the leaf is already in the title above).
            val breadcrumb = hit.titlePathFromRoot.dropLast(1)
            if (breadcrumb.isNotEmpty()) {
                val crumbEl = document.createElement("div") as HTMLElement
                crumbEl.className = "notegrow-link-item-crumb"
                crumbEl.textContent = breadcrumb.joinToString(" › ")
                row.appendChild(crumbEl)
            }

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
                insertHighlighted()
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

    private fun insertHighlighted() {
        val hit = matches.getOrNull(highlightedIndex) ?: return
        val vm = pinnedVm ?: return
        val label = pinnedSelection.takeIf { it.isNotEmpty() } ?: hit.title
        val capturedFileRel = pinnedFileRel
        val capturedInFilePath = pinnedInFilePath
        // Closing here clears pinned state, so capture above first.
        close()
        parentScope.launch {
            val cursorFullPath =
                vm.vaultIndex.fullPathFor(capturedFileRel, capturedInFilePath) ?: emptyList()
            val url = vm.vaultIndex.shortestUrlFor(hit, cursorFullPath)
            vm.insertMarkdownLink(label, LinkUrl.format(url.segments, url.isAbsolute))
        }
    }
}
