---
name: verify
description: Build, launch, and drive the TreeFacts Electron app to verify changes at the real surface (renderer DOM + pixels via CDP).
---

# Verifying TreeFacts changes

TreeFacts's only runtime surface is the Electron app — the JS `FileSystem`
actual errors outside Electron (`noteApi bridge unavailable`), so a plain
browser run of `:web` does NOT work.

## Build + launch

```bash
./gradlew electron:run          # builds web dist, copies bundle, launches
```

For verification, launch with a CDP port instead (after `electron:run` has
built `electron/resources/web/` at least once):

```bash
cd electron && ./node_modules/.bin/electron . --remote-debugging-port=9222
```

Confirm the fresh bundle actually contains your change before trusting
what you see: `grep -o "<some-new-identifier>" electron/resources/web/web.js`.

## Drive + observe via CDP

`screencapture` fails without screen-recording permission — use CDP
instead. Node ≥22 has a global `WebSocket`; no deps needed:

- List targets: `curl -s http://localhost:9222/json`
- `Runtime.evaluate` for DOM state, `Page.captureScreenshot` for pixels,
  `Input.dispatchMouseEvent` + `Input.dispatchKeyEvent` to click/type.
- A working driver script pattern lives in git history / can be rebuilt in
  ~40 lines (connect ws, promise-per-id send helper).

Gotchas:
- Focus the editor by CLICKING (mouse events) at the `.treefacts-editor`
  rect — `element.focus()` from Runtime.evaluate is not enough; keystrokes
  go to `<body>`.
- `Page.captureScreenshot` can stall ~1 min when the window is occluded /
  unfocused; don't chain tight timeouts around it.
- Autosave interval is 5 s — flows that assert on saved/dirty state need
  a >5 s settle wait.
- Multi-statement `Runtime.evaluate` snippets: wrap in an IIFE, top-level
  `const` leaks between evaluations ("already been declared").

## Useful probes

- Sidebar logo/save dot: `#app-logo`, `#app-logo-dot` (class
  `state-unsaved` + inline opacity while unsaved edits pend).
- Toolkit chrome: `.dt-sidebar-header`, `.dt-bottombar` (bottom bar is
  disabled in TreeFacts), `.treefacts-editor` for the note pane.
- Vault content on disk: the Electron main process resolves the vault
  root; check `electron/main.js` / `scripts/dev-brand.js` for the dev
  vault location if you need to inspect saved `.md` files.
