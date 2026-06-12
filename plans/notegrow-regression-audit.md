# Notegrow regression audit — bullets show `*`, links don't click

## Symptoms reported

After the toolkit adoption + post-merge WIP cascade:
1. Typing `* ` shows a literal asterisk instead of the bullet glyph + WYSIWYG bullet UX.
2. Clicking links (external `http(s)://` and internal `#notegrow-bullet=…`) does nothing.
3. "Most stuff in notegrow is broken" — implying the editor surface in general is misbehaving.

## What I verified is intact

These were the first suspects; all check out in source:

- `OutlinePaintLoop.paint` (web/.../main/OutlinePaintLoop.kt:102) still recognises bullet lines via `DocumentLayout.bulletAsteriskColumn` (DocumentLayout.kt:45) and routes them through `buildBulletPrefix` (line 360) + `buildStyledTextRegion` (line 251). The marker `*` is sliced off via `line.substring(bulletCol + 2)` (line 223), so the model still owns `* foo` but the DOM never sees the `*` or trailing space — the prefix span replaces them.
- `buildBulletPrefix` (line 360) sets `class = notegrow-bullet-prefix` + `contenteditable="false"` and appends a `.notegrow-bullet` glyph; CSS in `ensureStyles` (line 528–547) paints it via `background: currentColor` + 50% radius.
- `buildStyledTextRegion` (line 251) tokenizes inline markdown via `InlineMarkdownTokenizer.tokenize`, stamps `data-href` on link runs (line 308–310), and applies `.notegrow-md-link` styling (CSS at line 652).
- `MainScreen.wireInputListeners` (MainScreen.kt:329) attaches `mousedown` → `handleExternalLinkMouseDown` → `handleNotegrowLinkMouseDown` → `maybeBeginGutterDrag` (lines 347–352).
- `ancestorHref` (line 775) walks parent chain looking for `data-href`; works for both text-node and element targets.
- `ensureStyles` is keyed off `notegrow-cursor-style` id (line 491–493) so re-mounts don't break the stylesheet.
- Kotlin compile succeeds (`./gradlew :web:compileKotlinJs`); bundled JS at `web/build/dist/js/productionExecutable/web.js` contains `notegrow-bullet` (17×), `notegrow-md-link` (2×), and the bullet/markdown detection code (DocumentLayout class `r17` method).

## Top hypotheses, ordered by likelihood

### H1 — Stale Electron bundle (most likely)

`scripts/runClean.sh` builds the web JS and copies it to `electron/resources/web/web.js`. If the user is running Electron from a previously-built bundle from before the most recent paint/wireInputListeners fixes (the last few WIP commits did touch both), they would see old broken behavior even though `git diff` shows working code.

**Diagnostic:** check the mtime of `electron/resources/web/web.js` vs the most recent edits to `OutlinePaintLoop.kt` / `MainScreen.kt`. Sample command:
```bash
stat -f "%Sm %N" electron/resources/web/web.js \
  web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/OutlinePaintLoop.kt \
  web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/MainScreen.kt
```
If the bundle is older than the .kt files, run `./scripts/runClean.sh` and re-launch.

### H2 — Pane container clobbers the editor mid-mount

`AppShell.renderPaneContent` (AppShell.kt:1595) creates a **fresh** container `div` every time the toolkit asks for pane content, appends it to the toolkit-provided slot, and hands it to `MainScreen.render`. `MainScreen.render` then either (a) builds title/scroll/editor/banner from scratch into that container, or (b) moves pre-existing elements from a previous container.

The idempotent re-mount branch (MainScreen.kt:166–184) requires **all four** elements (title, scrollWrapper, editor, banner) to be non-null to short-circuit. If any one is null because of a previous partial failure, the branch falls through to "build from scratch" — wiping the stale state's collector references and re-attaching a *new* editor element, while the *old* state collector (launched in the previous mount) keeps painting into the **detached** old editor. Net effect: the visible editor never repaints, so typed characters stay as raw `*`'s from the browser's contenteditable defaults.

**Diagnostic:** in dev tools, after the symptom appears, check `document.querySelectorAll('.notegrow-editor').length`. If it's > 1, there are orphan editors and H2 is confirmed.

**Why this might've recently regressed:** the most recent commits added `editor.focus()` calls inside the state-collector lambda (line 242) and on a separate `focusEditor()` path (line 291). If two MainScreen instances ever race for the same pane id, this would surface as "the wrong editor is wired."

### H3 — `syncSelectionFromDom` rejects every input

`handleBeforeInput` (line 384) gates every keystroke through `syncSelectionFromDom` (line 401). If selection sync returns false, the handler returns without inserting and the browser's default `preventDefault` already ran (line 387) — so the keystroke is silently dropped, the editor stays empty, and the user reports "I can't type bullets."

`syncSelectionFromDom` is in MainScreen.kt — its skip path was changed to inspect `pendingInlineStyles` (line 398). If that field's default isn't an empty set (e.g. due to a corrupted persisted state), the skip-sync branch fires for every press and selection sync never reconciles. Less likely but worth a 30-second check.

**Diagnostic:** set a breakpoint on `handleBeforeInput` line 401 or `console.log` the sync result. If it's `false` on every press, the selection root isn't `editor`.

### H4 — `data-href` link spans hit by browser default before our handler

Our `mousedown` handler calls `preventDefault()` + `stopPropagation()`, and the toolkit's `LayoutRenderer.kt:625` capture-phase pane mousedown handler does **not** stop propagation. So our handler should still fire. But: the toolkit's handler calls `focusPane(spec.id)` which calls `callbacks.onPaneFocused` (LayoutRenderer.kt:473) which in the notegrow wiring eventually persists layout state. If anything in that chain throws *after* the capture handler runs but before bubble reaches editor, the editor's mousedown listener never fires.

**Diagnostic:** in dev tools, on a broken instance, run:
```js
const ed = document.querySelector('.notegrow-editor');
ed.addEventListener('mousedown', e => console.log('editor mousedown fired', e.target), true);
```
Then click a link. If you see the log, our handler is reachable but `ancestorHref` or `isExternalUrl` rejects. If you don't, propagation is being killed upstream.

### H5 — Stale `documentState` keeps `state.lines = [""]`

`PaneBackingViewModel.State.lines` falls back to `listOf("")` when `documentState` is null (PaneBackingViewModel.kt:170). If the document never finishes loading (registry acquire stuck or `applyDefaultCollapseIfNeeded` throws silently in the inner collector), every emission has `state.isLoaded = false` → `paintLoading` is called and the user sees only the "Loading…" overlay. They'd report "I can't see anything happen" — close to but not exactly the reported symptom.

**Diagnostic:** in dev tools, on a broken pane, eval the pane's stateFlow value (you may need a global handle to MainViewModel). Or just check whether the editor DOM contains `.notegrow-row` divs vs `.notegrow-loading`.

### H6 — Toolkit CSS reset clobbering the editor

The fbdcec6 WIP removed several `.dt-app-frame …` override blocks from `ensureStyles`. That just removes overrides — toolkit defaults take effect again. Unless the toolkit's defaults set `user-select: none` or `pointer-events: none` on a pane content ancestor, this isn't the cause. I checked: `.dt-pane-floating` doesn't apply either rule. But worth eyeballing computed styles on the editor in devtools (`getComputedStyle(editor).userSelect`, `pointerEvents`) for a sanity check.

## Lower-likelihood checks (do last)

- **State collector launch ordering:** `MainScreen.render` launches `viewModel.stateFlow.collect` *after* `wireInputListeners`. If the collector throws on its very first emission, the catch is the coroutine scope's exception handler — silent failure. Worth wrapping the collect body in try/catch with `console.error` to surface.
- **VaultFooter reflow eating clicks:** `paintVaultFooter` (called from line 258) appends children to the same scroll wrapper as the editor. If the footer has overlapping z-index and a transparent overlay, clicks within the footer's bounds could miss the editor. But the user says editor *typing* is broken too, so this can't be the only cause.
- **Editor element not being passed to reconcile after re-mount:** `editorElement` is `var`; if a fresh build path runs after a successful re-mount, the old element ref is replaced but the *old* collector still closes over the *old* element. Same root as H2.

## Suggested fix order

1. **Rebuild and relaunch** (`./scripts/runClean.sh && open electron/...`). Rule out H1 entirely before code-diving.
2. **DOM sanity in dev tools** — `document.querySelectorAll('.notegrow-editor').length`, computed style of `.notegrow-editor`, presence of `.notegrow-bullet` spans for known bullet lines. Catches H2, H5, H6 in seconds.
3. **Console-log `mousedown` and `beforeinput` at the editor element** (see H3, H4 snippets). Catches H3 and H4.
4. **If H2 confirmed**: add a guard to `MainScreen.render` that compares the incoming `root` to `rootElement` and replaces children atomically rather than relying on the four-element null check. Also clear `editorElement = null` whenever the editor is intentionally detached (e.g. AppShell drops the pane).
5. **If H3 confirmed**: log what `syncSelectionFromDom` is rejecting on (anchorNode outside editor? selection rangeCount 0?). Likely root: a focus race after toolkit's capture-phase mousedown moves focus away from the editor before `beforeinput` runs.

## What I would NOT touch yet

- The `InlineMarkdownTokenizer` — it has comprehensive tests (`InlineMarkdownTokenizerTest.kt`, 400 LoC) and the bundled output preserves it. Almost certainly not the bug.
- The CSS in `ensureStyles` — the colors all have fallback values (`var(--t-accent, #5ab0ff)`) so even a missing toolkit theme would still render visible links.
- `bulletAsteriskColumn` — three-line pure function, untouched in months, covered by `DocumentLayout` tests.

## Files to grep first when investigating

- `web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/MainScreen.kt` — render lifecycle, input wiring, link handlers (single biggest file, 1940 lines, biggest delta since last "works well" commit)
- `web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/OutlinePaintLoop.kt` — paint, bullet prefix, styled-text region, CSS
- `web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/AppShell.kt` — `renderPaneContent` (1595), `paneEditors` map, pane lifecycle, focus callbacks
- `client/src/commonMain/kotlin/se/soderbjorn/notegrow/main/PaneBackingViewModel.kt` — `State.lines` fallback, `documentState` flow

## Recent commits worth bisecting if all else fails

Between `ffbcfd6 WIP works well` and `HEAD` (b238a86), the files with the biggest delta are:
- AppShell.kt: ~2000 line delta (-2 / +2 in net but lots of churn)
- MainScreen.kt: 196 line delta
- OutlinePaintLoop.kt: 76 line delta (the bullet `click` handler removal in particular)

`git bisect` against `ffbcfd6` with a simple "open the app, type `* foo`, does it render a dot" predicate would identify the offending WIP in under 10 minutes.
