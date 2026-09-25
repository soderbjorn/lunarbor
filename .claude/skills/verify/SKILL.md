---
name: verify
description: Build, launch, and drive the TreeFacts Electron app to verify changes at the real surface (renderer DOM + pixels via CDP).
---

# Verifying TreeFacts changes

TreeFacts's only runtime surface is the Electron app — the JS `FileSystem`
actual errors outside Electron (`noteApi bridge unavailable`), so a plain
browser run of `:web` does NOT work.

## Isolation first: TREEFACTS_LOCAL_DATA + TREEFACTS_VAULT

Never launch a verification run against the real data. Every launch sets:

- `TREEFACTS_LOCAL_DATA=<dir>` — Electron `userData` (and so the
  single-instance lock) moves to `<dir>/electron`; the themes, UI-settings
  and layout files move from `~/Library/Application Support/Darkness` into
  `<dir>`. Without it your instance collides with (or quits in favour of)
  the maintainer's running app and writes their settings.
- `TREEFACTS_VAULT=<abs path>` — the notes vault. Defaults to `<dir>/vault`
  when only `TREEFACTS_LOCAL_DATA` is set, and to `~/treefacts-db` (the
  REAL notes) when neither is.

Use a throwaway directory, e.g. `/tmp/treefacts-verify` (or
`/tmp/ai-dev/<KEY>` under `/ai-dev`). Directories are created on launch.

## Build + launch

Build the Electron resources (repeat after each change; add
`-Plunula.toolkit.path=…` if you work in a worktree):

```bash
./gradlew :electron:npmInstall :electron:copyWebBundle :electron:copyMainBundle
```

Optionally seed content, always naming the vault explicitly:

```bash
python3 scripts/seed-vault.py --vault /tmp/treefacts-verify/vault
```

Launch with a CDP port, in the background (it blocks until quit):

```bash
cd electron && \
TREEFACTS_LOCAL_DATA=/tmp/treefacts-verify \
TREEFACTS_VAULT=/tmp/treefacts-verify/vault \
./node_modules/.bin/electron . --remote-debugging-port=9222
```

`scripts/run.sh [vault]` / `./gradlew electron:run` forward the same
variables but give you no CDP port, so prefer the command above.

**Check the startup log** before trusting anything. It must show:

```
==> Vault: /tmp/treefacts-verify/vault (TREEFACTS_VAULT)
==> Data: /tmp/treefacts-verify (TREEFACTS_LOCAL_DATA)
```

If either line is missing or names `~/treefacts-db` / the Darkness folder,
quit immediately — you are on real data.

Confirm the fresh bundle actually contains your change before trusting
what you see: `grep -o "<some-new-identifier>" electron/resources/web/web.js`.

Stop only your own instance:
`pkill -f -- "--remote-debugging-port=9222"` — never kill Electron or
TreeFacts by name, which would take the maintainer's window with it.

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
- Vault content on disk: saved `.md` files land under the
  `TREEFACTS_VAULT` you launched with (the `==> Vault:` log line names it;
  resolution lives in `electron-main/.../RunPaths.kt`).
