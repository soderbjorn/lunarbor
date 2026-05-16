/*
 * VaultFooter.kt (jsMain)
 * -----------------------
 * Renders the editor's filesystem-tree footer — a non-editable, bullet-styled
 * listing of the directory anchored by the currently-viewed file, lazy-loaded
 * one folder level at a time. Lives in a sibling DOM block to the
 * contenteditable editor host (see `MainScreen`) so its DOM is never wiped by
 * the editor's paint loop and never participates in caret/selection mapping.
 *
 * Visual continuity with the document outline is intentional: the same bullet
 * glyph and chevron classes (`notegrow-bullet`, `notegrow-chevron`) are
 * reused so the footer reads as "another outline beneath the document".
 * Folders use their basename; files render with their display name.
 *
 * Every footer row carries a `data-vault-path` (or `data-vault-dir`) data
 * attribute instead of `data-row` so any DOM walker that filters on
 * `data-row` (selection mapping, hit testing) skips the footer entirely.
 *
 * ### When the footer renders
 *
 * The footer renders for the pane's **effective anchor file** — the file
 * whose content is currently the focus of the view. Two cases produce an
 * effective anchor:
 *
 *  - The pane is unzoomed and [PaneBackingViewModel.State.activeFileRel]
 *    is itself an anchor: the configured root file (anchors `""`, the
 *    vault root) or a doubled-name file `<dir>/<basename>.md` (anchors
 *    `<dir>`).
 *  - The pane is zoomed into a promoted-ref bullet whose child file is an
 *    anchor. The visible zoom region *is* that child file's content, so
 *    the footer lists the child's anchored directory — exactly as if the
 *    user had navigated into the child file directly.
 *
 * In every other case (non-anchor active file, zoom into a plain inline
 * bullet, zoom into a promoted-ref whose child is a loose non-anchored
 * file) the footer is hidden — the user is "inside" a specific note and a
 * filesystem listing would be noise.
 *
 * The anchor file itself is filtered out of the listing so the footer
 * never redundantly points at the file currently being viewed.
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
 * when there is no effective anchor file (see the file-level comment).
 *
 * @param container The sibling div allocated by `MainScreen` for the footer.
 *   Receives `contenteditable="false"` once at mount; this function only
 *   manipulates its child nodes.
 * @param state Latest viewer state, used for the effective-anchor lookup,
 *   master toggle, and per-folder expand state.
 * @param viewModel Receives the user's intent calls — toggle the master
 *   chevron, toggle a folder, navigate to a file. Also resolves the
 *   zoomed-promoted-ref child file when computing the effective anchor.
 * @param style Visual constants, used for indent step.
 */
fun paintVaultFooter(
    container: HTMLElement,
    state: PaneBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
) {
    container.innerHTML = ""
    if (!state.isLoaded) return
    if (state.documentState == null) return
    // Effective anchor file: when zoomed into a promoted-ref bullet, the
    // visible content belongs to the child file — use *that* as the
    // candidate anchor so the footer reflects what the user is actually
    // looking at. Falls back to the pane's active file in every other case
    // (including non-promoted-ref zooms, which still want footer-hidden).
    val zoomedChild = viewModel.zoomedPromotedRefFileRel(state)
    val effectiveAnchorFile = zoomedChild ?: state.activeFileRel
    // Hide the footer for any zoom that *doesn't* land on a promoted-ref
    // child anchor. Plain inline zooms keep the editor view focused.
    if (zoomedChild == null && viewModel.zoomInfo(state) != null) return
    // The footer renders only when the effective anchor file anchors some
    // directory — see the file-level comment. Loose / non-anchor files
    // hide the footer entirely.
    val anchorDir = state
        .anchoredDirectoryFor(effectiveAnchorFile, viewModel.rootFileName) ?: return

    // Decide whether there is anything to show **before** appending the
    // header. We don't render the "Files" separator (or any placeholder)
    // when the directory has no listable entries — the editor view just
    // ends at the document body.
    val entries = state.vaultListings[anchorDir]
    if (entries == null) {
        // Listing hasn't been fetched yet — kick off the lazy load and
        // render nothing. `ensureVaultListing` is a no-op when the entry
        // is already present, so calling it on every repaint is safe.
        // The load is fast; a transient "Loading…" row that may vanish
        // into an empty footer would flicker.
        viewModel.ensureVaultListing(anchorDir)
        return
    }
    val filtered = entries.filter { it.pathRel != effectiveAnchorFile }
    if (filtered.isEmpty()) return

    container.appendChild(buildHeader(state, viewModel))
    if (!state.isVaultFooterExpanded) return
    val sorted = sortVaultEntries(filtered, state)
    renderEntries(container, sorted, depth = 0, state = state, viewModel = viewModel, style = style)
}

/**
 * Returns [entries] reordered to match the user's active sort mode.
 * Directories always sort before files; within each group the active
 * [PaneBackingViewModel.State.filesSortMode] decides whether to compare
 * by lower-cased name or by [VaultEntry.lastEditedMs], and the
 * per-mode descending flag flips the result.
 */
private fun sortVaultEntries(
    entries: List<VaultEntry>,
    state: PaneBackingViewModel.State,
): List<VaultEntry> {
    val sign = when (state.filesSortMode) {
        FilesSortMode.NAME -> if (state.filesSortNameDescending) -1 else 1
        FilesSortMode.EDITED -> if (state.filesSortEditedDescending) -1 else 1
    }
    val byMode = Comparator<VaultEntry> { a, b ->
        val cmp = when (state.filesSortMode) {
            FilesSortMode.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
            FilesSortMode.EDITED -> a.lastEditedMs.compareTo(b.lastEditedMs)
        }
        sign * cmp
    }
    return entries.sortedWith(
        compareByDescending<VaultEntry> { it.isDirectory }.then(byMode)
    )
}

/**
 * Builds the "Files" header row: the master chevron, the label, and the
 * two sort-mode icons (alpha + clock). Clicking the chevron or label
 * toggles [PaneBackingViewModel.State.isVaultFooterExpanded] via
 * [MainViewModel.toggleVaultFooter]; clicking either icon flows through
 * [MainViewModel.cycleFilesSort]. The icons stop event propagation so a
 * sort click never collapses the footer.
 */
private fun buildHeader(state: PaneBackingViewModel.State, viewModel: MainViewModel): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "notegrow-vault-header"

    val chevron = document.createElement("div") as HTMLElement
    chevron.className = "notegrow-chevron notegrow-vault-header-chevron"
    chevron.setAttribute("contenteditable", "false")
    val rotation = if (state.isVaultFooterExpanded) "none" else "rotate(-90deg)"
    chevron.innerHTML = "<svg viewBox=\"0 0 16 16\" width=\"10\" height=\"10\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"transform: $rotation; transition: transform 120ms ease; pointer-events: none;\">" +
        "<polyline points=\"4,6 8,10 12,6\"></polyline></svg>"
    row.appendChild(chevron)

    val label = document.createElement("span") as HTMLElement
    label.className = "notegrow-vault-header-label"
    label.textContent = "Files"
    row.appendChild(label)

    val nameActive = state.filesSortMode == FilesSortMode.NAME
    val editedActive = state.filesSortMode == FilesSortMode.EDITED
    val alphaIcon = buildSortIcon(
        svg = sortIconNameSvg(descending = state.filesSortNameDescending),
        isActive = nameActive,
        title = if (nameActive) {
            if (state.filesSortNameDescending) "Sort by name (Z→A) — click to flip"
            else "Sort by name (A→Z) — click to flip"
        } else "Sort by name",
        marginLeftAuto = true,
    ) { viewModel.cycleFilesSort(FilesSortMode.NAME) }
    val clockIcon = buildSortIcon(
        svg = sortIconEditedSvg(descending = state.filesSortEditedDescending),
        isActive = editedActive,
        title = if (editedActive) {
            if (state.filesSortEditedDescending) "Sort by last edit (newest first) — click to flip"
            else "Sort by last edit (oldest first) — click to flip"
        } else "Sort by last edit",
        marginLeftAuto = false,
    ) { viewModel.cycleFilesSort(FilesSortMode.EDITED) }
    row.appendChild(alphaIcon)
    row.appendChild(clockIcon)

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
 * Builds one of the two sort-mode toggle icons in the Files header.
 * Mirrors the chevron's 22×22 affordance but flows inline at the right
 * edge of the row instead of absolute-positioning. Stops both mousedown
 * and click propagation so the parent row's "toggle footer" handler
 * doesn't fire when the user is just changing sort order.
 *
 * @param svg Inline SVG markup that fills the button. The element is
 *   coloured via `color: currentColor` so the parent's `color` style
 *   tints the strokes.
 * @param isActive Drives colour — active icons render in the primary
 *   text colour, inactive in the tertiary colour.
 * @param title Hover tooltip — describes both the mode and (when active)
 *   what a click will do.
 * @param marginLeftAuto When `true`, pushes this and every subsequent
 *   sibling to the right edge of the flex row. Set on the first of the
 *   two icons so the pair sits at the row's trailing edge.
 * @param onClick Invoked once per click; receives no arguments.
 */
private fun buildSortIcon(
    svg: String,
    isActive: Boolean,
    title: String,
    marginLeftAuto: Boolean,
    onClick: () -> Unit,
): HTMLElement {
    val btn = document.createElement("div") as HTMLElement
    btn.className = "notegrow-vault-sort-icon"
    btn.title = title
    btn.setAttribute("contenteditable", "false")
    btn.style.apply {
        width = "22px"
        height = "22px"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        cursor = "pointer"
        setProperty("user-select", "none")
        color = if (isActive) "var(--t-text-primary, #e6e6e6)"
                else "var(--t-text-tertiary, #7a7a7a)"
        opacity = if (isActive) "1" else "0.65"
        if (marginLeftAuto) setProperty("margin-left", "auto")
    }
    btn.innerHTML = svg
    btn.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    btn.addEventListener("click", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        onClick()
    })
    return btn
}

/**
 * SVG for the alphabetic sort icon. Renders the letter "A" with a small
 * down- or up-arrow to its right depending on [descending] — down for
 * descending (Z→A), up for ascending (A→Z) so the arrow points the way
 * the list "grows" through the alphabet.
 */
private fun sortIconNameSvg(descending: Boolean): String {
    val arrow = if (descending) "<polyline points=\"12,7 12,15\"/><polyline points=\"9.5,12.5 12,15 14.5,12.5\"/>"
                else "<polyline points=\"12,15 12,7\"/><polyline points=\"9.5,9.5 12,7 14.5,9.5\"/>"
    return "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" stroke=\"currentColor\" " +
        "stroke-width=\"1.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"pointer-events: none;\">" +
        // Letter A: two diagonal strokes and a crossbar.
        "<polyline points=\"2,14 5,4 8,14\"/>" +
        "<line x1=\"3.2\" y1=\"10\" x2=\"6.8\" y2=\"10\"/>" +
        arrow +
        "</svg>"
}

/**
 * SVG for the last-edit sort icon. Renders a clock face plus an arrow —
 * down for descending (newest first), up for ascending (oldest first).
 */
private fun sortIconEditedSvg(descending: Boolean): String {
    val arrow = if (descending) "<polyline points=\"12,7 12,15\"/><polyline points=\"9.5,12.5 12,15 14.5,12.5\"/>"
                else "<polyline points=\"12,15 12,7\"/><polyline points=\"9.5,9.5 12,7 14.5,9.5\"/>"
    return "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" stroke=\"currentColor\" " +
        "stroke-width=\"1.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"pointer-events: none;\">" +
        // Clock face + hands.
        "<circle cx=\"5.5\" cy=\"9\" r=\"4\"/>" +
        "<polyline points=\"5.5,6.5 5.5,9 7.3,10.2\"/>" +
        arrow +
        "</svg>"
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
    state: PaneBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
) {
    for (entry in entries) {
        container.appendChild(buildEntryRow(entry, depth, state, viewModel, style))
        if (entry.isDirectory && entry.pathRel in state.expandedVaultPaths) {
            val children = state.vaultListings.get(entry.pathRel)
            if (children == null) {
                container.appendChild(buildLoadingRow(depth + 1, style))
            } else if (children.isEmpty()) {
                // Empty folder — render a muted "(empty)" row at the next indent.
                container.appendChild(buildEmptyChildRow(depth + 1, style))
            } else {
                renderEntries(container, sortVaultEntries(children, state), depth + 1, state, viewModel, style)
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
    state: PaneBackingViewModel.State,
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
        val fileIcon = buildVaultFileIcon()
        fileIcon.style.left = "${depth * style.indentStepPx - 22}px"
        rowDiv.appendChild(fileIcon)

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
 * Leading document-icon for a standalone-file row. Positioned in the same
 * 22px-wide slot used by [buildVaultChevron] on folder rows, so file rows
 * line up horizontally with folder rows in the tree. Inert: the icon does
 * not handle clicks itself — the parent row's listener navigates to the
 * file. The page-with-fold glyph mirrors `AppShell.ICON_NOTE`.
 */
private fun buildVaultFileIcon(): HTMLElement {
    val target = document.createElement("div") as HTMLElement
    target.className = "notegrow-vault-file-icon"
    target.setAttribute("contenteditable", "false")
    target.style.apply {
        setProperty("position", "absolute")
        top = "0"
        width = "22px"
        height = "100%"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        color = "var(--t-text-tertiary, #7a7a7a)"
        setProperty("user-select", "none")
        setProperty("pointer-events", "none")
    }
    target.innerHTML = "<svg viewBox=\"0 0 24 24\" width=\"11\" height=\"11\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" style=\"pointer-events: none;\">" +
        "<path d=\"M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z\"/>" +
        "<polyline points=\"14 3 14 9 20 9\"/></svg>"
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

