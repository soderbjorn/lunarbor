/*
 * ImageSearchModal.kt (jsMain)
 * ----------------------------
 * Per-pane modal for the "Insert Image" palette command. Lists every
 * image already in the vault's `Images/` folder (resolved through
 * `NoteRepository.listImageFiles`) and lets the user pick one to drop
 * into the active document at the cursor as a markdown `![](…)` ref.
 *
 * Modeled on [LinkSearchModal] — same backdrop / panel chrome, same
 * input/list/arrow-key/Enter/Escape keyboard model, same one-instance-
 * per-pane lifecycle — but tailored to a flat file list rather than a
 * searchable vault tree. Differences from `LinkSearchModal`:
 *
 *  - No `VaultIndex.search` plumbing. Images aren't part of the outline
 *    tree, so the list is just `listImageFiles()` filtered locally by
 *    case-insensitive substring match on the filename.
 *  - Each row renders a thumbnail (via the same `notegrow-asset:` URL
 *    used in the editor's inline image rendering) so the user can pick
 *    visually rather than by filename alone.
 *  - No "Folder stub" branch — the image either exists on disk or it
 *    doesn't, and the list only ever contains existing files.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * @param parentScope App-scoped coroutine scope for the suspending
 *   `listImageFiles()` call that runs on each open.
 * @param activePaneVmProvider Returns the focused pane's [MainViewModel]
 *   at open time.
 * @param onAfterPick Called after the user picks an image. The host
 *   typically uses this to re-focus the editor so the caret lands inside
 *   the contenteditable.
 */
internal class ImageSearchModal(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
    private val onAfterPick: () -> Unit = {},
) {

    private var backdropEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var listEl: HTMLElement? = null

    private var allImages: List<String> = emptyList()
    private var matches: List<String> = emptyList()
    private var highlightedIndex: Int = 0

    private var pinnedVm: MainViewModel? = null
    private var pendingLoadJob: Job? = null

    private var focusToRestore: HTMLElement? = null
    private var documentKeyHandler: ((Event) -> Unit)? = null

    fun open() {
        val vm = activePaneVmProvider() ?: return
        if (backdropEl != null) {
            inputEl?.focus()
            return
        }
        pinnedVm = vm
        focusToRestore = document.activeElement as? HTMLElement
        buildDom()
        attachDocumentKeyHandler()
        inputEl?.focus()
        // Kick off the load. The list paints as soon as the suspend
        // returns; before then a placeholder row sits in the dropdown.
        pendingLoadJob = parentScope.launch {
            allImages = vm.listImageFiles()
            if (listEl != null) rebuildList(inputEl?.value.orEmpty())
        }
    }

    fun close() {
        pendingLoadJob?.cancel()
        pendingLoadJob = null
        backdropEl?.let { it.parentNode?.removeChild(it) }
        backdropEl = null
        inputEl = null
        listEl = null
        allImages = emptyList()
        matches = emptyList()
        highlightedIndex = 0
        pinnedVm = null
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
        input.placeholder = "Find an image in Images/…"
        input.autocomplete = "off"
        input.spellcheck = false
        input.addEventListener("input", { _ -> rebuildList(input.value) })
        input.addEventListener("keydown", { e -> handleInputKey(e as KeyboardEvent) })

        val list = document.createElement("div") as HTMLElement
        list.className = "notegrow-palette-list"

        // Initial placeholder while listImageFiles() loads.
        val loading = document.createElement("div") as HTMLElement
        loading.className = "notegrow-palette-empty"
        loading.textContent = "Loading…"
        list.appendChild(loading)

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
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allImages
        else allImages.filter { it.substringAfterLast('/').lowercase().contains(q) }
        renderRows(filtered)
    }

    private fun renderRows(images: List<String>) {
        val list = listEl ?: return
        matches = images
        highlightedIndex = 0
        while (list.firstChild != null) list.removeChild(list.firstChild!!)
        if (images.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "notegrow-palette-empty"
            empty.textContent = if (allImages.isEmpty())
                "No images yet — paste one into a note to start"
            else "No matches"
            list.appendChild(empty)
            return
        }
        for ((index, rel) in images.withIndex()) {
            val row = document.createElement("button") as HTMLElement
            row.className = "notegrow-palette-item notegrow-image-item" +
                if (index == highlightedIndex) " is-active" else ""
            row.setAttribute("type", "button")

            val thumb = document.createElement("img") as HTMLImageElement
            thumb.className = "notegrow-image-item-thumb"
            thumb.src = notegrowAssetUrl(rel)
            thumb.alt = ""
            thumb.draggable = false
            row.appendChild(thumb)

            val meta = document.createElement("div") as HTMLElement
            meta.className = "notegrow-image-item-meta"
            val title = document.createElement("div") as HTMLElement
            title.className = "notegrow-image-item-title"
            title.textContent = rel.substringAfterLast('/')
            val pathEl = document.createElement("div") as HTMLElement
            pathEl.className = "notegrow-image-item-path"
            pathEl.textContent = rel
            meta.appendChild(title)
            meta.appendChild(pathEl)
            row.appendChild(meta)

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
            row.className = "notegrow-palette-item notegrow-image-item" +
                if (active) " is-active" else ""
            if (active) row.scrollIntoView(js("({block:'nearest'})"))
        }
    }

    private fun pickHighlighted() {
        val rel = matches.getOrNull(highlightedIndex) ?: return
        val vm = pinnedVm ?: return
        close()
        vm.insertImageRef(rel)
        onAfterPick()
    }
}
