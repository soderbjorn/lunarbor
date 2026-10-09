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
- **Markdown mode is pane state.** `State.isMarkdownMode` is `true` on a plain `.md` note: same editor, no bullets, folds, zoom or drag; the title renames the file. See `docs/features/editor.md`.
- **Blocks are the only other outline line.** Bordered free Markdown among the bullets, one `Document.lines` row per content line behind hidden private-use markers (`BlockLayout.FIRST` / `NEXT` / `CODE`); `>` quotes and ```` ``` ```` fences exist only on disk. Full rules (Enter / Backspace / Tab inside, leaving, large previews, Convert block to nodes, Clear formatting): `docs/features/editor.md`.
- **File views are pane state.** Images, `.excalidraw` drawings and `.html` pages open without a `Document` (`State.isFileView`). See `docs/features/file-views.md`.
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
- **Mirrors add nothing to the format**: a mirror is an ordinary link bullet on disk; its items stay in the mirrored node's own `_node.md`.
- **Links are relative Markdown links** (see `docs/features/links.md`): `[soups](Recipes/Soups/_node.md)`, from the folder the line is stored in. Older vaults' `lunarbor:/…` links were converted by a migration and are still read.
- **Format changes ship with a migration**: any change to the vault format comes with code that converts existing vaults on startup, before anything loads, keeping the originals in `.trash/<timestamp> format migration/`, and maps remembered window locations onto the new paths. (The node stamps' front matter is additive and optional, so it ships without one.)
- **Folder names** (`data/FolderName.kt`): the title's plain text without its `#tags` (`FolderName.nameTextOf`: `1-1 #private` → `1-1`; the tag stays on the line, so it still shows as a pill, is searched and drives privacy modes — breadcrumbs, pane labels, link-target titles and Starred labels leave it out too, the zoomed page title keeps it as a pill), percent-encoded where unsafe (`/ \ : * ? " < > |`, `%`, a leading dot, trailing dots/spaces, control characters), capped at 120 bytes; sibling collisions are case-insensitive and get ` (2)`, ` (3)`, …; an empty title is `Untitled`.
- **Save rules** (`NoteRepository.save`, run 1 s after the last edit and at most 5 s apart; bullets and blocks alike): a leaf that gets its first child becomes a folder — adopting a sibling folder of the same name that no bullet references (ignoring case and Unicode normalization) instead of making ` (2)`, its outline's bullets kept after the new children (`SaveResult.adoptedItems`, spliced in by `Document`); a leaf named like such a folder adopts it without children too when the folder holds anything; a folder whose last child is removed is deleted unless it still holds files; title edits rename the folder — except on a node with no child bullets, which lets go of its folder instead (it becomes a leaf; the folder keeps its name and files); moves (indent, outdent, drag, cut and paste) `rename` the folder so attachments travel; a deleted folder-backed bullet's folder goes to `<vault>/.trash/<timestamp> <name>/` — unless it holds user files (anything but its `_node.md`, dotfiles and its child bullets' folders): then the folder stays in place with those files (unreferenced, so the parent's folder contents list shows it) and only its outline plus each child bullet's folder, by the same rule, go to the trash (`NoteRepository.trashNode`); undo in the same session moves it back or merges the trashed parts back in (`PromotedRef.keptAt`, `NoteRepository.mergeBack`); the trash is never emptied automatically. A child link (of a bullet or block) whose folder is missing loads as a plain leaf and is saved back as `- title` — the title is kept, the dangling reference is not. Deleting a row's whole text as part of a multi-row selection deletes that row as an item (its folder is trashed), so typing afterwards never inherits its folder or hidden children (`TextEditingViewModel.deleteSelectionIfAny`).
- **Parser/codec**: `client/src/commonMain/.../data/SubtreeCodec.kt` parses and emits the outline format; `NoteRepository.kt` is the only thing that touches `FileSystem`. Files that are not `_node.md` outlines (`Starred.md`, other `.md` notes) are read and written as plain lines, verbatim.
- **Images** (`data/ImagePaths.kt`): an image `src` resolves like a CommonMark link, relative to the folder the line is stored in (`Document.storageFolderOf`: the nearest folder-backed ancestor's folder, the outline's own folder, or a `.md` note's folder). A pasted image is written into that folder and referenced by bare file name (`![](shot.png)`), so it moves with the node's folder; a pasted one goes at the caret, a dropped one on a line of its own at the row under the drop point (`PaneBackingViewModel.onImageDropped`: an empty row takes it, else a new line after the row, as Enter at its end); a hovered image's × takes it out of its row (`removeImageAt`; the file stays), and dragging the image moves it onto a line of its own at the row it is dropped on (`moveImage`: a row it leaves empty goes too, unless something hangs under it; its src re-written for the new row's folder); when a save moves a row to another folder, the images it names that way move with it (`NoteRepository.moveAttachments`, unless another row still uses them). A `/…` src is vault-rooted (the Insert Image palette uses it for images outside the row's folder); a src with a URL scheme is external.

## Feature specs

Detailed behaviour of each feature lives in `docs/features/` — read the relevant file before changing a feature, and update it in the same commit:

- `editor.md` — Markdown mode, blocks, code blocks, Clear formatting, zoom navigation, drag and drop, outline look, navigation animation
- `file-views.md` — Excalidraw drawings and HTML pages
- `links.md` — links, wiki links, mirrors, Starred, backlinks, folder contents list
- `search.md` — pane search, query syntax, text index, search nodes, Toggle done on results
- `daily-notes.md` — Today, journal paths, pending rows, Previous / Next day, daily template, done
- `privacy.md` — privacy modes
- `agent-access.md` — the MCP server and its tools
- `lunicle.md` — Lunicle connections and board nodes
- `app-settings.md` — vault location, window bounds, outside changes, page memory, folds, pane locations, backup, News & updates
- `3d-mode.md` — Pages, Grove and the maps
- `demo.md` — the browser demo
- `../files-and-bullets.md` — the on-disk model, for humans

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
  DoneLayout.kt                       ← done rows of an open outline; "Hide done items"
  LinkPreview.kt                      ← which link bullets mirror a node; node listings
  LunicleBoardRows.kt                 ← a board node's navigable rows: order, steps, relocation by key
  LunicleBoardEditing.kt              ← board edits: drafts, their placement, title / draft commit rules
  LunicleBoardMenu.kt                 ← board properties: # / @ menus, resolution step, move + flash placement
  LunicleBoardDetails.kt              ← an issue's description lines (edit rules, large preview) + comment rows
  LunicleBoardDrag.kt                 ← board drags: where a dropped issue lands (above / below, into a column)
  OpenGesture.kt                      ← press → here / new window / leave to contextmenu
  PageSpaceLayout.kt                  ← 3D mode: page positions + the pages around a page
  GroveLayout.kt                      ← 3D Grove: page poses over the whole vault, quaternions, vault tree
  VaultGraph.kt                       ← 3D maps: node bodies, leaves, links (privacy-filtered)
  GraphLayout.kt                      ← 3D maps: Crown / Cone / Galaxy positions, SpaceShape
  LinkBundling.kt                     ← 3D maps: link curves bundled along the tree
  SpacePalette.kt                     ← 3D mode's own colours: a hue per area
  NoteConversion.kt                   ← "Convert to node": note → bullet + block rows
  DailyNotes.kt                       ← daily notes: CalendarDate, ISO weeks, Journal path titles + rows, day steps
  DailyTemplate.kt                    ← which node new journal days copy; follows moves
  VaultRelocation.kt                  ← rebase pane locations onto a new vault root

client/src/commonMain/.../newsupdates/
  NewsUpdatesBackingViewModel.kt      ← News & updates: 24 h check, dismissals, restore
  NewsFeed.kt                         ← news.json (builds + items) model + lenient parser
  NewsHttp.kt                         ← Ktor fetcher (engine per target: Js / OkHttp / Darwin)
  NewsUpdatesPorts.kt                 ← state store + fetcher interfaces, dev toggles

client/src/commonMain/.../lunicle/
  LunicleApi.kt                       ← request + connection-store ports (Electron relay on the web)
  LunicleClient.kt                    ← typed calls; request building (names, snake_case, omit vs null)
  LunicleModels.kt                    ← board / issue / me models, lenient parsers, errors
  LunicleService.kt                   ← app-scoped: connections, clients, KEY → project resolution
  LunicleBoards.kt                    ← board cache: request, interest, polling, stream bursts, optimistic writes
  LunicleBoardModel.kt                ← board rows + words: columns, pills, sync line, diff, notice

client/src/commonMain/.../mcp/
  McpServer.kt                        ← MCP JSON-RPC + agent instructions
  McpTools.kt                         ← the agent tools (read/search/edit/windows)
  AgentOutline.kt                     ← outline text agents read and write
  AgentWorkspace.kt                   ← tabs + windows interface for agents

client/src/commonMain/.../data/
  NoteRepository.kt                   ← vault I/O + folder-per-bullet save rules
  SubtreeCodec.kt                     ← `_node.md` outline codec
  Privacy.kt                          ← privacy modes, `_privacy.config`, tag filter
  DoneState.kt                        ← done = whole title struck; Toggle done's wrap / unwrap
  FolderName.kt                       ← title → folder name encoding
  ImagePaths.kt                       ← image `src` → vault file rules
  LunarborLink.kt                           ← links: relative in files, vault-rooted `/…` in the app; find, rewrite
  WikiLink.kt                         ← `[[Name]]` links: parse, resolve by title
  LinkSource.kt                       ← the link under a column (popup edits)
  LunicleNode.kt                      ← `{{lunicle: <conn>/<KEY>}}` board nodes: parse, strip
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
  main/HitDoneControls.kt             ← ✓ toggle on pane search rows + "Marked done · Undo" toast
  main/SearchNodeHitCursor.kt         ← arrow keys over a search node's result rows
  main/LunicleBoardCursor.kt          ← arrow keys over a board node's rows; ⌘↑ / ⌘↓ fold; the title / draft / description / comment field; property menus
  main/LunicleBoardMenuPopup.kt       ← the # / @ / pill menu and resolution popup on <body>
  main/LunicleBoardDragGesture.kt     ← dragging an issue by its dot: threshold, drop marks, LUNICLE_DROP_EVENT
  main/ImageViewer.kt                 ← read-only view of an opened image
  main/HtmlViewer.kt                  ← sandboxed web page view of an opened .html file
  main/DrawingEditor.kt               ← Excalidraw editor for an opened .excalidraw file
  main/DrawingPreview.kt              ← SVG pictures of embedded drawings + palette thumbnails
  main/LinkSearchModal.kt             ← Insert Link / Insert Mirror / Navigate to
  main/LinkEditDialog.kt              ← Edit link dialog: a link's text + URL
  main/LinkHoverPopup.kt              ← hover "Edit link" button on links
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
  main/LunicleSettings.kt             ← App settings → Lunicle: connections dialog, Test
  main/LunicleBridge.kt               ← LunicleApi + connection store + change streams over the preload
  main/LunicleBoardView.kt            ← a board node's board + sync indicator (rows, pills, CSS)
  main/PrivacyDialog.kt               ← Configure privacy dialog + top-bar mode chip
  main/McpBridge.kt                   ← MCP requests from the main process → McpServer
  main/NewsUpdates.kt                 ← News & updates bell + dialog, Electron store/fetch
  main/space/SpaceMode.kt             ← 3D mode: enter / leave, views, strip + dock, backdrop, render loop
  main/space/PageSpaceView.kt         ← one window's view: live page, previews, flights, threads
  main/space/GroveView.kt             ← Grove: one window's view over the whole vault (slabs, turning camera)
  main/space/SpaceWindowView.kt       ← what SpaceMode needs of a Pages or Grove view
  main/space/PageFlight.kt            ← free flight in Pages and Grove: keys, page ahead, legend
  main/space/SpacePageHead.kt         ← a page card's header in Pages and Grove: arrows + breadcrumb
  main/space/MapView.kt               ← Crown / Cone / Galaxy: bodies, links, labels, window cards
  main/space/MapLegend.kt             ← the maps' keyboard legend (MAP / FREE FLIGHT, K hides)
  main/space/FreeFlight.kt            ← free flight: Lunamux's ship model and keys
  main/space/SpaceHelp.kt             ← 3D mode's help dialog: every view explained
  main/space/three/ThreeLib.kt        ← three.js + CSS3DRenderer externals, loaded lazily

electron-main/src/jsMain/.../electron/
  ElectronMain.kt                     ← main process: window, IPC file access
  RunPaths.kt                         ← LUNARBOR_VAULT / LUNARBOR_LOCAL_DATA
  WindowBounds.kt                     ← remembered window size / position, fitted to the displays
  VaultWatcher.kt                     ← vault fs.watch, self-write filter
  VaultFilePath.kt                    ← vault-confined paths for openPath
  VaultBackup.kt                      ← backups: settings, schedule, write gate
  ZipWriter.kt                        ← folder → .zip (deflate, ZIP64)
  McpHttpServer.kt                    ← MCP endpoint: localhost, key, relay to renderer
  LunicleHost.kt                      ← Lunicle connections (lunarbor-lunicle.json), API relay, SSE change streams
  NewsHost.kt                         ← app version for the renderer, lunarbor-news.json, external links
  FsWatchdog.kt                       ← notices a stuck libuv thread pool, offers a restart

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

