/*
 * VaultFooter.kt (jsMain)
 * -----------------------
 * Renders the editor's filesystem-tree footer — a non-editable, bullet-styled
 * outline of every `.md` file and folder in the vault, lazy-loaded one
 * directory at a time. Lives in a sibling DOM block to the contenteditable
 * editor host (see `MainScreen`) so its DOM is never wiped by the editor's
 * paint loop and never participates in caret/selection mapping.
 *
 * Visual continuity with the document outline is intentional: the same bullet
 * glyph and chevron classes (`notegrow-bullet`, `notegrow-chevron`) are
 * reused so the footer reads as "another outline beneath the document". The
 * difference is opacity and provenance — files render dimmed if they're
 * foreign (no `notegrow: true` marker), and folders use their basename.
 *
 * Every footer row carries a `data-vault-path` (or `data-vault-dir`) data
 * attribute instead of `data-row` so any DOM walker that filters on
 * `data-row` (selection mapping, hit testing) skips the footer entirely.
 *
 * The footer is hidden when the editor is zoomed into a subtree — the
 * filesystem index only makes sense at root view.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.notegrow.data.VaultEntry

/**
 * Repaints [container] for [state]. Empties and rebuilds the entire footer
 * tree on every call — same approach as the document paint loop and equally
 * cheap, because the footer only renders folders the user has explicitly
 * opened (lazy expansion keeps the DOM small).
 *
 * Does nothing while the document is loading (no listings cached yet) or
 * while the editor is zoomed into a subtree.
 *
 * @param container The sibling div allocated by `MainScreen` for the footer.
 *   Receives `contenteditable="false"` once at mount; this function only
 *   manipulates its child nodes.
 * @param state Latest viewer state, used for zoom gate, master toggle, and
 *   per-folder expand state.
 * @param viewModel Receives the user's intent calls — toggle the master
 *   chevron, toggle a folder, navigate to a file.
 * @param style Visual constants, used for indent step.
 */
fun paintVaultFooter(
    container: HTMLElement,
    state: DocumentViewBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
) {
    container.innerHTML = ""
    if (!state.isLoaded) return
    if (viewModel.zoomInfo(state) != null) {
        // Hidden when zoomed — keep the wrapper present so layout doesn't
        // jump, but don't render any rows.
        return
    }
    // Only show the footer on the root document. On any other file the
    // user is "inside" a specific note and the vault-wide file index would
    // just be noise — they navigate back via the pane's back button or the
    // breadcrumb's leading segment.
    val docState = state.documentState ?: return
    if (docState.activeFileRel != viewModel.rootFileName) return

    container.appendChild(buildHeader(state.isVaultFooterExpanded, viewModel))
    if (!state.isVaultFooterExpanded) return

    val rootEntries = docState.vaultListings[""]
    if (rootEntries == null) {
        container.appendChild(buildLoadingRow(0, style))
        return
    }
    if (rootEntries.isEmpty()) {
        container.appendChild(buildEmptyRow())
        return
    }
    renderEntries(container, rootEntries, depth = 0, state = state, viewModel = viewModel, style = style)
}

/**
 * Builds the "Files" header row plus its master chevron. Clicking either the
 * chevron or the header text toggles [DocumentViewBackingViewModel.State.isVaultFooterExpanded]
 * via [MainViewModel.toggleVaultFooter].
 */
private fun buildHeader(isExpanded: Boolean, viewModel: MainViewModel): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "notegrow-vault-header"

    val chevron = document.createElement("div") as HTMLElement
    chevron.className = "notegrow-chevron notegrow-vault-header-chevron"
    chevron.setAttribute("contenteditable", "false")
    val rotation = if (isExpanded) "none" else "rotate(-90deg)"
    chevron.innerHTML = "<svg viewBox=\"0 0 16 16\" width=\"10\" height=\"10\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"transform: $rotation; transition: transform 120ms ease; pointer-events: none;\">" +
        "<polyline points=\"4,6 8,10 12,6\"></polyline></svg>"
    row.appendChild(chevron)

    val label = document.createElement("span") as HTMLElement
    label.className = "notegrow-vault-header-label"
    label.textContent = "Files"
    row.appendChild(label)

    val toggle: (org.w3c.dom.events.Event) -> Unit = { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        viewModel.toggleVaultFooter()
    }
    row.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    row.addEventListener("click", toggle)
    return row
}

/**
 * Recursively appends rows for [entries] at indentation [depth] into
 * [container]. Each entry is rendered as a bullet row that mirrors the
 * outline's visual style; folders also carry a chevron.
 */
private fun renderEntries(
    container: HTMLElement,
    entries: List<VaultEntry>,
    depth: Int,
    state: DocumentViewBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
) {
    for (entry in entries) {
        container.appendChild(buildEntryRow(entry, depth, state, viewModel, style))
        if (entry.isDirectory && entry.pathRel in state.expandedVaultPaths) {
            val children = state.documentState?.vaultListings?.get(entry.pathRel)
            if (children == null) {
                container.appendChild(buildLoadingRow(depth + 1, style))
            } else if (children.isEmpty()) {
                // Empty folder — render a muted "(empty)" row at the next indent.
                container.appendChild(buildEmptyChildRow(depth + 1, style))
            } else {
                renderEntries(container, children, depth + 1, state, viewModel, style)
            }
        }
    }
}

/**
 * Builds one bullet row for [entry] at indent depth [depth]. Folders get a
 * chevron (clicks toggle expand) and a click handler on the bullet that
 * also toggles. Files get a clickable bullet/text that navigates to the
 * file. Foreign files are styled dimmed.
 */
private fun buildEntryRow(
    entry: VaultEntry,
    depth: Int,
    state: DocumentViewBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
): HTMLElement {
    val rowDiv = document.createElement("div") as HTMLElement
    rowDiv.className = "notegrow-vault-row"
    if (entry.isDirectory) rowDiv.setAttribute("data-vault-dir", entry.pathRel)
    else rowDiv.setAttribute("data-vault-path", entry.pathRel)
    rowDiv.style.apply {
        setProperty("position", "relative")
        minHeight = "${style.lineHeightPx}px"
        setProperty("line-height", "${style.lineHeightPx}px")
        paddingLeft = "${depth * style.indentStepPx}px"
    }

    if (entry.isDirectory) {
        val isExpanded = entry.pathRel in state.expandedVaultPaths
        val chevron = buildVaultChevron(isExpanded)
        chevron.style.left = "${depth * style.indentStepPx - 22}px"
        rowDiv.appendChild(chevron)

        rowDiv.appendChild(buildBulletGlyph(isFolder = true))
        rowDiv.appendChild(buildEntryLabel(entry.name))

        val toggle: (org.w3c.dom.events.Event) -> Unit = { event ->
            val me = event as MouseEvent
            me.stopPropagation()
            me.preventDefault()
            viewModel.toggleVaultFolder(entry.pathRel)
        }
        rowDiv.addEventListener("mousedown", { event ->
            val me = event as MouseEvent
            me.stopPropagation()
            me.preventDefault()
        })
        rowDiv.addEventListener("click", toggle)
    } else {
        rowDiv.appendChild(buildBulletGlyph(isFolder = false))
        rowDiv.appendChild(buildEntryLabel(entry.name))

        val navigate: (org.w3c.dom.events.Event) -> Unit = { event ->
            val me = event as MouseEvent
            me.stopPropagation()
            me.preventDefault()
            viewModel.navigateToVaultFile(entry.pathRel)
        }
        rowDiv.addEventListener("mousedown", { event ->
            val me = event as MouseEvent
            me.stopPropagation()
            me.preventDefault()
        })
        rowDiv.addEventListener("click", navigate)
    }
    return rowDiv
}

/**
 * The bullet glyph used by every footer row. Mirrors the outline's
 * `.notegrow-bullet-prefix` shape (non-editable bullet + trailing space) so
 * the visual reads identical to a document bullet.
 *
 * @param isFolder When `true`, an additional class marks the bullet as a
 *   folder so CSS can render it differently (a slightly larger or hollow
 *   dot, etc.). For now both share the same dot — visual differentiation
 *   comes from the chevron.
 */
private fun buildBulletGlyph(isFolder: Boolean): HTMLElement {
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "notegrow-bullet-prefix"
    prefix.setAttribute("contenteditable", "false")
    prefix.style.apply {
        setProperty("user-select", "none")
        cursor = "pointer"
    }
    val glyph = document.createElement("span") as HTMLElement
    glyph.className = if (isFolder) "notegrow-bullet notegrow-vault-folder" else "notegrow-bullet"
    prefix.appendChild(glyph)
    val space = document.createElement("span") as HTMLElement
    space.textContent = " "
    prefix.appendChild(space)
    return prefix
}

/**
 * Builds the entry's text label. Plain `<span>` — no caret can land here
 * because the footer root carries `contenteditable="false"`.
 */
private fun buildEntryLabel(text: String): HTMLElement {
    val span = document.createElement("span") as HTMLElement
    span.className = "notegrow-vault-text"
    span.textContent = text
    return span
}

/**
 * Disclosure chevron for a folder row. Same SVG and structure as the
 * outline's chevron (`OutlinePaintLoop.buildChevron`) but click handling
 * lives at the row level — clicking the chevron and clicking the bullet
 * text both toggle the folder in [State.expandedVaultPaths].
 */
private fun buildVaultChevron(isExpanded: Boolean): HTMLElement {
    val target = document.createElement("div") as HTMLElement
    target.className = "notegrow-chevron"
    target.title = if (isExpanded) "Collapse" else "Expand"
    target.setAttribute("contenteditable", "false")
    target.style.apply {
        setProperty("position", "absolute")
        top = "0"
        width = "22px"
        height = "100%"
        cursor = "pointer"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        color = "var(--t-text-tertiary, #7a7a7a)"
        setProperty("user-select", "none")
    }
    val rotation = if (isExpanded) "none" else "rotate(-90deg)"
    target.innerHTML = "<svg viewBox=\"0 0 16 16\" width=\"10\" height=\"10\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"transform: $rotation; transition: transform 120ms ease; pointer-events: none;\">" +
        "<polyline points=\"4,6 8,10 12,6\"></polyline></svg>"
    return target
}

/**
 * Placeholder row shown beneath an expanded folder while its listing is in
 * flight. Resolves to the folder's actual children on the next state
 * emission.
 */
private fun buildLoadingRow(depth: Int, style: EditorStyle): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "notegrow-vault-loading"
    row.textContent = "Loading…"
    row.style.paddingLeft = "${depth * style.indentStepPx}px"
    row.style.minHeight = "${style.lineHeightPx}px"
    row.style.setProperty("line-height", "${style.lineHeightPx}px")
    return row
}

/**
 * Row shown when an expanded folder turns out to have no markdown files
 * or subdirectories. Mostly useful while developing, but the dimmed
 * "(empty)" label avoids a confusing blank space in the tree.
 */
private fun buildEmptyChildRow(depth: Int, style: EditorStyle): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "notegrow-vault-loading"
    row.textContent = "(empty)"
    row.style.paddingLeft = "${depth * style.indentStepPx}px"
    row.style.minHeight = "${style.lineHeightPx}px"
    row.style.setProperty("line-height", "${style.lineHeightPx}px")
    return row
}

/**
 * Row shown when the vault root listing comes back empty — typically only
 * happens before the first file is created.
 */
private fun buildEmptyRow(): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "notegrow-vault-loading"
    row.textContent = "(empty vault)"
    return row
}
