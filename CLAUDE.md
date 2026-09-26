# TreeFacts — Architectural Guidelines

TreeFacts is a Kotlin Multiplatform project. It follows a strict layered architecture modeled after the `framnafolk` and `almedalen` codebases. Keep the layers tight; don't blur responsibilities.

## The layers (outside → in)

```
View                  (per-platform — DOM, Compose, UIKit, …)
  ↓ intent calls
MainViewModel         (per-platform — thin facade over the pane backing VM)
  ↓ delegates
PaneBackingViewModel  (commonMain — one pane's cursor, selection, zoom,
                       file/zoom history, undo/redo, fold state, etc.)
  ↓ primitive edits / observes state
Document              (commonMain — one loaded file + its autosave loop)
  ↑ acquired/released through
DocumentRegistry      (commonMain — fileRel → Document, refcounted)
  ↓
NoteRepository        (commonMain — file I/O, plain text)
  ↓
FileSystem (expect/actual)  (per-platform — Node fs on JS, java.io on Android, …)
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

`client/src/commonMain/.../Document.kt`. One instance per loaded file. Responsibilities:

- Owns `lines: List<String>`, `lineIds: List<LineId>`, `unloadedRefIds: Set<LineId>`, `isLoaded: Boolean`, plus the in-memory `promotedSubtrees` map for that file.
- Exposes primitive edits only: `insertText(row, col, text) → InsertResult`, `insertNewline(row, col)`, `delete(startRow, startCol, endRow, endCol)`, `replaceContent(...)`, `expandSubtree(id)`, `collapseSubtree(id)`. No cursor concept, no selection concept, no editor policy, no file-switching concept.
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
- **Every outline line is a bullet.** In a `.treefacts` document (`Document.bulletsOnly`) no intent may produce a non-bullet line: Enter on an empty bullet outdents it or opens another bullet, Backspace merges or deletes, paste makes one bullet per line (`bulletLinesForPaste`). Free-form content goes in blocks. Plain `.md` files keep plain-line editing — that flag is the switch for Markdown mode.
- **Blocks are the only other outline line.** A block (bordered free Markdown among the bullets) is one row per content line in `Document.lines`, each `<indent><marker><content>` with a hidden private-use marker (`BlockLayout.FIRST` opens a block, `BlockLayout.NEXT` continues it). The marker keeps every context-free helper from mistaking a `* item` inside a block for a bullet; the `:::` fences exist only on disk. A block nests under the preceding bullet by indent, so a block under a leaf makes it folder-backed. Inside a block Enter adds a row, Backspace joins rows (an empty block is deleted), paste is verbatim, Tab moves the whole block, Cmd-Enter / Escape leave it onto a new bullet. Insert / Delete block are palette commands; deletion is undoable.
- **Selection-aware writes compose in the pane VM.** Typing first deletes the selection, then inserts. The pane VM owns this composition; `Document` only exposes the primitives.

## On-disk format

One folder per parent bullet. A bullet is backed by a folder if and only if it has content: child bullets, blocks, or files in that folder. Every node folder holds one hidden outline file, `.treefacts`, listing only that node's **direct** children (no indentation). The vault root is the root node (`<vault>/.treefacts`); top-level folders act as separate areas.

```
* Buy oat milk
+ [Recipes](Recipes)
* Trip to **Lisbon**
:::
**Packing**: passport, charger, adapter
:::
```

- `* text` — a leaf bullet; the text is inline Markdown.
- `+ [title](folder)` — a folder-backed bullet; the title keeps its formatting, the folder name is stored explicitly (relative to the file's folder).
- `:::` … `:::` — a block (a longer fence when the content contains a `:::` line).
- **Folder names** (`data/FolderName.kt`): the title's plain text, percent-encoded where unsafe (`/ \ : * ? " < > |`, `%`, a leading dot, trailing dots/spaces, control characters), capped at 120 bytes; sibling collisions are case-insensitive and get ` (2)`, ` (3)`, …; an empty title is `Untitled`.
- **Save rules** (`NoteRepository.save`, run 1 s after the last edit and at most 5 s apart): a leaf that gets its first child becomes a folder; a folder whose last child is removed is deleted unless it still holds files; title edits rename the folder; moves (indent, outdent, drag, cut and paste) `rename` the folder so attachments travel; a deleted folder-backed bullet's folder goes to `<vault>/.trash/<timestamp> <name>/` (undo in the same session moves it back; the trash is never emptied automatically).
- **Parser/codec**: `client/src/commonMain/.../data/SubtreeCodec.kt` parses and emits the outline format; `NoteRepository.kt` is the only thing that touches `FileSystem`. Files that are not `.treefacts` outlines (`Starred.md`, other `.md` notes) are read and written as plain lines.

## Folder contents list

Under the bullets of the node a pane is showing (the zoom target, or the root of the open outline) the web view draws everything in that node's folder that is not already a bullet (`web/.../main/FolderContentsList.kt`). The rules live in commonMain (`main/FolderContents.kt`, tested in `FolderContentsTest`):

- **Shown:** `.md` notes, images, other files, foreign subfolders, TreeFacts folders no bullet references. **Hidden:** folders a `+` line of the node's own outline points at (`VaultEntry.isReferenced`, set by `NoteRepository.listVaultLevel`), dotfiles (including `.treefacts`) and `.trash`.
- **Order:** folders first, then files, each group by `FolderContents.naturalCompare` (case-insensitive, `Note 2` before `Note 10`).
- **Which folder:** `PaneBackingViewModel.currentNodeFolder` — the zoomed bullet's folder, the open outline's folder, or none (a zoomed leaf, a `.md` note).
- An expanded folder-backed bullet carries a count badge (`FolderContents.badgeLabel`, e.g. `2 folders, 3 files`) computed from the same list. Clicking a folder row opens it as a node (`openFolderAsNode`); "New Markdown file" in the palette creates `Untitled.md`, `Untitled 2.md`, … in the current folder and opens it.

## Zoom navigation

`PaneBackingViewModel` keeps three zoom-related fields:

- `zoomedLineId: LineId?` — the bullet whose subtree is currently shown, or `null` for the root view. Stored as a stable id so it survives edits above the target.
- `zoomHistory: List<LineId?>` — browser-style back stack. Every zoom-changing intent (`zoomInto`, `zoomTo`, `zoomOut`) pushes the current target before changing, and clears the forward stack.
- `zoomForward: List<LineId?>` — populated by `zoomBack` and consumed by `zoomForward`. Capped at 50 entries per direction.

User-facing affordances on the web: the pane toolbar shows a back-arrow and forward-arrow whenever the corresponding stack is non-empty, plus the existing `up` (zoom one level out) and `home` (clear zoom) buttons. Keyboard shortcuts are `Option-Cmd-Left` (back), `Option-Cmd-Right` (forward), `Option-Cmd-Up` (zoom out one level), and `Escape` (clear zoom). Clicking the bullet dot of a folder-backed bullet navigates into it via the existing `zoomInto` intent — the lazy-load on a folded ref + history push gives a "click to open this page" UX without any link-specific click handling.

## Where things live

```
client/src/commonMain/.../main/
  Document.kt                         ← one loaded file: content + autosave
  DocumentRegistry.kt                 ← fileRel → Document, refcounted
  PaneBackingViewModel.kt             ← per-pane state + editor intents
  TextEditingViewModel.kt             ← typing/movement/selection slice
  ZoomNavigation.kt                   ← zoom in/out/back/forward slice
  MarkdownStyleViewModel.kt           ← inline + line-level markdown slice
  SelectionHelper.kt                  ← pure helpers (selection, breadcrumb)
  DocumentLayout.kt                   ← pure layout helpers (visible rows, hit-test)
  BlockLayout.kt                      ← block rows: markers, block ranges
  FolderContents.kt                   ← folder contents list: filter, order, badge

client/src/commonMain/.../data/
  NoteRepository.kt                   ← vault I/O + folder-per-bullet save rules
  SubtreeCodec.kt                     ← `.treefacts` outline codec
  FolderName.kt                       ← title → folder name encoding

client/src/*Main/.../platform/
  FileSystem.kt                       ← interface; per-platform PlatformFileSystem

web/src/jsMain/.../
  Main.kt                             ← entry, creates graph
  di/JsAppGraph.kt                    ← Metro DI graph
  main/MainViewModel.kt               ← thin facade (one per pane)
  main/MainScreen.kt                  ← DOM rendering + event handling
  main/FolderContentsList.kt          ← folder contents list under the bullets
  main/AppShell.kt                    ← per-pane VM construction + lifecycle
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

