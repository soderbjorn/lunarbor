# Toolkit Hotkeys — Generalization Plan

Move generic keyboard-shortcut concerns into `darkness-toolkit` so all
darkness apps (notegrow, termtastic, …) share one hotkey mechanism.

## TL;DR

- The toolkit **already has** a hotkey registry (`HotkeyRegistry`,
  `Hotkey`, `HotkeyScope`) plus canonical chord constants
  (`StandardHotkeys.PreviousPane`, `NextPane`, `PreviousTab`, `NextTab`)
  in `toolkit-web/src/jsMain/.../hotkey/`. Pane nav is auto-wired in
  `LayoutRenderer.init`; tab nav is auto-wired inside `renderTabBar`.
- **Both apps already get all four chords for free** — both go through
  `LayoutRenderer` (pane nav) and through the toolkit's `renderTabBar`
  (tab nav, via `mountAppShell` → `TopBarSpec.tabBar` → `renderTopBar`
  → `renderTabBar`).
- An earlier draft of this plan claimed termtastic built its own tab
  bar. **That was wrong** — see "Termtastic & the tab bar" below.
  Termtastic uses the toolkit's tab bar; the only artefacts that look
  like duplication are stale unused imports in `WindowConnection.kt`.
- The remaining generic chord that *isn't* yet in the toolkit is
  `Cmd/Ctrl+/` — open the hotkeys cheatsheet — plus the modal it opens.
  Today it lives in notegrow's `HotkeysModal.kt`.
- Apps already *can* extend the registry with custom chords —
  `HotkeyRegistry.register` is public.
- The cheatsheet **content stays handpicked per app** — no auto-derive
  from the registry. Toolkit ships the modal shell (rendering,
  styling, `Cmd/Ctrl+/` binding, close behavior); each app passes in
  its own list of groups/entries. Notegrow keeps its four groups
  (Outline navigation, Editor, Panes & tabs, App); termtastic supplies
  a smaller list. Toolkit also exports the canonical chord labels for
  `StandardHotkeys` so apps don't have to rewrite "Ctrl+Alt+→" by hand.

The actual delta is small: promote the cheatsheet modal to the
toolkit *as a content-agnostic shell*, smoke-test that both apps
already light up for pane-nav and tab-nav, fix one rough edge in
the toolkit's pane focus path (focusing a hidden pane while another
is maximized — see §5), and remove the dead code catalogued below.

## Termtastic & the tab bar — investigation

Original concern: "termtastic builds its own tab bar; that's duplicate
functionality, why?"

**Conclusion: it doesn't.** Termtastic post-toolkit-migration uses the
toolkit's tab bar exclusively. Tracing the code paths:

```
termtastic/web/.../main.kt                 ── bootViaToolkitShell()
  → TermtasticToolkitBootstrap.kt:374      ── mountAppShell(spec)
  → toolkit AppShellMount.kt:1219          ── builds TabBarSpec
  → AppShellMount.kt:1353                  ── TopBarSpec(tabBar = …)
  → toolkit TopBar.kt:119                  ── renderTabBar(spec.tabBar)
  → toolkit TabBar.kt:231                  ── installTabNavigationHotkeys(spec)
```

That last line is the toolkit's pane/tab-nav installer firing for
termtastic, the exact same way it fires for notegrow.

What looks like duplication is purely cosmetic:

- `WindowConnection.kt:20-23` still imports `TabBarCallbacks`,
  `TabBarSpec`, `TabSpec`, and `buildTabElement` from the toolkit, but
  none of them are used in the file.
- The legacy comment at `WindowConnection.kt:273-287` documents this
  explicitly: "The toolkit handles each concern internally … historical
  reference: the previous body built the tab bar via `buildTabElement`
  …". The pre-migration `renderConfig` body that *did* build a bespoke
  tab strip was removed; the imports survived.
- A grep across `termtastic/develop/` confirms zero call sites for
  `buildTabElement`, `TabBarSpec(...)`, `TabSpec(...)`, or
  `renderTabBar` outside of those four import lines and the comment.

**What's still bespoke in termtastic** (and rightly so — these are
content, not chrome):

- A `TermtasticTabSource` that adapts the WebSocket-driven config push
  into the toolkit's `TabSource` interface. This is the documented
  extension point — apps push tab snapshots to the toolkit, the
  toolkit owns rendering.
- Per-pane content factories registered via `mountAppShell`'s
  `paneContent` slot (e.g. terminal panes, git pane, file-browser
  pane). These are the *contents* of panes, not the chrome around
  them.

Both are app-specific responsibilities the toolkit cannot supply.
Neither implies a parallel tab bar.

**Action — cleanup, not refactor:** delete the four stale imports in
`WindowConnection.kt` (and the `buildTabElement` reference can be
dropped from the legacy comment too). One-line change. No
behavioural risk; verified unused by grep.

**Consequences if we hadn't caught this:** the imports would have
continued to nag the next reader (and the next plan!) into thinking
termtastic had a parallel tab implementation. They cost compile time
proportional to package import resolution, and they leak knowledge
that termtastic *can* do its own tab rendering — which it shouldn't.
Removing them tightens the only-one-tab-bar invariant in the
codebase, both for humans and for static-analysis tooling.

## Current state — hotkey infrastructure

### Toolkit (`darkness-toolkit/develop/`)

```
toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/hotkey/
  Hotkey.kt          — pure data model; exact-match modifier mask;
                       case-insensitive letter keys.
  HotkeyRegistry.kt  — singleton, lazy window keydown listener in
                       capture phase; preventDefault only when an
                       action fires. Replace-on-register semantics.
                       `HotkeyScope.Global` (only value today).
  StandardHotkeys.kt — PreviousPane/NextPane (Ctrl+Alt+←/→) and
                       PreviousTab/NextTab (Ctrl+Alt+Shift+←/→).
```

Auto-wiring sites:

- `LayoutRenderer.init` (`toolkit-web/.../layout/LayoutRenderer.kt:257-258`)
  registers `NextPane` / `PreviousPane`, points them at
  `cycleFocusedPane(forward)`. Re-installed every time a renderer is
  constructed for the active tab.
- `renderTabBar`'s private `installTabNavigationHotkeys(spec)`
  (`toolkit-web/.../shell/TabBar.kt:260-263`) registers `NextTab` /
  `PreviousTab`, points them at `cycleTab(spec, forward)`. Skipped
  when fewer than two visible tabs exist.

Both fire for **both** apps because both go through `mountAppShell`
+ `LayoutRenderer` + `renderTabBar`.

### Notegrow

- Gets pane-nav and tab-nav for free via the toolkit.
- App-only chords as document-level `keydown` listeners in
  `AppShell.kt`, *not* through the registry today:
  - `installPaletteShortcut` — `Cmd/Ctrl+P` opens command palette.
  - `installNavigateToShortcut` — `Cmd/Ctrl+O` opens "Navigate to file".
  - `installStarredShortcut` — `Cmd/Ctrl+S` opens Starred bookmarks.
  - `installHotkeysShortcut` — `Cmd/Ctrl+/` opens `HotkeysModal`.
- `HotkeysModal.kt` — notegrow-local cheatsheet modal listing every
  chord. Data source is the in-file `HOTKEY_GROUPS` constant —
  hand-maintained, not driven by the registry, so it can drift.
- Outline-zoom (`Opt+Cmd+Enter/↑/←/→`, `Esc`) and editor chords
  (`Cmd+B/I/A/Z`, `Tab`, `Shift+Tab`) live inside
  `MainScreen.handleKey`. Notegrow-specific; not toolkit candidates.

### Termtastic

- Gets pane-nav and tab-nav for free via the toolkit (verified above).
- No app-level hotkeys today. No command palette, no cheatsheet modal.
- Only dialog-local handlers (`WorktreeDialog`, `PaneTypeModal`,
  `QuitConfirmationDialog`, `RenameHandlers`, `AboutDialog`) listen
  for `Escape` / `Enter`.
- xterm.js inside terminal panes attaches its own key handlers to its
  textarea, but the toolkit's window-level **capture-phase** listener
  beats those, so pane-nav and tab-nav still fire even when a
  terminal has focus. Verify in smoke test.

## What's "generic" vs "app"

| Chord | Action | Verdict |
|---|---|---|
| `Ctrl+Alt+←/→` | Previous / Next pane | **Toolkit** — already there, both apps |
| `Ctrl+Alt+Shift+←/→` | Previous / Next tab | **Toolkit** — already there, both apps |
| `Cmd/Ctrl+/` | Open hotkeys cheatsheet | **Toolkit** — promote modal |
| `Cmd/Ctrl+P` | Command palette | App — palette content is app-specific |
| `Cmd/Ctrl+O` | Navigate to file | App — notegrow-specific |
| `Cmd/Ctrl+S` | Starred bookmarks | App — notegrow-specific |
| `Opt+Cmd+Enter/↑/←/→`, `Esc` | Outline zoom | App — notegrow-specific |
| `Cmd+B/I/A/Z`, `Tab/Shift+Tab` | Inline markdown / indent | App — notegrow editor |

Pane-nav, tab-nav, and "open the cheatsheet" are the three chords
that belong in the toolkit. Everything else stays in the app it
serves, but registered through the same `HotkeyRegistry` so it
appears in the cheatsheet too.

## Proposed changes

### 1. Promote the hotkeys cheatsheet shell into the toolkit

**Why:** `Cmd+/` → "show me what shortcuts exist" is generic UX.
Every darkness app should have it. Today only notegrow does, and the
~600 lines that implement it would otherwise be copy-pasted on the
next app to want one.

**Design preference (per the user):** the cheatsheet *content* is
handpicked by each app, not derived from the registry. Apps know
which chords are user-facing vs. plumbing, what to call each entry,
how to group them, and which icon to show. The toolkit owns the
shell — modal element, styling, group/entry rendering, `Cmd+/`
binding, `Esc` close — but takes the list of groups as input.

**Add** to the toolkit:
`toolkit-web/src/jsMain/.../hotkey/HotkeyCheatsheet.kt` plus its CSS.

Public API:

```kotlin
/** One row in the cheatsheet. Apps construct these directly. */
data class HotkeyEntry(
    val label: String,                // "Zoom into bullet"
    val chord: List<String>,          // ["⌥", "⌘", "⏎"] — already
                                      // platform-resolved by the app
    val iconSvg: String? = null,      // optional inline svg
)

/** A titled group of [HotkeyEntry] rows. */
data class HotkeyGroup(
    val title: String,                // "Outline navigation"
    val entries: List<HotkeyEntry>,
)

/** Spec consumed by [ToolkitHotkeysModal.setContent]. */
data class HotkeysModalSpec(
    val groups: List<HotkeyGroup>,
    val footerNote: String? = null,   // optional small print
)

/**
 * Modal shell. Apps construct one and call [setContent] with their
 * handpicked list whenever it changes (typically once at boot).
 */
class ToolkitHotkeysModal {
    fun setContent(spec: HotkeysModalSpec)
    fun open()
    fun close()
    val isOpen: Boolean
}

/**
 * Convenience: register Cmd/Ctrl+/ against [HotkeyRegistry] pointing
 * at [modal].open(). Idempotent (replace-on-register). Apps can
 * skip this and bind their own chord if they prefer.
 */
fun installCheatsheetHotkey(modal: ToolkitHotkeysModal)
```

Theming uses existing toolkit CSS variables. No new variables, no
new theme keys.

**Toolkit also exports** canonical chord-label helpers so apps don't
hand-write "Ctrl+Alt+→" for the four `StandardHotkeys`:

```kotlin
/**
 * Platform-formatted chord string for a [Hotkey]. macOS uses ⌃ ⌥ ⇧ ⌘
 * glyphs; Windows/Linux uses Ctrl/Alt/Shift/Win words. Returned as a
 * `List<String>` matching [HotkeyEntry.chord].
 */
fun Hotkey.toChordLabel(): List<String>
```

This is a pure helper — apps use it by calling
`StandardHotkeys.NextPane.toChordLabel()` when assembling their entry
list. The toolkit does **not** auto-include any entries on the app's
behalf.

### 2. Wire each app's handpicked content

**Notegrow:**
- Keep the existing four-group structure (Outline navigation, Editor,
  Panes & tabs, App) as the source of truth — *move* it from the
  doomed `HotkeysModal.kt` to a small new file like
  `main/NotegrowHotkeysContent.kt` that builds a `HotkeysModalSpec`.
- For the "Panes & tabs" group, prefer `StandardHotkeys.*.toChordLabel()`
  over hardcoded glyph lists so the labels stay in sync with the
  toolkit's chord constants.
- In `AppShell` boot: construct `ToolkitHotkeysModal()`, call
  `setContent(notegrowHotkeysSpec())`, call
  `installCheatsheetHotkey(modal)`. Keep the electron menu-item
  bridge but point it at the toolkit modal's `open()`.
- *Delete* `main/HotkeysModal.kt`, the `installHotkeysShortcut`
  function in `AppShell.kt`, and the `hotkeysShortcutHandler` field.
- Wire `onFloatingMaximizeCleared` in the `PaneCallbacks`
  construction site (sibling of the existing
  `onFloatingMaximizeToggled`): flip the matching
  `FloatingPaneSpec.isMaximized` to `false` and re-render — same
  shape as the toggle handler, just unconditional clear instead of
  flip.

**Termtastic:**
- Add `TermtasticHotkeysContent.kt` listing termtastic's user-facing
  chords. Initial pass:
  - "Panes & tabs": `StandardHotkeys.PreviousPane`,
    `StandardHotkeys.NextPane`, `StandardHotkeys.PreviousTab`,
    `StandardHotkeys.NextTab` (chord labels via `toChordLabel`).
  - "Dialogs": Esc to dismiss, Enter to confirm — these are
    convention-level entries the user expects to see.
- In `TermtasticToolkitBootstrap.kt`: same three lines as notegrow
  (construct modal, `setContent`, `installCheatsheetHotkey`).
- Wire `onFloatingMaximizeCleared` alongside the existing
  `PaneCallbacks` — push a fresh tab snapshot via
  `TermtasticTabSource` with the flag cleared so the toolkit
  animates the unmaximize.

**Outcome:** notegrow's modal shows the exact same content the user
sees today. Termtastic gets a (smaller) modal it didn't have before.
Neither modal is auto-populated; both are explicit lists the
maintainer can curate without surprises.

### 3. App-extension hooks — already there

`HotkeyRegistry.register` is public and apps can freely register
their own chords against it. No new mechanism is needed for apps
to add custom chords — the registration already works; only the
cheatsheet listing is handpicked (as requested).

### 4. Verify pane-nav and tab-nav already work in both apps

Smoke test, not a code change. The expectation — and what the code
paths above predict — is that `Ctrl+Alt+←/→` and
`Ctrl+Alt+Shift+←/→` already fire in both apps, including when a
terminal pane has focus in termtastic (capture-phase window listener
beats xterm's textarea handler).

If the smoke test surfaces a gap (xterm consuming the chord — possible
but unexpected since xterm hooks its own textarea, not `window`), the
fix would be an xterm `attachCustomKeyEventHandler` that returns
`false` for the four `StandardHotkeys` chords. Mention but don't
pre-build.

### 5. Unmaximize on focus-of-hidden-pane

**Problem.** When a pane in the active tab is maximized
(`FloatingPaneSpec.isMaximized = true`), it covers every other pane
in the tab. Today, focusing a different pane via
`Ctrl+Alt+←/→` (toolkit hotkey, calls
`LayoutRenderer.cycleFocusedPane` → `focusPane`) **or** via a
sidebar pane click (host calls `LayoutRenderer.focusPane(paneId)`
directly — documented use case in `LayoutRenderer.kt:443-444`)
moves focus, but the target pane stays invisible because the
maximized pane is still on top. The `cycleFocusedPane` filter at
`LayoutRenderer.kt:469` already excludes minimized panes but
*not* maximized ones — and even if it did, the cycle would just
skip every other pane, which is also wrong (the user wants to
*get to* a hidden pane, not be told "no other targets exist").

**Desired behavior.** Whenever `focusPane(paneId)` is asked to
focus a pane that is not the currently-maximized pane, the
maximized state should be cleared first, with the existing
maximize/restore CSS transition (`LayoutRenderer.kt:516-565`)
animating the unmaximize. After the transition the target pane is
visible at its normal geometry, and the focus class lands on it.

This rule applies uniformly — it's a property of `focusPane`, not
of the hotkey path — so it covers:

- `Ctrl+Alt+←/→` hotkey (via `cycleFocusedPane → focusPane`).
- Sidebar pane-list clicks (host calls `focusPane` directly).
- Any other future caller that drives focus from outside the
  layout (tab-switch auto-focus, command-palette "go to pane",
  etc.).

**Toolkit-side change** in `LayoutRenderer.kt`:

- Extend `PaneCallbacks` with an optional callback used to ask
  the host to clear the maximized flag:

  ```kotlin
  /**
   * Fired when the renderer needs the host to drop the
   * maximized flag off a pane (currently: focusing a different
   * pane while [paneId] is maximized). Hosts flip the matching
   * [FloatingPaneSpec.isMaximized] back to `false` and re-render;
   * the renderer's existing transition machinery animates the
   * restore. `null` to leave the maximized pane on top — focus
   * still moves but the target pane stays hidden, which is the
   * pre-existing behaviour.
   */
  val onFloatingMaximizeCleared: ((PaneId) -> Unit)? = null,
  ```

  Reusing the existing `onFloatingMaximizeToggled` is tempting
  (semantically it's "toggle off") but spelling out the intent
  pays for itself: hosts that don't allow user-initiated maximize
  toggles still want the auto-clear here, and the named callback
  lets them wire only this one path without enabling the
  toggle button on pane headers.

- Inside `focusPane(paneId)` (and equivalently the path it's
  reached from `cycleFocusedPane`):

  ```kotlin
  val maximized = lastLayout.floatingPanes.firstOrNull { it.isMaximized }
  if (maximized != null && maximized.id != paneId) {
      callbacks.onFloatingMaximizeCleared?.invoke(maximized.id)
  }
  // existing focus logic continues:
  focusedLeafId = paneId
  applyFocusClass(paneId)
  callbacks.onPaneFocused(paneId)
  ```

  Order matters: ask for the clear *first*, then update internal
  focus state and fire `onPaneFocused`. Hosts re-render
  asynchronously; setting `focusedLeafId` synchronously means the
  next render will already have the right focus target alongside
  the unmaximized geometry, and the renderer's
  `previousMaximizedStates` check produces the transition.

- The "filter visible panes" rule for `cycleFocusedPane` is
  unchanged — it still uses `filterNot { it.isMinimized }`. We
  *want* to cycle into the maximized pane and into panes hidden
  by it; the unmaximize logic in `focusPane` handles the
  visibility.

- Edge cases:
  - **No pane maximized:** callback never fires. Standard focus.
  - **Target IS the maximized pane:** callback never fires;
    target is already visible. Standard focus.
  - **Multiple panes flagged maximized** (theoretical — UX intent
    is one-at-a-time, and `clearMaximized()` in
    `FloatingPane.kt:73-87` exists to enforce this on add):
    `firstOrNull` clears one. Hosts that allow multiple should
    iterate themselves; not a real case for either current app.

**Host-side wiring.**

- **Notegrow:** wire `onFloatingMaximizeCleared = { paneId ->
  layout flips that pane's `isMaximized = false` and re-renders
  }`. Same shape as the existing `onFloatingMaximizeToggled` —
  one new closure in the `PaneCallbacks` construction site.
- **Termtastic:** identical wiring in
  `TermtasticToolkitBootstrap.kt`. Termtastic's `TabSource`-driven
  config push is the path back to the renderer; the host pushes a
  fresh tab snapshot with the flag cleared and the toolkit
  animates.
- Both apps' sidebar clicks already call
  `LayoutRenderer.focusPane(paneId)` (or equivalent through their
  own focus pipelines that funnel into the toolkit), so no
  sidebar-side changes are needed.

**Out of scope for this section.** The "click on a maximized pane's
own header" gesture, drag-from-sidebar, and tab-switch animation
are all separate flows already handled. This change only addresses
focus-mediated unmaximize.

### 6. Dead-code removal (discovered during this investigation)

These removals are independent of the cheatsheet work and can land
in the same branch or separately. All confirmed unused by grep.

**Termtastic — `WindowConnection.kt`:**

- `WindowConnection.kt:20-23` — delete four stale imports left over
  from the toolkit migration:
  ```kotlin
  import se.soderbjorn.darkness.web.shell.TabBarCallbacks
  import se.soderbjorn.darkness.web.shell.TabBarSpec
  import se.soderbjorn.darkness.web.shell.TabSpec
  import se.soderbjorn.darkness.web.shell.buildTabElement
  ```
- `WindowConnection.kt:273-287` — the legacy-reference comment plus
  the empty `_legacyRenderConfigRemoved()` placeholder (annotated
  `@Suppress("UNUSED")`, body is a single comment "Intentionally
  empty"). The comment explains a removal that's already two
  migrations old and the function exists only to host the
  `@Suppress`. Delete both. If a tombstone is wanted, leave a
  one-line comment on `renderConfig` itself ("post-migration:
  toolkit owns chrome rebuild") and drop the rest.

**Notegrow — superseded by §1/§2:**

- `web/src/jsMain/.../main/HotkeysModal.kt` — entire file deleted
  once the toolkit modal lands and `NotegrowHotkeysContent.kt`
  takes over content authorship. The CSS string baked into this
  file (referenced from `AppShell.kt:1545`) follows the same path —
  the toolkit modal supplies its own stylesheet.
- `AppShell.kt` — `installHotkeysShortcut`,
  `hotkeysShortcutHandler`, the `hotkeysModal` lazy property, and
  the `${HotkeysModal.STYLESHEET}` injection at line 1545 all go
  with the file. The single `installCheatsheetHotkey(modal)` call
  replaces the bundle.

**Cross-repo — none found beyond the above.** A pass over both
apps for unused imports and `@Suppress("UNUSED")` placeholders that
shadow toolkit concerns is reasonable to do alongside, but is
out of scope for this plan unless we find more during execution.

## Files touched

### `darkness-toolkit/develop/`

- *(new)* `toolkit-web/src/jsMain/.../hotkey/HotkeyCheatsheet.kt` —
  `HotkeyEntry`, `HotkeyGroup`, `HotkeysModalSpec`,
  `ToolkitHotkeysModal`, `installCheatsheetHotkey`. Content-agnostic
  shell — apps supply the spec.
- `toolkit-web/src/jsMain/.../hotkey/Hotkey.kt` — add
  `Hotkey.toChordLabel(): List<String>` extension (platform-formatted
  chord glyphs). Used by apps when assembling their cheatsheet
  content; toolkit itself does not consume it elsewhere.
- `toolkit-web/src/jsMain/.../layout/LayoutRenderer.kt` — add
  `onFloatingMaximizeCleared: ((PaneId) -> Unit)? = null` to
  `PaneCallbacks`, and call it from `focusPane` when the target
  pane differs from the currently-maximized pane (see §5).
- *No changes* to `HotkeyRegistry.kt`, `StandardHotkeys.kt`,
  `TabBar.kt` — pane-nav and tab-nav fire correctly; no
  descriptor mechanism is being added.

### `notegrow/develop/web/src/jsMain/.../`

- *(new)* `main/NotegrowHotkeysContent.kt` — builds the
  `HotkeysModalSpec` listing notegrow's four groups (Outline
  navigation, Editor, Panes & tabs, App). Source of truth for
  cheatsheet content; mirrors today's `HOTKEY_GROUPS` constant.
- `main/AppShell.kt` — replace `installHotkeysShortcut` +
  `hotkeysShortcutHandler` + `hotkeysModal` lazy + the
  `${HotkeysModal.STYLESHEET}` injection at line 1545 with: build a
  `ToolkitHotkeysModal()`, call `setContent(notegrowHotkeysSpec())`,
  call `installCheatsheetHotkey(modal)`. Point the electron
  menu-item bridge at the toolkit modal's `open()`.
- *(delete)* `main/HotkeysModal.kt` — superseded.

### `termtastic/develop/web/src/jsMain/.../`

- *(new)* `TermtasticHotkeysContent.kt` — small `HotkeysModalSpec`
  builder (Panes & tabs from `StandardHotkeys.*.toChordLabel()`, plus
  Dialogs group for Esc/Enter conventions).
- `TermtasticToolkitBootstrap.kt` — three lines at boot: construct
  modal, `setContent`, `installCheatsheetHotkey`.
- `WindowConnection.kt` — **dead-code cleanup:**
  - delete imports at lines 20-23 (`TabBarCallbacks`, `TabBarSpec`,
    `TabSpec`, `buildTabElement`);
  - delete the legacy comment block + empty
    `_legacyRenderConfigRemoved()` placeholder at lines 273-287.
  - Optionally retain a single one-line comment on `renderConfig`
    explaining its post-migration scope.

## Test plan

Manual, both apps, both macOS modifier maps:

- **Pane nav (regression check):** `Ctrl+Alt+←/→` cycles focused
  pane in both apps. Wraps. Skips minimized. Works even when a
  terminal pane has focus in termtastic (capture-phase listener).
  No conflict with browser word-jump (`Opt+←/→` *without* Ctrl).
- **Tab nav (regression check):** `Ctrl+Alt+Shift+←/→` cycles tabs
  in both apps. Wraps. Single-tab apps no-op. Hidden tabs skipped.
  In termtastic, confirm the binding survives a tab-list rebuild
  (open a new pane, switch tab, rebind still works thanks to
  replace-on-register).
- **Cheatsheet (new):** `Cmd/Ctrl+/` opens the toolkit cheatsheet
  in both apps. The list matches the registered chords (pane-nav +
  tab-nav guaranteed; any app-side `register(... descriptor = …)`
  calls show too). `Esc` closes it. Re-opening works. Modal is
  themed.
- **Notegrow regression:** Cmd+P, Cmd+O, Cmd+S continue to work
  after the cheatsheet swap.
- **Termtastic edge:** open a focused contenteditable (e.g. the
  worktree-dialog input) — confirm it does not eat
  `Ctrl+Alt+Shift+←/→` (capture-phase registry should beat the
  input's keydown).
- **Smoke:** open multiple tabs, focus a pane, fire each chord
  rapid-fire — no exceptions, no console spam.
- **Unmaximize-on-focus (§5), hotkey path:** in a tab with ≥2
  panes, maximize one pane; press `Ctrl+Alt+→`. The maximized
  pane animates back to its stored geometry (existing transition)
  and focus lands on the next pane in z-order, which is now
  visible. Reverse with `Ctrl+Alt+←`. Test in both apps.
- **Unmaximize-on-focus (§5), sidebar path:** maximize one pane,
  click a different pane in the sidebar. Same behavior — the
  maximized pane unmaximizes with transition, focus lands on the
  clicked pane. Test in both apps.
- **Unmaximize-on-focus, target IS the maximized pane:** focus
  via hotkey or sidebar onto the already-maximized pane. No
  animation, no flicker — focus is a no-op visually because the
  pane is already on top. (Verify the callback is *not* fired in
  this case so the host doesn't push a redundant config.)
- **Unmaximize-on-focus, no maximized pane:** baseline — focus
  flow is unchanged. Verify no regressions in standard pane
  cycling.

## Risks & call-outs

- **Registry is global and cross-app safe-by-construction** because
  it's the toolkit's singleton; one window, one registry. No new
  isolation work required.
- **Replace-on-register is the contract.** Already established by
  `renderTabBar` and `LayoutRenderer.init`; the cheatsheet modal
  follows the same pattern (its hotkey is registered once at boot
  and never re-keyed).
- **Cheatsheet content is handpicked, by design.** No
  registry-driven auto-listing in this plan — keeps every entry's
  label and grouping under maintainer control, at the cost that
  adding a new chord without a matching cheatsheet entry will leave
  it unadvertised. Acceptable trade-off; the `HOTKEY_GROUPS`-style
  list lives next to the registration site so they're easy to keep
  in sync.
- **Not promoting Cmd+P / Cmd+O / Cmd+S to the toolkit.** Their
  semantics are notegrow-specific. Per the "Minimal solution first"
  preference, not introducing a toolkit "command palette"
  abstraction now.
- **No backwards-compat layer for `HotkeysModal` removal** —
  only consumer is notegrow's own `AppShell`. Per the "no
  persistence compat" preference, dropped without a transitional
  shim.
- **xterm.js focus is the most likely smoke-test failure.** If it
  turns out to swallow the chord before capture-phase, fix with an
  `attachCustomKeyEventHandler` that returns `false` for the four
  StandardHotkeys chords. Don't pre-build.

## Out of scope

- Refactoring the editor / outline keydown handler in
  `MainScreen.handleKey` to flow through `HotkeyRegistry`. The
  editor chords are correct as-is, and notegrow's cheatsheet
  already lists them via the handpicked content spec — no extra
  plumbing needed.
- Auto-deriving cheatsheet entries from the registry. Explicitly
  ruled out per maintainer preference; entries are handpicked.
- Adding `HotkeyScope.TextInput` (skip when focused element is
  editable). The current chords don't collide with any text-editing
  shortcut; the enum is in place for when a binding actually needs
  scoping.
- Adding non-web (Android / iOS / desktop Compose) hotkey plumbing.
  This plan is `jsMain` only, matching where the existing toolkit
  hotkey code lives.
- Building a toolkit-side "command palette" abstraction. Not enough
  signal yet — only one app has one.
