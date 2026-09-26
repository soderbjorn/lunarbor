/*
 * VaultFooter.kt (jsMain)
 * -----------------------
 * Renders the editor's filesystem-tree footer — a non-editable, bullet-styled
 * listing of the directory anchored by the currently-viewed file, lazy-loaded
 * one folder level at a time. Lives in a sibling DOM block to the
 * contenteditable editor host (see `MainScreen`) so its DOM is never wiped by
 * the editor's paint loop and never participates in caret/selection mapping.
 *
 * Visual continuity with the document outline is intentional: the row wrapper
 * (`treefacts-bullet-prefix`), bullet dot (`treefacts-bullet`), and chevron
 * (`treefacts-chevron`) classes are reused so the footer reads as "another
 * outline beneath the document". This bullet-first styling is deliberate — it
 * lets a vault dropped in from Obsidian or Dynalist read as a seamless
 * continuation of the outline rather than a separate file browser.
 *
 * The bullet slot therefore shows the same dot as the outline for note files
 * and folders; only image files carry a picture glyph, since they aren't
 * outline content. Files are leaves (a bullet, no chevron); folders are
 * expandable (a bullet plus a disclosure chevron) — exactly the parent/leaf
 * distinction the outline draws. Clicking a file's bullet (via the row
 * listener) navigates to it. Folders show their decoded folder name; files
 * their display name.
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
 *    is a node outline: the root outline (lists `""`, the vault root) or
 *    `<dir>/.treefacts` (lists `<dir>`).
 *  - The pane is zoomed into a folder-backed bullet. The visible zoom
 *    region *is* that bullet's folder, so the footer lists it — exactly
 *    as if the user had navigated into the folder's outline directly.
 *
 * In every other case (a `.md` note, a zoom into a leaf bullet) the footer
 * is hidden — the user is "inside" a specific note and a filesystem
 * listing would be noise.
 *
 * The anchor file itself is filtered out of the listing so the footer
 * never redundantly points at the file currently being viewed.
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.treefacts.data.VaultEntry

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
    // The footer stays visible while the pane is in image view even
    // though `isLoaded` is false (no document is loaded) — its whole
    // job in that mode is to let the user navigate to a sibling file.
    if (!state.isLoaded && !state.isImageView) return
    if (state.documentState == null && !state.isImageView) return
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
    row.className = "treefacts-vault-header"

    val chevron = document.createElement("div") as HTMLElement
    chevron.className = "treefacts-chevron treefacts-vault-header-chevron"
    chevron.setAttribute("contenteditable", "false")
    val rotation = if (state.isVaultFooterExpanded) "none" else "rotate(-90deg)"
    chevron.innerHTML = "<svg viewBox=\"0 0 16 16\" width=\"10\" height=\"10\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"transform: $rotation; transition: transform 120ms ease; pointer-events: none;\">" +
        "<polyline points=\"4,6 8,10 12,6\"></polyline></svg>"
    row.appendChild(chevron)

    val label = document.createElement("span") as HTMLElement
    label.className = "treefacts-vault-header-label"
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
    btn.className = "treefacts-vault-sort-icon"
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
        color = if (isActive) "var(--t-text, #e6e6e6)"
                else "var(--t-text-dim, #7a7a7a)"
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
    rowDiv.className = "treefacts-vault-row"
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

        rowDiv.appendChild(buildVaultFolderGlyph(entry))
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
        // The document (or image) icon replaces the bullet entirely and is
        // itself the affordance: clicking it navigates to the file. Sits in
        // the same inline slot as a folder row's folder icon so file and
        // folder labels line up in one column.
        rowDiv.appendChild(buildVaultFileGlyph(entry.isImage))
        rowDiv.appendChild(buildEntryLabel(entry.name))

        // Image entries route through the same `navigateToVaultFile`
        // intent as notes — the pane VM detects the extension and skips
        // the document-registry acquire, leaving the editor surface to
        // swap to the read-only image viewer.
        if (entry.isImage) rowDiv.title = entry.pathRel
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
 * The glyph used by file footer rows. Ordinary note files get the outline's
 * bullet dot (via [buildVaultBulletGlyph]) so imported content reads as a
 * continuation of the document outline; image files keep a distinct picture
 * glyph because they aren't outline content. Either way the glyph *is* the
 * navigation affordance — clicking it (via the parent row's listener) opens
 * the file. Keeps the same `.treefacts-bullet-prefix` wrapper + trailing
 * space as [buildVaultFolderGlyph] so file and folder labels line up in one
 * column. File rows have no chevron, so the leading 22px slot stays empty and
 * the glyph sits where a document bullet does.
 *
 * @param isImage Selects the picture-frame glyph over the plain bullet. Image
 *   rows navigate through the same `navigateToVaultFile` intent, which routes
 *   them to the read-only image viewer.
 */
private fun buildVaultFileGlyph(isImage: Boolean): HTMLElement {
    if (!isImage) return buildVaultBulletGlyph()
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "treefacts-bullet-prefix treefacts-vault-file-prefix"
    prefix.setAttribute("contenteditable", "false")
    prefix.style.apply {
        setProperty("user-select", "none")
        cursor = "pointer"
    }
    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "treefacts-vault-file-glyph"
    glyph.innerHTML = VAULT_IMAGE_SVG
    prefix.appendChild(glyph)
    val space = document.createElement("span") as HTMLElement
    space.textContent = " "
    prefix.appendChild(space)
    return prefix
}

/**
 * The outline's bullet dot, reused for footer rows that should read as plain
 * outline nodes: ordinary note files and non-space folders. Mirrors
 * `OutlinePaintLoop.buildBulletPrefix` exactly — the `.treefacts-bullet`
 * glyph (a CSS-drawn dot) inside a `.treefacts-bullet-prefix` wrapper with a
 * trailing space — so the footer bullet is pixel-identical to a document
 * bullet and the two outlines flow together. Inert: click handling lives on
 * the parent row (navigate for files, toggle for folders).
 */
private fun buildVaultBulletGlyph(): HTMLElement {
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "treefacts-bullet-prefix treefacts-vault-bullet-prefix"
    prefix.setAttribute("contenteditable", "false")
    prefix.style.apply {
        setProperty("user-select", "none")
        cursor = "pointer"
    }
    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "treefacts-bullet"
    prefix.appendChild(glyph)
    val space = document.createElement("span") as HTMLElement
    space.textContent = " "
    prefix.appendChild(space)
    return prefix
}

/**
 * The glyph used by folder footer rows: the outline's bullet dot (via
 * [buildVaultBulletGlyph]), so the footer reads as one continuous outline.
 * The disclosure chevron in the leading 22px slot ([buildVaultChevron])
 * already tells folders apart from files.
 *
 * Inert: click handling lives on the parent row, which toggles the folder's
 * expand state — the glyph inherits the row's pointer cursor.
 */
@Suppress("UNUSED_PARAMETER")
private fun buildVaultFolderGlyph(entry: VaultEntry): HTMLElement = buildVaultBulletGlyph()

/**
 * Builds the entry's text label. Plain `<span>` — no caret can land here
 * because the footer root carries `contenteditable="false"`.
 */
private fun buildEntryLabel(text: String): HTMLElement {
    val span = document.createElement("span") as HTMLElement
    span.className = "treefacts-vault-text"
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
    target.className = "treefacts-chevron"
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
        color = "var(--t-text-dim, #7a7a7a)"
        setProperty("user-select", "none")
    }
    val rotation = if (isExpanded) "none" else "rotate(-90deg)"
    target.innerHTML = "<span class=\"treefacts-chevron-hit\">" +
        "<svg viewBox=\"0 0 16 16\" width=\"10\" height=\"10\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"transform: $rotation; transition: transform 120ms ease; pointer-events: none;\">" +
        "<polyline points=\"4,6 8,10 12,6\"></polyline></svg></span>"
    return target
}

/**
 * Picture-frame-with-mountain glyph for image rows, rendered inline inside
 * [buildVaultFileGlyph] so the file kind is legible at a glance.
 */
private const val VAULT_IMAGE_SVG =
    "<svg viewBox=\"0 0 24 24\" width=\"12\" height=\"12\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" style=\"pointer-events: none;\">" +
        "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/>" +
        "<circle cx=\"8.5\" cy=\"9.5\" r=\"1.5\"/>" +
        "<polyline points=\"3 17 9 12 13 16 17 12 21 16\"/></svg>"

/**
 * Placeholder row shown beneath an expanded folder while its listing is in
 * flight. Resolves to the folder's actual children on the next state
 * emission.
 */
private fun buildLoadingRow(depth: Int, style: EditorStyle): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "treefacts-vault-loading"
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
    row.className = "treefacts-vault-loading"
    row.textContent = "(empty)"
    row.style.paddingLeft = "${depth * style.indentStepPx}px"
    row.style.minHeight = "${style.lineHeightPx}px"
    row.style.setProperty("line-height", "${style.lineHeightPx}px")
    return row
}

