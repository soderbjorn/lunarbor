# The browser demo

Lunarbor's web bundle runs as a self-contained demo in any plain browser:
no Electron, no disk, no server. It opens on the life of Maya Kepler, a
mobile developer (Android, iOS, Kotlin Multiplatform) at a pizza start-up,
Star Trek fan and Commodore 64 / Amiga hoarder, and her cat Spot. The tree
is built so that the root's teasers lead into every feature: zooming,
folds, blocks, tags, search, search nodes, link previews, wiki links,
images, Excalidraw drawings, HTML pages, Markdown notes, Starred, tabs and
windows.

```
./scripts/run-demo.sh               # dev bundle at http://localhost:8082/
./scripts/run-demo.sh --prod        # the minified bundle
./scripts/stop-demo.sh              # stop the local demo server, if one is listening
./scripts/build-demo-site.sh [dir]  # deployable static site (default build/demo-site/)
```

The site folder can be served from any static host at any path.

## How it works

- **Demo mode** (`web/.../demo/DemoMode.kt`) is on whenever the page has
  no Electron `noteApi` bridge, so the desktop app never enters it and
  the website bundle never looks for real files.
- The vault is a `DemoFileSystem` in memory, seeded from `demo-vault.js`,
  which the `:web:generateDemoVault` task packs from `demo/vault/` and
  `demo/state.json`. Everything is editable; a reload starts over.
  Images, drawings and HTML pages are served as `blob:` URLs.
- `DemoPersister` keeps everything in memory, the look (theme,
  appearance) included: tabs, windows, pane locations, folds and theme
  all start from `demo/state.json` on every load.

## Editing the tour

`demo/vault/` and `demo/state.json` are **generated**. Edit the sources in
`demo/source/` and run:

```
python3 demo/source/build_vault.py
```

- `tree.txt`: the whole outline as indented `* bullets` (2 spaces per
  level), `:::` blocks, and `@ file` lines that put a file from `assets/`
  (or an inline `@ name.md <<<` … `>>>` file) into the enclosing node's
  folder. `{#id}` after a bullet names it for links, written
  `[text](lb:id)`, `[text](lb:id/file.ext)` or `[text](lb:/file.ext)`.
  `{open}` makes the bullet start unfolded.
- `starred.txt`: the Starred bookmarks, with the same `lb:` links.
- `layout.json`: the tabs and windows the demo opens with, where each
  window is (`at`: a node id, `id/file`, or `/`), and the default theme.
- `assets/`: images, `.excalidraw` drawings and `.html` pages.

To capture a layout or other persisted state by hand, arrange it in the
running demo and run `copy(lunarborDemoState())` in the browser console.
