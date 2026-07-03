# Hide promoted-tree entries from the vault footer

## Context

The generalised vault footer (the directory-anchor footer) currently lists
every direct child of the anchored directory. That includes files and
folders the user has *already* surfaced in the document outline via
`#treefacts` promoted-ref links — e.g. when viewing `Root.md`, the footer
shows the `Dynalist Import/` folder even though `Root.md` has a
`* [Dynalist Import](Dynalist Import/Dynalist Import.md#treefacts)` bullet
pointing into it. The duplicate is noise; the footer's value is showing
*loose* / *unreferenced* files the outline doesn't reach.

We want the footer to filter out entries that are already promoted-ref
targets of the active document.

## Approach

Filter the footer entries against the **active document's own** set of
`PromotedRef.fileRel` values (vault-relative paths). Direct only — no
transitive walk through nested promoted-ref files. The rule for each
entry:

- **File entry** (`entry.pathRel` ends in `.md`): hide when
  `entry.pathRel` is in the set.
- **Directory entry**: hide when the doubled-name anchor file
  `<entry.pathRel>/<entry.name>.md` is in the set. Directories without
  a doubled-name anchor stay visible even if some adopted-foreign file
  inside them is promoted-ref'd — this is conservative on purpose;
  picking "any file inside" would hide directories that still contain
  useful unreferenced siblings.

### Step 1 — Expose the active doc's promoted-ref paths in `Document.State`

In `client/src/commonMain/kotlin/se/soderbjorn/treefacts/main/Document.kt`:

- Add a new field to `State` (line 96): `val promotedFileRels: Set<String> = emptySet()`. The set holds every `PromotedRef.fileRel` currently in `promotedSubtrees`.
- Wherever the document mutates `promotedSubtrees` and emits a new
  `State`, populate `promotedFileRels = promotedSubtrees.values.map { it.fileRel }.toSet()` (or an equivalent helper). Cheap — the map is small.
- Update `KDoc` for `State` so the new field is documented (per
  CLAUDE.md's documentation rules).

This makes the set part of every state emission, so panes that mirror
`documentState` see it immediately and synchronously.

### Step 2 — Filter in `paintVaultFooter`

In `web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/VaultFooter.kt`:

- After computing the existing `filtered` list (which already drops the
  anchor file itself), drop any entry that's a promoted-ref target:

  ```kotlin
  val promoted = state.documentState?.promotedFileRels ?: emptySet()
  val filtered = entries.filter { entry ->
      if (entry.pathRel == anchorFile) return@filter false
      val key = if (entry.isDirectory) "${entry.pathRel}/${entry.name}.md"
                else entry.pathRel
      key !in promoted
  }
  ```

- The existing "empty → render nothing" branch (no header / separator)
  still applies, so a directory where every entry is promoted now
  renders no footer at all.

## Out of scope

- **Transitive reachability.** Files reachable via a chain of promoted
  refs (e.g. Root.md → `Framna.md` → some deeper file) are NOT hidden
  unless they are direct refs of the active document. The user opted
  for direct refs only to keep this synchronous and cheap.
- **Adopted-foreign file detection inside dirs.** A directory whose
  anchor isn't promoted but whose hand-authored siblings are partially
  promoted stays visible. The conservative match rule was the user's
  explicit choice.
- **Non-`#treefacts` markdown links.** Plain markdown link bullets (no
  `#treefacts` fragment) are not promoted refs and do not appear in
  `promotedFileRels`, so the footer won't hide their targets. That's
  correct: plain links are navigation, not subtree promotion.

## Critical files

- `client/src/commonMain/kotlin/se/soderbjorn/treefacts/main/Document.kt`
  — add `promotedFileRels` to `State`; populate from `promotedSubtrees`
  at every state emission. `Document.promotedByRow()` (line 288) is the
  existing accessor that already enumerates `promotedSubtrees`; the new
  field is a cheaper, set-shaped projection of the same data.
- `web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/VaultFooter.kt`
  — add the promoted-ref filter to `paintVaultFooter`'s entry-filtering
  block.

## Output of the plan

The user asked for a local copy of the plan in the repo (alongside
`plans/auto-promote-plan.md` etc.). After plan approval, copy the final
plan to `plans/footer-promoted-filter.md` in the repo as part of
implementation.

## Verification

1. `./gradlew :web:jsBrowserDevelopmentWebpack` and reload.
2. Open `Root.md`. The footer must no longer show any folder/file that
   `Root.md` itself promotes — `Dynalist Import/`, `Amiga/`, etc. must
   be hidden. Loose files / folders without anchors (and folders whose
   anchor isn't promoted from Root.md) still appear.
3. Navigate into a promoted-ref folder anchor like
   `Framna/Framna.md`. The footer shows entries inside `Framna/`
   *minus* the anchor file itself and *minus* any directory whose
   doubled-name anchor `Framna/<x>/<x>.md` is promoted from
   `Framna.md`.
4. Pick an Insert Link folder stub for a folder whose anchor file
   didn't exist before. After creation, the newly-created
   `[name](#treefacts-bullet=…)` link in the source document is a
   `#treefacts-bullet=…` URL — NOT a `#treefacts` promoted ref — so the
   directory will stay visible in the source document's footer. Expected.
5. Sanity: type a fresh `* [Foo](Foo/Foo.md#treefacts)` bullet in
   `Root.md` (a manually-authored promoted ref). Within one autosave
   tick, the `Foo/` folder must disappear from the footer.
6. `./gradlew :client:jsTest` — no regressions in Document state tests.
