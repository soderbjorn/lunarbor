# Files and bullets

A TreeFacts vault is an ordinary directory. The outline you edit and the
folders you see in Finder are the same tree:

- **Every bullet with content is a folder.** A bullet that has child
  bullets, blocks or files is backed by a folder named after its title. A
  bullet with nothing under it is just a line in its parent's outline.
- **Every node folder holds one outline file**, `.treefacts` (hidden),
  listing that node's direct children. The vault root is the root node.
- **Everything else in a folder is a file of yours** — Markdown notes,
  images, PDFs, anything. TreeFacts lists them under the bullets and never
  rewrites them.

```
vault/
  .treefacts              * Buy oat milk
                          + [Recipes](Recipes)
  Recipes/
    .treefacts            + [Soups](Soups)
                          * Granola ![](granola.png)
    Soups/
      .treefacts          * Tomato
    granola.png
    Shopping notes.md
  Starred.md
```

## The outline file

`.treefacts` holds one line per direct child, with no indentation (depth is
the folder tree):

- `* text` — a leaf bullet; the text is inline Markdown.
- `+ [title](folder)` — a folder-backed bullet. The title keeps its
  formatting; the folder name is stored explicitly, relative to this
  folder.
- `:::` … `:::` — a block: free Markdown shown in a bordered box among
  the bullets. A list inside a block is just a list, not part of the
  outline. The fence grows (`::::`) when the content contains a `:::`
  line.

Folder names are the title's plain text with unsafe characters
percent-encoded (`Q3/Q4 plan` is stored as `Q3%2FQ4 plan`), capped at 120
bytes; sibling collisions get ` (2)`, ` (3)`, …; an empty title is
`Untitled`.

## Saving

TreeFacts saves one second after the last edit, and at least every five
seconds while you keep typing. Each save keeps the folders in step with the
outline:

- A leaf that gets its first child becomes a folder; a folder whose last
  child goes away is removed again, unless it still holds files.
- Editing a title renames the folder. Indent, outdent, drag and cut/paste
  move it, so its files travel with it.
- Deleting a folder-backed bullet moves its folder to
  `<vault>/.trash/<timestamp> <name>/`. Undo in the same session moves it
  back. The trash is never emptied automatically.

## The folder contents list

Below the bullets of the node you are looking at, TreeFacts lists
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
* See [soups](tf:/Recipes/Soups)
* Plan in [Budget 2027](tf:/Budget%202027.md)
```

A link can target any non-empty folder and any file. When a save renames or
moves a folder (or moves an image), TreeFacts rewrites every link that
points at or through it, in open documents and on disk. A link
whose target has gone missing (trashed, or moved in Finder) is drawn struck
through and left as it is.

`Starred.md` in the vault root stores bookmarks as the same `tf:` links,
so they stay current the same way.

## Why this shape

- **The vault is readable without TreeFacts.** Every node is a folder with
  a plain-text outline; every note is plain Markdown. Another tool, a sync
  service or a shell script sees an ordinary directory tree.
- **Files live where they belong.** A pasted image is written into the
  folder of the bullet it belongs to and referenced by bare file name, so
  it moves when the bullet moves.
- **Top-level folders are separate areas.** There is no separate "space"
  concept: put `Work/` and `Private/` at the root and zoom into either.
