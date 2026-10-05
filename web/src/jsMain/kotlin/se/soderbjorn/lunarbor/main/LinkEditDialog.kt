/*
 * LinkEditDialog.kt (jsMain)
 * --------------------------
 * The "Edit link" dialog, opened from the small button that pops up on a
 * link the pointer rests on ([LinkHoverPopup]). Two fields — the text the
 * link shows and where it points — plus Remove link, Cancel and Save.
 *
 *  - **Text**: the link's label as plain text ([LinkSource.text]); other
 *    inline Markdown (`**bold**`) shows as written. Left empty, the link
 *    shows its URL.
 *  - **Link**: any URL (`https://…`, `mailto:…`), or a place in the vault
 *    as the file writes it — relative to the row's folder
 *    (`../Pasta/_node.md`), or vault-rooted (`/Recipes/Pasta`). Typing
 *    something that is not a URL or a path searches the vault
 *    (`VaultIndex.search`, like Insert Link); picking a hit fills in its
 *    relative link ([LunarborLink.relative]), and its title as the text
 *    when the text is empty. A place in the vault is described under the
 *    field (its readable path). `www.…` gets `https://`. Emptied, Save
 *    removes the link.
 *
 * Enter saves (or picks the highlighted hit), ↑ / ↓ move through the hits,
 * Escape closes the hits, then the dialog. Reuses the Agent access dialog's
 * shell (`lunarbor-mcp-*`); its own rules are `lunarbor-linkedit-*`.
 *
 * View glue only: Save is `MainViewModel.updateLinkAt`, Remove link
 * `MainViewModel.removeLinkAt`; the link is addressed by row + column.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.VaultEntryKind

/**
 * Opens the Edit link dialog for the link at [row] / [col] (one at a time;
 * nothing happens when no link is there any more).
 *
 * Called by [LinkHoverPopup]'s "Edit link" button.
 *
 * @param viewModel The pane's view model: reads the link, receives the edit.
 * @param scope Scope for the vault search behind the Link field.
 * @param row Document row of the link.
 * @param col A model column inside the link's source span.
 * @param href The link's resolved target as drawn (`data-href`); the Link
 *   field starts with it for a wiki link, whose source names no path.
 * @param focusEditor Puts focus back in the editor once the dialog closes.
 */
internal fun openLinkEditDialog(
    viewModel: MainViewModel,
    scope: CoroutineScope,
    row: Int,
    col: Int,
    href: String,
    focusEditor: () -> Unit,
) {
    if (document.querySelector(".lunarbor-linkedit-dialog") != null) return
    val link = viewModel.linkAt(row, col) ?: return
    val base = viewModel.linkBaseOf(row)
    ensureAgentAccessStyles()
    ensureLinkEditStyles()
    val prepareJob = scope.launch { viewModel.prepareLinkSearch() }

    val backdrop = el("div", "lunarbor-mcp-backdrop")
    val panel = el("div", "lunarbor-mcp-dialog lunarbor-linkedit-dialog")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", "Edit link")
    backdrop.appendChild(panel)

    lateinit var close: (save: Boolean) -> Unit

    val header = el("div", "lunarbor-mcp-dialog-head")
    header.appendChild(el("h2", "lunarbor-mcp-dialog-title", "Edit link"))
    val closeButton = button("×", "lunarbor-mcp-button lunarbor-mcp-close") { close(false) }
    closeButton.title = "Close"
    header.appendChild(closeButton)
    panel.appendChild(header)

    val body = el("div", "lunarbor-mcp-dialog-body")
    panel.appendChild(body)

    val textInput = input("lunarbor-linkedit-text", "Shown text")
    textInput.value = link.text
    body.appendChild(field("Text", textInput))

    val urlInput = input("lunarbor-linkedit-url", "https://… or search for a node or file")
    urlInput.value = link.url.ifEmpty { href }
    val urlField = field("Link", urlInput)
    val hint = el("div", "lunarbor-linkedit-hint")
    val hitList = el("div", "lunarbor-linkedit-hits")
    hitList.hidden = true
    urlField.appendChild(hint)
    urlField.appendChild(hitList)
    body.appendChild(urlField)

    val footer = el("div", "lunarbor-linkedit-footer")
    // A bare URL is a link to itself: unlinked, it would link again.
    if (!(link.text == link.url && link.url.isNotEmpty())) {
        val remove = button("Remove link", "lunarbor-mcp-button is-danger") {
            close(false)
            viewModel.removeLinkAt(row, col)
        }
        footer.appendChild(remove)
    }
    footer.appendChild(el("span", "lunarbor-linkedit-spacer"))
    footer.appendChild(button("Cancel", "lunarbor-mcp-button") { close(false) })
    footer.appendChild(button("Save", "lunarbor-mcp-button is-primary") { close(true) })
    panel.appendChild(footer)

    var hits: List<LinkTarget> = emptyList()
    var highlighted = 0
    var searchJob: Job? = null

    fun describe() {
        val url = urlInput.value.trim()
        val path = if (looksLikePath(url)) LunarborLink.resolve(url, base) else null
        hint.textContent = when {
            path == null -> ""
            viewModel.isPathHidden(path) -> "Not found"
            else -> "In this vault · " + LunarborLink.displayPath(path)
        }
        hint.hidden = hint.textContent.isNullOrEmpty()
    }

    fun paintHits() {
        while (hitList.firstChild != null) hitList.removeChild(hitList.firstChild!!)
        hitList.hidden = hits.isEmpty()
        for ((index, hit) in hits.withIndex()) {
            val item = el("button", "lunarbor-linkedit-hit" + if (index == highlighted) " is-active" else "")
            item.setAttribute("type", "button")
            item.appendChild(el("span", "lunarbor-linkedit-hit-title", hit.title))
            item.appendChild(el("span", "lunarbor-linkedit-hit-path", kindLabel(hit) + " · " + LunarborLink.displayPath(hit.pathRel)))
            // Act on mousedown, keeping focus in the field.
            item.addEventListener("mousedown", { e ->
                e.preventDefault()
                highlighted = index
                pick(hit, base, urlInput, textInput)
                hits = emptyList()
                paintHits()
                describe()
            })
            hitList.appendChild(item)
        }
    }

    fun search() {
        searchJob?.cancel()
        val query = urlInput.value.trim()
        if (query.isEmpty() || looksLikeUrl(query) || looksLikePath(query)) {
            hits = emptyList()
            paintHits()
            return
        }
        searchJob = scope.launch {
            prepareJob.join()
            val found = viewModel.vaultIndex.search(query, max = 8)
            if (urlInput.value.trim() != query) return@launch
            hits = found
            highlighted = 0
            paintHits()
        }
    }

    urlInput.addEventListener("input", { _ -> describe(); search() })
    urlInput.addEventListener("blur", { _ -> hits = emptyList(); paintHits() })

    val keyHandler: (Event) -> Unit = handler@{ e ->
        val ke = e as KeyboardEvent
        val inUrl = document.activeElement === urlInput
        when (ke.key) {
            "Escape" -> {
                ke.preventDefault()
                ke.stopPropagation()
                if (inUrl && hits.isNotEmpty()) {
                    hits = emptyList()
                    paintHits()
                } else {
                    close(false)
                }
            }
            "Enter" -> {
                if (document.activeElement is HTMLButtonElement) return@handler
                ke.preventDefault()
                ke.stopPropagation()
                val hit = hits.getOrNull(highlighted)
                if (inUrl && hit != null) {
                    pick(hit, base, urlInput, textInput)
                    hits = emptyList()
                    paintHits()
                    describe()
                } else {
                    close(true)
                }
            }
            "ArrowDown", "ArrowUp" -> {
                if (!inUrl || hits.isEmpty()) return@handler
                ke.preventDefault()
                highlighted = (highlighted + if (ke.key == "ArrowDown") 1 else -1).coerceIn(0, hits.lastIndex)
                paintHits()
            }
        }
    }

    close = { save ->
        document.removeEventListener("keydown", keyHandler, true)
        searchJob?.cancel()
        backdrop.remove()
        if (save) {
            viewModel.updateLinkAt(row, col, textInput.value, normalizedUrl(urlInput.value))
        }
        focusEditor()
    }
    document.addEventListener("keydown", keyHandler, true)
    backdrop.addEventListener("mousedown", { e -> if (e.target === backdrop) close(false) })
    document.body?.appendChild(backdrop)

    describe()
    // A link that shows its own URL starts in the Link field.
    val first = if (link.text.isEmpty() || link.text == urlInput.value) urlInput else textInput
    first.focus()
    first.select()
}

/**
 * Puts a link to [hit], relative to the row's folder [base], in
 * [urlInput], and its title in an empty [textInput].
 */
private fun pick(hit: LinkTarget, base: String, urlInput: HTMLInputElement, textInput: HTMLInputElement) {
    urlInput.value = LunarborLink.relative(hit.pathRel, hit.kind == VaultEntryKind.FOLDER, base)
    if (textInput.value.isBlank()) textInput.value = hit.title
}

/** `true` when [text] already reads as a URL (`scheme:…` or `www.…`), so the vault is not searched. */
private fun looksLikeUrl(text: String): Boolean =
    text.startsWith("www.") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S").containsMatchIn(text)

/**
 * `true` when [text] reads as a path rather than words to search for: it
 * holds a `/` or a file extension (`plan.md`).
 */
private fun looksLikePath(text: String): Boolean =
    '/' in text || Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(text)

/** [url] trimmed, with `https://` before a `www.` address. */
private fun normalizedUrl(url: String): String {
    val u = url.trim()
    return if (u.startsWith("www.")) "https://$u" else u
}

/** Short type label before a hit's path: node, note, image, drawing, web page or file. */
private fun kindLabel(hit: LinkTarget): String = when (hit.kind) {
    VaultEntryKind.FOLDER -> if (hit.pathRel.isEmpty()) "home" else "node"
    VaultEntryKind.MARKDOWN -> "note"
    VaultEntryKind.IMAGE -> "image"
    VaultEntryKind.DRAWING -> "drawing"
    VaultEntryKind.HTML -> "web page"
    VaultEntryKind.FILE -> "file"
}

private fun field(label: String, control: HTMLInputElement): HTMLElement {
    val wrap = el("label", "lunarbor-linkedit-field")
    wrap.appendChild(el("span", "lunarbor-mcp-label", label))
    wrap.appendChild(control)
    return wrap
}

private fun input(id: String, placeholder: String): HTMLInputElement {
    val i = document.createElement("input") as HTMLInputElement
    i.type = "text"
    i.id = id
    i.className = "lunarbor-linkedit-input"
    i.placeholder = placeholder
    i.autocomplete = "off"
    i.spellcheck = false
    return i
}

private fun button(label: String, className: String, onClick: () -> Unit): HTMLButtonElement {
    val b = document.createElement("button") as HTMLButtonElement
    b.type = "button"
    b.className = className
    b.textContent = label
    b.addEventListener("click", { _: Event -> onClick() })
    return b
}

private fun el(tag: String, className: String, text: String? = null): HTMLElement =
    (document.createElement(tag) as HTMLElement).also {
        it.className = className
        if (text != null) it.textContent = text
    }

private fun ensureLinkEditStyles() {
    if (document.getElementById("lunarbor-linkedit-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-linkedit-style"
    style.textContent = LINK_EDIT_CSS
    document.head?.appendChild(style)
}

private const val LINK_EDIT_CSS = """
.lunarbor-linkedit-dialog { width: min(480px, 100%); }
.lunarbor-linkedit-dialog .lunarbor-mcp-dialog-body { gap: 14px; overflow: visible; }
.lunarbor-linkedit-field { display: flex; flex-direction: column; gap: 5px; position: relative; }
.lunarbor-linkedit-input {
    font: inherit; font-size: 13px; color: inherit; padding: 7px 9px; border-radius: 6px;
    background: color-mix(in srgb, var(--t-text, #e6e6e6) 4%, transparent);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12));
}
.lunarbor-linkedit-input:focus { outline: none; border-color: var(--t-accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--t-accent) 22%, transparent); }
.lunarbor-linkedit-hint { font-size: 12px; color: var(--t-text-dim, #9a9a9a); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lunarbor-linkedit-hint[hidden], .lunarbor-linkedit-hits[hidden] { display: none; }
.lunarbor-linkedit-hits {
    position: absolute; top: 100%; left: 0; right: 0; z-index: 1; margin-top: 4px;
    display: flex; flex-direction: column; padding: 4px; max-height: 260px; overflow-y: auto;
    background: var(--t-surface-alt, var(--t-bg, #1e1e1e)); border: 1px solid var(--t-border, rgba(255,255,255,0.12));
    border-radius: 8px; box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
}
.lunarbor-linkedit-hit {
    display: flex; flex-direction: column; align-items: flex-start; gap: 1px; padding: 6px 8px;
    font: inherit; color: inherit; text-align: left; background: transparent; border: 0; border-radius: 5px; cursor: pointer;
}
.lunarbor-linkedit-hit.is-active { background: color-mix(in srgb, var(--t-accent) 18%, transparent); }
.lunarbor-linkedit-hit-title { font-weight: 600; }
.lunarbor-linkedit-hit-path { font-size: 11px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-linkedit-footer {
    display: flex; align-items: center; gap: 8px; padding: 12px 16px 14px;
    border-top: 1px solid var(--t-border, rgba(255,255,255,0.12));
}
.lunarbor-linkedit-spacer { flex: 1; }
.lunarbor-mcp-button.is-primary { background: var(--t-accent); border-color: var(--t-accent); color: var(--t-accent-on, #fff); padding: 4px 16px; }
.lunarbor-mcp-button.is-primary:hover { background: color-mix(in srgb, var(--t-accent) 85%, #fff); border-color: var(--t-accent); }
"""
