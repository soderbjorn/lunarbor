# Insert Link — Title-Path Markdown Links

## Context

TreeFacts currently produces only one kind of markdown link: the auto-emitted promoted-ref bullet `[Title](Path/File.md#treefacts)`. There is no way for the user to *manually* insert a link to another note or to a specific bullet anywhere in the vault.

We want to add a user-driven "Insert Link" feature with the following properties:

- One unified link concept — a target is *anything that has a title*: a file, an inline bullet, or a bullet inside a child (promoted) file. The user does not care which.
- Title-based resolution. `LineId` is in-memory only (regenerated on every load — see `Document.kt:487`), so it cannot appear in a persisted URL.
- Relative resolution by default: typing `Foo` finds a child / sibling / nearest match, just like a wiki-style soft link.
- Absolute resolution when the user provides a leading `/`: `/Recipes/Pasta` resolves from the vault root regardless of where the link sits.
- A new `Insert Link` command in the existing command palette opens a modal with a search-as-you-type input, full-vault scope, and up/down/enter keyboard navigation. Enter inserts a markdown link at the cursor.
- Clicking a rendered link in the editor navigates to the target.

The vault tree can be large; the search index is in-memory and built lazily. Open files (those held by `DocumentRegistry`) are read live from `Document.lines`, bypassing the cache entirely — so the constant autosave loop never thrashes the cache. Closed files are parsed once on first lookup and re-parsed only when their mtime changes.

## URL syntax

A TreeFacts link's URL is **a title path in the unified outline tree**, never a file path. Bullets can be promoted to their own files or demoted back to inline at any time; tying the link to whichever file currently hosts the target would break the link the moment the target is reshaped. The outline tree is the stable address space.

URLs are encoded as a fragment-only link so that other CommonMark tools degrade gracefully (a fragment-only link stays on the current page, no broken file-open attempts):

```
[Display label](#treefacts-bullet=<title-path>)
```

| Target | URL the link carries |
|---|---|
| Sibling bullet `Pasta` | `#treefacts-bullet=Pasta` |
| Direct child of current bullet | `#treefacts-bullet=Sub/Pasta` (`Sub` = current bullet's own title) |
| Anywhere in the vault | `#treefacts-bullet=/Recipes/Dinner/Pasta` |

Conventions:

- Title-path segments are `/`-separated. Percent-encode `/`, `#`, `%` inside titles. Spaces stay as `%20` (CommonMark also allows wrapping the URL in `<…>`; `formatLinkUrl` already handles this).
- Leading `/` ⇒ **absolute**: walk from the vault root (root's children = top-level files).
- No leading `/` ⇒ **relative**: walk from the *parent* of the bullet containing the cursor (its children include the cursor's siblings — matching the user's "look among sibling documents or bullets"). To name a child of the current bullet, the path includes the current bullet's title as its first segment.
- `..` segments at the start of a relative path walk one level up before consuming further segments (standard URL semantics).
- "File" is not a special concept in the URL — a "file link" is just a title path that lands on a node that happens to be a top-level file. Promotion / demotion don't change the title path, so the link survives both.

The existing promoted-ref `*.md#treefacts` form is **untouched** — it is auto-emitted by the save pipeline and uses a different fragment (`#treefacts`, no `=`), so detection is unambiguous: `parseRef` checks `endsWith("#treefacts")`, our new code checks `contains("#treefacts-bullet=")`.

### Compatibility with external tools

We accept reduced compatibility in exchange for the promotion/demotion stability the user asked for:

- TreeFacts-emitted links are fragment-only in other tools → they stay on the current page, no scroll target. Acceptable degradation.
- Plain markdown file links (`[Foo](path/to/Foo.md)`) hand-authored or written by other tools still **work as click targets in TreeFacts** (the click handler resolves them as file targets via `NoteRepository`'s existing path-walking convention). We just don't emit that form ourselves, because we want our own links to survive promotion/demotion.

## Resolution semantics

The vault has a unified logical tree:

- Roots = top-level files in the vault (their title = the file's `<Name>` from `<Name>/<Name>.md`).
- A file's children = the bullets in its content (parsed with `DocumentLayout.bulletAsteriskColumn` + `SubtreeCodec.titleOf`).
- A bullet that is a promoted-ref (`[Title](File.md#treefacts)`) has its children replaced by the bullets of the child file (recursively). The bullet's own title is the link label.

Resolution is **deterministic** — the URL's title-path is walked literally, no fuzzy retry. Fuzzy/global search lives only in the modal at insertion time.

1. Parse the URL with `SubtreeCodec.parseAnyLinkBullet`. Pass the raw URL through `LinkUrl.parse`:
   - If it has the `#treefacts-bullet=<title-path>` shape, decode into segments + isAbsolute (leading `/`).
   - If it's a plain `path/to/File.md` URL (no `#treefacts-bullet=`), treat it as a legacy/external file link and resolve via the existing path-walking convention. Skip step 2.
   - Otherwise (plain `#section`, `https://...`, etc.) → not ours; the view layer falls back to default browser behavior (open URL in new tab) or no-op.
2. Determine the **start scope**:
   - Absolute → vault root (its children = top-level files of the vault).
   - Relative → parent of the bullet containing the cursor.
3. Apply `..` segments by walking up.
4. Apply each remaining segment as a **direct-child title match** (case-insensitive, exact) at the current node. Promoted-ref boundaries are transparent — the children of a promoted-ref bullet are the top-level bullets of the linked file (loaded lazily through `NoteRepository` if no `Document` is open for it).
5. If any step has no matching child, return `Resolution.NotFound` (view layer shows a non-fatal "link target not found" message; the link text stays in the document).
6. On success, the resolved node carries a `(fileRel, titlePathWithinFile)` pair. Return:
   - `Resolution.File(fileRel)` — target is a file root, no in-file bullet.
   - `Resolution.Bullet(fileRel, titlePathWithinFile)` — target is a bullet inside a file. The view layer walks `titlePathWithinFile` inside the freshly-loaded document to translate it into a live `LineId`, then calls the existing `zoomTo(lineId)`.

Ambiguity (two siblings sharing a title) is resolved by document order — first match wins. The Insert Link modal avoids producing ambiguous URLs by lengthening the path until uniqueness holds.

### What the modal embeds (insertion-time path choice)

Modal search is **global, fuzzy, across the whole vault** — backed by the in-memory `VaultIndex`. Once the user picks a target, the modal computes the URL by:

1. Try the shortest **relative** title-path (from the cursor's parent scope) that walks unambiguously to the target. May include `..` segments.
2. If no relative form within a small budget (say 3 `..` levels) is unique, emit the **absolute** form `/A/B/C`.
3. Format with `LinkUrl.format` and prepend `#treefacts-bullet=`.

The modal never embeds a file path — only title paths. This is the whole point: links survive when the user later promotes or demotes any node in the path.

## Components and files

### New (commonMain)

- **`client/src/commonMain/kotlin/se/soderbjorn/treefacts/data/LinkUrl.kt`** — pure codec for the title-path URL form.
  - `data class LinkUrl(val segments: List<String>, val isAbsolute: Boolean)` — `..` is a regular segment.
  - `parse(url: String): LinkUrl?` — accepts only `#treefacts-bullet=<title-path>`; returns null otherwise.
  - `format(segments: List<String>, isAbsolute: Boolean): String` — emits `#treefacts-bullet=…` with `/` separators, percent-encoding `/`, `#`, `%` inside each title segment.
  - `TREEFACTS_BULLET_PREFIX = "#treefacts-bullet="`.
  - Plain markdown file links (no `treefacts-bullet=` fragment) are handled separately in the resolver — they don't go through `LinkUrl`.

- **`client/src/commonMain/kotlin/se/soderbjorn/treefacts/data/VaultIndex.kt`** — app-scoped, lazy outline index.
  - Owns a `CoroutineScope` (passed in by `DocumentRegistry`).
  - Stores per-file parsed entries: `data class FileEntry(val fileRel: String, val rootTitle: String, val bullets: List<BulletNode>)`.
  - Public API:
    - `suspend fun search(query: String, max: Int = 50): List<SearchHit>` — fuzzy ranked title matches across the whole outline tree (files + bullets, transparent across promoted-ref boundaries). Each hit carries its full vault title path, the `fileRel` it currently lives in, and the title-path-within-that-file.
    - `suspend fun resolve(url: LinkUrl, fromFileRel: String, cursorTitlePath: List<String>): Resolution` — runs the deterministic walk over the unified outline tree. Loads file entries lazily as the walk crosses promoted-ref boundaries.
    - `suspend fun resolveLegacyFilePath(url: String, fromFileRel: String): String?` — for plain `*.md` links written by other tools.
    - `suspend fun shortestUrlFor(target: SearchHit, fromFileRel: String, cursorTitlePath: List<String>): LinkUrl` — the path the modal embeds. Tries relative (with up to ~3 `..`); falls back to absolute.
  - **Cache strategy** — designed so the constant autosave loop does NOT trigger constant invalidation:
    - For files with an open `Document` in `DocumentRegistry`: bypass the cache entirely. Read the live `Document.lines` directly — it's already in memory, parsing it is a fast in-RAM scan, and saves are no-ops for the index because the index never *had* a stale snapshot to flush.
    - For files with no open `Document`: parse on first lookup, store in `cache: Map<fileRel, FileEntry>`. Stays cached until either:
      - The file's last-known-mtime check at lookup time disagrees with disk (we already touch the file when reading; the platform `FileSystem` exposes mtime cheaply), OR
      - The file becomes open as a `Document` (we drop the cached entry — the live document is now the source).
    - There is no global "invalidate everything" path. Saves on the active file flow through the bypass and never touch the cache.
    - Cross-file dependencies (a parent file whose promoted-ref points at a child file): each file is cached independently; the resolver/walker reaches into the appropriate per-file source (live or cached) at walk time. So saving a child file never invalidates the parent's cache because the parent's cached entry doesn't *contain* the child's bullets — it just contains the promoted-ref bullet, which the walker follows on demand.

- Add VM intents in **`client/src/commonMain/kotlin/se/soderbjorn/treefacts/main/PaneBackingViewModel.kt`**:
  - `fun insertMarkdownLink(label: String, url: String)` — composes `deleteSelectionIfAny` then `document.insertText` of `"[" + escape(label) + "](" + url + ")"`. Reuses `SubtreeCodec`'s label-escape helper (extract to internal API or copy as private helper — extraction is preferable, one new internal `fun escapeLinkLabel`).
  - `fun navigateToLink(url: String)` — calls `LinkUrl.parse`, then `vaultIndex.resolve(...)`. Routes to `navigateToVaultFile(fileRel)` and, after the new document loads, calls `zoomToTitlePath(titlePath)` (new helper that walks the freshly loaded document's bullets to find the matching `LineId`, then calls existing `zoomTo(lineId)`).
  - Helper `currentTitlePath(): List<String>` — derives the path from `activeFileRel` + the bullet containing the cursor (use `DocumentLayout` to walk from the cursor row up by indent).

- Wire `VaultIndex` into **`DocumentRegistry`** so it's a single shared instance; `JsAppGraph` exposes it for the modal.

### New (web/jsMain)

- **`web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/InsertLinkModal.kt`** — DOM modal. Pattern lifted from `CommandPalette.kt:44+` (input + filtered list + arrow keys + enter), not from `StarredModal` (which is heavier, owns a doc-VM trio — we don't need that since `VaultIndex` is the data source).
  - Constructor takes the active `MainViewModel` so it can call `searchLinkTargets`, `currentTitlePath`, `insertMarkdownLink`.
  - Each result row shows: title (bold), breadcrumb (muted), e.g. `Pasta · Recipes / Dinner`.
  - Enter inserts `[title](url)` where `url = vaultIndex.shortestPathFor(...)`. Selection in the editor before opening becomes the label; otherwise the title is the label.
  - Esc / click-outside closes.

### Modified

- **`web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/AppShell.kt`** — add `CommandPalette.Command("insert-link", "Insert Link", run = { insertLinkModal.open() })` to `provideCommands` (file already has multiple `CommandPalette.Command(...)` blocks at lines 387–461).

- **`web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/MainScreen.kt`** — add a click listener on `[data-href]` spans inside the editor surface (`OutlinePaintLoop.kt:308–309` already emits the attribute). Read `data-href`, dispatch `mainViewModel.navigateToLink(href)`.

- **`web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/MainViewModel.kt`** — one-line delegations for `insertMarkdownLink`, `navigateToLink`, `searchLinkTargets`, `currentTitlePath`.

- **`web/src/jsMain/kotlin/se/soderbjorn/treefacts/di/JsAppGraph.kt`** — `@Provides` for `VaultIndex`, constructed with the registry's repository and scope.

### Tests (commonTest)

- `LinkUrlTest.kt` — round-trip parse/format, escape edge cases (titles with `/`, `#`, `%`, spaces, parens).
- `VaultIndexTest.kt` — using the existing in-memory `FakeFileSystem` pattern (or the test repo plumbing already used by `NoteRepository` tests):
  - Resolves an absolute title path through inline + promoted-file boundaries transparently.
  - Resolves a relative title path (with the cursor placed in a deep bullet) using parent-of-cursor as the start scope.
  - Walks `..` segments correctly.
  - Picks the first match by document order on title collision.
  - **Promotion stability**: a link written when the target was inline still resolves after the target is promoted to its own file. A link written when the target was a file still resolves after demotion back to inline. (This is the headline test for the title-path-only URL design.)
  - `shortestUrlFor` produces a single-segment relative URL when unique; lengthens to absolute when not.
  - Plain `*.md` URLs (no fragment) resolve via `resolveLegacyFilePath` to the right `fileRel`.
  - **Cache invariants**: opening a file as a `Document` drops its cache entry; saving the active file does not touch the cache; lookups for files without an open Document populate the cache exactly once until mtime changes.

## Implementation order

1. `LinkUrl` codec + tests.
2. `VaultIndex`: lazy build, search, resolve, shortest-path. Hook into `DocumentRegistry`. Tests.
3. `PaneBackingViewModel` intents (`currentTitlePath`, `insertMarkdownLink`, `navigateToLink`, the title-path → LineId helper inside the doc).
4. `MainViewModel` delegations + `JsAppGraph` binding.
5. `InsertLinkModal` DOM + keyboard handling.
6. Wire `Insert Link` command in `AppShell`.
7. Editor click handler for `[data-href]` spans in `MainScreen`.
8. Manual smoke + `./gradlew :client:allTests :web:jsBrowserTest`.

## Verification

- Unit: `./gradlew :client:allTests` covers the codec + index.
- Manual on web (`./gradlew :web:jsBrowserDevelopmentRun`):
  1. Open a note with several siblings; place cursor in one bullet. Cmd-P → `Insert Link` → type a sibling's title → Enter inserts `[Sibling](#treefacts-bullet=Sibling)`. Click the link → pane navigates / zooms to that bullet.
  2. Insert a link to a deeply-nested bullet inside a different top-level file via absolute path. Click → pane switches files and zooms.
  3. Insert a link to a bullet inside a *folded* promoted-child file. Click → child file loads, pane navigates and zooms.
  4. Save the file, reload (refresh browser). Reopen the note — link still resolves (no LineIds involved).
  5. Edit the target bullet's title. Old link no longer resolves; modal/click surfaces "not found" gracefully (toast or no-op).
  6. **Promotion/demotion stability**: insert a link to an inline bullet `Foo`. Promote `Foo` to its own file (existing promotion intent). Click the link — still resolves. Demote it back. Click — still resolves.
  7. External-tool round-trip: open the file in Obsidian / VS Code. TreeFacts links appear as fragment-only links (`#treefacts-bullet=...`); other tools stay on the page (degraded but harmless). Plain `*.md` links written by Obsidian still work as click targets in TreeFacts.
  8. Cache sanity: type continuously in a file with autosave running. Confirm via logging that `VaultIndex` is *not* parsing the active file repeatedly — the live `Document` bypass keeps it idle. Close the pane and search via the modal — the file is parsed once and cached.
