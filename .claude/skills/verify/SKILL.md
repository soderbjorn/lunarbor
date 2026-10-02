---
name: verify
description: Build, launch, and drive the Lunarbor Electron app to verify changes at the real surface (renderer DOM + pixels via CDP).
---

# Verifying Lunarbor changes

Lunarbor's only runtime surface is the Electron app — the JS `FileSystem`
actual errors outside Electron (`noteApi bridge unavailable`), so a plain
browser run of `:web` does NOT work.

## Isolation first: launch only through `scripts/ai-dev-run.sh`

Never launch a verification run against the real data, and never launch the
app any other way than through the run script. It takes an isolated data
directory from `LUNARBOR_LOCAL_DATA` and refuses to start without one:

- `LUNARBOR_LOCAL_DATA=<dir>` — Electron `userData` (and so the
  single-instance lock) moves to `<dir>/electron`; the themes, UI-settings
  and layout files move from `~/Library/Application Support/Darkness` into
  `<dir>`. Without it your instance collides with (or quits in favour of)
  the maintainer's running app and writes their settings.
- The vault is always `<dir>/vault`. Leave `LUNARBOR_VAULT` unset — the
  script refuses any other value, because the app's default when neither
  variable is set is `~/lunarbor-db`, the REAL notes.

Use a throwaway directory outside your home directory, e.g.
`/tmp/lunarbor-verify` (under `/ai-dev`, the `{dataDir}` your brief gives
you). The script refuses `$HOME/…` paths. Directories are created on launch.

## Build + launch

Build the Electron resources (repeat after each change; add
`-Plunula.toolkit.path=…` if you work in a worktree). The script never
builds, and refuses to launch until these exist:

```bash
./gradlew :electron:npmInstall :electron:copyWebBundle :electron:copyMainBundle
```

Optionally seed content, always naming the vault explicitly:

```bash
python3 scripts/seed-vault.py --vault /tmp/lunarbor-verify/vault
```

Launch, from the repo (or worktree) root:

```bash
LUNARBOR_LOCAL_DATA=/tmp/lunarbor-verify scripts/ai-dev-run.sh 9222 /tmp/lunarbor-verify
```

`<port>` becomes `--remote-debugging-port=<port>`; the optional second
argument must equal `LUNARBOR_LOCAL_DATA` (a typo check). The script starts
the app in the background and returns once it is up. It refuses (exit 2,
nothing launched) when the variable is unset, relative or under `$HOME`,
when `LUNARBOR_VAULT` names anything but `<dir>/vault`, when the port is
already taken, or when the Electron resources are not built. It prints the
startup lines, which must read:

```
==> Vault: /tmp/lunarbor-verify/vault (LUNARBOR_LOCAL_DATA)
==> Data: /tmp/lunarbor-verify (LUNARBOR_LOCAL_DATA)
```

and it checks them itself: if either differs, or the app dies before CDP
answers, it stops the run and exits non-zero. The app's output is in
`<dir>/electron-run.log`.

Do not use `scripts/run.sh`, `./gradlew electron:run`, `npm start` or
`node_modules/.bin/electron` directly for verification — none of them
enforce isolation, and they give you no CDP port.

Confirm the fresh bundle actually contains your change before trusting
what you see: `grep -o "<some-new-identifier>" electron/resources/web/web.js`.

Stop only your own instance:
`pkill -f -- "--remote-debugging-port=9222"` — never kill Electron or
Lunarbor by name, which would take the maintainer's window with it.

## Drive + observe via CDP

`screencapture` fails without screen-recording permission — use CDP
instead. Node ≥22 has a global `WebSocket`; no deps needed:

- List targets: `curl -s http://localhost:9222/json`
- `Runtime.evaluate` for DOM state, `Page.captureScreenshot` for pixels,
  `Input.dispatchMouseEvent` + `Input.dispatchKeyEvent` to click/type.
- A working driver script pattern lives in git history / can be rebuilt in
  ~40 lines (connect ws, promise-per-id send helper).

Gotchas:
- Focus the editor by CLICKING (mouse events) at the `.lunarbor-editor`
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
  disabled in Lunarbor), `.lunarbor-editor` for the note pane.
- Vault content on disk: saved `.md` files land under the
  vault you launched with (the `==> Vault:` log line names it;
  resolution lives in `electron-main/.../RunPaths.kt`).
