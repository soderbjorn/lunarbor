# Collapsed sidebar drag handle — unresolved investigation

## Symptom

In **notegrow** (Electron dev mode, macOS), when the left sidebar is fully collapsed (drag-to-close → 0-width placeholder), the user cannot grab the resize handle to drag it back open. They report seeing the macOS native window-resize cursor instead, suggesting the OS window-edge resize gutter is winning the events.

Two iterations of CSS fixes were applied with **no observable difference**, even after a confirmed full Gradle rebuild + Electron restart by the user.

## What we know about the system

### Sidebar mount path (notegrow)

- `notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/AppShell.kt:596-636` calls `leftSidebarController.mountSidebarOrPlaceholder(...)`.
- When the controller's `isOpen` is `false`, `mountSidebarOrPlaceholder` (in `darkness-toolkit/develop/toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/SidebarController.kt:260-306`) builds a placeholder `<aside>` with `width: 0`, `min-width: 0`, and adds the class `dt-sidebar-collapsed`. The resize handle from `attachSidebarResizeHandle` (in `Sidebar.kt:192-269`) is appended to it.

So the placeholder + handle should exist in the DOM when "collapsed."

### Toolkit CSS pipeline

- Source CSS lives at `darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css`.
- `darkness-toolkit/develop/toolkit-web/build.gradle.kts:25-54` defines `generateDarknessToolkitCssKt`, which reads the CSS and writes it as a Kotlin raw-string into `build/generated/source/darknessToolkitCss/jsMain/kotlin/se/soderbjorn/darkness/web/DarknessToolkitCssBundle.kt`. `inputs.file(cssFile)` is declared, so Gradle should re-run on CSS changes.
- `AppShell.kt:46` (toolkit) calls `injectDarknessToolkitStyles()`, which appends a `<style>` tag with `DARKNESS_TOOLKIT_CSS_BUNDLE` to `document.head`.
- notegrow consumes the toolkit via Gradle composite include — `notegrow/develop/settings.gradle.kts:53` does `includeBuild("../../darkness-toolkit/develop")` when the sibling exists.

### Electron config (notegrow)

- `notegrow/develop/electron/main.js:111-122` creates a vanilla `BrowserWindow` — no `frame: false`, no `titleBarStyle`, no transparency. The OS provides standard chrome and the OS native resize gutter is in play at the window edges.

### Layout structure (relevant z/painting)

- `.dt-app-frame-body` is a flex row containing `.dt-app-frame-sidebar-left`, `.dt-app-frame-main`, `.dt-app-frame-sidebar-right` (toolkit AppFrame).
- `.dt-app-frame-main` (CSS line ~45-57) has `flex: 1 1 auto`, **its own background fill** (`var(--t-surface-sunken, …)`) and 4px padding. No explicit `position` or `z-index`.
- `.dt-sidebar` (CSS line ~553-580) has `position: relative; overflow: hidden`. The protruding resize handle is anchored relative to the sidebar.
- `.dt-sidebar-collapsed` was lifting overflow to `visible` so the protrusion can paint past the 0-width sidebar edge.

## Iterations tried

### Iteration 0 — original code

```css
.dt-sidebar-resize-handle { width: 8px; z-index: 5; }
.dt-sidebar-resize-handle-right { right: -3px; }
.dt-sidebar-collapsed > .dt-sidebar-resize-handle { width: 14px; z-index: 20; }
.dt-sidebar-collapsed > .dt-sidebar-resize-handle-right { right: -14px; }
```

Handle hit area when collapsed at left=0: window x: `[0, 14]`. Overlaps macOS resize gutter (~3-5 px). Bug present.

### Iteration 1 — bumped offset to -30px

```css
.dt-sidebar-collapsed > .dt-sidebar-resize-handle-right { right: -30px; }
.dt-sidebar-collapsed > .dt-sidebar-resize-handle-left  { left:  -30px; }
```

Handle hit area now at window x: `[16, 30]`. Theoretically clear of OS gutter. **User reported "no difference" after a page reload (no rebuild).**

### Iteration 2 — confirmed rebuild + restart

User did `Yes — full rebuild + restart` and **still saw no difference**. This invalidated the "stale cache" theory… in principle. (See open question below: was the rebuild actually doing what we thought?)

### Iteration 3 — stacking context + larger offset

Combined two fixes in one edit:

```css
.dt-sidebar-collapsed {
    overflow: visible;
    position: relative;   /* redundant w/ .dt-sidebar but explicit */
    z-index: 20;          /* form a real stacking context */
}
.dt-sidebar-collapsed > .dt-sidebar-resize-handle {
    width: 14px;
    z-index: 1;           /* above empty sidebar; sidebar itself is at 20 */
}
.dt-sidebar-collapsed > .dt-sidebar-resize-handle-right { right: -44px; }
.dt-sidebar-collapsed > .dt-sidebar-resize-handle-left  { left:  -44px; }
```

Handle hit area now at window x: `[30, 44]`. Sidebar wraps the protrusion in its own stacking context so it should hit-test above `.dt-app-frame-main`'s background.

**User reported "no difference."**

## Hypotheses still on the table

After three iterations failing, my confidence in any single theory is low. Four possibilities, ordered by suspicion:

### H1 — The CSS rebuild isn't actually reaching the running renderer

Not yet ruled out by direct evidence. Composite-build + Kotlin/JS dev server can hold onto a cached toolkit klib. The user said they "fully rebuilt" but we never verified by inspecting the live `<style>` tag in DevTools. **This is the cheapest thing to verify and we haven't done it.**

Diagnostic:
```js
getComputedStyle(document.querySelector('.dt-sidebar-collapsed > .dt-sidebar-resize-handle')).right
// expected: "-44px"
```

### H2 — The collapsed-state DOM isn't what we think

Maybe notegrow's collapse path doesn't actually mount the placeholder, or doesn't add `dt-sidebar-collapsed`, or removes the sidebar element entirely under some condition. The code path *looks* right (`AppShell.kt:596-636` → `mountSidebarOrPlaceholder`) but we haven't observed the live DOM.

Diagnostic:
```js
!!document.querySelector('.dt-sidebar-collapsed')
document.querySelector('.dt-sidebar-collapsed > .dt-sidebar-resize-handle')?.getBoundingClientRect()
```

### H3 — Hit-testing is correct but the user's mouse is targeting the window edge by instinct

When a sidebar is collapsed against the window edge, the natural place to grab is the edge itself — exactly where the OS gutter wins. Even with a properly-positioned handle 30-44px inward, the user may simply not be hovering there. The visible 1px hairline may be too subtle to redirect the user's aim.

Diagnostic — ask user to try grabbing well inside the page (~30-50px from window edge) and report whether THAT works. If yes, the engineering is fine and the problem is UX/discoverability.

### H4 — Something else entirely intercepts the events

Possibilities not investigated:
- A renderer-level pointer-events listener or drag region.
- The handle's `mousedown` listener fires on a parent that calls `stopPropagation` somewhere we haven't found.
- A wrapping Compose/HTML element with `pointer-events: none` that we missed (grep showed none on the sidebar's ancestors, but not exhaustively).
- An Electron preload script hooking mouse events. Worth grepping `notegrow/develop/electron/preload.js`.

## What we still have not done

These are the gaps. **Do these before the next code change.**

1. **Inspect the live `<style>` tag** for the actual CSS rule values currently applied. The single most important diagnostic.
2. **Inspect the live DOM** for the `.dt-sidebar-collapsed` element and its computed `getBoundingClientRect`.
3. **Run `document.elementFromPoint(x, y)`** at the position the user is actually trying to grab. This separates "handle is missing" from "handle is covered" from "handle is present but you're aiming somewhere else."
4. **Try grabbing 30-50px in from the window edge** to test H3.
5. **Read `notegrow/develop/electron/preload.js`** for any mouse-event interference (H4).
6. **Verify `frame: true` macOS resize-gutter width** — search Electron source or test with a contrived page that has a colored 1px-wide bar at varying x offsets to find empirically where OS resize stops winning.

## File pointers (current code)

- CSS source — `/Users/soderbjorn/repo/darkness/darkness-toolkit/develop/toolkit-web/src/jsMain/resources/darkness-toolkit.css:670-735` (collapsed sidebar + handle rules; current state has the iteration-3 changes)
- CSS codegen — `/Users/soderbjorn/repo/darkness/darkness-toolkit/develop/toolkit-web/build.gradle.kts:25-54`
- Toolkit handle JS — `/Users/soderbjorn/repo/darkness/darkness-toolkit/develop/toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/Sidebar.kt:192-269`
- Toolkit controller — `/Users/soderbjorn/repo/darkness/darkness-toolkit/develop/toolkit-web/src/jsMain/kotlin/se/soderbjorn/darkness/web/shell/SidebarController.kt:260-306`
- notegrow shell — `/Users/soderbjorn/repo/darkness/notegrow/develop/web/src/jsMain/kotlin/se/soderbjorn/notegrow/main/AppShell.kt:596-636`
- notegrow electron — `/Users/soderbjorn/repo/darkness/notegrow/develop/electron/main.js:111-122`
- notegrow toolkit dep wiring — `/Users/soderbjorn/repo/darkness/notegrow/develop/settings.gradle.kts:38-53`
- Plan file (iteration 3) — `/Users/soderbjorn/.claude/plans/hidden-whistling-starlight.md`

## Wondering / loose threads

- Does termtastic exhibit the same bug, or only notegrow? Termtastic uses `titleBarStyle: 'hiddenInset'` (when its custom title-bar pref is on) — different OS hit-zone behavior. Cross-checking would isolate whether this is notegrow-specific or toolkit-wide.
- Is the visible hairline actually visible at all? `--t-border-default` defaulting to `#444` on a near-black editor background may be effectively invisible. Could be a hidden contributor to H3 — user can't *see* where to grab, so they default to the window edge.
- The original handle (iteration 0) had `width: 8px; right: -3px` — handle at x: `[5, 13]` when expanded near edge. That nominally overlaps the OS gutter too, yet expanded-sidebar resize works. Why does the OS gutter only "win" when collapsed? Theory: when expanded, the sidebar has visible chrome that the user clicks into; when collapsed, the sidebar's visible footprint is gone and the user's aim drifts to the window edge. Supports H3.
- Are there OTHER consumers of the toolkit CSS that we'd want to verify against? darkness-toolkit's own theme-editor preview window etc.
