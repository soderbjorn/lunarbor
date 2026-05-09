# Switch the editor to a proportional font (browser-native rewrite)

## Context

The user wants the editor to render in a proportional font. The existing web view paints text manually — every row is a series of DOM `div` chunks; cursor and selection are absolutely-positioned overlays; wrap, caret X, selection rectangles, and click-to-column hit-testing all flow through a single `charWidthPx` constant (the width of `"M"`). That model only works because every glyph is the same width.

The first plan I drafted preserved this architecture and threaded a per-line pixel measurement through every site that uses `charWidthPx`. The user pushed back: the browser already does proportional text editing, very well, and we're paying a large complexity tax to reimplement it badly. They explicitly said the abstractions can be remodeled, including pushing measurement and cursor placement out of commonMain if a different design is cleaner — as long as core document logic stays shareable across web/Android/iOS.

This plan takes them up on it. We let the browser own text rendering, caret, selection, wrap, IME, accessibility, and clipboard — and we keep commonMain as the source of truth for *the document and its semantics*, not for cursor pixels.

The non-obvious upside: when we get to Android and iOS, each will use its native text surface (`BasicTextField` on Compose, `UITextView` / SwiftUI `TextField` on iOS), which is exactly what users expect on those platforms — proper IME, system selection menus, accessibility, native gestures. The same shared layer (document state, bullet/zoom semantics, autosave, persistence) supports all three; only the per-platform "translate native edit events to document primitives" adapter differs. That is actually *more* code-sharing than today, because today's cursor/selection logic in `DocumentViewBackingViewModel` would have to be re-asserted against each platform's native text surface anyway — fighting the platform on every platform.

## Execution mode — run straight through, no stops

**This work runs to completion in a single autonomous session — no staged delivery, no pausing for review between milestones.** The user is going to sleep and expects to wake up to a finished, testable editor: proportional font live, bullets rendering as `•`, click-to-zoom working, folding working, and the obsolete commonMain helpers deleted. All three milestones below are internal phases of one continuous run, not separate deliverables.

Specifically:

- Do **not** stop at any "verification gate" for human approval. The gates are self-checks — keep going past each one as soon as the build is green and the behavior looks right.
- Do **not** ask clarifying questions mid-run on routine decisions. Make the reasonable call (the recommendations already in this plan are the defaults) and proceed.
- Do **not** leave the codebase in an intermediate state — e.g. don't ship Milestone 1 with bullets broken and call it done. Either everything in the plan lands, or the run reports exactly where it stopped and why.
- Do **not** open a PR with only part of the work. One coherent set of changes, all milestones included.
- The "Open question" about retiring `DocumentViewBackingViewModel` is **already decided for this run**: do **not** bypass it. It must remain the commonMain abstraction that represents *a view into a document*, and the web view talks to it (not directly to `DocumentBackingViewModel`). Its *contents* should change as needed so the abstractions make sense in the new world — drop the fields and intents that no longer fit (per-character cursor pixels, anchor/cursor pair driving manual selection painting, `charWidthPx`-shaped helpers) and add whatever new shape the contenteditable design genuinely needs (e.g. a viewer-scoped notion of "which row range is in focus", zoom target, last-known logical caret as `(row, col)` if useful for restoring after reconciliation, intents that take a `(row, col)` or `Selection` parameter from the platform's native text surface). The class stays; its API is reshaped. Selection-aware operations (cut, deleteSelection, indent at selection, selectWord) still live here as commonMain functions — they just take the selection as a parameter from the DOM instead of reading it from a commonMain-owned cursor field. This keeps the layered architecture in CLAUDE.md intact and gives Android/iOS the same seam to plug their native text surfaces into later.

The only things that justify stopping early: a genuine architectural blocker that wasn't anticipated here, a build/tooling failure that can't be diagnosed from local context, or a destructive action that needs explicit user confirmation (deleting shared state, force-pushing, etc. — none of which this plan calls for).

## Approach

Replace the custom paint loop with a single `contenteditable` container. Inside it, each visible row is a `<div data-row="N">` (block-level so cross-row selection works naturally). The browser owns:

- Glyph rendering (any font we want, including proportional)
- Caret position and blink
- Selection drawing, drag-to-select, double-click-word, triple-click-line
- Word wrap (CSS `white-space: pre-wrap`)
- IME composition, clipboard, undo affordances we may want to disable in favor of our own
- Accessibility (screen readers see real text)

We own:

- The document model (`DocumentBackingViewModel`, unchanged)
- Translating native edit events (`beforeinput`, `keydown`) to document primitives (`insertText`, `delete`, `insertNewline`)
- Mapping DOM ranges (`getSelection().anchorNode`/`anchorOffset`) to `(row, col)` and back
- Bullet glyph rendering (non-editable span at the start of each bullet row), bullet click-to-zoom
- Zoom UI (which range of rows to render, breadcrumb)
- Collapsed-bullet folding

Cursor and selection move out of commonMain on the web side. They live in the DOM, are read on demand when an intent needs them. The shared `DocumentViewBackingViewModel` either becomes a no-op pass-through on web (each platform decides whether to use it) or is bypassed entirely on web, with platform-native viewer state on top of `DocumentBackingViewModel`. (Decision deferred to Milestone 1; see "Open question" below.)

## Bullet & cursor-column mapping

This is the only real architectural subtlety. Document lines look like `"  * Some text"`. We want the user to see `"  • Some text"` and be unable to put the caret inside the `* ` prefix.

Approach: each bullet row's DOM is

```html
<div data-row="N" data-prefix-len="4">
  <span contenteditable="false" class="bullet" data-row="N">•</span>
  <span class="text">Some text</span>
</div>
```

The container is `contenteditable="true"`; the bullet span overrides to `false`. The `text` span is the editable region. Cursor mapping:

- DOM → model: `col = prefixLen + offsetInTextSpan`
- Model → DOM: `offsetInTextSpan = col - prefixLen` (clamped to 0 on the boundary)
- Click on `.bullet` → don't move caret; trigger `viewModel.zoomInto(row)` (existing intent on `MainViewModel` / `DocumentBackingViewModel`)

Non-bullet rows have no prefix span; `prefixLen = leading-whitespace-count`, and indentation is rendered with `padding-left` (computed from leading-whitespace count × an em-based step) so leading whitespace is visible as space but not editable as text. Or — simpler for the first cut — keep leading whitespace as literal characters and let the user edit them like any other text.

## Files to modify

| File | Change |
|------|--------|
| `web/src/jsMain/.../main/MainScreen.kt` | Major rewrite. Builds the contenteditable container, mounts row divs, wires `beforeinput`/`keydown`/`paste`/`cut` listeners that route to `DocumentBackingViewModel` primitives. Reconciles DOM from each state emission and restores caret. |
| `web/src/jsMain/.../main/EditorStyle.kt` | Replace monospace stack with `system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`. Drop `lineHeightPx` if we let CSS line-height be unitless (or keep as a CSS variable). |
| `web/src/jsMain/.../main/OutlinePaintLoop.kt` | Strip drastically. Keep: row iteration, zoom range filter, bullet glyph rendering, bullet click target, collapsed-row chevron. **Delete**: cursor span, selection rectangle, `appendCursor`, `appendSelectionHighlight`, `measureCharWidth`, `wrapWidthInChars`, all `charWidthPx` usage. |
| `web/src/jsMain/.../main/HitTesting.kt` | Delete entirely. Browser's `Selection` API replaces it. |
| `web/src/jsMain/.../main/OutlineInputHandlers.kt` | Delete most of it. Mouse selection is browser-native. Keep: bullet click handler logic if any of it isn't trivially re-expressible in MainScreen. |
| `web/src/jsMain/.../main/ScrollIntoView.kt` | Replace with `scrollIntoView({block: 'nearest'})` on the row element after caret moves. |
| `web/src/jsMain/.../di/JsAppGraph.kt` | Possibly drop the `DocumentViewBackingViewModel` provide (decision below); ensure `MainViewModel` gets `DocumentBackingViewModel`. |
| `web/src/jsMain/.../main/MainViewModel.kt` | Adjust intent delegations to call `DocumentBackingViewModel` (or a thin web-side viewer) instead of `DocumentViewBackingViewModel`. |

## What stays in commonMain (and why)

| File | Status | Reason |
|------|--------|--------|
| `client/src/commonMain/.../main/DocumentBackingViewModel.kt` | **Unchanged** | Canonical document, autosave, primitive edits — the shareable core. |
| `client/src/commonMain/.../data/NoteRepository.kt` | **Unchanged** | I/O. |
| `client/src/commonMain/.../platform/FileSystem.kt` | **Unchanged** | expect/actual. |
| `client/src/commonMain/.../main/DocumentLayout.kt` (bullet helpers) | **Unchanged** | `bulletAsteriskColumn`, `subtreeEnd`, `hasChildren`, `parentBulletIdsOf`, `visibleRowsOf` are pure document semantics. They'll be reused by every platform. |
| `client/src/commonMain/.../main/DocumentLayout.kt` (wrap/cursor/selection helpers) | **Likely retire** | `wrapLine`, `chunkCountOf`, `cursorVisualPosition`, `locateLogicalPosition`, `visualRowOfCursor`, `chunkSelectionHighlight`, `ChunkHighlight` exist only because the web view paints text manually. Browser CSS replaces them on web; Android Compose `BasicTextField` and iOS `UITextView` replace them on those platforms. Delete in Milestone 2 once the web view no longer references them, or keep with a `@Deprecated` annotation if we want to be cautious. |
| `client/src/commonMain/.../main/DocumentViewBackingViewModel.kt` | **Reshaped, not bypassed** — see below. Stays as the commonMain "view into a document" abstraction; its fields and intents change to fit the contenteditable design (drop pixel-cursor state and manual-paint helpers; keep/add what represents a logical viewer: zoom target, visible row range, selection-aware intents that take `(row, col)` or `Selection` parameters from the platform). |

## `DocumentViewBackingViewModel` — reshape, do not bypass

Today this commonMain class holds cursor + anchor + selection state and exposes selection-aware editor intents (`insertChar`, `backspace`, `selectWord`, `onCutRequested`, etc.) that compose primitive document edits with cursor updates. Most of that shape is wrong for a contenteditable web view (and wrong for Compose `BasicTextField` / UIKit `UITextView` later) because the platform's native text surface already owns cursor/selection.

**Decision for this run: keep `DocumentViewBackingViewModel` as the commonMain abstraction representing "a view into a document", and reshape its contents to fit. Do not have the web view talk directly to `DocumentBackingViewModel`.** The class is the seam every platform plugs its native text surface into; preserving it is what keeps the layered architecture in `CLAUDE.md` honest and what makes Android/iOS adoption a pure additive step rather than a re-architecture.

What changes inside it:

- **Drop** state and intents that only existed to drive the manual paint loop: the always-present `cursorRow`/`cursorCol`, the `anchorRow?`/`anchorCol?` pair as the canonical selection store, any helper coupled to `charWidthPx` or wrap-in-chars, `cursorVisualPosition`/visual-row math, `ChunkHighlight` plumbing.
- **Keep** the role: it is still *one viewer's logical state on top of `DocumentBackingViewModel`*, mirroring the document state through a collector and emitting a single consistent snapshot per change.
- **Add or reshape** to fit:
  - A logical viewer model — zoom target row, visible row range / focused subtree, collapsed-row set, last-known logical caret as `(row, col)?` (useful for restoring caret after DOM reconciliation, *not* as the source of truth during editing).
  - Selection-aware intents take the selection as a parameter from the platform's native text surface — e.g. `insertText(selection: Selection, text: String)`, `backspace(selection: Selection)`, `indentLines(selection: Selection)`, `cut(selection: Selection)`, `selectWord(row: Int, col: Int) → Selection`. These compose `DocumentBackingViewModel` primitives and return the new logical caret/selection so the platform can write it back to its native surface.
  - A small `Selection` value type in commonMain (`(anchorRow, anchorCol, cursorRow, cursorCol)` or `(start, end)`) so the API is uniform across platforms; each platform translates to/from its native representation (DOM `Range`, Compose `TextRange`, UIKit `selectedRange`) at the edge.
  - Zoom and folding intents (`zoomInto(row)`, `toggleCollapsed(row)`) move here if they aren't already, since they are viewer state, not document state.

What stays the same:

- It still lives in commonMain, has no DOM/Compose/UIKit imports, and is constructed via `@Provides` in each platform's Metro graph.
- `MainViewModel` is still a thin facade over it. Web's `MainViewModel` delegates to the reshaped intents; it does **not** call `DocumentBackingViewModel` directly.
- One instance per viewer (window / device / pane), as today.

This is the design mature multi-platform editors (Lexical, ProseMirror, CodeMirror 6) converge on: *document model is shared; native text surface owns cursor/selection; a thin shared viewer layer ties them together.* `DocumentViewBackingViewModel` becomes that viewer layer, properly.

## Milestones

The three phases below are internal sequencing for the implementer — *not* separate deliverables. Run through all three in one go (see "Execution mode" above). The "verification gates" are self-checks to confirm the previous phase didn't regress before moving on; they are not approval gates.

### Milestone 1 — Architectural shift (proportional font lands here)

- Replace custom paint loop with contenteditable container.
- Each row = `<div data-row="N">`, text shown literally including any leading `* ` prefix (no bullet glyph styling yet).
- `beforeinput` listener routes character input, deletion, and Enter to `DocumentBackingViewModel.insertText` / `delete` / `insertNewline`.
- DOM reconciliation: on each state emission, set `textContent` on each row div if it changed (keep the diff narrow so caret doesn't reset more than necessary), then restore caret from a captured `(row, col)` snapshot.
- Caret read: `getSelection()` → ancestor `data-row` → `(row, anchorOffset)`.
- Caret write: find the row div, set selection at the requested offset.
- Bullet click-to-zoom: temporarily attach a click handler to anything that *looks* like a bullet (a `*` at the indent column), or accept that zoom is broken in this milestone and use a keyboard shortcut.
- Font: change `EditorStyle.fontFamily` to the proportional stack.
- **Verification gate**: type, paste, drag-select, click-to-place-caret, multi-row-select, copy/cut/paste, undo via browser default, IME (if testable). All work natively. Proportional font visible. No drift between caret and glyphs.

### Milestone 2 — Restore bullet glyphs and zoom

- Render `* ` prefix as a non-editable `<span class="bullet">•</span>` with the editable text after.
- Update DOM↔model column mapping to account for the prefix length per row (`data-prefix-len="N"`).
- Restore bullet click-to-zoom via the bullet span's click handler (existing `viewModel.zoomInto(row)` intent).
- Restore the bullet hover halo / glyph scale CSS from `OutlinePaintLoop.ensureStyles`.
- **Verification gate**: bullet glyphs render as `•`; clicking a bullet zooms; cursor cannot be placed inside the bullet; typing/backspace at the boundary behaves naturally.

### Milestone 3 — Restore folding and any chrome we lost

- Collapsed-bullet chevron: render and wire to the existing `toggleCollapsed(row)` intent.
- Any remaining visual polish (selection color via `::selection`, custom caret color via `caret-color`, scrollbar styling — most of this carries over unchanged).
- Delete the now-unused `DocumentLayout` wrap/cursor helpers and `ChunkHighlight`.
- **Verification gate**: parity with the current editor's feature set.

## Verification (end-to-end, after Milestone 1)

Run the web target (`./gradlew :web:jsBrowserDevelopmentRun` or the project's standard dev command — confirm in `web/build.gradle.kts` and any README) and check:

1. Editor renders in the new proportional font; nothing looks monospace.
2. Type into a row — caret sits exactly at the right edge of the last glyph, no drift.
3. Click anywhere mid-line, including on lines mixing narrow (`i`, `l`) and wide (`m`, `W`) glyphs — caret lands precisely where clicked.
4. Drag-select a partial line, then a multi-row span — selection rectangles match the browser's normal behavior.
5. Double-click selects a word; triple-click selects a line — natively, no custom code.
6. Copy with Cmd-C / Ctrl-C and paste into another app — gets the right text. Paste from another app — text appears.
7. Type non-Latin text via IME (if available) — composition works.
8. Press Enter — splits the current row; press Backspace at column 0 of a non-empty row — merges with previous (via `DocumentBackingViewModel.delete` primitive).
9. Press Tab — indents the current bullet (existing intent).
10. Resize the window — text reflows live; caret stays anchored.
11. After Milestone 2: click a `•` — zooms into that bullet, doesn't move the caret. Cursor cannot be placed in the bullet.
12. After Milestone 3: collapsed bullets fold and unfold; chevrons render.

## Out of scope

- **Android/iOS**: not implemented in this work. The architecture this plan moves to is *better* for them (native text surfaces) but the implementations are separate tasks.
- **Real-time collab cursors**: today's single shared cursor in commonMain is gone on web. That single-cursor sharing wasn't a real collab feature anyway; serious collab needs CRDTs.
- **Custom undo stack**: browser's undo for `contenteditable` is messy; we may want our own at some point. Out of scope here — accept browser default initially, add if/when it bites.
- **Word-aware wrap heuristics for the old painted model**: not needed; CSS does it.
