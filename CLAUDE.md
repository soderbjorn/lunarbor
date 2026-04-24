# Notegrow — Architectural Guidelines

Notegrow is a Kotlin Multiplatform project. It follows a strict layered architecture modeled after the `framnafolk` and `almedalen` codebases. Keep the layers tight; don't blur responsibilities.

## The layers (outside → in)

```
View                     (per-platform — DOM, Compose, UIKit, …)
  ↓ intent calls
ViewModel                (per-platform — thin facade over the view backing VM)
  ↓ delegates
DocumentViewBackingViewModel   (commonMain — one viewer's cursor + selection)
  ↓ primitive edits / observes state
DocumentBackingViewModel       (commonMain — the canonical document + autosave)
  ↓
NoteRepository                 (commonMain — file I/O, plain text)
  ↓
FileSystem (expect/actual)     (per-platform — Node fs on JS, java.io on Android, …)
```

### 1. View (per-platform)

For web: `web/src/jsMain/.../MainScreen.kt`. Renders state; translates user input into intent calls on the `MainViewModel`. Holds only ephemeral UI state (e.g. `isDragging`, scroll offset, auto-scroll timer handle). No business rules, no document logic.

### 2. ViewModel (per-platform — thin facade)

For web: `web/src/jsMain/.../MainViewModel.kt`.

Structure:
- Receives its `DocumentViewBackingViewModel` via constructor (Metro-injected).
- Exposes a single `val stateFlow: StateFlow<State>` where `data class State(val backingState: DocumentViewBackingViewModel.State? = null)` — the envelope wraps the backing state so each platform can tack on its own fields without touching common code.
- `init` collects the backing state flow and re-emits it through the envelope.
- Every intent method is a one-line delegation. Do NOT re-implement logic here.
- Only extra code allowed: platform-specific glue that cannot exist in commonMain (e.g. Android `SignInManager.signOut()` before delegating).

### 3. DocumentViewBackingViewModel (common)

`client/src/commonMain/.../DocumentViewBackingViewModel.kt`. One instance per viewer (window / device / pane). Responsibilities:

- Owns view-local state: `cursorRow`, `cursorCol`, `anchorRow?`, `anchorCol?`. Mirrors `DocumentBackingViewModel.State` through its collector so every emission is a consistent snapshot of both document + cursor.
- Implements all selection-aware editor intents: `insertChar`, `insertNewline`, `backspace`, `moveLeft(extend)`, `selectAll`, `selectWord`, `indentLine`, `onCutRequested`, …
- Composes primitive edits: typing first calls `deleteSelectionIfAny`, then `documentBackingViewModel.insertText(row, col, …)`, then updates its own cursor. Use the private `patch { }` helper so every emission carries a fresh `documentState` snapshot (avoids stale mirrors while the async collector catches up).
- Exposes the normalized selection via `DocumentViewBackingViewModel.selectionOf(state)` (companion) so views don't re-implement the anchor/cursor sort.
- No DOM, Android UI, or UIKit imports. commonMain only.

### 4. DocumentBackingViewModel (common)

`client/src/commonMain/.../DocumentBackingViewModel.kt`. The canonical, persisted document. Responsibilities:

- Owns `lines: List<String>` and `isLoaded: Boolean`.
- Exposes primitive edits only: `insertText(row, col, text) → InsertResult`, `insertNewline(row, col)`, `delete(startRow, startCol, endRow, endCol)`. No cursor concept, no selection concept, no editor policy.
- Owns the `NoteRepository` and the autosave loop.
- Single source of truth for document content — any future second viewer (another window, a collab peer) subscribes to the same flow.

## Dependency injection (Metro)

The project uses **Metro** (`dev.zacsweers.metro`) for DI, applied **per-platform**. commonMain code has no DI annotations — it's plain classes with constructor parameters, which keeps it portable.

Version is pinned in `gradle/libs.versions.toml` under the `metro` version ref and the `metro` plugin entry. The `:web` module applies the plugin; `:client` does not (no codegen needed in commonMain since no annotations live there).

### JS graph (`web/src/jsMain/.../di/JsAppGraph.kt`)

```kotlin
object AppScope

@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val mainViewModel: MainViewModel
    val coroutineScope: CoroutineScope

    @SingleIn(AppScope::class) @Provides
    fun provideCoroutineScope(): CoroutineScope = GlobalScope
    // … @Provides for FileSystem, NoteRepository, DocumentBackingViewModel,
    //   DocumentViewBackingViewModel, MainViewModel
}

fun createJsAppGraph(): JsAppGraph = createGraph<JsAppGraph>()
```

Rules:
- **Interface-based graph** annotated `@DependencyGraph` + `@SingleIn(AppScope::class)`.
- Exposed bindings as interface properties (`val mainViewModel: MainViewModel`).
- Every binding is declared with an `@Provides` method. Every `@Provides` is `@SingleIn(AppScope::class)` because the whole graph is app-scoped.
- commonMain classes stay annotation-free; they're constructed inside `@Provides` methods. This matches the `almedalen` pattern and keeps Metro out of commonMain.
- `Main.kt` calls `createJsAppGraph()` once and pulls `mainViewModel` + `coroutineScope` out.

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

Mirror the JS graph with a platform-specific scope (`AndroidAppScope`, `IosAppScope`) and platform-specific `@Provides` — e.g. `FileSystem(context)` on Android, plain `FileSystem()` on iOS. The common `DocumentBackingViewModel` and `DocumentViewBackingViewModel` bindings are identical across platforms.

## State and intents

- **One state per VM.** Add fields; don't introduce parallel flows.
- **Envelope at the outer VM.** `MainViewModel.State.backingState` gives each platform a seam for platform-specific fields without touching common code.
- **Name intents after the action**: `onSignOutTapped`, `selectWord(row, col)`, `insertNewline`. Not `_setLines(...)`.
- **Cursor and selection are view state, not document state.** They live in `DocumentViewBackingViewModel`. `DocumentBackingViewModel` knows nothing about them.
- **Selection-aware writes compose in the view VM.** Typing first deletes the selection, then inserts. The view VM owns this composition; the document VM only exposes the primitives.

## Where things live

```
client/src/commonMain/.../main/
  DocumentBackingViewModel.kt         ← content + persistence
  DocumentViewBackingViewModel.kt     ← cursor + selection + editor intents
  DocumentLayout.kt                   ← pure layout helpers (wrap, hit-test)

client/src/commonMain/.../data/
  NoteRepository.kt                   ← plain-text I/O

client/src/*Main/.../platform/
  FileSystem.kt                       ← expect/actual

web/src/jsMain/.../
  Main.kt                             ← entry, creates graph
  di/JsAppGraph.kt                    ← Metro DI graph
  main/MainViewModel.kt               ← thin facade
  main/MainScreen.kt                  ← DOM rendering + event handling
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

Every top-level class, object, interface, and non-trivial function gets KDoc. "Non-trivial" excludes one-line delegation methods on facade classes, but even those should at least reference the underlying function (e.g. `/** See DocumentViewBackingViewModel.moveLeft. */`). Private helpers inside a class that are genuinely one-liners with obvious names can be left undocumented; anything with real logic should have KDoc.

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

1. Decide whether it's a *document* operation (content change that would matter to any viewer) or a *view* operation (cursor, selection, UI-local behavior).
2. If document: add a primitive on `DocumentBackingViewModel`.
3. Build the user-facing intent on `DocumentViewBackingViewModel`, composing document primitives and local cursor updates via `patch { }`.
4. Add a one-line delegation to `MainViewModel`.
5. Wire the input mapping in the platform view.
6. If a new binding is required, add an `@Provides` in `JsAppGraph` (and future platform graphs).

