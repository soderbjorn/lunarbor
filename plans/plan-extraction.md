# Toolkit Shell Extraction — Termtastic → darkness-toolkit → Lunarbor

## Progress log

- **2026-04-26 — Phase 9 lunarbor final wire-up ✅ done.**
  - **Side quest: `toolkit-store` JS target.** Phase 7 originally shipped `LayoutState` + filesystem actuals on JVM/Android/iOS only — the assumption was "browsers can't reach the filesystem so they don't need this module." That assumption broke on the renderer side: lunarbor's web bundle needs the `LayoutState` / `PaneNodeJson` / `SidebarState` / `TabState` data classes for parsing the boot snapshot and serialising mutations through the IPC bridge, even though the disk operations themselves run main-process-side. Added `js(IR) { browser() }` to `toolkit-store/build.gradle.kts` and shipped three jsMain stubs: `LayoutState.js.kt`, `UiSettingsStore.js.kt`, `Closeable.js.kt`. Each stub returns `null`/`false` for the filesystem helpers and a no-op `Closeable` for the watch helpers — the model classes themselves live in commonMain and are fully usable on JS. Lunarbor `web/build.gradle.kts` gained `implementation(libs.darkness.store)`; the composite-build `dependencySubstitution` block in `lunarbor/develop/settings.gradle.kts` already mapped `toolkit-store` → `:toolkit-store` so no settings change was needed.
  - Lunarbor: added `web/.../main/PaneTreeJson.kt` — three thin extension fns bridging the toolkit's `PaneNode` (jsMain) to commonMain's `PaneNodeJson`. `PaneNode.toPaneNodeJson()`, `PaneNodeJson.toPaneNode()`, and `PaneNodeJson.toPaneTree()` are one-liners that pattern-match on the sealed class and translate orientations between the parallel enum types.
  - Lunarbor: rewrote `web/.../main/AppShell.kt` end-to-end. The shell now does what the plan called for in one `mountAppFrame(...)` call:
    - **TopBar** with `leadingContent = "Lunarbor"`, a full `TabBarSpec` (per-tab close, `+` add button, drag-to-reorder, double-click rename — all gated only by tab-count guards), and a trailing slot holding a palette glyph + appearance toggle.
    - **Tabs** are first-class state. Each tab carries its own `PaneLayout` (`tabLayouts: Map<String, PaneLayout>`); switching the active tab swaps the renderer's tree. `addTab()` allocates a fresh `tab-{n}` / `pane-{n}` id pair, drops a single-leaf placeholder pane in, and switches active. `closeTab(id)` is gated when the tab list has only one entry. Reorder and rename mutate `layoutState.tabs` in place and re-render.
    - **Pane chrome** via `PaneHeaderSpec(actions = [expandRestore, close, kebab], onRename = …, isDraggable = true)`. `expandRestore` flips between `PaneActions.expand` / `PaneActions.restore` based on the layout's `expandedLeafId`; `close` is gated to keep the last leaf in a tab. The kebab button has no anchor at construction time (the button doesn't exist in the DOM yet), so the click handler is wired *after* render via a `wireKebabFromSlot(slot, paneId)` helper that walks up to `.dt-pane`, finds `.dt-pane-action-kebab`, and calls `openPaneMenu(anchor, PaneMenuSpec(…))` with `splitHorizontal` / `splitVertical` / `toggleExpand` / `close` items. Cross-tab pane drag is wired via `PaneCallbacks.onPaneDragged` — the dropped source becomes a horizontal split next to the target. Inline rename uses the toolkit's hover-arm gesture verbatim (no lunarbor-side reimplementation).
    - **Right sidebar** mounts the toolkit's `ThemeManager` when `themeManagerOpen = true`. The sidebar shell wraps a placeholder `mountTarget` div; after the AppFrame is in the DOM, a `requestAnimationFrame` callback invokes `showThemeManager(themeHost, mountInto = mountTarget)` so the manager's CSS-var inheritance resolves correctly. The sidebar is `isResizable = true` with `onResize` writing back to `layoutState.rightSidebar.widthPx` and persisting. Toggle behaviour: clicking the palette button flips the open flag and rebuilds the shell; closing the sidebar calls `closeThemeManager()`.
    - **Theme persistence** via `DefaultThemeManagerHost`. `themeState: DefaultThemeManagerState` is the shell's source of truth for `mainSchemeName` / `appearance` / per-section overrides; the host's `onChange` callback mirrors mutations into `uiSettings` and calls `persistUiSettings()`. The appearance toggle in the trailing slot now writes through `themeState.appearance` (same path the manager uses) so the manager and the toggle stay in lock-step. The existing `subscribeToExternalThemeChanges()` IPC subscription was kept verbatim — Electron pushes external file changes here, and the callback re-seeds `themeState`, repaints the document, and `refreshThemeManager()`s any open editor.
    - **Layout-state persistence** via the new `darknessApi.writeLayoutState` / `readLayoutState` IPC bridge. Boot reads `globalThis.__darknessLayoutState` (the preload's pack-into-`additionalArguments` channel from Phase 7); each layout mutation calls `persistLayoutState()` which snapshots `tabLayouts` into `LayoutState.tabs`, mirrors `themeManagerOpen` into `layoutState.rightSidebar.visible`, and fires `darknessApi.writeLayoutState(json)` if the API is present. No-ops cleanly outside Electron. The boot path also promotes the active tab's first leaf to the well-known `editorPaneId = "pane-editor"` so the live `MainScreen` always has somewhere to mount even on a freshly-defaulted state.
  - Lunarbor: `Main.kt` is unchanged from the Phase-8 patch (still calls `tagBodyForElectronMac()` then `AppShell(...).render(app)`).
  - All four targets compile via composite build (with `--no-configuration-cache` to bypass a stale config-cache entry from before the toolkit-store target list changed): `:toolkit-store:compileKotlinJs`, `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, termtastic `:web:compileKotlinJs` — all green.
  - Webpack bundle re-emitted via `:electron:copyWebBundle --rerun-tasks` so Electron picks up the new shell.
  - **Verification still owed:** Phase-9 visual smoke test in Electron: (a) tab `+` adds a tab; click on a tab switches active; double-click renames; HTML5-drag reorders; × closes (and is hidden when only one tab remains); (b) palette button opens the right sidebar with the ThemeManager mounted; sidebar drag-resize works and persists; (c) pane chrome's expand/restore button toggles; close button is hidden on the last leaf; rename gesture works; kebab opens the split/expand/close popover; (d) appearance toggle still cycles Auto/Dark/Light and persists; (e) layout-state JSON round-trips through `darknessApi.writeLayoutState` (kill the app, relaunch — last tab list, active tab, sidebar width should restore); (f) the Phase-6 ThemeManager wiring works end-to-end now that a real host is wired (theme assign / favorite / clone / save).
- **2026-04-26 — Phase 7 toolkit-add + lunarbor Electron wiring ✅ done.**
  - Toolkit (`toolkit-store/commonMain`): added `LayoutState.kt` with `data class LayoutState(schemaVersion, leftSidebar, rightSidebar, activeTabId, tabs)`, `data class SidebarState(widthPx, visible, collapsed)`, `data class TabState(id, title, expandedLeafId, tree)`, `sealed class PaneNodeJson` (with `Leaf(id, title)` / `Split(orientation, ratio, first, second)` and a `SplitOrientation { Horizontal, Vertical }` nested enum). The JSON envelope carries `schemaVersion: 1` (per the plan's risk note); higher versions parse to `defaults()` rather than crash. Top-level `LayoutState.toJsonString()` / `fromJsonString()` mirror `UiSettings`'s hand-rolled `Json.encodeToString` + `parseToJsonElement` style — no `kotlinx.serialization` codegen needed (avoids @Serializable plugin gymnastics across native/JS/JVM). `defaults()` ships one tab + one leaf so apps never have to handle a "zero tabs" empty state on first launch. Split `ratio` is quantised to 4 decimal places on every serialize to keep saved files diffable across drag cycles. Right sidebar defaults `visible = false` so apps with no right sidebar don't render an empty rail.
  - Toolkit: added `expect fun defaultAppLayoutStatePath(appName)`, `expect fun readLayoutState(path)`, `expect fun writeLayoutState(path, layout)`, `expect fun watchLayoutState(path, onChange)`. Path conventions follow the plan: `~/Library/Application Support/Darkness/<AppName>/layout-state.json` on macOS, `%APPDATA%\Darkness\<AppName>\layout-state.json` on Windows, `$XDG_CONFIG_HOME/darkness/<app-name>/layout-state.json` on Linux (lowercased per XDG). `<AppName>` is whatever the app passes — lunarbor uses `"Lunarbor"`. Per-app, **not shared** (lunarbor's tree ≠ termtastic's; different blast radius and write cadence).
  - Toolkit: added the three actuals. `LayoutState.jvm.kt` is a line-for-line mirror of `UiSettingsStore.jvm.kt` — same OS sniff (mac/windows/xdg), same atomic write via `Files.move(ATOMIC_MOVE)` with `AtomicMoveNotSupportedException` fallback to non-atomic replace, same per-path `lastWrittenLayoutBytes` self-write suppression map, same `WatchService` daemon thread with 200ms debounce. `LayoutState.android.kt` mirrors `UiSettingsStore.android.kt` — `File.renameTo`-based atomic write, `FileObserver` filewatcher, API-29-aware constructor selection. `LayoutState.ios.kt` is a stub matching `UiSettingsStore.ios.kt`'s posture (returns null/false; iOS Darkness apps consume layout via the host's existing flow until a fully-local iOS app needs it).
  - Lunarbor Electron: `electron/main.js` extended with `defaultAppLayoutStatePath()` (mirrors `defaultDarknessSettingsPath()` but appends the per-app subdir `Lunarbor/`), `readDarknessLayoutStateSync()` for the boot-time pack into `additionalArguments`, and two new IPC handlers — `darkness:writeLayoutState` (atomic tmp+rename, records `lastWrittenLayoutBytes` for future watch suppression) and `darkness:readLayoutState` (returns null on ENOENT). `BrowserWindow` construction now packs both `--darkness-settings=…` and `--darkness-layout-state=…` into `additionalArguments`. The watch helper is intentionally **not** wired Electron-side for v1 — layout state is written *by* this app, not *by* external apps, so the cross-process notify pattern that justifies `darkness:uiSettingsChanged` doesn't apply here. The Kotlin `watchLayoutState` actual still exists for future server-backed scenarios (e.g. cross-window sync).
  - Lunarbor Electron: `electron/preload.js` extended with the matching `__darknessLayoutState` global (parses the new arg) and two `darknessApi` methods: `writeLayoutState(json) → Promise<void>` and `readLayoutState() → Promise<string|null>`. Symmetric with the existing `writeUiSettings`/`readUiSettings` shape so the renderer-side bridge stays predictable.
  - All four targets compile via composite build: `:toolkit-store:build` (jvm + android + iosArm64 + iosSimulatorArm64), `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, termtastic `:web:compileKotlinJs` — all green.
  - **Termtastic swap — deferred per plan.** Termtastic persists per-client UI bits server-side via existing `/api/ui-settings`. The toolkit's `LayoutState` schema is now the canonical shape; termtastic's server-side adapter (round-trip the same JSON envelope through its REST endpoint) lands in its own follow-up. The toolkit doesn't care which transport the host uses; it only defines the shape and ships filesystem actuals for non-server apps.
  - **Verification still owed:** Phase-7 round-trip smoke test once lunarbor's Phase-9 wire-up reads `__darknessLayoutState` at boot, applies it to the live `PaneTree` + sidebar widths + active tab, and writes back via `darknessApi.writeLayoutState` on every layout mutation. The bridge is in place; only the renderer-side wire-up is left, and that's Phase 9's job. Also: a lunarbor-side `PaneTree ↔ PaneNodeJson` converter (one extension each direction in jsMain) is owed in Phase 9.
- **2026-04-26 — Phase 8 toolkit-add + lunarbor opt-in ✅ done.**
  - Toolkit CSS: appended `body.dt-electron-mac .dt-topbar-leading { padding-left: 80px; }` to `darkness-toolkit.css`. The class is the public opt-in — apps detect Electron + macOS at boot and add it to `<body>`. The rule is keyed off the toolkit's `.dt-topbar-leading` so it only fires for hosts that use the toolkit's `TopBar` primitive; apps that stuff their own header into the AppFrame slot (current termtastic) keep their own padding rule until they migrate. The stylesheet itself does no UA sniffing — the host owns detection.
  - Lunarbor: `Main.kt` gained a `tagBodyForElectronMac()` helper that runs at `window.onload` (before `AppShell.render`). It UA-sniffs `Electron` + `Mac OS X` and adds `dt-electron-mac` to `<body>` so the toolkit rule paints. Non-mac Electron and plain browser dev (no `Electron` in UA) skip the class — non-mac platforms put window controls on the right and don't need the leading padding.
  - **Termtastic swap — deferred.** Termtastic's existing `body.is-electron-mac .app-header { padding-left: 80px }` rule still targets the legacy `.app-header` element it stuffs into the AppFrame's topBar slot (Phase 1 re-parented `#app` but kept termtastic's own header DOM intact). The rename `is-electron-mac` → `dt-electron-mac` lands when termtastic migrates its top bar to the toolkit `TopBar` primitive (separate, larger swap; not this phase's concern). The two body classes can co-exist in the meantime — termtastic doesn't load the toolkit stylesheet's electron-mac rule unless it ever adds `dt-electron-mac` itself.
  - All three projects compile via composite build: `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, termtastic `:web:compileKotlinJs` all green.
  - **Verification still owed:** Phase-8 visual smoke test once lunarbor runs in Electron on macOS to confirm the leading slot reserves room for the traffic-light buttons; trivial to verify by running `./gradlew :electron:run` and checking that the leftmost top-bar child doesn't sit under the OS chrome.
- **2026-04-26 — Phase 6 toolkit-add ✅ done.**
  - Toolkit: added `toolkit-web/.../themeeditor/DefaultThemeManagerHost.kt` with three pieces. (a) `DefaultThemeManagerState` — a mutable bag holding everything `ThemeManagerHost` exposes (`mainSchemeName`, `appearance`, `lightThemeName`, `darkThemeName`, `customThemes`, `customSchemes`, `favoriteThemes`, `favoriteSchemes`). Apps own its lifetime; the manager reads through it on every render. (b) `open class DefaultThemeManagerHost(state, onChange = {})` — a ready-to-use `ThemeManagerHost` whose every setter writes to `state` and fires `onChange()` synchronously, so apps can persist a snapshot in one line (e.g. via `writeUiSettings(...)` or an Electron IPC bridge). The class is `open` and the silhouette/swatch fns are non-final, so termtastic can subclass and override `renderConfigSilhouetteHtml` / `renderThemeSwatchHtml` without rewriting the rest. Cascading deletes are built in: `deleteCustomTheme(name)` removes the name from `favoriteThemes` and clears the light/dark slot when bound; `deleteCustomScheme(name)` removes from `favoriteSchemes` and resets `mainSchemeName` to `DEFAULT_THEME_NAME` if it pointed there. (c) two free helpers `defaultRenderConfigSilhouetteHtml(theme, isDark, customSchemes = emptyMap())` and `defaultRenderThemeSwatchHtml(scheme, isDark)` that paint a neutral mini-app silhouette (tabs / sidebar / main / accent) and a mini terminal-card swatch (sample line + 8 syntax-colour dots). The silhouette resolves section override names against `customSchemes` first then `recommendedColorSchemes`; unknown names fall through to the main scheme so a partially-broken theme still paints. The default host wires its overrides through to these helpers.
  - Toolkit CSS: appended `.dt-config-silhouette`, `.dt-cs-tabs`, `.dt-cs-tab-dot{,-dim}`, `.dt-cs-body`, `.dt-cs-sidebar{,-line}`, `.dt-cs-short`, `.dt-cs-main`, `.dt-cs-content`, `.dt-cs-prompt`, `.dt-cs-text`, `.dt-cs-accent`, `.dt-theme-swatch`, `.dt-swatch-line`, `.dt-swatch-prompt`, `.dt-swatch-syntax-row`, and `.dt-syntax-dot` rules to `darkness-toolkit.css`. The new selectors mirror termtastic's `.config-silhouette` / `.theme-swatch` shape (so the visual is unchanged) but live under the `.dt-` namespace and are themed off the existing `--t-border-subtle` token, matching the rest of the toolkit. Backgrounds are inline per-theme (the renderer writes them as `style="…"` attributes) so each preview shows its real colours.
  - Compiles via composite build: `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, termtastic `:web:compileKotlinJs` all green. No consumer-side change forced — the new types are additive; the existing `ThemeManagerHost` interface is unchanged so termtastic's `TermtasticThemeManagerHost` still compiles as-is.
  - **Termtastic swap — skipped intentionally.** The plan called for termtastic to "route the rest of the host plumbing through `DefaultThemeManagerHost`," but the impedance mismatch makes that net-zero in practice: termtastic's reads come straight from `appVm.stateFlow.value` (no in-process mutable state to back), and its setters are `suspend` server-roundtrips (`appVm.setLightThemeName(name)` etc.) — there is no synchronous local-state mutation to delegate to the parent class's setter implementations. Subclassing would force termtastic to override every getter and every setter, leaving only `renderConfigSilhouetteHtml` / `renderThemeSwatchHtml` as inherited behaviour, which termtastic deliberately overrides anyway. Net code re-use: zero. So `TermtasticThemeManagerHost` keeps implementing `ThemeManagerHost` directly. `DefaultThemeManagerHost` is the right abstraction for filesystem-/in-process-state apps (lunarbor Electron, mobile); it shouldn't be forced onto server-backed apps. Lunarbor's Phase-9 wire-up will be the first real user.
  - **Verification still owed:** Phase-6 visual smoke test once lunarbor wires `DefaultThemeManagerHost` in Phase 9.
- **2026-04-26 — Phase 5 toolkit-add ✅ done.**
  - Toolkit: `shell/Sidebar.kt` `SidebarSpec` extended with `isResizable`, `minWidthPx` (default 120), `maxWidthPx` (default 600), and `onResize: ((widthPx: Int) -> Unit)?`. When `isResizable=true`, `renderSidebar` appends an inside-edge `.dt-sidebar-resize-handle` (right edge for left sidebars, left edge for right sidebars). The drag is document-scoped: `mousedown` records the start position and bar width, attaches `mousemove` + `mouseup` listeners on `document`, and removes them on mouseup so each drag is a fresh closure (no leak per re-render). Drag direction mirrors per side — left sidebars grow when the cursor moves right, right sidebars grow when it moves left. Width is clamped to `[minWidthPx, maxWidthPx]` live during the drag; `onResize` fires once on mouseup with the final clamped width. Persistence is host-owned — toolkit only emits. Existing `SidebarSpec(widthPx, header, content, visible)` call sites still compile (all new params have defaults).
  - Toolkit: added `shell/SidebarSection.kt` with `SidebarSectionSpec(title, isOpen, items, onToggle?, trailingHeader?)` and `renderSidebarSection(spec)`. Section header is a `<button>` with a 10×10 chevron SVG that CSS rotates -90° when closed, plus a title span and an optional trailing slot for action buttons / count badges. State is host-owned: `isOpen` is read-only and `onToggle` fires once per header click — host flips its own state and re-renders. `onToggle = null` renders the header as `disabled` for static always-open sections. The `items` list is `List<HTMLElement>` so hosts can drop in any row layout — toolkit does not constrain row shape.
  - Toolkit: added `SidebarRow(label, iconHtml?, leadingBadge?, isActive, handler?)` factory in the same file for the common icon-plus-label row case. Returns a `.dt-sidebar-row` element with `role=button` when interactive, `.dt-active` when active. Icons are 14×14 to match the rest of the toolkit's icon slots; the click handler `stopPropagation`'s so row clicks don't bubble to the section header. Hosts that need bespoke rows (rename gestures, multi-line, drag handles) build their own elements and append them to `SidebarSectionSpec.items` directly — `SidebarRow` is a convenience, not a requirement.
  - Toolkit CSS: `.dt-sidebar` gained `position: relative` so the absolutely-positioned resize handle anchors to the inside edge. Added `.dt-sidebar-resize-handle` (8px-wide hit zone with a 1px ::after line that becomes accent-coloured 2px while `.dt-dragging`), with `-right` / `-left` variants bleeding 3px past the sidebar's border for sub-pixel slack. Added `.dt-sidebar-section`, `.dt-sidebar-section-header` (uppercase 11px label, hover-brightens), `.dt-sidebar-section-chevron` (rotates -90° via `.dt-closed`), `.dt-sidebar-section-title`, `.dt-sidebar-section-trailing`, `.dt-sidebar-section-children` (display:none when closed), plus `.dt-sidebar-row`, `.dt-sidebar-row.dt-active`, `.dt-sidebar-row-icon`, `.dt-sidebar-row-badge`, `.dt-sidebar-row-label` — themed off existing `--t-sidebar-*` / `--t-border-default` / `--t-accent-primary` / `--t-surface-raised` tokens.
  - Compiles via composite build: `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, termtastic `:web:compileKotlinJs` all green (the `SidebarSpec` ctor extension is source-compatible — both consumers' existing call sites keep working without edit).
  - **Verification still owed:** Phase-5 visual smoke test once a host wires `isResizable = true` + persists `onResize` widths, and once lunarbor / termtastic render a `SidebarSectionSpec` to confirm chevron rotation, hover state, and click-to-toggle behave.
  - **Termtastic swap ✅ done (bundled with the toolkit-add).** Both halves shipped:
    - **Public helper exposed.** Toolkit `shell/Sidebar.kt` gained a public `enum class SidebarResizeEdge { LEFT, RIGHT }` and `attachSidebarResizeHandle(bar, edge, minWidthPx = 0, maxWidthPx = 600, onResize)` returning the mounted handle. The previously-`private` `attachResizeHandle` was lifted to this signature so hosts whose sidebar element is rendered outside the toolkit (declared in `index.html`, looked up by id) can adopt the gesture without rebuilding the bar via `renderLeftSidebar`. `SidebarSpec(isResizable = true)` now delegates to the public helper, so the two paths share one implementation.
    - **Resize swap.** `web/.../main.kt` dropped the inline `Sidebar resize via divider drag` `run` block (~35 lines) and replaced it with a single `attachSidebarResizeHandle(sidebarElLocal, SidebarResizeEdge.RIGHT, minWidthPx = 0, maxWidthPx = 400) { w -> … }`. The onResize callback preserves the legacy collapse-by-drag boundary (`w <= 10` → `setSidebarCollapsed(true)`, else `setSidebarWidth(w) + setSidebarCollapsed(false)`) plus the post-drag `fitVisible()` call. `minWidthPx = 0` keeps drag-to-collapse working; the existing sidebar-toggle button remains the re-expansion path from a fully-collapsed state. `web/.../index.html` deleted `<div class="sidebar-divider" id="sidebar-divider"></div>`. `DomRefRegistry.kt` dropped the now-unused `internal var sidebarDividerEl`. `web/.../resources/styles.css` deleted `.sidebar-divider`, `.sidebar-divider::after`, `.sidebar-divider:hover::after`, `.sidebar-divider.dragging::after`; `.sidebar` gained `position: relative` so the toolkit's absolutely-positioned `.dt-sidebar-resize-handle` anchors to its right edge; the `prefers-reduced-motion` rule swapped `.sidebar-tab-chevron`/`.sidebar-tab-children` references for `.dt-sidebar-section-chevron`. Note: the existing termtastic divider was a sibling of `#sidebar` and remained reachable even at `width: 0`, letting the user grab it to re-grow from collapse. The toolkit's handle is a child of `.sidebar` (per the plan's "drag-handle on inside edge" wording) and gets clipped by `overflow-x: hidden` when the bar is at `width: 0` — re-expansion now goes exclusively through the toggle button, which always worked and was the primary path anyway. Acceptable tradeoff for sharing the primitive across all Darkness apps.
    - **Section-collapse swap.** `web/.../Sidebar.kt::renderSidebar` rewritten to delegate per-tab section chrome (header chevron, hover/click-to-toggle, open/closed visuals) to `renderSidebarSection(SidebarSectionSpec(...))` from the toolkit. `collapsedTabs: HashSet<String>` stays — it's host-owned state, exactly what the toolkit's `isOpen` + `onToggle` API expects. The pane-item rows stay bespoke (per-kind icons via SVG strings, `pane-status-spinner` decoration, RTL-truncated labels, focus-and-raise click semantics with cross-tab switch + 50ms RAF settle) — they're built as plain `HTMLElement`s and dropped into `SidebarSectionSpec.items: List<HTMLElement>`. The per-tab status spinner moves into the section's `trailingHeader` slot. Active-tab highlighting is decorated post-render: termtastic adds a `.active-tab` class to the `.dt-sidebar-section` root and a CSS rule `.dt-sidebar-section.active-tab .dt-sidebar-section-header { color: var(--t-active-accent, var(--termtastic-orange)); }` paints it. RTL tail-truncation for filesystem-path titles is applied post-render to the `.dt-sidebar-section-title` element. `web/.../resources/styles.css` deleted the now-superseded `.sidebar-tab-header`, `.sidebar-tab-header:hover`, `.sidebar-tab-header.active-tab`, `.sidebar-tab-chevron`, `.sidebar-tab-header.collapsed-tree .sidebar-tab-chevron`, `.sidebar-tab-children`, `.sidebar-tab-children.collapsed-tree` rules. `.sidebar-pane-item*` rules (the pane row treatment, including `.active-pane`, indentation, hover) are kept verbatim since the rows are still bespoke termtastic DOM. `.collapsed-tree` matches in `.md-list-section-*` and `.git-list-section-*` are unrelated content-pane components that happen to share the class name and are out of scope for this phase.
    - **Visual deltas vs. legacy:** (a) the section-children no longer animate `max-height 200ms ease-in-out` on toggle — the toolkit uses `display: none / flex` for the closed/open transition, so collapse is instant. Acceptable; can be added back as a toolkit option later. (b) the section header is now a `<button type="button">` instead of a `<div>` — improves a11y (the toolkit emits `aria-expanded` automatically). (c) section borders gain a 1px bottom separator from the toolkit's `.dt-sidebar-section` rule; lets the user see section boundaries when multiple stacked sections share a sidebar. Only one section-per-tab today, so the user-visible delta is one extra hairline below each tab's pane list.
    - All three projects compile via composite build: `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, termtastic `:web:compileKotlinJs` — all green.
    - **Verification still owed:** visual smoke test of termtastic to confirm (a) the resize gesture still tracks the cursor 1:1 and the orange line appears while dragging, (b) drag-to-collapse still flips `setSidebarCollapsed(true)` at `width ≤ 10`, (c) the sidebar-toggle button still re-grows from collapse to the persisted width, (d) per-tab chevrons rotate -90° on close, (e) clicking a section header still toggles the tab's `collapsedTabs` membership and re-renders, (f) active-tab orange treatment paints on the right tab, (g) pane-item click still does cross-tab switch + raise-pane, (h) RTL tail-truncation still works for filesystem-path tab titles, (i) the `prefers-reduced-motion` media query still suppresses chevron rotation transition.
- **2026-04-25 — Phase 1 ✅ done (toolkit + lunarbor + termtastic).**
  - Toolkit: added `toolkit-web/.../shell/AppFrame.kt` (`AppFrameSpec`, `renderAppFrame`, `mountAppFrame`) and `.dt-app-frame*` CSS rules.
  - Lunarbor: `AppShell.render` composes via `mountAppFrame(root, AppFrameSpec(topBar, main = layoutHost))`.
  - Termtastic: `start()` repackages the existing `#app` shell tree into AppFrame slots in place — element identities preserved, so the rest of `main.kt`'s `getElementById(...)` lookups still work. The pre-existing `#app { display: flex; flex-direction: column; ... }` rule becomes a harmless duplicate of `.dt-app-frame`.
  - All three projects compile clean; libs-repo refreshed via `publishAllToLibsRepo`.
  - **Verification still owed:** visual smoke test of termtastic to confirm no regression (especially around traffic-light padding, sidebar drag, and Claude usage bar visibility — all of which depend on flex-column ordering).
- **2026-04-26 — Phase 4 toolkit-add ✅ done.**
  - Toolkit: added `PaneLayout(tree, expandedLeafId)` + `PaneTree.containsLeaf(id)` to `layout/PaneTree.kt`, plus `PaneTreeOps.expand(layout, leafId)`, `PaneTreeOps.restore(layout)`, and `PaneTreeOps.toggleExpand(layout, leafId)`. The pane tree itself is **never mutated** — `expandedLeafId` is a pure overlay flag, so "prior-tree memory" is implicit (restore = same tree, minus the flag) and structural ops (split/close/retitle) made while one leaf is expanded still apply and become visible on restore.
  - Toolkit: `LayoutRenderer` gained a non-breaking `render(layout: PaneLayout)` overload alongside the existing `render(tree: PaneTree)`. The legacy entry now delegates to the new one (`PaneLayout(tree)`); private `buildNode/buildLeaf/buildSplit` thread an optional `expandedId` through, and `buildLeaf` adds `LayoutClassNames.PANE_EXPANDED = "dt-expanded"` to the matching pane. Unknown ids (not in the tree) are silently ignored so the host can't render an empty container.
  - Toolkit: added `toolkit-web/.../layout/PaneMenu.kt` with `PaneMenuItem(label, iconHtml, handler, isEnabled, isActive, isDanger, isSeparator)`, `PaneMenuSpec(items)`, and `openPaneMenu(anchor, spec): () -> Unit` (returns a programmatic-close lambda). The popover is appended to `<body>`, positioned `fixed` next to the anchor's bottom-right corner, and auto-flips above (`.dt-pane-menu-flip-up`) or right (`.dt-pane-menu-flip-left`) when it would clip the viewport. Dismisses on outside `mousedown` (capture phase, with `contains()` check), `Escape`, viewport `resize`, ancestor `scroll` (capture phase), and after any item handler runs — handlers fire **after** dismissal so a host re-render doesn't race a still-mounted menu.
  - Toolkit: `PaneMenuItems` ships factories for `splitHorizontal`, `splitVertical`, `expand`, `restore`, `toggleExpand(isExpanded, handler)` (single-row variant that swaps icon + label + sets `isActive` when expanded), `close` (rendered as danger), and `separator()`. Icons are 14×14 stroke SVGs to match `PaneActions`' button glyphs; the close glyph and expand/restore glyphs are reused from `PaneActions.ICON_*`.
  - Toolkit CSS: `.dt-pane-root` gained `position: relative` so `.dt-pane.dt-expanded { position: absolute; inset: 6px; z-index: 5; }` anchors correctly. Added `.dt-pane-menu`, `.dt-pane-menu-item`, `.dt-pane-menu-item-icon`, `.dt-pane-menu-item-label`, `.dt-pane-menu-item.dt-pane-menu-item-{active,danger}`, `.dt-pane-menu-item:disabled`, and `.dt-pane-menu-separator` rules — themed off the existing `--t-surface-overlay` / `--t-border-default` / `--t-text-danger` / `--t-accent-primary` tokens, so the popover inherits Light/Dark automatically.
  - Compiles via composite build: `:toolkit-web:compileKotlinJs`, lunarbor `:web:compileKotlinJs`, and termtastic `:web:compileKotlinJs` all green (the `render(tree)` API is preserved, so neither consumer needed any change to keep building).
  - **Verification still owed:** Phase-4 visual smoke test once a host wires `PaneMenuItems.splitHorizontal/splitVertical/toggleExpand/close` into a kebab `PaneAction` whose handler calls `openPaneMenu`.
  - **Termtastic swap ✅ done (bundled Phase 3 + Phase 4).** Phase-4-only swap in termtastic was structurally blocked on the Phase-3 swap, so they shipped together.
    - `web/.../PaneHeader.kt`: `buildPaneHeader` rewritten end-to-end. Now constructs a `PaneHeaderSpec(title, leadingBadge = spinner, actions = …, onRename = { launchCmd(WindowCommand.Rename(...)) })` and delegates to `renderPaneHeader(paneId, spec)`. The 7 buttons map as: bespoke `PaneAction(...)` for `Create worktree` / `Color scheme (palette)` / `Reformat` (terminals only) / `Copy path` (file-browser only); `PaneActions.expand` / `PaneActions.restore` factories for the maximize toggle (the latter via `.copy(isActive = true)` so the toolkit paints the active-state visual); `PaneActions.close` for close (handler retains termtastic's linked-session confirm-dialog logic, including the floating-pane animation target detection). Server-driven maximize is preserved verbatim — the toolkit owns the icon/tooltip but the click handler still dispatches `WindowCommand.ToggleMaximized`, so multi-window sync is unchanged. Local `ICON_MAXIMIZE` / `ICON_RESTORE` constants deleted; local `ICON_CLOSE` deleted from `PaneHeader.kt` and re-added 16×16 inside `TabBarMenu.kt` (which still uses 16×16 icons across its menu and shouldn't shrink). The palette popover anchors via `document.querySelector(".dt-pane-header[data-pane-id='$paneId'] .tt-pane-action-palette")` at click time — the toolkit's `PaneAction.handler` is `() -> Unit`, so we use `extraClass` to re-find the rendered button rather than capture a stale ref. The `markPaneFocused` mousedown listener that used to live on the `.pane-actions` container is re-attached after `renderPaneHeader` returns by querying `.dt-pane-actions`; the toolkit's own mousedown stop-propagation runs in addition (both fire on the same node, propagation stops at this element either way).
    - `web/.../RenameHandlers.kt`: pane variant of `startRename` deleted (pane rename is now the toolkit's `wireInlineRename` driven by `PaneHeaderSpec.onRename`); `startTabRename` retained — tab strip still owns its own DOM.
    - `web/.../LayoutBuilder.kt`: querySelector for the rendered header swapped from `.terminal-header` to `.dt-pane-header`; the in-tab free-form drag guard for action-button clicks swapped from `.closest(".pane-actions")` to `.closest(".dt-pane-actions")`.
    - `web/.../FileBrowserPane.kt` + `GitPane.kt`: their custom in-header controls (filter wrap, sort wrap, expand/collapse, refresh, diff-mode, search, auto-refresh) still insert *before* the action strip, but now find it via `.dt-pane-actions`.
    - `web/.../resources/styles.css`: deleted `.terminal-header`, `.terminal-header[draggable="true"]`, `.terminal-title`, `.terminal-title.armed`, `.terminal-title-input`, `.terminal-header-spacer`, `.pane-actions`, `.pane-actions-sep`. Kept `.pane-action-btn` (still used by GitPane's in-content auto-refresh button). Retargeted `.terminal-cell.focused .terminal-title` → `.terminal-cell.focused .dt-pane-title`, and the floating-pane unfocused-fade rule from `.floating-header .terminal-title` → `.floating-header .dt-pane-title`. Added `.terminal-cell .dt-pane-title { direction: rtl; text-align: left; max-width: 60%; }` to keep filesystem-path titles tail-truncating (the toolkit's `.dt-pane-title` doesn't ship RTL since lunarbor doesn't need it).
    - `web/.../TabBarMenu.kt`: gained a local 16×16 `ICON_CLOSE` (textually identical to the deleted PaneHeader copy) since the tab-bar menu's other icons are all 16×16 — shrinking just one to the toolkit's 14×14 would have looked off.
    - **Skipped intentionally:** no kebab in termtastic. Termtastic's free-form `floating-pane` model has no splits, so `PaneMenuItems.splitHorizontal/splitVertical` don't apply; `expand`/`close` are already inline icon buttons. A kebab there would be cargo-culted UI.
    - All three projects compile via composite build: `:toolkit-web:compileKotlinJs`, `:web:compileKotlinJs` in lunarbor, `:web:compileKotlinJs` in termtastic — all green.
    - **Verification still owed:** visual smoke test of termtastic to confirm (a) pane title renders/truncates as before for filesystem paths, (b) hover-arm rename gesture still fires after 1s, (c) Enter/blur commit dispatches the rename command, (d) maximize/restore icon flips correctly across multi-window, (e) palette popover anchors below the palette button (not somewhere else), (f) linked-session close confirmation still warns about siblings, (g) free-form drag-to-move on the title area still works *only* when an action button wasn't the mousedown target, (h) cross-tab pane drag via the type-icon still functions, (i) status spinner placement next to the title still reads as part of the header.
- **2026-04-26 — Phase 3 toolkit-add ✅ done.**
  - Toolkit: added `toolkit-web/.../layout/PaneActions.kt` with `data class PaneAction(iconHtml, tooltip, handler, isActive, extraClass)` and one-line factories `PaneActions.expand`, `PaneActions.restore`, `PaneActions.close` (each ships a minimal stroke-SVG; consumers wanting custom glyphs construct `PaneAction` directly).
  - Toolkit: added `toolkit-web/.../layout/PaneHeader.kt` with `PaneHeaderSpec(title, leadingBadge?, actions, onRename?, isDraggable)`, `renderPaneHeader(paneId, spec)`, `defaultPaneHeader(leaf)` (now returns a spec, not an `HTMLElement`), the public `isPaneDrag(ev)` filter, and `DT_PANE_DRAG_MIME = "application/x-darkness-pane"`. Inline rename is the verbatim termtastic mechanic — 1s hover-arm timer → `.dt-pane-title-armed` → click swaps to an `<input>`; `dblclick` short-circuits the timer; Enter / blur (non-empty changed) commits, Esc cancels, no-op restores in place. Action-strip mousedown is `stopPropagation()`'d so action clicks don't trigger the header drag. `wireHeaderDragSource` puts `setData(DT_PANE_DRAG_MIME, paneId)` and adds `.dt-pane-dragging` to the parent pane on dragstart; `dragend` clears `.dt-pane-drop-target` highlights document-wide.
  - Toolkit: `LayoutRenderer` swapped `PaneCallbacks.paneHeader` from `(Leaf) -> HTMLElement` to `(Leaf) -> PaneHeaderSpec`. Removed the legacy `onClose` callback + `.dt-pane-close` querySelector wiring (handlers now ride on `PaneAction.handler`). Added `PaneCallbacks.onPaneDragged(sourceId, targetId)?` plus `wirePaneDropTarget` that filters by MIME, skips the source pane, uses `contains()` on `dragleave` to avoid flicker, and fires the callback on drop. Cross-tab pane drops are still host-side (host wires drop on its tab strip).
  - Toolkit CSS: replaced `.dt-pane-close` with `.dt-pane-actions`, `.dt-pane-action`, `.dt-pane-action.dt-active`, `.dt-pane-leading-badge`, `.dt-pane-title.dt-pane-title-armed`, `.dt-pane-title-input`, `.dt-pane.dt-pane-dragging`, `.dt-pane.dt-pane-drop-target`, plus `grab`/`grabbing` cursors on draggable headers. Header padding tightened to suit icon buttons (4px vertical, 6px gap).
  - Lunarbor: dropped the no-op `onClose = { }` from `AppShell.kt`'s `PaneCallbacks` (the only consumer-side change forced by the API trim — full Phase 9 wire-up still pending).
  - All three projects compile via composite build (`:toolkit-web:compileKotlinJs`, `:web:compileKotlinJs` in lunarbor and termtastic — termtastic's swap is deferred since its `buildPaneHeader` still produces its own `terminal-header` DOM end-to-end).
  - **Verification still owed:** Phase-3 visual smoke test in lunarbor once a header factory wires `PaneActions` (currently the default returns a title-only spec, so visually the pane chrome lost its decorative close button — expected, since lunarbor's old `onClose` was a no-op anyway). Termtastic swap (replacing `buildPaneHeader` with a `PaneHeaderSpec` factory) is its own follow-up PR per the plan's pairing convention.
- **2026-04-25 — Phase 2 toolkit-add ✅ done; termtastic swap ✅ done.**
  - Toolkit: added `toolkit-web/.../shell/TabBar.kt` with `TabSpec(id, label, isClosable, isDraggable, isRenamable, leadingBadge?)`, `TabBarCallbacks(onSelect, onClose?, onAdd?, onReorder?, onRename?)`, `TabBarSpec(tabs, activeTabId?, showAddButton, callbacks)`, `renderTabBar(spec)` for one-shot rendering, and a public `buildTabElement(tab, spec)` for hosts that need bespoke strip layouts (sliding active indicator, overflow menu, etc.). Drag uses MIME `application/x-darkness-tab` so the bar ignores foreign drags; drop position is decided by cursor-vs-midpoint. Inline rename swaps the label for an `<input>` on dblclick (Enter/blur commit, Esc cancel). State stays host-owned — toolkit only renders + emits.
  - Toolkit: `TopBarSpec` gained an optional `tabBar: TabBarSpec?` slot. When present, `renderTopBar` delegates the middle strip to `renderTabBar`; the legacy `tabs/activeTabId/onTabSelected` shorthand still works for fixed-tab apps (lunarbor's empty-tabs case).
  - Toolkit CSS: added `.dt-tabbar`, `.dt-tabbar-strip`, `.dt-tab`, `.dt-tab-label`, `.dt-tab-label-input`, `.dt-tab-leading-badge`, `.dt-tab-close`, `.dt-tab-add`, `.dt-tab.dt-selected/.dt-dragging/.dt-drop-before/.dt-drop-after`. Bar is horizontal-overflow scrollable; drop indicators render via 2px ::before/::after.
  - Termtastic: `WindowConnection.applyState`'s per-tab construction loop now calls `buildTabElement` for the click/drag/drop-indicator wiring, then decorates the returned element with termtastic-specific bits (status spinner via `leadingBadge`, "entering" enter animation, pane-drop-onto-tab gesture, FLIP-snapshot capture before `WindowCommand.MoveTab`). Legacy `.tab-button` / `.active` / `data-tab` aliases stay on the element so the existing CSS, `setActiveTab`, `positionActiveIndicator`, `runTabFlip`, overflow-menu, and SettingsPanel selectors keep working without a sweeping rename — those tear-downs land in later phases or follow-ups.
  - All three projects compile via composite build (`:toolkit-web:compileKotlinJs`, `:web:compileKotlinJs` in lunarbor and termtastic).
  - **Verification still owed:** visual smoke test of termtastic to confirm tab DnD reorder, FLIP animation, sliding indicator, drop-pane (cross-tab pane drag), and pane status spinner still behave. Lunarbor swap deferred — its single-pane case doesn't need the new primitive until Phase 9 wire-up, when it gains add/close/rename + the rest of the gestures.

## Context

Lunarbor recently adopted `darkness-toolkit` and now uses the toolkit's `LayoutRenderer` + `PaneTree` + `TopBar` primitives. The latest fixes (LayoutRenderer attach-order, pane chrome CSS, `AppShell` TopBar wiring) gave it a working themed window with a basic top bar and a centrally-persisted appearance toggle.

The end-state goal: lunarbor should look and *behave* largely like termtastic — same shell idiom of tabs, themed panes, close/layout/resize, top bar, left sidebar, right theme sidebar — but for note-taking, with no terminal/git/server pieces. The toolkit should ship every shell primitive needed for *any* future Darkness app to inherit that look. Lunarbor ships with one default tab and pane, but users will add/close/rename/drag tabs, drag panes between tabs, expand/restore panes, rename panes inline, and resize sidebars — all the same gestures termtastic exposes.

In other words: **everything currently marked "termtastic only — needed by lunarbor" in the inventory above is in scope for this refactor.** It moves to the toolkit, termtastic swaps to consume it, lunarbor wires it up. Same pattern as the recent `Swap termtastic web ConfirmDialog and ThemeHelpers duplicates for toolkit-web` commit.

This plan breaks that work into phases sized for one PR pair each (toolkit-add + termtastic-swap), ordered so each phase compiles cleanly before the next starts.

## Inventory snapshot

| Concern | Today |
|---|---|
| TopBar (leading / tabs / trailing slots) | ✅ toolkit (`shell/TopBar.kt`) |
| Sidebar shell | ✅ toolkit (`shell/Sidebar.kt`) |
| ConfirmDialog | ✅ toolkit |
| ThemeManager (full editor) | ✅ toolkit (`themeeditor/`) |
| Pane tree + split + divider drag | ✅ toolkit (`layout/LayoutRenderer.kt`, `PaneTree`, `PaneTreeOps`) |
| Default pane header (title + close) | ✅ toolkit (`defaultPaneHeader`) |
| `UiSettings` shared persistence | ✅ toolkit (`store/UiSettingsStore.kt`) |
| Per-pane action buttons (palette/expand/branch/etc.) | ❌ termtastic only — **needed by lunarbor** |
| Pane expand/restore (fullscreen-within-tab) | ❌ termtastic only — **needed by lunarbor** |
| Pane HTML5 drag-reorder between tabs | ❌ termtastic only — **needed by lunarbor** |
| Pane inline rename | ❌ termtastic only — **needed by lunarbor** |
| Pane status-badge slot | ❌ termtastic only — **needed by lunarbor** |
| Tab add / close / rename / drag / scroll-overflow | ❌ termtastic only — **needed by lunarbor** |
| Sidebar drag-resize handle | ❌ termtastic only — **needed by lunarbor** |
| Sidebar section collapse/expand rows | ❌ termtastic only — **needed by lunarbor** |
| Per-app layout-state persistence | ❌ neither (lunarbor needs this; termtastic uses server) |
| Mac-electron traffic-light leading-padding | ❌ termtastic only |

## Phases

### Phase 1 — `AppFrame` shell primitive ✅
Compose TopBar + LeftSidebar + Main + RightSidebar in one container with show/hide per slot. Single point of entry for any consumer.

- ✅ *Added:* `toolkit-web/.../shell/AppFrame.kt` with `AppFrameSpec(topBar?, leftSidebar?, main, rightSidebar?)`, `renderAppFrame(spec)`, and `mountAppFrame(host, spec)`.
- ✅ *Added:* `.dt-app-frame*` rules in `darkness-toolkit.css` (vertical column with horizontal body row; `min-width: 0`/`min-height: 0` on growing children to keep first-paint stable).
- ✅ *Lunarbor swap:* `AppShell.render` calls `mountAppFrame(root, AppFrameSpec(topBar, main = layoutHost))`. No left/right sidebars yet.
- ✅ *Termtastic swap:* `start()` re-parents the existing `#app` shell into AppFrame slots in place. Element identities preserved (header stays `appHeaderEl`, sidebar/divider/usage-bar all keep their IDs and event handlers), so no other code needs to change.

### Phase 2 — Tab management
- *Add:* `TabBarSpec` extending today's `TopBarSpec.tabs` — per-tab close, optional `+` add, drop-target indicators, drag-to-reorder, horizontal-overflow scroll.
- *Add:* `TabBarCallbacks(onSelect, onClose, onAdd, onReorder, onRename)`.
- *Constraint:* state owned by host; toolkit only renders + emits.
- *Lunarbor swap:* tab strip wired with `showAddButton = true`, all tabs `isClosable = isDraggable = isRenamable = true`. Lunarbor gets the same gestures termtastic does — single hardcoded default tab is just the initial state.
- *Termtastic swap:* tab strip + DnD swap.

### Phase 3 — Pane chrome with action slots
- *Replace:* `defaultPaneHeader` with `PaneHeaderSpec(title, leadingBadge?, actions: List<PaneAction>, onRename?)`.
- *Add:* `data class PaneAction(iconHtml, tooltip, handler, isActive)`.
- *Add:* built-in factories `PaneActions.expand`, `PaneActions.close`, `PaneActions.restore` so all consumers get them with one line.
- *Add:* inline-rename gesture (hover-arm → click → text input; preserve termtastic's mechanic verbatim, refactor later).
- *Add:* HTML5-draggable header → `onPaneDragged(fromId, toId)` callback; host calls `PaneTreeOps`.
- *Lunarbor swap:* registers `PaneActions.expand`, `PaneActions.close`; opts into inline rename + cross-tab drag. Notes panes get the same chrome termtastic terminals get, minus terminal-specific actions.
- *Termtastic swap:* `terminal-header` swaps; branch / palette icons remain termtastic-side, injected into the action slot.

### Phase 4 — Pane expand/restore + kebab menu
- *Add:* `PaneTreeOps.expand(leafId)` + `restore()` with prior-tree memory. CSS `.dt-pane.dt-expanded`.
- *Add:* `PaneMenu` popover primitive with default items (split H, split V, expand/restore, close) + extension slot.
- *Lunarbor:* gets these for free; can split the single pane.
- *Termtastic swap:* expand button + kebab menu.

### Phase 5 — Sidebar polish
- *Add:* drag-handle on inside edge of `.dt-sidebar` → `onSidebarResize(side, widthPx)`. Persist via Phase 7.
- *Add:* `SidebarSection(title, isOpen, items, onToggle)` data class with chevron rows.
- *Lunarbor:* left sidebar with note-outline section, right sidebar for theme manager (Phase 6). Both resizable — same gesture termtastic ships.
- *Termtastic swap:* sidebar resize + section collapse.

### Phase 6 — ThemeManager wiring helper
- *Add:* `DefaultThemeManagerHost` in `toolkit-web` — implements `ThemeManagerHost` against `UiSettingsStore` and ships neutral silhouette/swatch HTML so apps don't need to provide their own.
- *Lunarbor:* trailing TopBar action `palette` opens the ThemeManager sidebar.
- *Termtastic:* keeps its custom silhouette (chrome-aware previews) but routes the rest of the host plumbing through `DefaultThemeManagerHost`.

### Phase 7 — Per-app layout-state persistence (filesystem; no server)
Lunarbor has no server, so layout state lives on disk via Electron IPC. Termtastic keeps using its server but conforms to the same schema.

- *Add:* `LayoutState` schema in `toolkit-store/commonMain` covering: sidebar widths, sidebar visibility, active tab id, per-tab pane tree, expanded leaf id.
- *Add:* `expect fun read/write/watchLayoutState(path)` actuals on JVM/Android/iOS, mirroring `UiSettingsStore`'s atomic-rename + self-write-suppression.
- *Add:* `defaultAppLayoutStatePath(appName: String): String?` — **per-app, not shared** (lunarbor's tree ≠ termtastic's, different blast radius and change frequency):
  - macOS: `~/Library/Application Support/Darkness/<AppName>/layout-state.json`
  - Windows: `%APPDATA%\Darkness\<AppName>\layout-state.json`
  - Linux: `$XDG_CONFIG_HOME/darkness/<app-name>/layout-state.json`
- *Lunarbor Electron wiring:* extend `electron/main.js` + `preload.js` with `darkness:readLayoutState` and `darkness:writeLayoutState` IPC channels pointing at `defaultAppLayoutStatePath("Lunarbor")`. Same atomic-rename + `lastWrittenBytes` self-suppression as the existing `darkness:writeUiSettings` handler. Renderer reads/writes through preload bridge; toolkit JS layer never touches disk directly.
- *Termtastic:* per-client UI bits (sidebar width, active tab, expanded pane) move to the same `LayoutState` schema, persisted server-side via existing `/api/ui-settings`. Toolkit doesn't care which transport the host uses; only defines the shape and ships filesystem actuals as the default for non-server apps.

### Phase 8 — Mac-electron polish
- *Add:* `body.dt-electron-mac` opt-in class (host adds on detection) + CSS rule reserving traffic-light room in `.dt-topbar-leading`. Move termtastic's existing rule into toolkit CSS keyed off the class.
- *Lunarbor:* opt in from `Main.kt` if Electron + macOS.

### Phase 9 — Lunarbor final wire-up
- *Throw away:* ad-hoc TopBar/appearance-toggle code currently in `AppShell.kt`.
- *Replace with:* one `renderAppFrame(...)` call configured with: full `TabBarSpec` (add/close/rename/drag enabled), pane chrome (expand/close + inline rename + cross-tab drag), resizable left/right sidebars, ThemeManager trailing action, appearance toggle in trailing slot, layout-state persistence wired through Electron IPC.
- *Verify:* parity with termtastic — every gesture termtastic ships (tab add/close/rename/reorder/overflow, pane drag-between-tabs/expand/rename, sidebar resize/section collapse) works in lunarbor against its note model.

## Dependency graph

```
Phase 1 (AppFrame) ──┬─→ Phase 2 (tabs) ─┐
                     ├─→ Phase 3 (pane chrome) ─→ Phase 4 (expand/menu)
                     └─→ Phase 5 (sidebar polish)
                                                 ↓
                     Phase 6 (theme host) ─┐
                     Phase 7 (layout state)─├─→ Phase 9 (lunarbor wire-up)
                     Phase 8 (mac padding) ─┘
```
Phases 1–5 independent of 6–8 except via final wire-up. Termtastic-swap commits trail each toolkit-add commit by one PR.

## Critical files

### Toolkit — modify
- `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/TopBar.kt` (extend for Phase 2)
- `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/Sidebar.kt` (extend for Phase 5)
- `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/layout/LayoutRenderer.kt` (extend `defaultPaneHeader` and add expand/restore in Phase 3/4)
- `toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/layout/PaneTree.kt` + `PaneTreeOps.kt` (add `expand`/`restore` ops in Phase 4)
- `toolkit-web/src/jsMain/resources/darkness-toolkit.css` (rules for `.dt-app-frame`, `.dt-tab-*`, `.dt-pane-action`, `.dt-pane-expanded`, `.dt-sidebar-section`, `.dt-sidebar-resize-handle`, `.dt-electron-mac`)
- `toolkit-store/src/commonMain/kotlin/se/soderbjorn/darkness/store/UiSettingsStore.kt` (Phase 7: add `LayoutState` schema + expect funcs)
- `toolkit-store/src/jvmMain/.../UiSettingsStore.jvm.kt`, `iosMain/.../UiSettingsStore.ios.kt`, `androidMain/.../UiSettingsStore.android.kt` (Phase 7 actuals)

### Toolkit — add (new files)
- `toolkit-web/.../shell/AppFrame.kt` (Phase 1)
- `toolkit-web/.../shell/TabBar.kt` (Phase 2)
- `toolkit-web/.../layout/PaneHeader.kt` — splits the action-slot header out of `LayoutRenderer.kt` (Phase 3)
- `toolkit-web/.../layout/PaneActions.kt` — built-in factories (Phase 3)
- `toolkit-web/.../layout/PaneMenu.kt` — kebab popover (Phase 4)
- `toolkit-web/.../themeeditor/DefaultThemeManagerHost.kt` (Phase 6)

### Lunarbor — modify
- `web/src/jsMain/kotlin/se/soderbjorn/lunarbor/main/AppShell.kt` (final swap to `renderAppFrame` in Phase 9; throws away the ad-hoc TopBar code added in the most recent commits)
- `electron/main.js` + `electron/preload.js` (Phase 7: add `darkness:readLayoutState` / `darkness:writeLayoutState` IPC handlers and bridge)

### Termtastic — modify (one swap PR per phase)
- `web/src/jsMain/.../main.kt` and friends (top-level shell swap, Phase 1)
- `web/src/jsMain/.../tab/*` (Phase 2)
- `web/src/jsMain/.../pane/*` (Phase 3, 4)
- `web/src/jsMain/resources/styles.css` — delete the lines superseded by toolkit CSS as each phase lands (avoids dead rules)

## Reuse / patterns to follow

- **Atomic write + self-suppression**: copy from `electron/main.js` `ipcMain.handle("darkness:writeUiSettings", ...)` (lines ~165-175) — same `tmp + rename + lastWrittenBytes` pattern.
- **`expect`/`actual` filesystem helpers**: mirror `UiSettingsStore.kt` for `LayoutState`. Same parameter shape (`path: String`, `extraSchemes: List<ColorScheme>`) where applicable.
- **Composite-build flow**: confirmed working — lunarbor auto-detects `../../darkness-toolkit/develop`. Each phase = edit toolkit source → lunarbor `gradlew :web:browserDevelopmentRun` picks it up. No manual republish needed during development; run `publishAllToLibsRepo` from the toolkit only at end-of-phase to refresh `libs-repo/` for fresh-clone builds.
- **Existing extraction precedent**: commit `d6a9f90` (`Swap termtastic web ConfirmDialog and ThemeHelpers duplicates for toolkit-web`) is the template — same shape of toolkit-add commit followed by termtastic-swap commit.
- **`PaneCallbacks` API contract**: contentRenderer receives an *attached* `.dt-pane-content` slot to fill in place. Phase 3's pane-header revamp must preserve this guarantee (any new callback that returns DOM must run after attachment, or take an attached slot).

## Risks

- **HTML5 DnD across tabs (Phase 2/3)** — termtastic has subtle workarounds for the macOS `dragend` quirk. Plan ~1–2 days alone.
- **Inline rename (Phase 3)** — uses a hover-arm timer + DOM caret-color manipulation in termtastic. Port verbatim, refactor later.
- **`LayoutState` schema (Phase 7)** — biggest design call. Sketch JSON shape **before** code; hard to migrate later if it ships wrong. Suggest a versioned `{ "schemaVersion": 1, ... }` envelope.
- **Action-button stability (Phase 3)** — pane action icons re-render on every pane-tree mutation. Toolkit must assign stable keys so click handlers don't get re-bound mid-click.
- **Termtastic regressions during swap** — its server tests don't cover the web shell. Either: (a) manual verification per swap commit, or (b) add a small Playwright smoke test to termtastic web. Recommend (b) before Phase 1's swap to make the rest cheaper.

## Effort estimate

| Phase | Toolkit add | Termtastic swap |
|---|---|---|
| 1 — AppFrame | ½ day | ½ day |
| 2 — Tab management | 2 days | 1 day |
| 3 — Pane chrome | 2–3 days | 1 day |
| 4 — Expand + kebab | 1 day | ½ day |
| 5 — Sidebar polish | 1 day | ½ day |
| 6 — ThemeManager host | ½ day | ½ day |
| 7 — Layout state | 1 day (+ ½ day Electron wiring in lunarbor) | ½ day (server adapter) |
| 8 — Mac padding | ½ hour | ½ hour |
| 9 — Lunarbor wire-up | — | ½ day |

**Total: ~2–3 weeks of focused work, in 9–10 PR pairs.**

## Recommended first commit

Phase 1 only, in two PRs:
1. Toolkit: add `AppFrame.kt` + `.dt-app-frame` CSS + a smoke fixture page wiring it.
2. Lunarbor: swap `AppShell.render` to use `renderAppFrame`.

Smallest blast radius, validates the consumer-rebuild + composite-build flow end-to-end before any of the bigger pieces land.

## Verification plan

After each phase:

1. **Toolkit compiles**: `./gradlew :toolkit-web:compileKotlinJs` from `darkness-toolkit/develop`.
2. **Lunarbor compiles via composite build**: `cd lunarbor/develop && ./gradlew :web:compileKotlinJs` — confirms toolkit edits reach the consumer through `includeBuild`.
3. **Termtastic compiles via composite build**: same in `termtastic/develop` — catches API breakage early.
4. **Visual smoke test (lunarbor)**: `./gradlew :electron:run` from `lunarbor/develop`, then:
   - First-paint shows correctly-laid-out content (no one-char-per-line collapse).
   - Top bar visible with leading title and trailing actions.
   - Theme toggle still cycles Auto/Dark/Light and persists across relaunch.
   - Per-phase additions visible (tab strip in Phase 2, pane action buttons in Phase 3, etc.).
5. **Visual smoke test (termtastic)**: launch and confirm no visual regressions.
6. **End-of-cycle**: `./gradlew publishAllToLibsRepo` from `darkness-toolkit/develop` to refresh both consumers' `libs-repo/` folders so fresh clones build without a sibling toolkit checkout.

## Out of scope (won't be moved to toolkit)

- Terminal emulator and `.terminal-cell` body rule
- Git pane / branch icon semantics
- Markdown pane (`md-view`)
- Termtastic server, REST/WS, auth tokens
- AI assistant working/waiting state semantics (the toolkit will provide a generic `leadingBadge` slot in the pane header — termtastic plugs in its spinner; toolkit doesn't know what state means)
- Worktree modal
- JediTerm dependency
