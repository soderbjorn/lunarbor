/*
 * LinkSearchModal.kt (jsMain)
 * ---------------------------
 * Single modal class shared by the "Insert Link" and "Link to node…"
 * commands and the "Navigate to" command (Cmd-O). Same DOM, same keyboard
 * handling, same search-as-you-type backed by `VaultIndex.search` — over
 * the whole vault from the root, however deep the pane is zoomed, and
 * offering only linkable targets (non-empty folders and files; never a
 * leaf bullet or an empty folder). The commands differ only in:
 *
 *  - the placeholder text shown in the input,
 *  - what to do when the user picks a hit (insert a `lunarbor:` link at the
 *    cursor, vs navigate this pane to the target).
 *
 * They're expressed as two factory functions on the companion. View
 * layer only: the search, the link format and the navigation live in
 * commonMain (`VaultIndex`, `LunarborLink`, `PaneBackingViewModel`).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.VaultEntryKind

/**
 * Per-pane search-and-pick modal for link targets.
 *
 * @param parentScope App-scoped coroutine scope for suspend-y vault
 *   queries triggered by typing.
 * @param activePaneVmProvider Returns the focused pane's
 *   [MainViewModel] when the modal opens. Same lookup pattern as
 *   `StarredModal`.
 * @param placeholder The text shown in the empty input.
 * @param action What to do once the user picks a hit. Receives the
 *   captured-at-open [OpenContext] (the selection to use as the label).
 */
internal class LinkSearchModal private constructor(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
    private val placeholder: String,
    private val action: Action,
) {

    /**
     * State captured when the modal opens, frozen so the action uses what
     * the user had selected when they invoked the command.
     *
     * @property selectedText The editor selection at open time; Insert
     *   Link uses it as the link label.
     */
    data class OpenContext(
        val selectedText: String,
    )

    /**
     * Lambda invoked when the user selects a hit. The modal closes
     * itself before invoking the action so the action can freely
     * launch its own coroutines / popovers.
     */
    fun interface Action {
        fun perform(vm: MainViewModel, hit: LinkTarget, context: OpenContext)
    }

    private var backdropEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var listEl: HTMLElement? = null

    private var matches: List<LinkTarget> = emptyList()
    private var highlightedIndex: Int = 0

    private var pinnedVm: MainViewModel? = null
    private var pinnedContext: OpenContext = OpenContext("")
    private var pendingSearchJob: Job? = null

    /** Saves open documents before the first search; see `MainViewModel.prepareLinkSearch`. */
    private var prepareJob: Job? = null

    private var focusToRestore: HTMLElement? = null
    private var documentKeyHandler: ((Event) -> Unit)? = null

    /**
     * Opens the modal (or refocuses it when already open).
     *
     * @param initialQuery Text to start the search with, selected so
     *   typing replaces it — the link's current text for "Change link…".
     */
    fun open(initialQuery: String = "") {
        val vm = activePaneVmProvider() ?: return
        if (backdropEl != null) {
            inputEl?.focus()
            return
        }
        pinnedVm = vm
        pinnedContext = OpenContext(selectedText = vm.getSelectedText().orEmpty())
        prepareJob = parentScope.launch { vm.prepareLinkSearch() }
        focusToRestore = document.activeElement as? HTMLElement
        buildDom()
        inputEl?.value = initialQuery
        rebuildList(query = initialQuery)
        attachDocumentKeyHandler()
        inputEl?.focus()
        inputEl?.select()
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
        pinnedContext = OpenContext("")
        prepareJob = null
        detachDocumentKeyHandler()
        focusToRestore?.focus()
        focusToRestore = null
    }

    private fun buildDom() {
        val backdrop = document.createElement("div") as HTMLElement
        backdrop.className = "lunarbor-palette-backdrop"
        backdrop.addEventListener("mousedown", { e ->
            if (e.target === backdrop) close()
        })

        val panel = document.createElement("div") as HTMLElement
        panel.className = "lunarbor-palette-panel"
        panel.addEventListener("mousedown", { e ->
            (e as MouseEvent).stopPropagation()
        })

        val input = document.createElement("input") as HTMLInputElement
        input.type = "text"
        input.className = "lunarbor-palette-input"
        input.placeholder = placeholder
        input.autocomplete = "off"
        input.spellcheck = false
        input.addEventListener("input", { _ -> rebuildList(input.value) })
        input.addEventListener("keydown", { e ->
            handleInputKey(e as KeyboardEvent)
        })

        val list = document.createElement("div") as HTMLElement
        list.className = "lunarbor-palette-list"

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
        val prepared = prepareJob
        pendingSearchJob = parentScope.launch {
            prepared?.join()
            val hits = vm.vaultIndex.search(query, max = 50)
            if (listEl == null) return@launch
            renderRows(hits)
        }
    }

    private fun renderRows(hits: List<LinkTarget>) {
        val list = listEl ?: return
        matches = hits
        highlightedIndex = if (hits.isEmpty()) 0 else 0
        while (list.firstChild != null) list.removeChild(list.firstChild!!)
        if (hits.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "lunarbor-palette-empty"
            empty.textContent = "No matches"
            list.appendChild(empty)
            return
        }
        for ((index, hit) in hits.withIndex()) {
            val row = document.createElement("button") as HTMLElement
            row.className = "lunarbor-palette-item lunarbor-link-item" +
                if (index == highlightedIndex) " is-active" else ""
            row.setAttribute("type", "button")

            val titleEl = document.createElement("div") as HTMLElement
            titleEl.className = "lunarbor-link-item-title"
            titleEl.textContent = hit.title
            row.appendChild(titleEl)

            if (hit.crumbs.isNotEmpty()) {
                val crumbEl = document.createElement("div") as HTMLElement
                crumbEl.className = "lunarbor-link-item-crumb"
                crumbEl.textContent = hit.crumbs.joinToString(" › ")
                row.appendChild(crumbEl)
            }
            val pathEl = document.createElement("div") as HTMLElement
            pathEl.className = "lunarbor-link-item-path"
            pathEl.textContent = kindLabel(hit) + " · " + LunarborLink.displayPath(hit.pathRel)
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
            row.className = "lunarbor-palette-item lunarbor-link-item" +
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

    /** Short type label shown before a hit's link: node, folder, note, image, drawing or file. */
    private fun kindLabel(hit: LinkTarget): String = when (hit.kind) {
        VaultEntryKind.FOLDER -> if (hit.pathRel.isEmpty()) "home" else "node"
        VaultEntryKind.MARKDOWN -> "note"
        VaultEntryKind.IMAGE -> "image"
        VaultEntryKind.DRAWING -> "drawing"
        VaultEntryKind.HTML -> "web page"
        VaultEntryKind.FILE -> "file"
    }

    companion object {
        /**
         * "Insert Link" / "Link to node…" flavour: at pick time, insert a
         * `[label](lunarbor:/…)` link at the cursor via
         * `MainViewModel.insertLinkTo`. If the user had an editor
         * selection at open time, that text becomes the link's label;
         * otherwise the hit's title is used.
         *
         * @param placeholder Input placeholder; differs per command.
         * @param onAfterPick Optional follow-up — typically the host
         *   focuses the pane's editor here so the caret lands inside
         *   the contenteditable after the modal closes.
         */
        fun forInsertLink(
            parentScope: CoroutineScope,
            activePaneVmProvider: () -> MainViewModel?,
            placeholder: String = "Find a node or file to link…",
            onAfterPick: () -> Unit = {},
        ): LinkSearchModal = LinkSearchModal(
            parentScope = parentScope,
            activePaneVmProvider = activePaneVmProvider,
            placeholder = placeholder,
            action = Action { vm, hit, ctx ->
                vm.insertLinkTo(hit, ctx.selectedText)
                onAfterPick()
            },
        )

        /**
         * "Change link…" flavour, from the link popup ([LinkHoverPopup]):
         * the picked target replaces the link at [row] / [col] through
         * `MainViewModel.retargetLinkAt`, keeping the link's text. A new
         * instance per use, since it is bound to one link.
         *
         * @param row Document row of the link.
         * @param col A model column inside the link's source span.
         */
        fun forChangeLink(
            parentScope: CoroutineScope,
            activePaneVmProvider: () -> MainViewModel?,
            row: Int,
            col: Int,
        ): LinkSearchModal = LinkSearchModal(
            parentScope = parentScope,
            activePaneVmProvider = activePaneVmProvider,
            placeholder = "Change the link to a node or file…",
            action = Action { vm, hit, _ -> vm.retargetLinkAt(row, col, hit) },
        )

        /**
         * "Navigate to" flavour: at pick time, follow the hit's `lunarbor:` link
         * through `MainViewModel.navigateToLink`, so the zoom / open
         * semantics match a real link click.
         *
         * @param onAfterPick Optional follow-up — typically the host
         *   focuses the pane's editor so the caret lands inside the
         *   target document after navigation. Deferred until navigation
         *   completes.
         */
        fun forNavigateTo(
            parentScope: CoroutineScope,
            activePaneVmProvider: () -> MainViewModel?,
            onAfterPick: () -> Unit = {},
        ): LinkSearchModal = LinkSearchModal(
            parentScope = parentScope,
            activePaneVmProvider = activePaneVmProvider,
            placeholder = "Navigate to a node or file…",
            action = Action { vm, hit, _ ->
                vm.navigateToLink(LunarborLink.format(hit.pathRel), onComplete = onAfterPick)
            },
        )
    }
}
