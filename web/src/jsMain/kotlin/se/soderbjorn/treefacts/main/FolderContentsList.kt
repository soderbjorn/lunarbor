/*
 * FolderContentsList.kt (jsMain)
 * ------------------------------
 * Draws the folder contents list (TRF-6): under the bullets of the node a
 * pane is showing — the zoom target, or the root of the open outline —
 * one row for everything in that node's folder that is not already a
 * bullet: Markdown notes, images, other files, foreign subfolders and
 * TreeFacts folders no bullet points at (e.g. after a sync conflict).
 *
 * Which entries show and in what order is decided in commonMain
 * ([FolderContents.visible], reached through
 * `MainViewModel.folderContentsOf`); which folder is listed by
 * `PaneBackingViewModel.currentNodeFolder`. This file only draws, and
 * maps clicks to intents.
 *
 * Lives in a sibling DOM block after the contenteditable editor host (see
 * `MainScreen`), itself `contenteditable="false"`, so its rows are never
 * editable text, never wiped by the editor's paint loop, and never part
 * of caret/selection mapping (rows carry `data-folder-entry`, not
 * `data-row`).
 *
 * Platform view only — no business rules.
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.treefacts.data.VaultEntry
import se.soderbjorn.treefacts.data.VaultEntryKind

/**
 * Repaints [container] for [state]: empties it, then appends one row per
 * visible entry of the current node's folder. Leaves it empty (so the
 * `:empty` rule collapses the divider) while the document is loading,
 * when the pane has no current folder (a `.md` note, a zoom into a leaf
 * bullet), while the folder's listing is being read, and when the folder
 * has nothing to show.
 *
 * Called by `MainScreen` after every state emission, right after the
 * editor paint.
 *
 * @param container The sibling div allocated by `MainScreen` for the list.
 * @param state Latest pane state.
 * @param viewModel Resolves the folder and its contents, and receives the
 *   click intents.
 * @param style Visual constants (line height).
 */
fun paintFolderContents(
    container: HTMLElement,
    state: PaneBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
) {
    container.innerHTML = ""
    // Image view keeps the list (no document is loaded): it is how the
    // user reaches the image's siblings.
    if (!state.isLoaded && !state.isImageView) return
    val folder = viewModel.currentNodeFolder(state) ?: return
    val entries = viewModel.folderContentsOf(state, folder) ?: return
    for (entry in entries) {
        // The image being viewed is not listed under itself.
        if (entry.pathRel == state.activeFileRel) continue
        container.appendChild(buildEntryRow(entry, viewModel, style))
    }
}

/**
 * One row: a type glyph and the entry's name. What a click opens (TRF-7):
 *
 * - **Folder:** the folder as a node ([MainViewModel.openFolderAsNode]).
 * - **Markdown note:** the note in this pane, in Markdown mode
 *   ([MainViewModel.navigateToVaultFile]).
 * - **Image:** the read-only image view in this pane (same intent).
 * - **Other file:** the system's default app for it
 *   ([MainViewModel.openInDefaultApp]); the pane stays where it is.
 *
 * The first three push the pane's file history, so Back returns here.
 */
private fun buildEntryRow(
    entry: VaultEntry,
    viewModel: MainViewModel,
    style: EditorStyle,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "treefacts-folder-entry"
    row.setAttribute("data-folder-entry", entry.pathRel)
    row.setAttribute("data-entry-kind", entry.kind.name.lowercase())
    row.title = entry.pathRel
    row.style.apply {
        minHeight = "${style.lineHeightPx}px"
        setProperty("line-height", "${style.lineHeightPx}px")
    }

    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "treefacts-folder-entry-glyph"
    glyph.innerHTML = glyphSvgFor(entry.kind)
    row.appendChild(glyph)

    val label = document.createElement("span") as HTMLElement
    label.className = "treefacts-folder-entry-name"
    label.textContent = entry.name
    row.appendChild(label)

    val action: () -> Unit = when (entry.kind) {
        VaultEntryKind.FOLDER -> { { viewModel.openFolderAsNode(entry.pathRel) } }
        VaultEntryKind.MARKDOWN, VaultEntryKind.IMAGE -> { { viewModel.navigateToVaultFile(entry.pathRel) } }
        VaultEntryKind.FILE -> { { viewModel.openInDefaultApp(entry.pathRel) } }
    }
    if (entry.kind == VaultEntryKind.FILE) row.title = "${entry.pathRel} — opens in the default app"
    // Keep the press away from the editor's caret placement and drag
    // handlers, which listen on the editor.
    row.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    row.addEventListener("click", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        action()
    })
    return row
}

/** Inline SVG for [kind]'s type glyph: folder, document, image or file. */
private fun glyphSvgFor(kind: VaultEntryKind): String = when (kind) {
    VaultEntryKind.FOLDER -> FOLDER_SVG
    VaultEntryKind.MARKDOWN -> DOCUMENT_SVG
    VaultEntryKind.IMAGE -> IMAGE_SVG
    VaultEntryKind.FILE -> FILE_SVG
}

private const val SVG_OPEN =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" style=\"pointer-events: none;\">"

/** Folder: a tabbed folder outline. */
private const val FOLDER_SVG = SVG_OPEN +
    "<path d=\"M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\"/></svg>"

/** Markdown note: a page with a folded corner and text lines. */
private const val DOCUMENT_SVG = SVG_OPEN +
    "<path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/>" +
    "<polyline points=\"14 3 14 8 19 8\"/>" +
    "<line x1=\"9\" y1=\"13\" x2=\"15\" y2=\"13\"/><line x1=\"9\" y1=\"17\" x2=\"15\" y2=\"17\"/></svg>"

/** Image: a picture frame with a mountain. */
private const val IMAGE_SVG = SVG_OPEN +
    "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/>" +
    "<circle cx=\"8.5\" cy=\"9.5\" r=\"1.5\"/>" +
    "<polyline points=\"3 17 9 12 13 16 17 12 21 16\"/></svg>"

/** Any other file: a blank page with a folded corner. */
private const val FILE_SVG = SVG_OPEN +
    "<path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/>" +
    "<polyline points=\"14 3 14 8 19 8\"/></svg>"
