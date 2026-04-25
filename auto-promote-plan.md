# Auto-promote/demote subtrees to separate files

## Context

Today the entire outline is persisted as a single plain-text file (`root.nogr`), loaded whole by `NoteRepository` and edited as `lines: List<String>` in `DocumentBackingViewModel`. The user wants large subtrees automatically spun out into their own files so a) individual files stay browsable in external editors and b) the on-disk tree roughly reflects the outline's structure — without the user having to manage it manually. Demotion (inlining a shrunken subtree back) must also be automatic. **No manual user controls** — the system decides entirely on its own based on heuristics; there are no shortcuts, intents, or UI nudges to override the policy.

The **key design decision**: splitting is a **persistence concern only**. The in-memory document remains a single flat, fully-composed `lines` list — identical to today. `DocumentBackingViewModel`, `DocumentViewBackingViewModel`, cursor, selection, zoom, autosave — none of them learn anything new. The load path recomposes child files into one flat outline; the save path splits it back out according to heuristics. This keeps the common VM layer unchanged and cursor/selection invariants untouched.

## On-disk format

The directory tree mirrors the outline tree. There is **no wrapper folder** — `root.nogr` sits alongside the directories of its promoted children. Each promoted bullet creates a sibling `<Title>/` directory containing `<Title>.nogr` plus, recursively, the directories of its further-promoted descendants.

```
notegrow-db/
    root.nogr
    Shopping list/
        Shopping list.nogr
    Projects/
        Projects.nogr
        Pancake recipe/
            Pancake recipe.nogr
        2026 plans/
            2026 plans.nogr
```

A promoted bullet keeps its title in the parent file and gains a trailing reference. The reference path is **relative to the parent file's directory** and always has the form `<Title>/<Title>.nogr`:

```
* Shopping list  [[Shopping list/Shopping list.nogr]]
```

This convention is symmetric for every file (root included): a file at `<dir>/<X>.nogr` finds its promoted children at `<dir>/<ChildTitle>/<ChildTitle>.nogr`.

Naming rules:

- **Filename = bullet title verbatim**, with only filesystem-illegal characters substituted (`/` and `NUL` → `-`, leading dots stripped so the file isn't hidden, runs of whitespace collapsed). Case, spaces, and non-ASCII are preserved. Names are capped to ~200 UTF-8 bytes to leave headroom for the `.nogr` extension. Empty after sanitisation falls back to `untitled`.
- **Collision scope is per-directory**: two siblings under the same parent file with the same title get ` 2`, ` 3`, … appended (Finder-style). Two cousins in different directories don't collide and never need disambiguation.
- The pair of `<Title>.nogr` and `<Title>/` always travel together — both rename when the bullet is renamed (see [Stable identity](#stable-identity-and-live-renames)).
- The reference marker is a `[[path]]` token preceded by two spaces at end-of-line; parsing is a simple regex on the trailing segment.
- Child files are plain outlines themselves and recurse once they grow.

## Heuristics (auto-decision)

On each autosave tick, walk the composed outline and for each bullet subtree compute `descendantCount`:

- **Promote** if `descendantCount >= PROMOTE_MIN` AND `depth >= 1` AND `depth <= MAX_DEPTH_TO_PROMOTE` AND not already promoted.
- **Demote** (inline back) if an already-promoted subtree drops to `descendantCount <= DEMOTE_MAX`.
- Hysteresis gap between promote and demote prevents flapping around a single value.
- Root never promotes (it's already a file).

### Where to cut — threshold reasoning

Calibrated against the user's own Dynalist corpus (`/Users/soderbjorn/Downloads/dynalist-backup-opml-2023-02-04`, 11 OPML files, **12,109 bullets** total). Analysis script: `/tmp/analyze_dynalist.py`. Key shape of that corpus:

- Heavy tail. **76% of bullets are leaves** (0 descendants); 95% have ≤ 11 descendants; p99 = 63; max = 2,951.
- Top-level bullets (depth 0) are the natural "chapters": only 90 of them across all docs, but p75 = 38 descendants, p90 = 356, p95 = 639. These are the obvious promotion candidates.
- Depth runs deep (max 20) but volume above depth 4 is mostly small subtrees — p99 at depth 4 is only 34 descendants.
- Document sizes range from 5 bullets (`Code Camp.opml`) to 7,417 (`Bontouch.opml`); promotion logic must work gracefully across that range.

How many subtrees would promote at each threshold (across the whole 12k-bullet corpus):

| `PROMOTE_MIN_DESCENDANTS` | subtrees promoted | % of bullets |
|---:|---:|---:|
| 20 | 367 | 3.03% |
| 30 | 256 | 2.11% |
| **40** | **200** | **1.65%** |
| 50 | 163 | 1.35% |
| 80 | 97 | 0.80% |
| 100 | 72 | 0.59% |

40 is the sweet spot: ~200 child files across the entire historical corpus (~18 per top-level doc on average), each genuinely chapter-sized. 20 would over-fragment; 80+ leaves obvious chapters un-promoted.

| Constant | Production value | Debug value | Why |
|---|---|---|---|
| `PROMOTE_MIN_DESCENDANTS` | **40** | **3** | Production: catches ~1.65% of bullets — corpus's natural chapters. Debug: triggers after typing a handful of bullets. |
| `DEMOTE_MAX_DESCENDANTS` | **15** | **1** | Production: 25-wide hysteresis below the promote line. In the corpus, only ~5–6% of bullets sit between 15 and 40 descendants, so this band is rarely crossed by typical edits. Debug: shrinks fast enough to verify demotion in a couple of deletes. |
| `MAX_DEPTH_TO_PROMOTE` | **4** | **4** | Beyond depth 4, p99 subtree size is ≤ 34 — most won't clear the threshold anyway, and the cap stops pathological deep-and-wide branches from spawning a tower of files. |
| `MIN_TITLE_LENGTH` | **1** | **1** | Don't promote an empty-title bullet — child filename would be a meaningless `untitled.nogr`. Skip until the user types a title. |
| Autosave tick | existing 5 s | existing 5 s | Don't promote faster than the user can type. |

The hysteresis gap is preserved in both modes — debug uses 3 vs 1 to stay quick to test without flapping.

### Debug flag

`PromotionPolicy` exposes a single `const val DEBUG: Boolean = true` (default **true** during initial development). All thresholds are returned via getters that branch on `DEBUG`:

```kotlin
object PromotionPolicy {
    const val DEBUG: Boolean = true

    val promoteMinDescendants: Int get() = if (DEBUG) 3 else 40
    val demoteMaxDescendants: Int get() = if (DEBUG) 1 else 15
    const val maxDepthToPromote: Int = 4
    const val minTitleLength: Int = 1

    fun shouldPromote(span: SubtreeSpan, alreadyPromoted: Boolean): Boolean
    fun shouldDemote(descendants: Int): Boolean
}
```

Flip `DEBUG` to `false` once the behaviour is validated. Single grep target for the inevitable "did we forget to ship with production thresholds?" check before release.

Secondary knobs worth considering once the primary ones settle (skip for v1):

- **Byte budget** (`PROMOTE_MIN_BYTES ≈ 1500`) as an alternative criterion — catches a subtree with few bullets but long paragraphs.
- **Cooldown** (`MIN_SECONDS_BETWEEN_DECISIONS = 30`): once a subtree is promoted or demoted, don't re-evaluate it for 30 s.
- **Stability window**: only promote when the subtree has been at/above the threshold for 2 consecutive autosave ticks.

All of this lives in one `PromotionPolicy` object so tuning is a one-file change and constants are greppable.

## Stable identity (and live renames)

`DocumentBackingViewModel` gains a side-map `promotedSubtrees: MutableMap<LineId, String>` where the value is the **directory path** (relative to the project root) that holds the promoted bullet's content — e.g. `Bontouch/Möten`. The corresponding `.nogr` file is always `<dir>/<basename>.nogr`. Map populated on load (by the resolver), consulted on save (by the composer). Not part of `State` — pure persistence metadata.

Stable identity is anchored in the `LineId`, not the filename. The filename is **derived from the current title on every save**, which makes renames live:

- User edits the parent bullet's title from "Shopping list" → "Groceries".
- Next autosave: the entry for that `LineId` is in `promotedSubtrees` → composer derives desired basename `Groceries` from the current title → notices the stored path's basename was `Shopping list` → renames both the directory (`<parent dir>/Shopping list/` → `<parent dir>/Groceries/`) and the inner file (`Groceries/Shopping list.nogr` → `Groceries/Groceries.nogr`). Two filesystem ops; either is straightforward atomic with the host's `moveFile`.
- The parent file's ref line is rewritten with the new path on the same save.
- If the desired basename collides with another sibling already taken in that directory, append ` 2`, ` 3`, … as on first promotion.
- Renaming an *ancestor* (e.g. a top-level file `Bontouch.nogr` → `Bontouch 2024.nogr`) is the same operation applied at that level: the directory `Bontouch/` becomes `Bontouch 2024/`, the inner file is renamed, and the ancestor's parent file (here `root.nogr`) gets its ref updated. Descendant files don't need touching — their refs are relative to their own directory and survive the dir rename unchanged.

This means **deletion is no longer the cleanup path on rename** — `moveFile` is. Deletion only happens on demotion: remove `<dir>/<basename>.nogr`, then `<dir>/` if empty (it normally is, since further-promoted descendants would have demoted first or stayed promoted).

## New/changed components

### 1. `platform/FileSystem.kt` (expect + jsMain actual)

Add:
- `suspend fun deleteFile(path: String)` — used both on demotion and as part of moves on hosts without atomic rename.
- `suspend fun deleteDirectoryIfEmpty(path: String)` — to clean up the `<Title>/` directory shell after demotion when no further-promoted descendants remain.
- `suspend fun moveFile(from: String, to: String)` — for live rename of the inner `.nogr` file when its parent bullet's title changes.
- `suspend fun moveDirectory(from: String, to: String)` — for live rename of the surrounding `<Title>/` directory. Same call as `moveFile` on POSIX; the JS host bridge needs to expose both.
- `suspend fun listDirectory(path: String): List<String>` — for orphan detection on load and for collision checking when minting a sibling filename.

Existing `ensureDirectory`, `readFileIfExists`, `writeFile` stay.

### 2. `data/SubtreeCodec.kt` (new, commonMain)

Pure functions — no I/O, no state:

- `fun parseRef(line: String): SubtreeRef?` — returns `SubtreeRef(titleText, path)` or null.
- `fun formatRef(title: String, path: String, indent: Int): String` — renders the parent-side line.
- `fun findSubtrees(lines: List<String>): List<SubtreeSpan>` — returns `(rootRow, rootIndent, endRowInclusive, descendantCount)` for every bullet. Reuses the indent-walking shape of `DocumentViewBackingViewModel.subtreeEnd` (factor that helper out to a top-level function in `DocumentLayout.kt` so both callers share it — currently it's private to the view VM).
- `fun reindent(lines: List<String>, delta: Int): List<String>` — shifts indentation when extracting (child stores at indent 0) or inlining (restore to parent indent + 1).
- `fun safeFilename(title: String): String` — strips/replaces filesystem-illegal characters (`/`, NUL → `-`), removes leading dots, collapses whitespace runs, caps to 200 UTF-8 bytes, falls back to `untitled` on empty input. Preserves case, spaces, non-ASCII.
- `fun uniqueFilename(base: String, used: Set<String>): String` — returns `base` if free, else `"$base 2"`, `"$base 3"`, … until unused. Caller passes the current set of names already taken in the target directory.

### 3. `data/PromotionPolicy.kt` (new, commonMain)

```kotlin
object PromotionPolicy {
    const val PROMOTE_MIN_DESCENDANTS = 40
    const val DEMOTE_MAX_DESCENDANTS = 15
    const val MAX_DEPTH_TO_PROMOTE = 4
    fun shouldPromote(span: SubtreeSpan, alreadyPromoted: Boolean): Boolean
    fun shouldDemote(descendants: Int): Boolean
}
```

### 4. `data/NoteRepository.kt` (extend)

New responsibilities — the repo is the boundary where splitting happens. Public `load()`/`save()` signatures stay shaped the same; internally they gain recursion over the directory tree.

- `load(rootDir)`:
  1. Read `rootDir/root.nogr`.
  2. Walk lines; for each `parseRef` hit, the ref's path is relative to the file's directory. Resolve to `rootDir/<refPath>`, recursively load it, `reindent` to the parent bullet's indent + 1, splice it in place of the ref line.
  3. Build and return `(lines, promotedMap)` where `promotedMap` records, per parent `LineId`, the directory path (relative to `rootDir`) that holds the resolved child.
  4. Missing child file: leave the ref line verbatim and log — don't crash; another editor may have renamed it externally.

- `save(rootDir, lines, lineIds, promotedMap)`:
  1. Recursively determine the new structure top-down:
     - At each level, run `findSubtrees(lines)` and apply `PromotionPolicy`. Use the per-directory used-name set (current siblings) for collision resolution.
     - For a parent `LineId` already in `promotedMap` whose desired dir name (derived from current title + collision-resolution) differs from the stored one, plan a `moveDirectory` + `moveFile` rename pair.
     - For new promotables, plan a fresh `<Title>/<Title>.nogr` creation.
     - For demotables (entry in map but descendants ≤ `DEMOTE_MAX`), plan: delete inner file, delete dir if empty, splice content back into parent.
  2. Produce each file's text: parent files have promoted subtrees replaced by single ref lines pointing at the (possibly renamed) child paths.
  3. Execute filesystem operations in a safe order: writes/renames bottom-up to avoid traversing through a directory mid-rename; deletions last.
  4. Return the updated `promotedMap` so the backing VM stays in sync.

The directory name of a promoted subtree never embeds an id — it's always derived from the current title. The `LineId` in the map is the stable handle.

### 5. `main/DocumentBackingViewModel.kt` (minimal changes)

- Add `private var promotedSubtrees: MutableMap<LineId, String>` (not in `State`) — parent bullet's `LineId` → child file path.
- On `load()`: receive the map from repo, store it.
- On autosave tick: pass `(lines, lineIds, promotedSubtrees)` to `repository.save(...)`; replace local map with the returned one.
- All edit primitives (`insertText`, `insertNewline`, `delete`) stay byte-for-byte identical. The view VM is untouched. Splitting and merging happen entirely inside the repo's save path.

### 6. `main/MainScreen.kt` (jsMain view — optional polish)

Parser can detect a trailing `[[…]]` and render the title with a small muted suffix (e.g. "↳ shopping-list"). Zero interaction — purely informational. Safe to ship without it and add later.

## Why this shape

- **No new layer between view and document.** The promise "children are just another file" is kept at the repo boundary, where it belongs.
- **Cursor/selection/zoom untouched.** The composed `lines` list is what the editor has always worked with.
- **External editors see exactly one truth.** Open `root.nogr` in another tool — readable outline with `[[<Title>/<Title>.nogr]]` pointers. Click into the directory; the file with the same name has the content; sibling sub-directories are further-promoted descendants. The file system tree is a faithful map of the outline tree.
- **No magic top-level wrapper.** `root.nogr` lives next to its promoted children's directories — no `notegrow/` enclosure to reason about.
- **Symmetric ref paths.** A file's refs always point at `<Title>/<Title>.nogr` relative to the file's own directory, so the resolution rule is the same at every level.
- **Idempotent.** A save that doesn't cross a threshold produces byte-identical files.
- **Reversible.** Demotion is just the reverse traversal; removing all promotion metadata yields today's single-file behaviour.

## Critical files

- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/data/NoteRepository.kt` — biggest change (split/compose logic).
- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/data/SubtreeCodec.kt` — new.
- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/data/PromotionPolicy.kt` — new.
- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/platform/FileSystem.kt` — add `deleteFile` / `listDirectory`.
- `client/src/jsMain/kotlin/se/soderbjorn/notegrow/platform/FileSystem.js.kt` — actual impls (add to `window.noteApi` bridge too).
- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/main/DocumentBackingViewModel.kt` — carry `promotedSubtrees` across save/load; pass `lineIds` to repo.
- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/main/DocumentLayout.kt` — expose `subtreeEnd` / an indent-range helper (currently private to the view VM) for reuse by `SubtreeCodec`.
- `web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/MainScreen.kt` — optional muted rendering of ref suffix.

## Reused utilities

- `DocumentViewBackingViewModel.subtreeEnd` → promote to top-level in `DocumentLayout.kt` and reuse from `SubtreeCodec.findSubtrees`.
- `DocumentLayout.bulletAsteriskColumn` → indent detection inside `findSubtrees`.
- Existing `LineId` list from `DocumentBackingViewModel` → keys for `promotedSubtrees` map (no new id machinery).
- Existing autosave loop → the trigger point (no new timer).

## Verification

1. **Unit tests** (`client/src/commonTest`):
   - `SubtreeCodecTest`: round-trip `findSubtrees → extract → reindent → splice back` is identity.
   - `PromotionPolicyTest`: threshold + hysteresis boundary cases.
   - `NoteRepositoryTest` with an in-memory `FakeFileSystem`: load → edit → save → load recovers equivalent `lines`; growing a subtree past 40 descendants produces a child file; shrinking it back to ≤15 removes the child file.

2. **Manual — JS app, with `DEBUG = true`** (thresholds are 3 / 1):
   - `./gradlew :web:jsBrowserDevelopmentRun` (or whatever the existing dev task is — check `web/build.gradle.kts`).
   - Type the **promotion test outline** below; wait one autosave tick (5 s); confirm the child file appears.
   - Type the **demotion test edit** below to shrink the subtree; wait one autosave tick; confirm the child file disappears and content is inlined.
   - Kill the app, restart, confirm the composed view is identical.
   - Edit `root.nogr` externally (rename a line, add a bullet); restart app, confirm external edits survive.
   - Open `root.nogr` and one promoted child in another editor — confirm both read as plain outlines and the `[[…]]` pointer is human-friendly.

### Promotion test outline (with `DEBUG = true`, threshold = 3)

Type this fresh into an empty document. The bullet "Recipes" has 4 descendants — over the debug threshold — so on the first autosave tick after typing the fourth descendant, it should promote into a child file.

```
* Top
  * Recipes
    * Pancakes
    * Pasta
    * Soup
    * Stew
  * Other notes
    * Mid
```

Expected after autosave (rooted at `<rootDir>`):

- `<rootDir>/root.nogr` contains `  * Recipes  [[Recipes/Recipes.nogr]]` (two spaces before `[[`).
- `<rootDir>/Recipes/Recipes.nogr` contains the four recipe bullets at indent 0.
- `Other notes` (1 descendant) stays inline.
- `Top` (root document) → never promotes.

### Rename test (continuing from the promoted state)

Edit the parent bullet text from `Recipes` to `Cooking`. After autosave:

- `<rootDir>/root.nogr` line is now `  * Cooking  [[Cooking/Cooking.nogr]]`.
- `<rootDir>/Recipes/` has been renamed to `<rootDir>/Cooking/`, and the inner file to `Cooking.nogr`.
- No data lost; `LineId` for the bullet is unchanged.

### Demotion test edit

From the promoted state, delete recipe bullets until only one descendant remains:

```
* Top
  * Cooking
    * Pancakes
  * Other notes
    * Mid
```

After one autosave tick (`descendantCount` = 1, ≤ `demoteMaxDescendants`):

- `root.nogr` shows `  * Cooking` (no ref) followed by `    * Pancakes` inlined back.
- `<rootDir>/Cooking/Cooking.nogr` is deleted; `<rootDir>/Cooking/` directory is removed.

### Re-promotion sanity check

From the demoted state, type three more recipe bullets back in. After autosave, expect a *new* `<rootDir>/Cooking/Cooking.nogr` to be created. The path is deterministic (no random component), so a third re-promote/demote cycle keeps producing the same path.

3. **Regression**: with `DEBUG = false`, edit a small outline (a few bullets total, far under the production threshold). Diff `root.nogr` before and after a save; expect no change and no `<Title>/` directories created.
