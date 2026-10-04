# Lunarbor — Architectural Guidelines

Lunarbor is a Kotlin Multiplatform project. It follows a strict layered architecture modeled after the `framnafolk` and `almedalen` codebases. Keep the layers tight; don't blur responsibilities.

## The layers (outside → in)

```
View                  (per-platform — DOM, Compose, UIKit, …)
  ↓ intent calls
MainViewModel         (per-platform — thin facade over the pane backing VM)
  ↓ delegates
PaneBackingViewModel  (commonMain — one pane's cursor, selection, zoom,
                       file/zoom history, undo/redo, fold state, etc.)
  ↓ primitive edits / observes state
Document              (commonMain — one loaded node outline or note + its autosave loop)
  ↑ acquired/released through
DocumentRegistry      (commonMain — fileRel → Document, refcounted)
  ↓
NoteRepository        (commonMain — vault I/O, folder-per-bullet save rules)
  ↓
FileSystem (interface)  (per-platform PlatformFileSystem — Electron IPC on JS, …)
```

### 1. View (per-platform)

For web: `web/src/jsMain/.../MainScreen.kt`. Renders state; translates user input into intent calls on the `MainViewModel`. Holds only ephemeral UI state (e.g. `isDragging`, scroll offset, auto-scroll timer handle). No business rules, no document logic.

### 2. MainViewModel (per-platform — thin facade)

For web: `web/src/jsMain/.../MainViewModel.kt`.

Structure:
- Receives its `PaneBackingViewModel` via constructor (built per-pane in `AppShell.ensurePaneViewModel`).
- Exposes a single `val stateFlow: StateFlow<State>` where `data class State(val backingState: PaneBackingViewModel.State? = null)` — the envelope wraps the backing state so each platform can tack on its own fields without touching common code.
- `init` collects the backing state flow and re-emits it through the envelope.
- Every intent method is a one-line delegation. Do NOT re-implement logic here.
- Only extra code allowed: platform-specific glue that cannot exist in commonMain (e.g. Android `SignInManager.signOut()` before delegating).

### 3. PaneBackingViewModel (common)

`client/src/commonMain/.../PaneBackingViewModel.kt`. One instance per visible pane (window / device / split). Responsibilities:

- Owns pane-local state: `activeFileRel`, `cursorRow`, `cursorCol`, `anchorRow?`, `anchorCol?`, `zoomedLineId`, zoom + file back/forward stacks, within-file `collapsedIds`, `pendingInlineStyles`, undo/redo. Mirrors the active `Document.State` through a swappable inner collector so every emission is a consistent snapshot of both document + pane state.
- Implements all selection-aware editor intents: `insertChar`, `insertNewline`, `backspace`, `moveLeft(extend)`, `selectAll`, `selectWord`, `indentLine`, `onCutRequested`, …
- Composes primitive edits: typing first calls `deleteSelectionIfAny`, then `document.insertText(row, col, …)`, then updates its own cursor. Use the private `patch { }` helper so every emission carries a fresh `documentState` snapshot (avoids stale mirrors while the async collector catches up).
- Holds *one* `Document` at a time — the file the pane is currently viewing. `navigateToVaultFile`, `fileBack`, and `fileForward` swap the active document by acquiring the new one from `DocumentRegistry`, cancelling the old inner collector, starting a new one, and releasing the old document. Other panes are unaffected by this — pane navigation is genuinely pane-local.
- Exposes the normalized selection via `PaneBackingViewModel.selectionOf(state)` (companion) so views don't re-implement the anchor/cursor sort.
- No DOM, Android UI, or UIKit imports. commonMain only.

### 4. Document (common)

`client/src/commonMain/.../Document.kt`. One instance per loaded file — a node's `_node.md` outline (`bulletsOnly`) or a plain `.md` note. Responsibilities:

- Owns `lines: List<String>`, `lineIds: List<LineId>`, `unloadedRefIds: Set<LineId>`, `isLoaded: Boolean`, plus the in-memory `promotedSubtrees` map (folder-backed row → its folder) for that outline. Descendant node folders are spliced into `lines` on demand (`acquireExpansion` / `releaseExpansion`, refcounted across panes) and saved back to their own `_node.md` files.
- Exposes primitive edits only: `insertText(row, col, text) → InsertResult`, `insertNewline(row, col)`, `insertLine`, `deleteRows`, `delete(startRow, startCol, endRow, endCol)`, `moveRows(...)`, `replaceContent(...)`, `rewriteLinks(moves)`, `acquireExpansion(id)`, `releaseExpansion(id)`. No cursor concept, no selection concept, no editor policy, no file-switching concept.
- Runs its own autosave loop (1 s debounce, 5 s max) on the scope passed in by `DocumentRegistry`. `start()` is called by the registry on first acquire; `shutdown()` (called by the registry when the last pane releases) flushes one final save synchronously and cancels the loop.
- Single source of truth for *one file's* content. When two panes acquire the same `fileRel`, they get the same `Document` instance and their edits flow through to each other in real time.

### 5. DocumentRegistry (common)

`client/src/commonMain/.../DocumentRegistry.kt`. App-scoped singleton owning every loaded `Document` plus the shared vault-listings cache (one raw listing per folder a pane has looked at, read by the folder contents list and the count badges).

- `acquire(fileRel)` → returns the live `Document`, refcount += 1, lazy-creates + `start()`s on the first call.
- `release(fileRel)` → refcount −= 1; on zero, the `Document.shutdown()` flushes one final save and the slot is dropped.
- `vaultListingsFlow` is observed by every pane so the folder contents list can render shared folder state. It is refreshed after every save, when the window regains focus, and (per folder) when a pane lands on a node.
- The only thing that touches `NoteRepository`. Both `Document` (for content I/O via `repository.loadFile` / `repository.save`) and the registry itself (for `repository.listVaultLevel`) go through this single instance.

## Dependency injection (Metro)

The project uses **Metro** (`dev.zacsweers.metro`) for DI, applied **per-platform**. commonMain code has no DI annotations — it's plain classes with constructor parameters, which keeps it portable.

Version is pinned in `gradle/libs.versions.toml` under the `metro` version ref and the `metro` plugin entry. The `:web` module applies the plugin; `:client` does not (no codegen needed in commonMain since no annotations live there).

### JS graph (`web/src/jsMain/.../di/JsAppGraph.kt`)

```kotlin
object AppScope

@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val coroutineScope: CoroutineScope
    val documentRegistry: DocumentRegistry

    @SingleIn(AppScope::class) @Provides
    fun provideCoroutineScope(): CoroutineScope = GlobalScope
    // … @Provides for FileSystem, NoteRepository, DocumentRegistry
}

fun createJsAppGraph(): JsAppGraph = createGraph<JsAppGraph>()
```

Rules:
- **Interface-based graph** annotated `@DependencyGraph` + `@SingleIn(AppScope::class)`.
- Exposed bindings as interface properties (`val documentRegistry: DocumentRegistry`).
- App-scoped infrastructure (`FileSystem`, `NoteRepository`, `DocumentRegistry`) is declared with `@Provides` methods, each `@SingleIn(AppScope::class)`. Per-pane VMs (`PaneBackingViewModel`, `MainViewModel`) are *not* in the DI graph — they're constructed directly in `AppShell.ensurePaneViewModel(paneId)` so each pane gets its own instance pointing at the shared `DocumentRegistry`.
- commonMain classes stay annotation-free; they're constructed inside `@Provides` methods. This matches the `almedalen` pattern and keeps Metro out of commonMain.
- `Main.kt` calls `createJsAppGraph()` once and pulls `documentRegistry` + `coroutineScope` out, then hands them to `AppShell`.

### JS-specific Metro config

Because Kotlin/JS IC doesn't support Metro's default top-level codegen, `web/build.gradle.kts` must set:

```kotlin
metro {
    enableTopLevelFunctionInjection.set(false)
    generateContributionHints.set(false)
    generateContributionHintsInFir.set(false)
}
```

### Android / iOS (future)

Mirror the JS graph with a platform-specific scope (`AndroidAppScope`, `IosAppScope`) and platform-specific `@Provides` — e.g. `FileSystem(context)` on Android, plain `FileSystem()` on iOS. The common `Document` and `PaneBackingViewModel` classes are identical across platforms; only `FileSystem` is per-platform.

## State and intents

- **One state per VM.** Add fields; don't introduce parallel flows.
- **Envelope at the outer VM.** `MainViewModel.State.backingState` gives each platform a seam for platform-specific fields without touching common code.
- **Name intents after the action**: `onSignOutTapped`, `selectWord(row, col)`, `insertNewline`. Not `_setLines(...)`.
- **Cursor and selection are pane state, not document state.** They live in `PaneBackingViewModel`. `Document` knows nothing about them.
- **Active file is pane state.** `PaneBackingViewModel.State.activeFileRel` says which file *this pane* is viewing. `Document` does not know "the active file" — there is no global active file. Two panes can be on the same file (sharing a `Document` instance) or on different files.
- **Every outline line is a bullet.** In a `_node.md` document (`Document.bulletsOnly`) no intent may produce a non-bullet line: Enter on an empty bullet outdents it or opens another bullet, Backspace merges or deletes, paste makes one bullet per line (`bulletLinesForPaste`). Free-form content goes in blocks. Plain `.md` files keep plain-line editing — that flag is the switch for Markdown mode.
- **Markdown mode is pane state.** `PaneBackingViewModel.State.isMarkdownMode` is `true` when the pane shows a file that is not a `_node.md` outline (a `.md` note). Same editor, fully editable, no bullet behaviour: nothing folds (`toggleCollapse`, default collapse), nothing zooms (`zoomInto` / `zoomTo`), rows are not dragged (`moveLineRange`, the view's drag handles), no expand / collapse controls or badges. The file is saved exactly as written and never gets a `_node.md` file. The page title is the file name and is editable: committing it renames the note in place (`PaneBackingViewModel.renameActiveFile` → `DocumentRegistry.renameFile`: save, move, re-key the shared `Document`, panes follow via a rename listener, links and Starred rewritten; the name is `FolderName`-encoded, a taken name gets ` (2)`). An image's or drawing's title (its file name, extension included) renames it the same way (`State.canRenameFromTitle` covers `isFileView`); the extension is always kept (`NoteRepository.renameTargetOf`), and every embed showing it is rewritten (`ImagePaths.rewriteEmbeds`: open documents via `Document.rewriteImageEmbeds`, other note files on disk by a vault walk). A first line `# <file name>` repeats the title, so the editor hides it and the blank lines right after it (`State.hidesTitleHeading` / `firstEditableRow`, never the last row; compared in NFC, since macOS file names are decomposed) and a rename rewrites it.
- **Blocks are the only other outline line.** A block (bordered free Markdown among the bullets) is one row per content line in `Document.lines`, each `<indent><marker><content>` with a hidden private-use marker (`BlockLayout.FIRST` opens a block, `BlockLayout.NEXT` continues it). The marker keeps every context-free helper from mistaking a `* item` inside a block for a bullet; the `> ` prefixes exist only on disk. A block is an outline item of its own: the view draws an item dot left of its box, which starts where sibling bullets' text starts. It nests under the preceding item by indent, so a block under a leaf makes it folder-backed — and a block can itself be a parent: rows indented under it are its children, saved in its own folder like a bullet's. `DocumentLayout.itemColumn` / `itemLastRow` treat a block's first row as the item row (fold, zoom, drag, Tab ceilings, expansion splicing after the block's last row). Its dot zooms (the zoomed block heads the page, above its children; a childless block gets no placeholder child) and drags like a bullet's. Inside a block Enter adds a row (continuing a `* ` / `- ` list item at its depth; on an empty item it ends the list), Backspace joins rows (an empty block is deleted), paste is verbatim, Tab / Shift-Tab indent / outdent the current row's *content* (`BlockLayout.contentIndentOf` — list items nest, the block never moves and no folder is made), Cmd-Enter / Escape leave it onto a new bullet, and Arrow Down on its last row with nothing below does the same. Leaving upwards puts a new bullet before the block (`TextEditingViewModel.exitBlockAbove`): Shift-Cmd-Enter anywhere in it, Enter at the text start of its first row, or Arrow Up on its first row with nothing above. Either new bullet is a throwaway (`State.pendingLeafZoomChild`, like a leaf zoom's placeholder): moving the caret off it or navigating away while it is empty removes it (`PaneBackingViewModel.dropAbandonedPlaceholder`). List items inside a block are drawn with dots (`BlockLayout.listPrefixLength`); the caret starts after their prefix. A block can hold code blocks: code rows carry a second hidden marker, `BlockLayout.CODE`, after the block marker, and the ```` ``` ```` fences exist only on disk (`BlockLayout.rowContentsOf` / `diskContentOf`; a fence with an info string such as ```` ```kotlin ```` or with no close stays plain text). A code row is drawn verbatim in a monospace band (no Markdown, no list dots, no line styles), Enter keeps its indentation (Enter on an empty last code row ends the code), Backspace at the start of the first code row leaves the code, Tab indents freely, paste makes code rows. Inline code across two or more rows of one block toggles a code block (the Style item then reads "Code block"; with the caret on a code row it takes the run out again — `MarkdownStyleViewModel.codeBlockRows` / `codeBlockState`). Insert block on an empty leaf bullet turns that bullet into the block, elsewhere it adds a block item after the caret's item; Insert / Delete block are palette commands; deletion is undoable, and the block's hover × asks first (`confirmDeleteBlock`). A large block (`BlockLayout.isLarge`: 16+ rows) shows only its first `BlockLayout.PREVIEW_ROWS` (8) rows, faded, with an "N more lines" label, until the pane expands it with the control right of the × (`State.expandedBlockIds`, `toggleBlockExpanded`; pane state, like folds). `visibleRowsIn` (SelectionHelper) applies it for paint, caret movement, clamping and drag; a block the pane is zoomed into always shows whole, and a caret that lands in the hidden rows (Enter past the preview, paste, undo) expands the block. "Convert block to nodes" (palette, offered only with the caret in a block) replaces the block with one bullet per paragraph in its parent — lines joined, headings and list items each their own bullet (nested items as children), code runs kept as blocks (`NoteConversion.nodeGroupsOfBlock`); the first bullet takes over the block's row id, so a block's children and folder stay under it, the rest follow after the block's subtree (`TextEditingViewModel.convertBlockToNodesAt`, undoable). "Insert Markdown file as block…" opens the system file chooser and puts a block holding the picked file's text where Insert block would (`PaneBackingViewModel.insertMarkdownAsBlock`, `NoteConversion.blockRowContentsOf`: verbatim, code fences made code rows); the file is only read.
- **Drawings are pane state too.** A `.excalidraw` file (`NoteRepository.isDrawingPath`, `VaultEntryKind.DRAWING`) opens like an image — no `Document`, `State.isDrawingView` (`isFileView` covers both) — but in the full Excalidraw editor (`web/.../main/DrawingEditor.kt`: React + `@excalidraw/excalidraw` loaded by dynamic `import()` into their own webpack chunks, mounted in a host that replaces the scroll wrapper). The editor loads through `PaneBackingViewModel.loadDrawing` and hands every scene change (element versions changed, not pointer moves) to `onDrawingChanged`; `DocumentRegistry.saveDrawing` writes it with the usual 1 s / 5 s autosave pause (in `unsavedFilesFlow` meanwhile; flushed when the pane leaves the drawing, closes, or `flushAll`). `drawingRevisionsFlow` → `State.drawingRevision` tells other panes on the same drawing, and outside changes (disk wins), to re-read it; an editor ignores its own text. Excalidraw's fonts ship as `excalidraw-assets/fonts` (`window.EXCALIDRAW_ASSET_PATH`; CJK Xiaolai falls back to its CDN). "New Excalidraw drawing" in the palette creates `Untitled.excalidraw` in the current folder and opens it. A drawing can also be embedded like an image (`![](flow.excalidraw)`; Insert Image lists drawings too): the row shows an SVG picture of the scene (`web/.../main/DrawingPreview.kt`, Excalidraw's `exportToSvg`, the file read through `lunarbor-asset://`, re-read at most every 2 s), and clicking it opens the drawing editor, as clicking an image opens the image view.
- **HTML pages are pane state too.** An `.html` / `.htm` file (`NoteRepository.isHtmlPath`, `VaultEntryKind.HTML`) opens like an image — no `Document`, `State.isHtmlView` (`isFileView` covers it) — as a web page filling the pane below the title (`web/.../main/HtmlViewer.kt`): a sandboxed `<iframe>` (`allow-scripts allow-forms allow-popups`, no `allow-same-origin`, so the page reaches neither the app nor its preload API) loading the file's `lunarbor-asset://` URL, so relative CSS, scripts and images resolve beside it (the protocol serves their MIME types); `target="_blank"` links go to the system browser. Read-only; the frame is dropped when the pane leaves the page.
- **Selection-aware writes compose in the pane VM.** Typing first deletes the selection, then inserts. The pane VM owns this composition; `Document` only exposes the primitives.

## On-disk format

One folder per parent bullet. A bullet is backed by a folder if and only if it has content: child bullets, blocks, or files in that folder. Every node folder holds one outline file, `_node.md` (`NoteRepository.OUTLINE_FILE_NAME`; visible on purpose, so Finder shows it; the `_` sorts it first and reserves only the note name `_node`), listing only that node's **direct** children (no indentation). The vault root is the root node (`<vault>/_node.md`); top-level folders act as separate areas. The file is plain Markdown — a list, blockquotes for blocks — so GitHub, Obsidian and other viewers render it, and a reformat by one of them (`*` → `-`, extra blank lines) loads the same tree.

```
- Buy oat milk
- Recipes [↳](<Recipes/_node.md>)
- Trip to **Lisbon**
> **Packing**: passport, charger, adapter
```

- `- text` — a leaf bullet; the text is inline Markdown. `*` and `+` read as bullets too; `-` is written. Text Markdown would misread (a leading `1.`/`1)`, `- `, `---`, `\`) is backslash-escaped on save and unescaped on load (`SubtreeCodec.escapeBulletText`); leading `#` and `>` are not (headings and quotes mean the same in Markdown).
- `- title [↳](<folder/_node.md>)` — a folder-backed bullet: the title (any inline Markdown, links included), then a **child link** to the child folder's outline. The bullet is recognised by that trailing link, never by its marker; the folder name is stored explicitly, relative to the file's folder, with `%` written `%25` (other tools' `%20`-style destinations are decoded too). In a viewer the link opens the child node.
- `>` lines — a block (blockquote): one `> ` line per content line (`>` for a blank one), content verbatim (`* ` lists, code fences). Two blocks in a row are separated by a blank line, the only blank lines written.
- A block whose last quote line is only a child link (`> [↳](<folder/_node.md>)`) is a block with children: its content stays in this file, its children live in `folder`, like a folder-backed bullet's. The title is the plain text of the block's first line (`SubtreeCodec.blockTitleOf`) and names the folder.
- **Node stamps** (LBR-16): an outline may open with YAML front matter, `---` / `created: 2026-10-04T12:34:56Z` / `updated: …` / `---` (ISO 8601, UTC, whole seconds; `NodeFrontMatter`, `SubtreeCodec.splitFrontMatter` / `withFrontMatter`). It is metadata, never content: `parseNodeFile` skips it, so it is never a bullet, never searched or indexed as text, never shown to agents as outline text. Every outline write goes through `NoteRepository.writeOutlineFile`: a body equal to the one on disk writes nothing (the front matter is not compared, so an unchanged save never restamps); a new `_node.md` gets `created` + `updated` (the app created it: a leaf's first child, Convert to node, `create_node`, …); otherwise the front matter is kept — other tools' keys (Obsidian's `tags`, `aliases`, …) included, in place — and `updated` is set when the node's **own** items changed (formatting-only differences, e.g. another tool's `*` markers, are rewritten without a stamp). One stamp pair per node folder, root included; no bubbling to ancestors. A retitled node is stamped too, though its title lives in its parent (`retitledChildren`, `touchOutline`; agent `move` with a new title alike). A moved node keeps its stamps; both parents are stamped. Link-path rewrites on disk (`rewriteLinksInFile`), outside changes and the watcher's reload stamp nothing. Missing stamps are unknown: **no migration** backfills them (the ticket's decision, an exception to the rule below) — `updated` appears with the next change, `created` only on outlines the app creates from now on.
- **Format changes ship with a migration**: any change to the vault format comes with code that converts existing vaults on startup, before anything loads, keeping the originals in `.trash/<timestamp> format migration/`, and maps remembered window locations onto the new paths. (The node stamps' front matter is additive and optional, so it ships without one.)
- **Folder names** (`data/FolderName.kt`): the title's plain text without its `#tags` (`FolderName.nameTextOf`: `1-1 #private` → `1-1`; the tag stays on the line, so it still shows as a pill, is searched and drives privacy modes — breadcrumbs, pane labels, link-target titles and Starred labels leave it out too, the zoomed page title keeps it as a pill), percent-encoded where unsafe (`/ \ : * ? " < > |`, `%`, a leading dot, trailing dots/spaces, control characters), capped at 120 bytes; sibling collisions are case-insensitive and get ` (2)`, ` (3)`, …; an empty title is `Untitled`.
- **Save rules** (`NoteRepository.save`, run 1 s after the last edit and at most 5 s apart; bullets and blocks alike): a leaf that gets its first child becomes a folder — adopting a sibling folder of the same name that no bullet references (ignoring case and Unicode normalization) instead of making ` (2)`, its outline's bullets kept after the new children (`SaveResult.adoptedItems`, spliced in by `Document`); a leaf named like such a folder adopts it without children too when the folder holds anything; a folder whose last child is removed is deleted unless it still holds files; title edits rename the folder — except on a node with no child bullets, which lets go of its folder instead (it becomes a leaf; the folder keeps its name and files); moves (indent, outdent, drag, cut and paste) `rename` the folder so attachments travel; a deleted folder-backed bullet's folder goes to `<vault>/.trash/<timestamp> <name>/` — unless it holds user files (anything but its `_node.md`, dotfiles and its child bullets' folders): then the folder stays in place with those files (unreferenced, so the parent's folder contents list shows it) and only its outline plus each child bullet's folder, by the same rule, go to the trash (`NoteRepository.trashNode`); undo in the same session moves it back or merges the trashed parts back in (`PromotedRef.keptAt`, `NoteRepository.mergeBack`); the trash is never emptied automatically. A child link (of a bullet or block) whose folder is missing loads as a plain leaf and is saved back as `- title` — the title is kept, the dangling reference is not. Deleting a row's whole text as part of a multi-row selection deletes that row as an item (its folder is trashed), so typing afterwards never inherits its folder or hidden children (`TextEditingViewModel.deleteSelectionIfAny`).
- **Parser/codec**: `client/src/commonMain/.../data/SubtreeCodec.kt` parses and emits the outline format; `NoteRepository.kt` is the only thing that touches `FileSystem`. Files that are not `_node.md` outlines (`Starred.md`, other `.md` notes) are read and written as plain lines, verbatim.
- **Images** (`data/ImagePaths.kt`): an image `src` resolves like a CommonMark link, relative to the folder the line is stored in (`Document.storageFolderOf`: the nearest folder-backed ancestor's folder, the outline's own folder, or a `.md` note's folder). A pasted image is written into that folder and referenced by bare file name (`![](shot.png)`), so it moves with the node's folder; when a save moves a row to another folder, the images it names that way move with it (`NoteRepository.moveAttachments`, unless another row still uses them). A `/…` src is vault-rooted (the Insert Image palette uses it for images outside the row's folder); a src with a URL scheme is external.

## App settings and the vault

**Fonts** (`web/fonts/`, OFL, listed in `NOTICE`): Lunarbor ships Instrument Sans, Unbounded and JetBrains Mono. `fonts.css` there is inlined by `:web:generateBundledFonts` into `bundled-fonts.css` (data: URIs, so they load offline and from a `file://` demo page; linked from `index.html`). Instrument Sans (proportional rows) and Unbounded (`FontKind.Display`: listed in every font row but Monospaced, LNA-15) are Lunula built-in presets, offered in any Lunula app whose page declares the family through `@font-face` (`detectInstalledFonts`) — here, `bundled-fonts.css` — and hidden in apps that ship no files; the page title and Markdown headings use the Display font (`--dt-font-display`), else the editor's.

The surfaces look is Lunula's: Appearance → Surfaces, Depth (default — lifted panes, accent glow and sheen, an ambient wash) or Flat, saved in the toolkit's appearance shape; Lunarbor adds no rules of its own beyond the navigation overlay's corners (see Navigation animation).

The topbar gear opens the toolkit's App settings sidebar with Lunarbor's body (`web/.../main/AppSettingsContent.kt`): jumps to Themes, Appearance and Keyboard Shortcuts (the toolkit's hotkeys sidebar, built from `lunarborHotkeysSpec`; Cmd-/ and `Lunarbor → Hotkeys…` open it too — there is no cheatsheet modal), and, in Electron, a **Vault** section.

- **Where the vault comes from** (`electron-main/.../RunPaths.kt`): `LUNARBOR_VAULT` (locks it — the sidebar can't change it) › `vaultPath` in the app's `lunarbor.json` › `<LUNARBOR_LOCAL_DATA>/vault` › `~/lunarbor-db`. `vaultPath` is owned by the main process: renderer ui-settings writes keep whatever is on disk.
- **Changing it** (`AppShell.switchVault`): refused while `DocumentRegistry.unsavedFilesFlow` is non-empty (the button is disabled meanwhile); flushes, rebases every pane's location onto the new root (`VaultRelocation` — panes whose place lies outside it close), writes pane locations + layout, then `noteApi.setVault` persists `vaultPath` and the main process recreates the window against the new vault. Everything is re-read from disk; nothing vault-scoped survives in the renderer.
- **Changes outside the app** (`electron-main/.../VaultWatcher.kt`): the main process watches the vault recursively, sends only real outside changes (`VaultChangeFilter`: a path counts when it differs from what the app last wrote, read or saw — text for notes, size + mtime for other files, existence for folders; a never-seen path only with a fresh mtime or when gone; our own texts and paths we touched in the last 3 s never count — so sync clients such as Google Drive re-touching unchanged files are ignored), debounced, over `lunarbor:vaultChanged` → `FileSystem.watchExternalChanges` → `NoteRepository.watchExternalChanges` → `DocumentRegistry.applyExternalChanges`. That reloads every open `Document` the paths concern (`isAffectedBy`: its file, or a folder-backed row's folder / outline) with `Document.reloadFromDisk` — the disk wins over unsaved edits, and a disk that already matches the document changes nothing; expanded folders are re-spliced and rows keep their `LineId` by folder or by text (`Document.matchIds`); `State.reloadCount` tells panes to clear undo and keep the caret on its line — then re-reads changed notes into the link index and refreshes listings.
- **Page memory** (`PaneBackingViewModel` `pageViews`, by file + zoom title path): leaving a page (any file switch or zoom change) records its caret (`Caret`: row, column and the line's text, so `rowOfCaret` finds the line again when rows shift), its scroll offset (reported by the view, `noteScroll` — not state) and its search (text, reversed); arriving anywhere — Back / Forward, breadcrumb, link, search result — brings them back (`recallPage` → `State.scrollRestore`, applied once by the view after the page has painted), and a page never seen starts at the top. Clicking a search result into another file keeps the search with the page left, so Back returns to the results. Arrival runs `recallAfterLoad` after each navigation's own history bookkeeping (so Back / Forward never wait on a load for their stacks); a search result skips it — it places the caret itself — and a placing patch sets `suppressRecall`. The current page's caret and scroll also persist (`PaneLocationStore` `caret` / `scroll`; `restoreCaret` / `restoreScroll(…, location)` file them under the restored page so a zoom that lands later still gets them).
- **Folds persist** (`main/FoldMemory.kt`, `DocumentRegistry.foldMemory`, persister key `lunarborOpenFolders`, per vault): the vault folders of the items panes left open. Every foldable item is folder-backed, so the folder is its stable key (re-keyed by `applyPathMoves`, dropped on trash). Panes write changes after every `patch` and document emission (`recordFolds`, only what changed for that pane; an item open only for a zoom counts as folded) and read it in the default-collapse pass: a remembered item starts open and the collector acquires its expansion, level by level. Fold state stays pane state — the memory is only where an item a pane sees for the first time starts. "Expand all children" / "Collapse all children" (palette, `setAllChildrenFolded`) change every fold under the page's node, large-block previews excepted. "Sort children by name" / "…, reversed" (palette, `sortChildrenByName(reverse)`) orders the page node's direct children by visible title (`FolderContents.naturalCompare`, A–Z or Z–A, untitled last), each with its subtree, not recursively; one undoable edit, ids kept.
- **Delete this node** (palette, `PaneBackingViewModel.deletePageNode`, tested in `DeletePageNodeTest`): offered when the page is a node other than the root (`pageNodeTitle`: the zoom target, or a node's own outline); after a confirmation (`showConfirmDialog`) the pane goes up a level and the node's item is deleted there with its subtree — one undoable edit, its folder trashed on save, Back / Forward entries inside it dropped. On a `.md` note, image, drawing or other file (`pageFile`) the same command reads "Delete this file": after a confirmation the pane goes to the file's node and the file moves to `.trash` (`trashPageFile` → `DocumentRegistry.trashNote`; not undoable, refused while another window has it open).
- **Pane locations persist** (`PaneLocationStore`, persister key `lunarborPaneLocations`): each pane's file + zoom title path, recorded on every navigation and restored in `AppShell.ensurePaneViewModel` (`PaneBackingViewModel.openLocation`), so windows reopen where they were. A stored file that no longer exists falls back to the root.

## Backup

App settings → **Backup** (Electron only) zips the whole vault into a folder the user picks. Off until a folder is chosen.

- **Main process owns it** (`electron-main/.../VaultBackup.kt`): settings — folder, `intervalHours` (0 off, 1, 6, 12, 24, 168) — in their own `lunarbor-backup.json`, over `lunarbor:getBackup` / `setBackup` / `chooseBackupFolder` / `backupNow`. The folder may not lie inside the vault. The zip (`ZipWriter.kt`: deflate via Node `zlib`, UTF-8 names, folder entries, ZIP64 when needed, symlinks skipped) is written as `<vault folder name> backup YYYY-MM-DD HH.mm.ss.zip` (local time), via a `.partial` file renamed when complete.
- **Writes wait during a backup**: every vault-changing file-op IPC handler runs through `VaultWriteGate.write`; the backup runs in `VaultWriteGate.exclusive`, which lets writes already under way finish first.
- **Edits are saved first**: "Back up now" and automatic backups call `DocumentRegistry.flushAll` before `backupNow` (`web/.../BackupSettings.kt`).
- **"Last backup" is read from the folder**: the newest date among this vault's backup file names (`backupTimeOf`), not stored.
- **Automatic backups**: checked every minute and at startup (when the renderer subscribes, `lunarbor:backupReady`); when the newest backup is at least the interval old (`isBackupDue`) the main process sends `lunarbor:backupDue`, and the renderer flushes and calls `backupNow` (no answering window: it backs up directly). A failure is shown in the section and retried after 15 minutes. Old backups are never deleted.

## News & updates

A bell in the top bar (Electron only) shows when lunarbor.dev publishes a newer build or an announcement; clicking it opens the "News & updates" dialog. Ported from Lunamux and kept behaving the same, except that Lunarbor's builds and announcements share one file.

- **Hosted file** (`lunarbor-www`): `https://lunarbor.dev/news.json` (`newsupdates/NewsFeed.kt`) — `platforms.mac` (`latestVersionCode`, `latestVersionName`, `url`) and `items` (`id`, `active`, `date`?, `title`, `body`, `url`?) in one file. The app hardcodes only that URL; the download and "Learn more" links come from the file. Parsing is lenient (`NewsFeedParser`: comments, trailing commas, unknown keys; a missing half reads as empty); a newer `schemaVersion` reads as nothing.
- **Rules** (commonMain `newsupdates/NewsUpdatesBackingViewModel.kt`, tested in `NewsUpdatesBackingViewModelTest`): checks at startup when 24 h have passed since the last successful check (persisted), then every 24 h; Check now on demand (ignored while one runs). An update shows when `latestVersionCode` is above the running build's and is not the exact code dismissed; news shows `active` items not dismissed; a failed fetch or parse changes nothing; Restore re-applies the last fetched feed (offline). Store, fetcher and clock are constructor parameters (`NewsStateStore`, `NewsFetcher`, `now`); dev toggles `USE_SAMPLE_DATA` / `CHECK_ON_EVERY_STARTUP` (never commit `true`) and `CHECK_NOW_BUTTON_ENABLED` live in `NewsUpdatesPorts.kt`.
- **Version** (`electron-main/.../NewsHost.kt`): the main process passes `--lunarbor-version-name=` (`app.getVersion()`) and `--lunarbor-version-code=` (`CFBundleVersion` from the packaged `Info.plist`; `build.mac.bundleVersion` from `electron/package.json` in dev), exposed as `noteApi.appVersionName` / `appVersionCode`. Raise `bundleVersion` for every release and bump `news.json`'s `platforms.mac` only once the DMG is live (`scripts/build-release-electron.sh` header).
- **State** lives in `lunarbor-news.json` beside `lunarbor-backup.json` (`dismissedNewsIds`, `dismissedUpdateVersionCode`, `lastCheckEpochMillis`), app-scoped, over `lunarbor:getNewsState` / `setNewsState`. Links open through `lunarbor:openExternalUrl` (`https:` only, `isOpenableUrl`).
- **View** (`web/.../main/NewsUpdates.kt`): `startNewsUpdates` (called by `Main.kt`; `null` without the Electron bridge, so the browser demo has no bell and fetches nothing) — the feed is fetched with Ktor (`newsupdates/NewsHttp.kt`: `createNewsFetcher`, an engine per target — the browser's `fetch` on JS / Wasm, OkHttp on Android, Darwin on iOS; 8 s connect, 15 s request), hidden behind the `NewsFetcher` port so `:web` needs no Ktor dependency; the bell `TopbarAction` (`AppShell`, after the palette) is always clickable: muted with nothing new, `--t-warn` with an update or news, pulsing only for news (`body[data-lunarbor-news]` / `[data-lunarbor-news-pulse]`; no pulse under `prefers-reduced-motion`); `showNewsDialog`: the update box (Download, × dismisses that version), news cards (× dismisses), "You're all caught up", Check now and Restore dismissed.

## Privacy modes

A **privacy mode** (LBR-10) has a name and a list of tags; while one is on, every item carrying one of its tags — and everything under it, at any depth and across files — is hidden everywhere in the app. A view filter, not security: nothing on disk changes, and "No privacy" needs no password.

- **Model** (`data/Privacy.kt`): `PrivacyMode(id, name, tags)`, `PrivacyFilter` (normalized tag keys: whole tag, case-insensitive — `#private` hides `#Private`, not `#privateer`), `PrivacyConfig` (the vault's `<vault>/_privacy.config` JSON, an app file — `NoteRepository.isAppFile` — so it never shows in the folder contents list, search, link search or to agents; plus the dialog's pure rules: `canEditModes`, `renamed`, `withTag`, …).
- **Registry** (`DocumentRegistry.privacyFlow` / `PrivacyView`): the modes, the app's **current mode** (one for every window and tab; `setPrivacyMode` builds the text index first) and `revision` (bumped when the index changes under a mode). `isPathHidden` / `hasHiddenUnder` are answered by `TextIndex` (`isPathHidden`: the item backing a folder or any above it carries a tag — `Entry.owned`, across files — or a `.md` note carries one anywhere). Search, search nodes, tag suggestions, link search (`VaultIndex.isHidden`), wiki links (a hidden target renders as plain text), Insert Image and link previews apply it. The `_privacy.config` file changing outside the app reloads the modes.
- **Rows** (`main/PrivacyLayout.kt`): `hiddenRows` marks an item carrying a tag (any row of a block) with its subtree. Hidden rows stay in `Document.lines` but are never on screen: `visibleRowsIn` skips them (paint, caret, clamp, drag); `hasChildrenOnScreen` gives an item whose children are all hidden no fold control; a page with nothing visible gets a throwaway empty bullet (`ensureVisibleRow`).
- **Editing next to hidden rows**: deleting a selection across them deletes only the visible rows around them and keeps visible items with hidden content under them (`deleteAroundHiddenIfAny`, `isProtectedRow`); Tab / Shift-Tab past a hidden sibling move the item instead of re-parenting it; a drop lands before hidden rows; sort keeps hidden children in place; paste into a bullet with hidden children adds first children; copy leaves them out; "Expand / Collapse all children" leave them alone; "Delete this node" is not offered on a node with hidden content. Everything else is caught by `recordEdit`'s check (`keepsHiddenContent` → `PrivacyLayout.keepsHiddenRows`): an edit that changed, deleted or re-parented a hidden row, moved a visible one under a hidden item, or deleted a folder-backed item with hidden content is undone. Typing a hiding tag hides the line (undo brings it back). Undo history is cleared when the mode changes.
- **Places**: every file switch goes through `visibleFileFor` (a hidden file or folder opens the nearest visible node); a zoom into a hidden item falls back to its nearest visible ancestor (`reconcile`); on a mode change every pane moves off hidden content (`leaveHiddenLocation`) and an open search re-runs; Back / Forward skip hidden entries (`dropHiddenHistory`). Links to hidden targets are drawn broken ("Not found") and don't navigate; Starred hides them; the folder contents list and count badges leave hidden notes and folders out; sidebar labels of panes not yet loaded stop before hidden titles.
- **UI** (`web/.../main/PrivacyDialog.kt`): "Configure privacy" (palette) and App settings → Privacy → "Edit privacy modes…" open the dialog; the palette also offers "Privacy mode: <name>" for every mode but the current one, and "Privacy mode: None" while one is on (`AppShell.buildPaletteCommands`). Apart from those and the dialog, nothing in the app's chrome tells an onlooker a mode is on. The dialog holds the current-mode picker, then — only under "No privacy" — one card per mode (name edited in place, tag chips in their `tagHue` colour, an add-tag field with the search's tag autocomplete, delete confirmed in the card naming agent connections that use it) and "+ Add mode"; with a mode on it shows "Switch to No privacy to edit modes." Changes apply and save at once. The current mode persists per vault (`AppShell.loadPrivacyMode`, persister key `lunarborPrivacyMode`), loaded before any pane renders.

## Agent access (MCP)

App settings → **Agent access** turns on an MCP server so agents (Claude Code, Cursor, Claude Desktop via `mcp-remote`) can read, search, edit and arrange windows. Off by default. The sidebar section is only a summary and an "Agent access…" button; all settings are in a modal dialog.

- **Transport** (`electron-main/.../McpHttpServer.kt`): Node `http` on `127.0.0.1:<port>/mcp` (default 47321), stateless Streamable HTTP (POST only). Every request needs `Authorization: Bearer <key>` of one **connection** (256-bit, constant-time compare against every key, `connectionFor`); a non-loopback `Origin` or `Host` is refused (web pages, DNS rebinding). Settings — on, port, and the connections, each `{ id, name, key, privacy, allowEdits }` — live in the main process's `lunarbor-mcp.json` (mode 0600, `formatVersion: 2`; connections from an older file are dropped and set up again), read and changed only over `lunarbor:getMcp` / `setMcp` / `addMcpConnection` / `updateMcpConnection` / `removeMcpConnection` / `newMcpKey`. Bodies go to the renderer over `lunarbor:mcpRequest` with the matched connection's `allowEdits` and `privacy`, and back over `lunarbor:mcpResponse`; a window's renderer must call `serveMcp` (`lunarbor:mcpReady`) before requests reach it.
- **Privacy scope** (LBR-10): a connection's `privacy` is a privacy mode's **id** (`""` = "No privacy"; renaming the mode keeps it) and reaches `McpServer.handle(body, allowEdits, privacyModeId)` → `McpTools.call(…, privacyModeId)`, which resolves it with `DocumentRegistry.filterForMode` — an id no mode has (deleted) turns the connection off until a scope is chosen again. The app's own current mode plays no part. Under a scope, what the mode hides does not exist for the agent: every path passes `McpTools.checkVisible` (hidden → "Nothing at …"); `read` / `list_folder` / `search` / `list_tags` leave it out; `edit` / `append` / `create_node` on a visible node keep its hidden items unchanged in place (`applyItems`, then a final `PrivacyLayout.keepsHiddenRows` check refuses anything else); deleting or dropping a node with hidden content inside (`hasHiddenUnder`) is refused; `list_windows` shows a window whose location or title is hidden (or not yet known) as only its id, placing an unloaded tab's window by its stored zoom path (`DocumentRegistry.folderOfZoomPath`). The agent's `INSTRUCTIONS` never mention privacy. Works the same for every client, since the key selects the scope.
- **Protocol + tools** (commonMain `mcp/`): `McpServer` (JSON-RPC: initialize with agent `INSTRUCTIONS`, tools/list, tools/call), `McpTools` (read — nodes, notes and any file: text, images as image blocks, other files as base64 resources up to 10 MB; a node's header gives `Created:` / `Updated:` from its stamps, or `unknown` (LBR-16, `DocumentRegistry.nodeStampsOf`), the front matter itself never shown —, list_folder, search, list_tags; edit, append, create_node, create_file, move, delete — hidden when edits are off; window tools when a workspace is given). `move` works on disk (`NoteRepository.moveNode` / `movePath` via `DocumentRegistry.moveForAgent`: flush, move, rewrite links, reload affected documents), refused while a window has something inside the moved path open, `AgentOutline` (the outline text agents see: `* ` bullets, 2-space nesting, `:::` blocks, `<!-- /path -->` marking items that are nodes), `AgentWorkspace` (tabs / windows; implemented by `AppShell.agentWorkspace`). Wired in `Main.kt` through `web/.../McpBridge.kt`.
- **Edits go through the app**: `DocumentRegistry.editForAgent` flushes, acquires the file's `Document`, applies the change, saves and reloads other documents that splice the file in. `edit` replaces one exact snippet of a node's own items and diffs the result onto the rows (`Document.rewriteRows`, ids paired by `Document.matchIds`), so a `<!-- /path -->` line keeps its folder even when retitled; dropping one needs `delete_nodes: true`. Paths are vault-relative; `..` and dot segments (the trash) are refused.
- **Settings UI** (`web/.../AgentAccessSettings.kt`): the sidebar summary + `openAgentAccessDialog`: the on/off toggle and status, one card per connection — name (editable), privacy scope (a picker: "No privacy" + the vault's modes; a deleted mode shows "Choose a scope…" and an off notice), allow edits, masked key (Show / Copy / New key…), and copy-ready setup for Claude Code (`claude mcp add --transport http --scope user <name> …`), JSON clients and Claude Desktop under a per-connection server name (`lunarbor`, `lunarbor-<name>`) — and "Add connection" (no privacy scope). Confirmations are inline in the card.

## Backlinks

Under the bullets of the page a pane shows — before the folder contents list, on nodes, notes and images — a **"Linked from · N"** section lists every line elsewhere that links to this page (LBR-7; `web/.../main/BacklinksList.kt`, painted by `paintFolderContents`).

- **The page** (`PaneBackingViewModel.backlinksTarget`): the zoomed item's folder, the open outline's folder, or the open note / image / file. A zoomed leaf (no folder) has none.
- **What counts** (`TextIndex.backlinks`, tested in `BacklinksTest`): a line whose `lunarbor:` link points exactly at the page (an outline path counts as its folder; links to things inside it do not), or whose `[[wiki]]` link resolves to it — the same rule as drawing it (`WikiLink.resolve` over the vault's targets: unique, not ambiguous; alias and heading forms count). Lines inside the page itself (its folder and below, or the note itself) are left out, and so is whatever the app's privacy mode hides. Each `TextIndex` line keeps its link targets and wiki names, current like the rest of the index; wiki links are still never rewritten.
- **Registry** (`DocumentRegistry.requestBacklinks` / `backlinksFlow`, mirrored into `State.backlinks`): computed on first ask (open documents saved, index built), recomputed with the listings (`refreshVaultListings`: after every save, external change and window focus — so renames re-resolve wiki names) and when the privacy mode changes.
- **View**: one compact row per line — its text, then, dimmed on the same row, the breadcrumb of where it lives (`searchHitCrumbs`). A click goes there in place (`navigateToSearchHit`: file history, caret on the line); a Shift- / ⌘-click or a right-click opens a new window (`openSearchHitInNewWindow`; the new-window rule, see Links → Clicking). The header folds the list (`State.backlinksCollapsed`, pane state). Hidden when nothing links here.

## Folder contents list

Under the bullets of the node a pane is showing (the zoom target, or the root of the open outline) the web view draws everything in that node's folder that is not already a bullet (`web/.../main/FolderContentsList.kt`). The rules live in commonMain (`main/FolderContents.kt`, tested in `FolderContentsTest`):

- **Shown:** `.md` notes, images, `.excalidraw` drawings, HTML pages, other files, foreign subfolders, Lunarbor folders no bullet references. **Hidden:** folders a `+` line of the node's own outline points at (`VaultEntry.isReferenced`, set by `NoteRepository.listVaultLevel`), dotfiles (`.trash`, `.DS_Store`, …) and app files (`NoteRepository.isAppFile`: every node's `_node.md`, and the root's `Starred.md` and `_privacy.config`), and — while a privacy mode is on — notes and folders it hides (never shown as "unreferenced" folders).
- **Order:** folders first, then files, each group by `FolderContents.naturalCompare` (case-insensitive, `Note 2` before `Note 10`).
- **Which folder:** `PaneBackingViewModel.currentNodeFolder` — the zoomed bullet's folder, the open outline's folder, or none (a zoomed leaf, a `.md` note, an image).
- An expanded folder-backed bullet carries a count badge (`FolderContents.badgeLabel`, e.g. `2 folders, 3 files`) computed from the same list. "New Markdown file" in the palette creates `Untitled.md`, `Untitled 2.md`, … in the current folder and opens it.
- **Clicking a row:** a folder opens as a node (`openFolderAsNode`); a `.md` note opens in Markdown mode, an image in the read-only image view, a drawing in the Excalidraw editor and an HTML page in the web page view (`navigateToVaultFile`) — all five push the pane's file history, so Back / Forward walk nodes, notes and images alike; any other file opens in the system's default app (`MainViewModel.openInDefaultApp` → `noteApi.openPath` → the main process's `lunarbor:openPath`, which refuses paths outside the vault).
- **Row menu (⋮, on hover):** the toolkit's `openPaneMenu` with "Reveal in Finder" (`MainViewModel.revealInFinder` → `lunarbor:revealPath` → `shell.showItemInFolder`, vault-confined; the palette's "Reveal in Finder" does the same for the pane's own place, `currentLocationPath`, the root as its `_node.md`), on a file "Move to Trash" (asks first; `PaneBackingViewModel.trashFile` → `DocumentRegistry.trashNote`: refused while the note is open in a pane, a drawing's unwritten change written first, links left to show as broken), on a folder "Convert to node" — a three-way dialog (Cancel / Only This Folder / Everything Inside), then `PaneBackingViewModel.convertFolderToNode` appends `* <folder name>` as the last child of the listed node and saves, so the bullet adopts the folder; "Everything Inside" first runs `DocumentRegistry.convertFolderTree` → `NoteRepository.convertFolderTree`, which on disk gives every unreferenced subfolder a `+` line and every `.md` note a node holding one block, all the way down, then offers to trash the converted notes (`trashConvertedNotes`) — and, on a `.md` note, "Convert to node": `PaneBackingViewModel.convertNoteToNode` appends `* <note name>` with one child block holding the note's Markdown (`NoteConversion.nodeRowsFor`; a leading `# <name>` heading is dropped) as the last child of the listed node — one undoable edit. A dialog then offers to move the note to `.trash` (`trashConvertedNote` → `DocumentRegistry.trashNote`: refused while the note is open in a pane; links and Starred entries to the note are rewritten to the new node's folder).

## Links and Starred

Links point at folders and files by path, never at bullets by title (`data/LunarborLink.kt`, tested in `LunarborLinkTest` and `LinksTest`):

```
* See [soups](lunarbor:/Recipes/Soups)
* Photo: [granola](lunarbor:/Recipes/granola.jpg)
* Plan in [Budget 2027](lunarbor:/Budget%202027.md)
```

- **Syntax:** `lunarbor:/` plus the vault-relative path of on-disk (already `FolderName`-encoded) names, each segment percent-encoded again for whitespace, `%`, `( ) < > [ ] \ # ?` (so `Q3%2FQ4 plan` is `lunarbor:/Q3%252FQ4%20plan`); `lunarbor:/` is the vault root. An encoded target never holds a space, bracket, parenthesis or backslash, so links can be found and rewritten in raw file text, including inside an outline's escaped `+ [title](folder)` titles.
- **Targets:** any non-empty folder (a node's folder, or any folder Lunarbor didn't create) and any file. Leaf bullets have no folder and empty folders are skipped, so neither is ever offered.
- **Search:** Insert Link, "Link to node…" (same modal) and Navigate to (Cmd-O) search `VaultIndex.search` — the whole vault from the root, however deep the pane is zoomed; titles are the bullet's plain text for node folders, decoded names otherwise, file names for files. The modal saves open documents first and builds the link index (`prepareLinkSearch`). Navigate to alone orders by recency (LBR-16, `LinkSearchModal` `byRecency`): an empty query lists node folders by their `updated` stamp, newest first, then the unstamped ones in vault-walk order (`VaultIndex.recentNodes`); with a query, match quality is unchanged and recency only breaks ties. The stamps come from the front matter of the outline texts the link index sees (`VaultIndex.noteText`, carried by `moveKeys`) — no extra disk reads.
- **Clicking** (`PaneBackingViewModel.navigateToLink`): a folder zooms there — in place when it is under the open outline (expanding the bullets on the way), otherwise by opening the parent node zoomed into its bullet, or the folder itself as a node when no bullet names it; a `.md` note or image opens as from the folder contents list; any other file opens in the default app. Shift-click or right-click opens the target in a new window instead (`AppShell.openLinkInNewPane`); Shift-clicking or right-clicking a bullet's dot opens its item there (`PaneBackingViewModel.locationOfRow` → `AppShell.openLocationInNewPane`). Elsewhere right-click shows the usual context menu.
- **The new-window rule** (LBR-8, `main/OpenGesture.kt`, tested in `OpenGestureTest`; DOM glue `web/.../main/OpenGestures.kt`): every link-like target — `lunarbor:` and resolved wiki links (rows and page title), bullet dots, search-node result rows, pane search results, backlinks, link preview items — classifies a press the same way: plain goes there in place; Shift or ⌘ (Ctrl off the Mac) opens a new window and leaves this pane where it was; right-click opens a new window from `contextmenu`; the Mac's Ctrl-click is a right-click, so its press does nothing (no navigation, caret or drag) and the `contextmenu` that follows opens the one window. A target that opens on the press calls `swallowTrailingClick`, so the toolkit's raise-on-click never puts the old pane back over the new one; the toolkit lets a pane the host opens during a press keep the focus (`AppShellMount.yieldToHostOpenedPanes`).
- **Links stay up to date:** after each save, `Document` reports every folder it renamed, moved or trashed and every image it moved (`PathMove`); `DocumentRegistry.applyPathMoves` rewrites every link pointing at or through an old path — in open documents' lines (`Document.rewriteLinks`, saved with them) and, on disk, in every other file the link index names (`NoteRepository.rewriteLinksInFile`). The link index (`VaultIndex`: file → linked paths) is built by one vault scan on first use and kept current from every note text the repository reads or writes (`NoteRepository.noteTextObserver`). Trash moves never rewrite links.
- **Link previews** (`main/LinkPreview.kt`, tested in `LinkPreviewTest`): a leaf bullet whose text holds exactly one `lunarbor:` link to a node with bullets gets the −/+ control; open, it shows that node's bullets under the link, greyed and read-only (`buildLinkPreview` in `OutlinePaintLoop`), each opening its target on click. Open or not is pane state (`State.expandedLinkIds`, `toggleLinkPreview`); the node's bullets are read from disk and cached by the registry (`requestLinkPreview`, `linkPreviewsFlow`, re-read with the listings after every save and on focus), so the preview shows the saved outline. Closed, the bullet wears the folded ring. Zoomed into such a bullet, the preview is the page (`zoomLinkPreviewOf`), which is read-only like a search node's (`ZoomInfo.isLinkNode`, `State.isReadOnlyPage`; no leaf placeholder). It is a preview, never a mirror: editing happens at the node itself. The MCP tools never follow it: `read` returns the link bullet's text only.
- **Broken links:** a link whose target is gone (moved in Finder, trashed) is drawn struck through with a "Not found" tooltip (`isLinkBroken`, from `DocumentRegistry.linkStatusFlow`, re-checked after every save and on window focus). Its text is never changed.
- **Wiki links and bare URLs** (`data/WikiLink.kt`, tested in `WikiLinkTest`): `[[Name]]` (alias `[[Name|shown]]`, heading `[[Name#Part]]`) stays in the text as written; when the name matches exactly one link target's title (or a file's name with extension; case-insensitive, NFC) it is drawn and clicked as a link to it (`DocumentRegistry.requestWikiLink` / `wikiLinksFlow`, re-resolved with the listings), otherwise it is plain, fully visible text. A resolved link shows only its name (or alias, `WikiLink.shownRangeOf`) except on the caret's row: its `[[`, `Name|` and `]]` are separate spans (`appendWikiLinkSpans`, `.lunarbor-md-wiki-syntax`) shrunk to nothing elsewhere, like a search node's query — still text, so caret mapping is unchanged. Wiki links never enter the link index and are never rewritten; the text index records each line's wiki names (`WikiLink.namesIn`) only to find backlinks. A bare `http(s)://…` in text is a link to itself (`bareUrlEndAt` in the tokenizer).
- **Link popup** (`web/.../main/LinkHoverPopup.kt`): resting the pointer on any link for a second shows where it goes plus "Change link…" (the link search, pre-filled; the pick replaces the whole link with `[text](lunarbor:/…)` — `PaneBackingViewModel.retargetLinkAt`), "Edit text" (`editLinkTextAt`: caret at the end of the link's text) and "Remove link" (`removeLinkAt`; not on a bare URL). The link under a column is found by `data/LinkSource.kt` (`LinkSourceTest`).
- **Starred** (`Starred.md`, `* [Label](lunarbor:/…)`) stores the same paths — the zoomed node's folder, the open outline's folder, or the open note / image — so the same rewrite keeps it current. Starring goes through the registry (`PaneBackingViewModel.toggleStarred`) so the index sees it. `Starred.md` is app data, not content: it is hidden from the folder contents list and from link / Navigate to search (`NoteRepository.isAppFile`), but stays in the link index.

## Pane search

The pane header's magnifier (before Style), Cmd-F (app-wide, `AppShell.installSearchShortcuts`; on an open search it focuses the field and selects its text) and the "Search this tree" palette command open a "Search this tree" field above the page title; Escape closes it from anywhere but another text field (`web/.../main/PaneSearchBar.kt`). While it holds a word, the pane lists the matching **lines** in the page's place — every bullet title, block row and `.md` note line of **the tree on screen and nothing above it** (`PaneBackingViewModel.searchScope` → `TextScope`: the zoomed node's folder or the open outline's folder with everything under it; on a `.md` note only that note; zoomed into a leaf, nothing), each with its file breadcrumb; the editor is hidden and not repainted meanwhile. The query is a search expression (`data/SearchQuery.kt`, tested in `SearchQueryTest`): words and `"phrases"` match substrings of a line's visible text (inline Markdown collapsed, like `FolderName.plainTextOf`), `#tag` a whole tag and `#tag*` a tag prefix; a space or `AND` joins, `OR` alternates, `NOT` / `-x` negates (operators only in capitals; NOT > AND > OR), parentheses group, and `in:/path` searches that tree instead (`in:/` the whole vault); Results come in reading order — down the outline, depth first, a folder-backed item's lines right after the item, then the folder's `.md` notes (`TextIndex.search`); `order:reverse` lists them backwards (the tail of all hits), as does the field's ⇅ button (`State.searchReversed`, persisted with the pane's open search, reset on close). A tag on a parent counts for every line under it — across files: each outline records its folder-backed items' tags (`TextIndex` `Entry.owned`) — and a line under an item that is itself a hit is not listed again. Parsing is lenient, so a half-typed query still searches. Clicking a result (or Enter on the highlighted one; ↑ / ↓ move) goes to it in place (`PaneBackingViewModel.navigateToSearchHit`: pushes file history, caret on the line, located by item index so spliced children don't shift it); its link icon (or Cmd-Enter, a Shift- / ⌘-click, or a right-click) opens it in a new window (`AppShell.openSearchHitInNewPane` → `openSearchHit`).

- **Index** (`data/TextIndex.kt`, tested in `PaneSearchTest`): every line of every note file, normalized, in memory in `DocumentRegistry.textIndex`. Built by one vault scan on the first search, then kept current exactly like the link index — fed by `NoteRepository.noteTextObserver` (the registry fans it out to both indexes) and `moveKeys` after folder moves. `DocumentRegistry.searchText` flushes open documents first, so unsaved edits are found. Each outline file holds only its node's direct children, so per-file indexing covers every item once. Dot folders (the trash) and `Starred.md` are left out; at most 300 hits are returned, with the total.
- **Tag look:** a tag is a soft pill in its own colour — the hue comes from its name (`tagHue`, case-insensitive), so a tag looks the same everywhere. The search field slides open and shut (grid-row transition; none under `prefers-reduced-motion`).
- **Tags:** the index keeps each line's `#tags` (as the tokenizer recognises them; none in code rows). Typing `#…` in the field opens an autocomplete popup of the tree's tags, most used first, with counts (`TextIndex.tags` → `PaneBackingViewModel.tagSuggestions`; modelled on Lunicle's mention popup — fixed on `<body>`, rows act on mousedown, ↑ / ↓ / Enter / Tab / Escape while it shows). Opening the search builds the index, so suggestions are ready. Matching stays substring (`#work` also finds `#workshop`).
- **Insert search node** (palette): `TextEditingViewModel.insertSearchNode` puts `Open tasks {{search: #todo -#done}}` where Insert block would put a block, the expression selected.
- **Search nodes** (`data/SearchNode.kt`): a bullet whose text holds `{{search: <expression>}}` lists the lines matching it under itself, live, as read-only rows (`OutlinePaintLoop.buildSearchNodeResults`: text + where it lives below the tree the node searches (`searchHitCrumbs(hit, under = view.scopeFolder)`), with the count on the node's own line after the magnifier, `buildSearchNodeCount`, which also folds the list; pressing a row goes there in place, a Shift- / ⌘-press or a right-click opens a new window via `MainViewModel.openSearchHitInNewWindow`; all on mousedown, since a repaint between press and release would swallow a click). It folds like any parent — its −/+ control (held to the text line) toggles its fold state, `State.collapsedIds`, open by default — and lists its first `SEARCH_NODE_INLINE` (10) results under itself: "…and N more" zooms into it, whose page lists them all (the registry keeps up to `SEARCH_NODE_MAX_HITS`, 2000). It searches the tree its line is stored in (`Document.storageFolderOf` and below) unless the query has `in:`; its own line is left out (`PaneBackingViewModel.searchNodeOf`). Results are never written: `DocumentRegistry.requestSearchNode` caches them per (tree, query) in `searchNodeResultsFlow` and re-runs them `SEARCH_NODE_REFRESH_MS` (3 s) after the text index changes (`TextIndex.onChanged`). The query is drawn as a chip (tokenizer `isSearchQuery` run, plain editable text) only on the caret's row (`.lunarbor-row-caret`); elsewhere a small magnifier stands in for it. It is no part of the title: `FolderName.plainTextOf`, breadcrumbs and the index strip it (`SearchNode.stripQuery`). Zoomed into, a search node's results head the page (`paint` → `buildSearchNodeResults(isPage = true)`; the count sits in the page title after the magnifier, `MainScreen.updateTitle`) and the page is read-only (`State.isReadOnlyPage`, from `ZoomInfo.isSearchNode`): no leaf placeholder child, the zoom holds with no rows, `recordEdit` / undo / redo refuse, and the editor is not contenteditable. A hit outside the zoom zooms out on the zoom history (`placeCaretAtHit` → `zoomOut`), so Back returns to the search node.
- **State** is pane state: `State.searchQuery` (`null` closed, `""` open and empty), `searchHits` / `searchTotal`, `isSearching` (only while the first search builds the index). `setSearchQuery` debounces 120 ms and cancels an older search. Zooming or switching file closes the search. The open search's text persists with the pane's location (`PaneLocationStore`, `search`) and is reapplied on restore.

## Zoom navigation

`PaneBackingViewModel` keeps three zoom-related fields:

- `zoomedLineId: LineId?` — the bullet whose subtree is currently shown, or `null` for the root view. Stored as a stable id so it survives edits above the target.
- `zoomHistory: List<LineId?>` — browser-style back stack. Every zoom-changing intent (`zoomInto`, `zoomTo`, `zoomOut`) pushes the current target before changing, and clears the forward stack.
- `zoomForward: List<LineId?>` — populated by `zoomBack` and consumed by `zoomForward`. Capped at 50 entries per direction.

The zoom stacks hold ids within the pane's open outline. Moving to another file (opening a folder as a node, a `.md` note or an image from the folder contents list, a link into another outline) goes through `navigateToVaultFile`, which pushes the pane's cross-file `fileHistory` instead. `PaneBackingViewModel.zoomBack` / `zoomForward` pop the zoom stack first and fall through to `fileBack` / `fileForward`, so one pair of Back / Forward buttons walks both.

Because every folder-backed bullet's children live in its own folder, zooming into a collapsed one loads them first (`acquireExpansion`); zooming into a leaf inserts an empty placeholder child. A zoom never leaves a fold changed behind it: a folded bullet it opens (the target, or folded ancestors a link or history step opens on the way) is recorded in `State.zoomUnfoldedIds` and folds again as soon as the zoom target leaves its subtree (`ZoomNavigation.refoldLeftBehind`, run by `patch` on every zoom change; the pane keeps its folder expansion, so re-entering is instant); toggling the fold by hand drops it from the set. Markdown mode never zooms: `zoomInto` / `zoomTo` are no-ops there.

**Drag and drop** (`PaneBackingViewModel.dropTarget`, drawn by `MainScreen`): dragging a dot moves the item with its subtree. The drop row snaps off block interiors and folded subtrees; sideways movement since the press (one level per 24 px) picks the level, clamped between the row below the drop point (so it never adopts the rows below as children) and one level under the item above (its own level when that item is folded). The drop line is indented to the chosen level; dropping in place only changes the level. While a drag is armed the pointer is a closed hand everywhere (`lunarbor-dragging` on `<body>`), and holding it near or past the page's top or bottom edge scrolls the page — faster the further out — re-aiming the drop at the rows scrolling by (`MainScreen.dragAutoScrollTick` / `aimDrop`; off a row but over the page, the nearest row is the target).

**Outline look** (`OutlinePaintLoop`, Dynalist-style): 30 px per level (`EditorStyle.indentStepPx`) with a hanging indent, so wrapped lines align with the text; thin guide lines run from each open parent's dot down past its last child (`appendIndentGuides`); a folded parent's dot wears a ring (`.lunarbor-row-folded`); the expand / collapse control is a −/+ shown only while the row is hovered. Folding and unfolding animate in place (`web/.../main/FoldTransition.kt`, armed by `MainScreen` when `collapsedIds` / `expandedRefIdsLocal` change): the rows below glide while the children are uncovered (unfold) or covered (fold) by an edge that moves with them; repaints mid-fold re-apply it (`FoldTransition.resume`), and typing never animates.

User-facing affordances on the web: the pane header starts with Back / Forward chevrons (always visible, dimmed when the corresponding stack is empty; `AppShell.paneNavCluster`, in the toolkit's leading badge slot), followed by a breadcrumb of the pane's whole location — `Home`, each node folder, the open file (`PaneBackingViewModel.fileBreadcrumb`), then the zoom ancestors and target (`AppShell.paneBreadcrumbSegments`). Every segment but the last navigates, so the parent segment is "up" and `Home` is "home"; there are no up / home buttons. The trailing strip holds Search, Style and the window controls. The top bar's + menu leads with "New window" (its default, also a plain click on +), which adds a pane at the focused pane's location (`AppShell.openWindowAtCurrentLocation` → `PaneBackingViewModel.currentLocation` / `openLocation`), then the toolkit's "New tab". Keyboard shortcuts are `Option-Cmd-Left` (back), `Option-Cmd-Right` (forward), `Ctrl-Cmd-Up` (up: `PaneBackingViewModel.navigateUp` — zoom one level out, or with no zoom open the parent folder's outline via `parentFileOf`; not Option-Cmd-Up, which the toolkit's Expand pane owns), `Shift-Ctrl-Cmd-Up` (home: `PaneBackingViewModel.navigateHome`), `Escape` (clear zoom), `Option-Cmd-Enter` (zoom into the caret's item), and `Cmd-Up` / `Cmd-Down` (fold / unfold the caret's item: `PaneBackingViewModel.setCaretItemFolded`).

**Navigation animation** (`web/.../main/NavigationTransition.kt`): zooming in, the clicked bullet's text flies up and grows into the page title while the old page falls away and the children rise in; zooming out, the title shrinks back into its row. Back / Forward and breadcrumb jumps without a visible row to morph drift sideways (right = deeper), another file is a plain fade-through, and `prefers-reduced-motion` gets a short fade. The old view is an overlay clone on `document.body` captured before the repaint (`MainScreen.captureOutgoing`), so the toolkit's chrome rebuild never flashes; it takes the pane's bottom corner radii, so it never pokes past the rounded pane into the Depth look's halo. Clicking the bullet dot of a folder-backed bullet zooms into it via `zoomInto` — the load on a folded bullet + history push gives a "click to open this node" UX without any link-specific click handling.

## 3D mode

Four shapes (`web/.../main/space/`, LBR-11): **Pages** and three maps — **Crown**, **Cone**, **Galaxy** (below). **Off by default**: App settings → Experimental → "Enable 3D mode" (`SpaceMode.setEnabled`, persister key `lunarborSpaceEnabled`, read as `isSpaceModeEnabled`) turns it on, like Lunamux's "Enable 3D app switcher"; while off the topbar cube is hidden (CSS, `body[data-lunarbor-space-enabled]`), ⌃⌘3 / ⌃⌘1 do nothing, the Keyboard Shortcuts sidebar leaves out their rows, and a remembered "on" is not restored; turning it off leaves 3D mode. Every node's page hangs at a fixed place in space, and each window looks into it through its own camera. Toggled by the topbar cube, ⌃⌘3 (Ctrl-Alt-3 off the Mac) and Esc; remembered under the persister key `lunarborSpace` (`{ on, split, shape }`) — app state, never the vault. The shape is picked in the strip's switcher (Pages / Crown / Cone / Galaxy), with ⌃⌘2 (next shape) or L on a map (`SpaceMode.setShape`, `SpaceShape`).

- **Full window:** the space is a layer over the whole window (`.lunarbor-space`, z-index 900): sidebar, top bar and panes are under it; menus, popups, modals and the palette still open above. A slim draggable strip on top holds Palette, the single / split switch and Leave 3D; a dock along the bottom lists the tabs (click to switch) and the active tab's windows (`1 · Recipes`, click to focus) and "+ Window".
- **Single or split** (⌃⌘1, the strip button): the focused window alone, filling the space, or every window of the tab at exactly its 2D place and size — read from the toolkit's drawn panes (`AppShell.spaceHost.spacePanes`), stacked by z-order; focusing a view raises its window as a click does in 2D. Each view has its own camera and flies on its own.
- **The live page is the pane's real `MainScreen`** (`MainScreen.mountInSpace` / `leaveSpace`), reparented into a CSS3D object at 1:1 — there is no second editor: typing, Tab, Enter, folding, search, palette commands, drawings and images all run the 2D code. In space the navigation and fold animations are off (the flight replaces them) and Escape leaves 3D instead of clearing the zoom; flying out is ⌃⌘↑, Back or the page header's breadcrumb. Leaving 3D puts every editor back with its caret and scroll.
- **Pages** fit their content (a transparent page-sized slot holds a card at most that tall; a drawing or web page fills it). Child pages hang behind the live page in a left and a right column, grandchildren in one column on their parent's outer side, faint; threads (SVG) run from each child bullet's dot to its page. Previews are read-only outlines (`PageSpaceModel`: from the open outline's rows, so an indent sprouts a page at once; else from `DocumentRegistry.requestLinkPreview` listings); clicking one, or a dot in it, zooms the window there. A page's header shows which windows are on it (`Window 1`, …). What the privacy mode hides (LBR-10) is never a page or a preview row (`spacePageOf` applies `hiddenRowsIn` and filters listings as link previews do); the live page shows backlinks (LBR-7) like the pane does.
- **Layout and model are pure commonMain** (`main/PageSpaceLayout.kt`, tested in `PageSpaceLayoutTest`): `PageSpaceLayout` places pages relative to the page a window is on (deterministic, so Back returns exactly where it was); `PaneBackingViewModel.spacePageOf` gives the page around a pane, keyed by node folder (`PageSpaceKeys`) so a node is one page whether zoomed into or opened. The camera follows pane state: a change of `(activeFileRel, zoomedLineId)` to another page flies (0.75–1.5 s; a cut under `prefers-reduced-motion`); editing never moves it.
- **Rendering rules** (`PageSpaceView`): the three.js camera never moves (the world does — Chrome stops hit-testing a CSS3D layer whose camera has moved); pages get z-indexes by distance and pages behind the camera ignore the pointer; stray `scrollTop` is reset every frame; one WebGL canvas draws every view's starfield via viewport + scissor; frames run only while something moves. Colours and fonts are the theme's `--t-*` / `--dt-font-prop` variables; the WebGL specks re-read them on theme change (glow on dark, ink on light).
- **Maps** (`space/MapView.kt`; Crown, Cone, Galaxy): the vault's node tree as bodies — the root and every folder-backed item, sized by what is under them, leaf bullets as dust around them — with branches to their parents, `lunarbor:` links as arcs between bodies, and the tab's windows as cards joined by a line to the body each shows. The graph (`main/VaultGraph.kt`, `VaultGraphBuilder`) comes from the registry's node listings (`requestLinkPreview`, asked folder by folder, so the map grows as they land; capped at `MAX_NODES`, 1500) and the link index (`DocumentRegistry.linkIndexSnapshot`); positions from `main/GraphLayout.kt` (pure, deterministic from paths and sibling order, tested in `VaultGraphTest`): Crown grows up and out from the root (depth is height, each area a slice of the circle), Cone hangs each node's children in a ring below it, Galaxy is a force layout seeded from the crown where links pull too. Folds are the map's own (F; a folded body wears a ring; a big vault starts folded below depth 2–3, `defaultFolds`). Drag orbits, right- or Shift-drag pans, scroll zooms; click a body to select it and fly there, double-click or ⏎ opens it in the focused window (`navigateToLink`), P opens it and switches to Pages once the window has arrived, E goes back to Pages, ← → ↑ ↓ walk siblings / parent / child, Home reframes. Labels are DOM (at most 40, greedy, never overlapping; tags left out, as in breadcrumbs). WebGL instanced spheres + line buffers through `ThreeLib.raw` (dynamic, kept inside `MapView`); the map's camera moves freely (no CSS3D layer there). **Privacy:** the graph is built with the current mode's filter — an item with a hiding tag, or a hidden folder, is left out with its subtree; a link whose file or target is hidden is dropped — and rebuilt when `privacyFlow` changes.
- **Colour** (`main/SpacePalette.kt`): 3D mode is deliberately more colourful than the theme. Each top-level area gets a vivid hue of its own (golden-angle steps in the root's unfiltered outline order, so a privacy mode never shifts a colour); bodies vary a little around it, branches take their child's hue, link arcs blend between their ends' hues, pages in Pages wear it as a top edge and glow, item dots and threads (`SpaceMode.areaColor`), and some of the background specks are tinted. The theme still decides background, text and chrome.
- **three.js** (`npm three 0.170.0`) loads lazily on first entry through a dynamic `import()` into its own chunk (`space/three/ThreeLib.kt`: minimal externals, kept free of Lunarbor types for a later move to Lunula).

## Browser demo

The web bundle doubles as a website demo (`demo/README.md`). **Demo mode** (`web/.../demo/DemoMode.kt`) is on exactly when the page has no Electron `noteApi` bridge, so the desktop app never enters it and the website build never looks for real files.

- **Vault in memory**: `DemoFileSystem` implements `FileSystem` over a map, seeded at boot (`loadDemoVault`, awaited in `Main.kt` before the DI graph is built) from `demo-vault.js`, which the `:web:generateDemoVault` task packs from `demo/vault/` (text as text, other files base64, dotfiles skipped) and `demo/state.json`; it is a script setting `window.lunarborDemoVault` that `loadDemoVault` adds as a `<script>`, not JSON to `fetch`, so the demo also runs from a page opened from disk (`file://`). `JsAppGraph` provides it as the `FileSystem` with vault root `DEMO_VAULT_ROOT`. Everything is editable; a reload starts over.
- **Assets**: `lunarborAssetUrl` returns `DemoFileSystem.assetUrl` — a `blob:` URL of the file's current bytes, cached until the file changes — in place of `lunarbor-asset://`; images, drawing previews and the HTML page view work unchanged. "Open in default app" opens the blob in a new browser tab.
- **Persister**: `DemoPersister` keeps the look (`darkness.theme*`, `darkness.uiSettings`) in `localStorage` under `lunarbor-demo`; every other key (layout, pane locations, folds) lives in memory. Both start from `demo/state.json`'s values (the look only until the visitor picks one). `window.lunarborDemoState()` dumps the in-memory keys, for authoring that file.
- **Content**: `demo/vault/` and `demo/state.json` are generated by `demo/source/build_vault.py` from `tree.txt` (an indented outline DSL), `starred.txt`, `layout.json` (tabs, windows, default theme) and `assets/`. Edit those, never the generated files.
- **Scripts**: `scripts/run-demo.sh` serves the bundle locally (`scripts/stop-demo.sh` stops it); `scripts/build-demo-site.sh [dir]` writes the deployable static site (production build, no source maps).

## Where things live

```
client/src/commonMain/.../main/
  Document.kt                         ← one loaded outline/note: content + autosave
  DocumentRegistry.kt                 ← fileRel → Document, refcounted
  PaneBackingViewModel.kt             ← per-pane state + editor intents
  TextEditingViewModel.kt             ← typing/movement/selection slice
  ZoomNavigation.kt                   ← zoom in/out/back/forward slice
  MarkdownStyleViewModel.kt           ← inline + line-level markdown slice
  SelectionHelper.kt                  ← pure helpers (selection, breadcrumb)
  DocumentLayout.kt                   ← pure layout helpers (visible rows, hit-test)
  BlockLayout.kt                      ← block rows: markers, block ranges
  FolderContents.kt                   ← folder contents list: filter, order, badge
  FoldMemory.kt                       ← which folder-backed items were left open
  PrivacyLayout.kt                    ← rows a privacy mode hides; the edit check
  LinkPreview.kt                      ← read-only previews of linked nodes
  OpenGesture.kt                      ← press → here / new window / leave to contextmenu
  PageSpaceLayout.kt                  ← 3D mode: page positions + the pages around a page
  VaultGraph.kt                       ← 3D maps: node bodies, leaves, links (privacy-filtered)
  GraphLayout.kt                      ← 3D maps: Crown / Cone / Galaxy positions, SpaceShape
  SpacePalette.kt                     ← 3D mode's own colours: a hue per area
  NoteConversion.kt                   ← "Convert to node": note → bullet + block rows
  VaultRelocation.kt                  ← rebase pane locations onto a new vault root

client/src/commonMain/.../newsupdates/
  NewsUpdatesBackingViewModel.kt      ← News & updates: 24 h check, dismissals, restore
  NewsFeed.kt                         ← news.json (builds + items) model + lenient parser
  NewsHttp.kt                         ← Ktor fetcher (engine per target: Js / OkHttp / Darwin)
  NewsUpdatesPorts.kt                 ← state store + fetcher interfaces, dev toggles

client/src/commonMain/.../mcp/
  McpServer.kt                        ← MCP JSON-RPC + agent instructions
  McpTools.kt                         ← the agent tools (read/search/edit/windows)
  AgentOutline.kt                     ← outline text agents read and write
  AgentWorkspace.kt                   ← tabs + windows interface for agents

client/src/commonMain/.../data/
  NoteRepository.kt                   ← vault I/O + folder-per-bullet save rules
  SubtreeCodec.kt                     ← `_node.md` outline codec
  Privacy.kt                          ← privacy modes, `_privacy.config`, tag filter
  FolderName.kt                       ← title → folder name encoding
  ImagePaths.kt                       ← image `src` → vault file rules
  LunarborLink.kt                           ← `lunarbor:` link paths: codec, find, rewrite
  WikiLink.kt                         ← `[[Name]]` links: parse, resolve by title
  LinkSource.kt                       ← the link under a column (popup edits)
  VaultIndex.kt                       ← link-target search + link index
  TextIndex.kt                        ← full-text index behind the pane search
  InlineMarkdownTokenizer.kt          ← inline Markdown → styled runs
  LineMarkdownPrefix.kt               ← line-level styles (headings, quote)

client/src/*Main/.../platform/
  FileSystem.kt                       ← interface; per-platform PlatformFileSystem

web/src/jsMain/.../
  Main.kt                             ← entry, creates graph
  demo/DemoMode.kt                    ← browser demo: detection, vault loading, DemoPersister
  demo/DemoFileSystem.kt              ← in-memory FileSystem + blob: asset URLs
  di/JsAppGraph.kt                    ← Metro DI graph
  main/MainViewModel.kt               ← thin facade (one per pane)
  main/MainScreen.kt                  ← DOM rendering + event handling
  main/OutlinePaintLoop.kt            ← paints bullets, blocks, guides, fold controls, badges
  main/NavigationTransition.kt        ← zoom morph + fade-through on navigation
  main/FoldTransition.kt              ← fold / unfold animation
  main/FolderContentsList.kt          ← folder contents list under the bullets
  main/BacklinksList.kt               ← "Linked from" section above the folder contents
  main/ImageViewer.kt                 ← read-only view of an opened image
  main/HtmlViewer.kt                  ← sandboxed web page view of an opened .html file
  main/DrawingEditor.kt               ← Excalidraw editor for an opened .excalidraw file
  main/DrawingPreview.kt              ← SVG pictures of embedded drawings + palette thumbnails
  main/LinkSearchModal.kt             ← Insert Link / Link to node / Navigate to / Change link
  main/LinkHoverPopup.kt              ← hover popup on links: change / edit text / remove
  main/OpenGestures.kt                ← OpenGesture for MouseEvents; isMacPlatform; trailing-click swallow
  main/PaneSearchBar.kt               ← the pane's search field + result list
  main/ImageSearchModal.kt            ← Insert Image (every image in the vault)
  main/StarredModal.kt                ← Starred bookmarks
  main/CommandPalette.kt              ← command palette
  main/AppShell.kt                    ← per-pane VM construction + lifecycle
  main/PaneLocationStore.kt           ← persisted pane → location (file + zoom)
  main/AppSettingsContent.kt          ← App settings + keyboard-shortcuts sidebars
  main/BackupSettings.kt              ← App settings → Backup section + automatic-backup answer
  main/AgentAccessSettings.kt         ← App settings → Agent access (MCP)
  main/PrivacyDialog.kt               ← Configure privacy dialog + top-bar mode chip
  main/McpBridge.kt                   ← MCP requests from the main process → McpServer
  main/NewsUpdates.kt                 ← News & updates bell + dialog, Electron store/fetch
  main/space/SpaceMode.kt             ← 3D mode: enter / leave, views, strip + dock, backdrop, render loop
  main/space/PageSpaceView.kt         ← one window's view: live page, previews, flights, threads
  main/space/MapView.kt               ← Crown / Cone / Galaxy: bodies, links, labels, window cards
  main/space/three/ThreeLib.kt        ← three.js + CSS3DRenderer externals, loaded lazily

electron-main/src/jsMain/.../electron/
  ElectronMain.kt                     ← main process: window, IPC file access
  RunPaths.kt                         ← LUNARBOR_VAULT / LUNARBOR_LOCAL_DATA
  VaultWatcher.kt                     ← vault fs.watch, self-write filter
  VaultFilePath.kt                    ← vault-confined paths for openPath
  VaultBackup.kt                      ← backups: settings, schedule, write gate
  ZipWriter.kt                        ← folder → .zip (deflate, ZIP64)
  McpHttpServer.kt                    ← MCP endpoint: localhost, key, relay to renderer
  NewsHost.kt                         ← app version for the renderer, lunarbor-news.json, external links

docs/files-and-bullets.md             ← the on-disk model, for humans
demo/source/                          ← the browser demo's tour (generates demo/vault/)
scripts/
  seed-vault.py                       ← sample vault in the current format
  run-demo.sh                         ← serve the browser demo locally
  stop-demo.sh                        ← stop that server if it is listening
  build-demo-site.sh                  ← build the browser demo as a static site
  build-release-electron.sh           ← signed + notarized macOS .dmg
  build-electron-no-notarize.sh       ← unsigned local .dmg
  dynalist_to_lunarbor.py            ← Dynalist OPML import (+ its test)
  ai-dev-run.sh                       ← isolated app launch for agent runs
```

## Source-file documentation

Every Kotlin source file in this project is expected to carry thorough, up-to-date documentation. The codebase is small enough that we can keep it pristine; big-picture comments and good KDoc make the layered architecture self-explanatory, which matters because much of the code is per-platform glue that rarely gets re-read.

### File-level header comment

At the very top of every `.kt` file, above the `package` declaration, include a block comment that states:

1. The file's filename (and platform source set where relevant, e.g. `MainScreen.kt (jsMain)`).
2. The file's purpose — what concept it owns and how it fits into the layered architecture described above.
3. Any non-obvious rules the reader needs to know before editing (e.g. "commonMain only — no DOM imports", "platform facade, no business rules").

Use `/* … */` for file-level comments rather than KDoc `/** … */` — the file header is for humans, not for the API doc generator.

### Class and function KDoc

Every top-level class, object, interface, and non-trivial function gets KDoc. "Non-trivial" excludes one-line delegation methods on facade classes, but even those should at least reference the underlying function (e.g. `/** See PaneBackingViewModel.moveLeft. */`). Private helpers inside a class that are genuinely one-liners with obvious names can be left undocumented; anything with real logic should have KDoc.

Each KDoc block should cover:

- **Purpose.** What the function/class does, in one or two sentences. Avoid rephrasing the name.
- **Callers.** Who calls it and why. This is often the most valuable field in this codebase — it's how the reader reconstructs the intent flow across layers. Use a `### Callers` sub-heading for classes with multiple distinct callers, or a short "Called by …" sentence for functions.
- **`@param`** for every parameter, with enough context to answer "what should I pass here?". Mention invariants (ranges, non-null expectations, format conventions) where they exist.
- **`@property`** for every property on a `data class` — the same standard as `@param`.
- **`@return`** when the return value is non-trivial. Omit for `Unit` and for getters whose meaning is exhausted by the type.
- **Invariants / side effects.** Flag any state mutations, flow emissions, or ordering constraints that aren't obvious from the signature.

### Style

- Use KDoc markdown (`[SomeSymbol]` for cross-references, backticks for code) so IDE rendering and Dokka output stay tidy.
- Prefer short sentences and bullet points to long paragraphs.
- Avoid restating the obvious ("this method returns a boolean"). Documentation earns its place only when it explains something the signature can't.
- When you add or rename a class or function, update every KDoc that references it — stale references are worse than none.

## Adding a feature

1. Decide whether it's a *document* operation (content change that would matter to any pane viewing the file) or a *pane* operation (cursor, selection, zoom, history, fold state — anything UI-local).
2. If document: add a primitive on `Document`.
3. Build the user-facing intent on `PaneBackingViewModel` (often via the relevant slice — `TextEditingViewModel`, `ZoomNavigation`, `MarkdownStyleViewModel`), composing document primitives and local cursor updates via `patch { }`.
4. Add a one-line delegation to `MainViewModel`.
5. Wire the input mapping in the platform view.
6. If a new app-scoped binding is required, add an `@Provides` in `JsAppGraph` (and future platform graphs). Per-pane wiring goes in `AppShell.ensurePaneViewModel`.

