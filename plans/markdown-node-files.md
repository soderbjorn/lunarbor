# Node files as plain Markdown (`_node.md`)

**Status:** implemented 2026-10-02 (migration verified on a copy of the real vault: 1042 outlines, identical items).

## Context

Every node folder holds one outline file, `node.lunarbor`. Its content is
almost Markdown, but not quite, and the differences make it fragile in any
other tool:

- **The bullet marker carries meaning.** `* text` is a leaf, `+ [title](folder)`
  a folder-backed bullet. Markdown treats `*`, `+` and `-` as the same thing,
  and Obsidian, Prettier and markdownlint rewrite them to `-` — one reformat
  and every folder reference is gone.
- **The title is link text.** A title holding a link becomes a link inside a
  link (invalid Markdown); `[`, `]` and `\` need escaping.
- **Blocks use `:::` fences**, which GitHub, Quick Look and most viewers show
  literally.
- **Titles that look like structure** (`1. Picard`) render as nested lists in
  other viewers — already true today.

Goal: every node file is a `_node.md` that GitHub, Obsidian, VS Code and Quick
Look render correctly, that survives being reformatted by those tools, and
whose child nodes are clickable links — so the whole tree can be browsed
outside Lunarbor.

Only the **on-disk codec** changes. The editor, `Document.lines` (bullets as
`<indent>* text`, blocks as hidden-marker rows), the save rules (folder per
parent, renames, adoption, trash) and the MCP agent format are untouched.
Typing `* item` inside a block keeps working exactly as now: block content is
stored verbatim, only prefixed with `> ` on disk.

## The new format

```markdown
- Buy oat milk
- Trip to **Lisbon**, see [the guide](https://example.com) [↳](<Trip to Lisbon/_node.md>)
- 1\. Picard — would hold a 1:1 with a Borg cube
> **Packing**: passport, charger, adapter
>
> * lists inside a block stay lists
>
> ```
> code works too
> ```

> A block with children keeps its body here…
> [↳](<A block with children keeps its body here…/_node.md>)
- Next bullet
```

1. **File name: `_node.md`** (`NoteRepository.OUTLINE_FILE_NAME`). The `_`
   keeps the reserved note name to `_node`, which nobody picks by accident,
   sorts the file first in Finder and on GitHub, and marks it as the
   folder's own file. Being `.md`, it is readable in Obsidian too, where
   each folder shows a `_node` note.
2. **Bullets: `- text`.** Read `-`, `*` and `+` alike (`+ ` only matters to the
   legacy reader below). An empty bullet is `-`.
3. **Folder-backed bullet:** the text, then a trailing ` [↳](<Folder/_node.md>)`.
   The bullet is recognised by that final link — its destination is
   `<child folder>/_node.md` — never by the marker, so reformatting can't
   break it. The title stays free inline Markdown, links included.
   - Destination in `<…>` form, so spaces and emoji need no encoding.
     `FolderName` already percent-encodes `<`, `>` and the other unsafe
     characters; a literal `%` in a folder name (e.g. `Work%3A Warp`) must be
     written as `%25` so viewers resolve the right folder
     (`<🍕 Work%253A Warp Factor Pizza/_node.md>`), and decoded on read.
4. **Blocks: a blockquote** — a run of lines starting with `>`; each content
   line is written as `> ` + line (`>` alone for a blank content line), read
   by stripping one `>` and one optional space. A real quote inside a block
   is `> > …`, so it round-trips.
   - Two blocks in a row are separated by one blank line, the only blank lines
     the writer emits; the reader must stop treating blank lines as
     meaningless between quote runs.
   - **Block with children:** the quote's last line is ` [↳](<Folder/_node.md>)`
     alone (`> [↳](<…>)`); the reader strips it and takes the folder from it.
     The title stays the plain text of the content's first line
     (`SubtreeCodec.blockTitleOf`), as now.
5. **Escaping bullet text** (writer escapes, reader unescapes, so the text in
   the app never changes): a leading ordered-list marker (`1.` → `1\.`,
   `1)` → `1\)`), a leading bullet marker (`- `, `* `, `+ ` → `\- ` …), and a
   text that itself ends in something shaped like the `[↳](<…/_node.md>)` link
   (escape its `[`). Leading `#` and `>` are **not** escaped: headings and
   quotes in bullets are Lunarbor line styles and render as such in Markdown
   too.
6. **Anything unrecognised** stays a verbatim `NodeLine.Text`, as now.

## Changes

### 1. Codec — `client/.../data/SubtreeCodec.kt`

- `parseNodeFile` / `formatNodeFile` / `formatFolderLine` → the format above.
  New helpers: `formatChildLink(folder)`, `parseChildLink(line)` (trailing
  `[↳](<…/_node.md>)`, `%25` handling), `escapeBulletText` / `unescapeBulletText`,
  quote-run parsing.
- Keep the old parser as `parseLegacyNodeFile` (today's code, renamed) for the
  migration only.
- **Keep `fenceFor` and `isFence`:** `mcp/AgentOutline.kt` uses them for the
  agent-facing `:::` format, which does not change.
- Rewrite the file's header comment ("On disk" section).

### 2. Repository — `client/.../data/NoteRepository.kt`

- `OUTLINE_FILE_NAME = "_node.md"`. Every check that treats `*.md` as a plain
  note must ask `isOutlineFile` / `isAppFile` first — audit the
  `NOTE_EXTENSION` uses (lines ~184, 989, 1193, 1237, 1498, 1758 `kind`
  mapping) so a `_node.md` is never listed, indexed or opened as a note.
- **Reserved name:** a note can no longer be called `_node.md`. Creating
  (`create_file`, "New Markdown file"), renaming from the title
  (`renameTargetOf`) or moving a note to that name takes ` (2)`, like any taken
  name.
- `isReferenced` (folder contents list) now reads the child links instead of
  `+` lines.
- KDoc / comments naming `node.lunarbor` (~55 mentions in main code).

### 3. One-time migration of existing vaults

The project rule is to drop old formats rather than migrate
([feedback_no_theme_format_compat]). **This plan proposes an exception**,
because the format holds the user's entire vault (the real one lives on
Google Drive) — without migration every node would silently vanish.

- `NoteRepository.migrateLegacyOutlines()`, run once when a vault opens,
  before anything loads: walk the vault; in each folder with a
  `node.lunarbor`, parse it with `parseLegacyNodeFile`, write `_node.md` with
  the new writer, then move the old file into
  `.trash/<timestamp> format migration/<same relative path>` — nothing is
  deleted.
- If a folder already holds a user note named `_node.md` (practically never),
  first rename it to `_node (2).md` (the usual collision rule) and rewrite
  links to it.
- Idempotent: a folder with no `node.lunarbor` is skipped, so it costs one
  vault walk per launch once done. Could be removed a few releases later.
- Electron: `VaultWatcher.isWatchedTextFile` already watches `.md`; drop
  `.lunarbor` once the migration has shipped.

### 4. Everything else that writes node files

- `demo/source/build_vault.py` — new format and name; regenerate
  `demo/vault/`. Its tour text says `node.lunarbor` twice (the "DO NOT OPEN"
  reward and step 11's block) → `_node.md`.
- `web/build.gradle.kts` `GenerateDemoVault` — nothing (`md` is already a
  text extension).
- `scripts/dynalist_to_lunarbor.py` + `test_dynalist_to_lunarbor.py`,
  `scripts/seed-vault.py` — new format.
- Comments in `AppShell.kt`, `OutlinePaintLoop.kt`, `FileSystem.kt`,
  `FolderContents.kt`, `TextIndex.kt`, `DocumentLayout.kt`, `Document.kt`,
  `PaneBackingViewModel.kt`, `SelectionHelper.kt`.
- `CLAUDE.md` "On-disk format", `demo/README.md`.
- Website (`lunarbor-www/content.js`, section 05 "It's all just files"):
  `node.lunarbor` → `_node.md`, and it can now say the files open in any
  Markdown app.

### 5. Tests

- Codec round-trip tests for every rule above: leaf, folder link (spaces,
  emoji, `%`, parentheses in names), escaping (`1.`, `- `, a text ending in a
  ↳-like link), blocks (blank content lines, nested quote, code fence,
  `* ` list lines, consecutive blocks, block with children), unknown lines.
- **Reformat resilience:** a file rewritten with `*` bullets, extra blank
  lines and `-`↔`*` changes inside blocks still loads to the same tree.
- Migration: legacy folder → `_node.md` + trashed original; existing user
  `_node.md` → `_node (2).md`; second run is a no-op.
- Fixture update: ~530 test mentions of `node.lunarbor` and old syntax in
  `client/src/commonTest`. Mostly mechanical; a small `outline(...)` test
  helper that writes fixtures in the new syntax keeps them readable.

## Open questions

- **Name:** `_node.md` (the user's choice). Considered: `node.md` (reserves
  the plausible note name "node") and `_node.markdown` (reserves nothing, but
  Obsidian ignores `.markdown`, so the tree can't be read there).
- **Migration exception** to [feedback_no_theme_format_compat] — needs the
  user's OK.
- **Arrow glyph** `↳` for child links (alternatives: `→`, `▸`, the child's
  title). Purely cosmetic; the reader accepts any link text.

## Verification

1. `./gradlew :client:allTests` green.
2. Copy the real vault, launch the desktop app on the copy
   (`LUNARBOR_VAULT=… ./scripts/run.sh`): every node loads, folds and zooms as
   before; `.trash/<timestamp> format migration/` holds the old files; no
   `node.lunarbor` left.
3. Open the migrated copy in Obsidian and push a sample to a GitHub repo:
   bullets render as lists, blocks as quotes, `↳` links open the child
   `_node.md`.
4. Reformat a few `_node.md` files with Prettier, relaunch: same tree.
5. `python3 demo/source/build_vault.py`, `./scripts/run-demo.sh`: the tour
   loads; rebuild the website's `demo-app/`.
