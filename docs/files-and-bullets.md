# Files and bullets

A Lunarbor vault is an ordinary directory. The outline you edit and the
folders you see in Finder are the same tree:

- **Every bullet with content is a folder.** A bullet that has child
  bullets, blocks or files is backed by a folder named after its title. A
  bullet with nothing under it is just a line in its parent's outline.
- **Every node folder holds one outline file**, `_node.md`, listing that
  node's direct children. It is plain Markdown, so it opens in any Markdown
  app, and on GitHub. The vault root is the root node.
- **Everything else in a folder is a file of yours** — Markdown notes,
  images, PDFs, anything. Lunarbor lists them under the bullets and never
  rewrites them.

```
vault/
  _node.md                - Buy oat milk
                          - Recipes [↳](<Recipes/_node.md>)
  Recipes/
    _node.md              - Soups [↳](<Soups/_node.md>)
                          - Granola ![](granola.png)
    Soups/
      _node.md            - Tomato
    granola.png
    Shopping notes.md
  Starred.md
```

## The outline file

`_node.md` holds one line per direct child, with no indentation (depth is
the folder tree). It is ordinary Markdown: a list, with quotes for blocks.

- `- text` — a leaf bullet; the text is inline Markdown. (`*` and `+`
  bullets are read too, so a file another app reformatted still loads.)
  Text that Markdown would read as something else — a bullet starting
  with `1.` or `- ` — is saved with a backslash (`1\.`), which Markdown
  shows as the plain text it is.
- `- title [↳](<folder/_node.md>)` — a bullet with its own folder: the
  title, then a link to that folder's outline. In a Markdown app the ↳
  opens the node underneath. The folder name is stored explicitly,
  relative to this folder.
- `>` lines — a block: free Markdown shown in a bordered box, as the
  body of an item of its own (its dot sits left of the box). On disk it
  is a blockquote. A list inside a block is just a list, not part of the
  outline: it is drawn with dots and Tab nests its items, but it never
  gets folders. A fenced code block (```` ``` ```` … ```` ``` ````) inside
  a block is shown as code: monospace, verbatim, no Markdown. Select
  several lines of a block and choose Style → Code block to make one (or
  to undo it).
- A block whose last line is `> [↳](<folder/_node.md>)` has children. The
  block's text stays here; its children live in `folder`, exactly like a
  bullet's. The folder is named after the block's first line.

The file may start with front matter holding the node's timestamps:

```
---
created: 2026-10-04T12:34:56Z
updated: 2026-10-04T13:02:11Z
---
- Buy oat milk
```

Both are UTC. `created` is set when Lunarbor creates the node's
`_node.md` and never changes; `updated` is set whenever the node's own
items change — a bullet or block added, removed, edited or moved, a
child renamed — and also when the node itself is renamed. Changes deeper
down do not touch it, and a save that changes nothing leaves the file
alone. The front matter is not part of the outline: it never shows up as a
bullet or in search. Keys other apps put there (Obsidian's `tags`, say) are
kept. Nodes saved before Lunarbor kept these times simply have none: their
`updated` appears with their next change, and their `created` stays
unknown. Agents see both times when they read a node, and Navigate to
(Cmd-O) lists recently changed nodes first.

Folder names are the title's plain text without its `#tags` (`1-1 #private`
is stored in `1-1`; the tag stays on the bullet's line), with unsafe
characters percent-encoded (`Q3/Q4 plan` is stored as `Q3%2FQ4 plan`), capped
at 120 bytes; sibling collisions get ` (2)`, ` (3)`, …; an empty title is
`Untitled`.

## Saving

Lunarbor saves one second after the last edit, and at least every five
seconds while you keep typing. Each save keeps the folders in step with the
outline:

- A leaf that gets its first child becomes a folder; a folder whose last
  child goes away is removed again, unless it still holds files.
- When that new folder's name is already taken by a folder no bullet points
  at (one you made in Finder, or one a deleted bullet left behind with its
  files), the bullet takes that folder over instead of making `<name> (2)`.
  Bullets the folder's own `_node.md` already lists stay, after the
  new children. A bullet without children takes such a folder over too, as
  long as the folder holds something.
- Editing a title renames the folder — except for a node with no child
  bullets, just files: it lets go of the folder and becomes a plain bullet,
  and the folder keeps its name. Indent, outdent, drag and cut/paste
  move it, so its files travel with it.
- Deleting a folder-backed bullet moves its folder to
  `<vault>/.trash/<timestamp> <name>/` — unless the folder holds your own
  files (images, notes, other folders). Then the folder stays where it is
  with those files, and only its child bullets go to the trash; the folder
  shows up in the parent's folder contents list. Undo in the same session
  puts everything back. The trash is never emptied automatically.

## The folder contents list

Below the bullets of the node you are looking at, Lunarbor lists
everything in that node's folder that is not already a bullet: subfolders
first, then files, each in natural name order. Dotfiles, `.trash` and the
folders the outline already points at are hidden.

- A folder opens as a node.
- A `.md` note opens in **Markdown mode**: the same editor, with no bullet
  behaviour (no folding, zooming or dragging). It is saved exactly as
  written.
- An image opens in the image view.
- Any other file opens in the system's default app.

## Links and Starred

Links point at folders and files by vault path, never at a bullet by its
title:

```
* See [soups](lunarbor:/Recipes/Soups)
* Plan in [Budget 2027](lunarbor:/Budget%202027.md)
```

A link can target any non-empty folder and any file. When a save renames or
moves a folder (or moves an image), Lunarbor rewrites every link that
points at or through it, in open documents and on disk. A link
whose target has gone missing (trashed, or moved in Finder) is drawn struck
through and left as it is.

A bullet whose only link points at a node can be expanded like a parent: it
then shows that node's bullets under the link, greyed and read-only — a
preview, not a copy. Click one to open it.

`Starred.md` in the vault root stores bookmarks as the same `lunarbor:` links,
so they stay current the same way. It is app data rather than a note:
the folder contents list and link search leave it out.

`_privacy.config` in the vault root holds the privacy modes (JSON: each
mode's id, name and the tags it hides). It is app data too: Lunarbor never
shows it. A mode only hides things on screen and from agents — the files
of hidden nodes stay exactly where and as they are.

## Editing the vault outside Lunarbor

Lunarbor watches the vault. When another program (a coding agent, an
editor, a sync tool) changes a node or note that is open, it is reloaded
from disk at once; edits not yet saved in the app are replaced. Changes made
outside the app are not timestamped; whatever front matter is on disk is
read as it is. The
folder contents list and link previews follow too.

## Why this shape

- **The vault is readable without Lunarbor.** Every node is a folder with
  a plain-text outline; every note is plain Markdown. Another tool, a sync
  service or a shell script sees an ordinary directory tree.
- **Files live where they belong.** A pasted image is written into the
  folder of the bullet it belongs to and referenced by bare file name, so
  it moves when the bullet moves.
- **Top-level folders are separate areas.** There is no separate "space"
  concept: put `Work/` and `Private/` at the root and zoom into either.
