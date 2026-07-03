# Anchor-link footer bug — re-diagnosis

## Context

First attempt (`zoomed-promoted-ref-footer.md` / the implemented patch) targeted "zoom into a promoted-ref bullet". User confirmed it makes no difference. Direct inspection of the live vault shows the click target is **not** a promoted-ref bullet at all:

- `Root.md` contains a bullet of the form `* [Person A](<#treefacts-bullet=Some/Sub/Person A>)`. A regular markdown link bullet whose URL is a `#treefacts-bullet=` title-path link, not a `<file>#treefacts` promoted-ref URL. `SubtreeCodec.parseRef` would reject it (`!url.endsWith("#treefacts")`), so the parent file's `promotedSubtrees` never registers it.
- A doubled-name anchor file `Some/Sub/Person A/Person A.md` exists with body content (no `treefacts: true` frontmatter, just two bullets).
- The directory `Some/Sub/Person A/` contains many real `.md` entries that should populate the footer listing.

Expected click flow with the actual data:

1. `MainScreen.kt:813` — `viewModel.navigateToLink("#treefacts-bullet=Some/Sub/Person A")`.
2. `PaneBackingViewModel.navigateToLink` — `LinkUrl.parse` → segments `["Some","Sub","Person A"]`, `isAbsolute=false`.
3. `VaultIndex.resolveStrict` walks `VaultRoot → Some/Some.md (FileRoot) → Some/Sub/Sub.md (FileRoot) → findLooseChild(["Some","Sub"], "Person A")`. With the doubled-name anchor file present, `findLooseChild` returns `WalkPosition.FileRoot(fileRel="Some/Sub/Person A/Person A.md", fullPath=["Some","Sub","Person A"])`. Resolution = `Found(fileRel=anchor, titlePathInFile=[])`.
4. `navigateToLink`: `isCrossFile = true`, `titlePathInFile.isEmpty()` → `switchActiveFile(anchor)` + `awaitFirstLoadedLineId()?.let { placeCursorOn(it) }`. No zoom set.
5. State after: `activeFileRel = "Some/Sub/Person A/Person A.md"`, `zoomedLineId = null`, `documentState.isLoaded = true`.
6. `paintVaultFooter` re-fires. `anchoredDirectoryOf(rootFileName)` returns `"Some/Sub/Person A"`. `ensureVaultListing` populates `vaultListings["Some/Sub/Person A"]`. Footer renders the directory's entries.

This already works on paper with **no code change**. The user reports it doesn't. Something between step 1 and step 6 is silently failing.

Candidates (each suggests a different fix):

- **A.** The mousedown on the link span doesn't reach `handleTreeFactsLinkMouseDown` — wrong target, the styled-text span's `data-href` isn't getting picked up by `ancestorHref`, or a higher-level handler intercepts. *Symptom:* no navigation; pane still shows the parent file.
- **B.** `navigateToLink` runs but the scope-launched coroutine throws / cancels before `switchActiveFile`. *Symptom:* state unchanged, no `[treefacts] link target not found` log.
- **C.** `vaultIndex.resolve` returns `NotFound` (cache stale, doubled-name file invisible to the index because the index hasn't been invalidated since the file was just-created). *Symptom:* the existing `println("[treefacts] link target not found …")` fires.
- **D.** `switchActiveFile` completes, but the freshly-loaded `Document.State.isLoaded` stays false because the doc.start collector hasn't ticked — or the file loads fine but `state.documentState` lags. *Symptom:* pane is on the anchor file but `state.isLoaded` is false at paint time, so `paintVaultFooter` early-returns.
- **E.** Navigation works, paint fires, `anchoredDirectoryOf` returns the right directory, but `vaultListings[…]` stays empty because `registry.ensureVaultListing(dirRel)` never resolves (FileSystem.listDirectory bug on this path? Permission? Path with spaces?). *Symptom:* state is correct but `entries == null` forever.
- **F.** Everything works at the model layer, but the footer container element is wiped or hidden by some sibling reconcile (a CSS class, `display: none`, `visibility: hidden`, layout collapse). *Symptom:* DOM has the header but it's invisible.

The earlier "promoted-ref" fix is orthogonal — it covers a real future case (zooming into a true promoted-ref bullet whose subtree is an anchor file) but is dead code for *this* bug. Leave it in.

## Approach

Drop a **temporary** diagnostic `console.log` into `paintVaultFooter` that prints on every paint, and a second one into `PaneBackingViewModel.navigateToLink`'s `scope.launch` block. User clicks the link once, copies the console output, and the failure mode falls out immediately.

### Step 1 — add diagnostic logging

`web/src/jsMain/.../main/VaultFooter.kt` — at the top of `paintVaultFooter`, before any returns:

```kotlin
js("console.log") (
    "[footer]",
    "active=", state.activeFileRel,
    "isLoaded=", state.isLoaded,
    "docState=", state.documentState != null,
    "zoom=", state.zoomedLineId?.toString() ?: "null",
    "anchorDir=", state.anchoredDirectoryOf(viewModel.rootFileName),
    "vaultKeys=", state.vaultListings.keys.joinToString(","),
)
```

`client/src/commonMain/.../main/PaneBackingViewModel.kt` — at the start of `navigateToLink`'s `scope.launch` block (around line 1172), and at each `return@launch` path, log:

```kotlin
println("[treefacts] navigateToLink: url=$url cursorPath=$inFilePath")
println("[treefacts] resolved: $resolution")
println("[treefacts] after switch: active=${_stateFlow.value.activeFileRel} zoom=${_stateFlow.value.zoomedLineId}")
```

(The existing `[treefacts] link target not found` print already covers the NotFound branch; keep it.)

### Step 2 — user runs the app, clicks the link once, copies the browser devtools console output

Two `[footer]` lines are expected: one before the navigation (showing `active=Root.md`) and one after (showing `active=<anchor path>`). If only the first appears, candidate A/B. If the second appears but `anchorDir=null`, the path math broke. If `anchorDir` is right but `vaultKeys` doesn't include that dir even after a beat, candidate E. Etc.

### Step 3 — apply the targeted fix

The log output tells us which candidate is real. Likely fixes per candidate:

- **A** — fix the DOM selector / `ancestorHref` walk for the styled link span.
- **B** — surface the swallowed exception.
- **C** — invalidate `VaultIndex` after the doubled-name file is created (`DocumentRegistry.ensureFolderStub` already does this for the modal path; the issue would be that we're hitting a stale index from before manual file creation).
- **D** — wait for `Document.stateFlow.first { it.isLoaded }` before deciding `paintVaultFooter` should early-return.
- **E** — debug `NoteRepository.listVaultLevel` / `FileSystem.listDirectory` for that path; could be Node fs encoding / path-with-spaces issue on the Electron build.
- **F** — locate the offending CSS / DOM removal.

### Step 4 — remove the diagnostic prints

Strip the `console.log` and `println` calls once the root cause is fixed. Keep the existing `[treefacts] link target not found` print — it's already permanent.

## Files to modify (Step 1)

- `web/src/jsMain/kotlin/se/soderbjorn/treefacts/main/VaultFooter.kt`
- `client/src/commonMain/kotlin/se/soderbjorn/treefacts/main/PaneBackingViewModel.kt`

## Verification

1. Apply Step 1, rebuild (`./gradlew :web:jsBrowserDevelopmentRun` or the equivalent Electron build).
2. Open the root file, click the anchor link in question.
3. Read the console. The log lines pin down which candidate is real.
4. Apply the Step 3 fix matching the candidate.
5. Re-click the link; confirm the "Files" header appears under the anchor file's content with the directory entries listed.
6. Run `./gradlew :web:compileKotlinJs :client:jsTest` to confirm no regressions.

## Notes

- The previously-implemented `zoomedPromotedRefFileRel` / effective-anchor-file logic in `VaultFooter` and `PaneBackingViewModel` is left in place; it covers a real but distinct case that the user did not exercise here. Revisit removal only if it produces a regression.
- The doubled-name anchor file in question lacks `treefacts: true` frontmatter. That's fine for read/write here (`NoteRepository.loadFile` doesn't require it), but worth noting in case it's relevant to a later "is this a TreeFacts file" check elsewhere.
