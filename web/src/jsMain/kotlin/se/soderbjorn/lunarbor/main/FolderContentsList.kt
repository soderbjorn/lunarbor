/*
 * FolderContentsList.kt (jsMain)
 * ------------------------------
 * Draws the folder contents list (TRF-6): under the bullets of the node a
 * pane is showing — the zoom target, or the root of the open outline —
 * one row for everything in that node's folder that is not already a
 * bullet: Markdown notes, images, other files, foreign subfolders and
 * Lunarbor folders no bullet points at (e.g. after a sync conflict).
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
 * Each row also carries a `⋮` button, shown on hover, that opens the
 * toolkit's popup menu ([openPaneMenu]): "Reveal in Finder" for every
 * entry, "Convert to node" on a Markdown note (then a dialog offers to
 * move the original to the trash) or a folder (a dialog first asks whether
 * to convert everything inside it too), and "Move to Trash" on a file.
 *
 * Platform view only — no business rules.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.VaultEntry
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunula.web.hotkey.isMacPlatform
import se.soderbjorn.lunula.web.layout.PaneMenuItem
import se.soderbjorn.lunula.web.layout.PaneMenuSpec
import se.soderbjorn.lunula.web.layout.openPaneMenu
import se.soderbjorn.lunula.web.DialogChoice
import se.soderbjorn.lunula.web.showChoiceDialog
import se.soderbjorn.lunula.web.showConfirmDialog

/**
 * Repaints [container] for [state]: empties it, then appends one row per
 * visible entry of the current node's folder. Leaves it empty (so the
 * `:empty` rule collapses the divider) while the document is loading,
 * when the pane has no current folder (a `.md` note, an image, a zoom
 * into a leaf bullet), while the folder's listing is being read, and when the folder
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
 * @param scope Scope the row menus' conversions run in.
 */
fun paintFolderContents(
    container: HTMLElement,
    state: PaneBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    scope: CoroutineScope,
) {
    container.innerHTML = ""
    if (!state.isLoaded) return
    val folder = viewModel.currentNodeFolder(state) ?: return
    val entries = viewModel.folderContentsOf(state, folder) ?: return
    for (entry in entries) container.appendChild(buildEntryRow(entry, viewModel, style, scope))
}

/**
 * One row: a type glyph and the entry's name. What a click opens (TRF-7):
 *
 * - **Folder:** the folder as a node ([MainViewModel.openFolderAsNode]).
 * - **Markdown note:** the note in this pane, in Markdown mode
 *   ([MainViewModel.navigateToVaultFile]).
 * - **Image:** the read-only image view in this pane (same intent).
 * - **Drawing:** the Excalidraw editor in this pane (same intent).
 * - **Other file:** the system's default app for it
 *   ([MainViewModel.openInDefaultApp]); the pane stays where it is.
 *
 * The first four push the pane's file history, so Back returns here.
 */
private fun buildEntryRow(
    entry: VaultEntry,
    viewModel: MainViewModel,
    style: EditorStyle,
    scope: CoroutineScope,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "lunarbor-folder-entry"
    row.setAttribute("data-folder-entry", entry.pathRel)
    row.setAttribute("data-entry-kind", entry.kind.name.lowercase())
    row.title = entry.pathRel
    row.style.apply {
        minHeight = "${style.lineHeightPx}px"
        setProperty("line-height", "${style.lineHeightPx}px")
    }

    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "lunarbor-folder-entry-glyph"
    glyph.innerHTML = glyphSvgFor(entry.kind)
    row.appendChild(glyph)

    val label = document.createElement("span") as HTMLElement
    label.className = "lunarbor-folder-entry-name"
    label.textContent = entry.name
    row.appendChild(label)

    val action: () -> Unit = when (entry.kind) {
        VaultEntryKind.FOLDER -> { { viewModel.openFolderAsNode(entry.pathRel) } }
        VaultEntryKind.MARKDOWN, VaultEntryKind.IMAGE, VaultEntryKind.DRAWING, VaultEntryKind.HTML ->
            { { viewModel.navigateToVaultFile(entry.pathRel) } }
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
    row.appendChild(buildMenuButton(entry, viewModel, scope))
    return row
}

/**
 * The row's `⋮` button (visible while the row is hovered or its menu is
 * open). Its clicks never reach the row, so they don't open the entry.
 */
private fun buildMenuButton(entry: VaultEntry, viewModel: MainViewModel, scope: CoroutineScope): HTMLElement {
    val button = document.createElement("span") as HTMLElement
    button.className = "lunarbor-folder-entry-menu"
    button.textContent = "⋮"
    button.title = "More"
    button.setAttribute("role", "button")
    button.addEventListener("mousedown", { event ->
        event.stopPropagation()
        event.preventDefault()
    })
    button.addEventListener("click", { event ->
        event.stopPropagation()
        event.preventDefault()
        val items = mutableListOf(
            PaneMenuItem(
                label = if (isMacPlatform()) "Reveal in Finder" else "Show in folder",
                iconHtml = REVEAL_SVG,
                handler = { viewModel.revealInFinder(entry.pathRel) },
            ),
        )
        if (entry.kind == VaultEntryKind.MARKDOWN) {
            items += PaneMenuItem(
                label = "Convert to node",
                iconHtml = CONVERT_SVG,
                handler = { convertToNode(entry, viewModel, scope) },
            )
        }
        if (entry.kind == VaultEntryKind.FOLDER) {
            items += PaneMenuItem(
                label = "Convert to node",
                iconHtml = CONVERT_SVG,
                handler = { convertFolderToNode(entry, viewModel, scope) },
            )
        }
        if (entry.kind != VaultEntryKind.FOLDER) {
            items += PaneMenuItem(
                label = "Move to Trash",
                iconHtml = TRASH_SVG,
                handler = { trashFile(entry, viewModel, scope) },
            )
        }
        // The toolkit tags the anchor with `dt-open`
        // while the menu is up, which keeps the button visible off-hover.
        openPaneMenu(button, PaneMenuSpec(items))
    })
    return button
}

/**
 * "Convert to node": appends the note as a node to the outline this list
 * belongs to ([MainViewModel.convertNoteToNode]), then asks whether to move
 * the original note to the trash ([MainViewModel.trashConvertedNote]).
 */
private fun convertToNode(entry: VaultEntry, viewModel: MainViewModel, scope: CoroutineScope) {
    scope.launch {
        val nodeId = viewModel.convertNoteToNode(entry.pathRel) ?: return@launch
        showConfirmDialog(
            title = "Delete the original file?",
            message = "“${entry.name}” is now a node at the end of this page. " +
                "Move the original Markdown file to the trash?",
            confirmLabel = "Move to Trash",
            cancelLabel = "Keep File",
            destructive = true,
            onConfirm = {
                scope.launch {
                    val error = viewModel.trashConvertedNote(entry.pathRel, nodeId) ?: return@launch
                    showConfirmDialog(
                        title = "The file was kept",
                        message = "“${entry.name}” was not moved to the trash. $error",
                        cancelLabel = "Close",
                    )
                }
            },
        )
    }
}

/**
 * "Move to Trash" on a file: asks first, then moves it to the vault's
 * `.trash` ([MainViewModel.trashFile]); says so when it was kept.
 */
private fun trashFile(entry: VaultEntry, viewModel: MainViewModel, scope: CoroutineScope) {
    showConfirmDialog(
        title = "Move “${entry.name}” to the trash?",
        message = "The file goes to the vault's .trash folder. Links to it will show as broken.",
        confirmLabel = "Move to Trash",
        cancelLabel = "Cancel",
        destructive = true,
        onConfirm = {
            scope.launch {
                val error = viewModel.trashFile(entry.pathRel) ?: return@launch
                showConfirmDialog(
                    title = "The file was kept",
                    message = "“${entry.name}” was not moved to the trash. $error",
                    cancelLabel = "Close",
                )
            }
        },
    )
}

/**
 * "Convert to node" on a folder: asks whether to convert only the folder
 * or everything inside it too, then appends the folder as a node to the
 * outline this list belongs to ([MainViewModel.convertFolderToNode]). When
 * Markdown notes were converted along the way, asks whether to move the
 * originals to the trash ([MainViewModel.trashConvertedNotes]).
 */
private fun convertFolderToNode(entry: VaultEntry, viewModel: MainViewModel, scope: CoroutineScope) {
    showChoiceDialog(
        title = "Convert “${entry.name}” to a node?",
        message = "The folder becomes a node at the end of this page, its files listed under it. " +
            "Convert its subfolders and Markdown files to nodes too, all the way down?",
        choices = listOf(
            DialogChoice(CHOICE_CANCEL, "Cancel"),
            DialogChoice(CHOICE_FOLDER_ONLY, "Only This Folder"),
            DialogChoice(CHOICE_RECURSIVE, "Everything Inside", isPrimary = true),
        ),
        onChoice = { choice ->
            if (choice != CHOICE_CANCEL) scope.launch {
                val result = viewModel.convertFolderToNode(entry.pathRel, recursive = choice == CHOICE_RECURSIVE)
                    ?: return@launch
                val notes = result.convertedNotes
                if (notes.isEmpty()) return@launch
                showConfirmDialog(
                    title = "Delete the original files?",
                    message = (if (notes.size == 1) "One Markdown file is now a node." else "${notes.size} Markdown files are now nodes.") +
                        " Move the originals to the trash?",
                    confirmLabel = "Move to Trash",
                    cancelLabel = "Keep Files",
                    destructive = true,
                    onConfirm = {
                        scope.launch {
                            val kept = viewModel.trashConvertedNotes(notes)
                            if (kept.isEmpty()) return@launch
                            showConfirmDialog(
                                title = "Some files were kept",
                                message = "These were not moved to the trash: " + kept.joinToString("; "),
                                cancelLabel = "Close",
                            )
                        }
                    },
                )
            }
        },
    )
}

private const val CHOICE_CANCEL = "cancel"
private const val CHOICE_FOLDER_ONLY = "folder"
private const val CHOICE_RECURSIVE = "recursive"

/** Inline SVG for [kind]'s type glyph: folder, document, image, drawing or file. */
private fun glyphSvgFor(kind: VaultEntryKind): String = when (kind) {
    VaultEntryKind.FOLDER -> FOLDER_SVG
    VaultEntryKind.MARKDOWN -> DOCUMENT_SVG
    VaultEntryKind.IMAGE -> IMAGE_SVG
    VaultEntryKind.DRAWING -> DRAWING_SVG
    VaultEntryKind.HTML -> WEB_SVG
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

/** Excalidraw drawing: a pencil over a scribble. */
private const val DRAWING_SVG = SVG_OPEN +
    "<path d=\"M3 19c3-1 4-4 7-4s3 3 6 2\"/>" +
    "<path d=\"M14.5 4.5l3 3L10 15l-3.5.5.5-3.5z\"/></svg>"

/** HTML page: a globe. */
private const val WEB_SVG = SVG_OPEN +
    "<circle cx=\"12\" cy=\"12\" r=\"9\"/>" +
    "<path d=\"M3 12h18\"/><path d=\"M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18z\"/></svg>"

/** "Reveal in Finder": a folder with an outward arrow. */
private const val REVEAL_SVG = SVG_OPEN +
    "<path d=\"M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\"/>" +
    "<polyline points=\"10 14 14 10\"/><polyline points=\"11 10 14 10 14 13\"/></svg>"

/** "Convert to node": a bullet with a nested block. */
private const val CONVERT_SVG = SVG_OPEN +
    "<circle cx=\"5\" cy=\"6\" r=\"1.6\" fill=\"currentColor\" stroke=\"none\"/>" +
    "<line x1=\"9\" y1=\"6\" x2=\"20\" y2=\"6\"/>" +
    "<rect x=\"9\" y=\"11\" width=\"11\" height=\"8\" rx=\"1.5\"/></svg>"

/** "Move to Trash": a bin with a lid. */
private const val TRASH_SVG = SVG_OPEN +
    "<polyline points=\"4 7 20 7\"/>" +
    "<path d=\"M9 7V4h6v3\"/>" +
    "<path d=\"M6 7l1 13h10l1-13\"/></svg>"

/** Any other file: a blank page with a folded corner. */
private const val FILE_SVG = SVG_OPEN +
    "<path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/>" +
    "<polyline points=\"14 3 14 8 19 8\"/></svg>"
