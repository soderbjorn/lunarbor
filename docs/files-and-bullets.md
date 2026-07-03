# Files and bullets

TreeFacts has two complementary structures that describe the same vault:

1. **A bullet outline.** Every `.md` file holds a CommonMark bullet list. The
   bullets form a tree by indentation. This is the user's writing surface —
   the document you scroll through and edit.
2. **A filesystem.** The vault is a directory of `.md` files. The filesystem
   describes where on disk the content lives, and which folders contain
   which files. This is the durable substrate — what you'd see in Finder, or
   what Obsidian sees if you point it at the same directory.

These two structures coexist deliberately. The outline gives you the
top-down, hierarchical reading view. The filesystem gives you the
bag-of-files reality — the kind another tool, or a future-you with a
different organisational instinct, can drop new notes into without
ceremony.

## Promoted refs are the bridge

A bullet in one file can be **promoted** to its own `.md` file via a
markdown link bullet whose URL ends in the literal fragment `#treefacts`:

```
* [Recipes](Recipes/Recipes.md#treefacts)
```

When TreeFacts encounters such a bullet, it treats the linked file's
bullets as if they were spliced in under that bullet. Folding the bullet
collapses the linked file's outline; unfolding reveals it. To the reader
they are one continuous outline; to the filesystem they are two distinct
files.

The link uses one of two URL forms:

- **Promoted-ref bullets** that the autosave loop emits on its own:
  `[Title](path/to/file.md#treefacts)` — a path to the on-disk file plus
  the `#treefacts` marker.
- **User-inserted links** from the Insert Link modal:
  `[Title](#treefacts-bullet=Title/Path)` — a title-path fragment that
  resolves to the same target via the outline tree. Title paths survive
  renames and promotion/demotion better than file paths.

Both forms render as a link bullet that the user can click to navigate
to the target file.

## Anchor files

A file whose path matches the doubled-name shape `<dir>/<dir>.md` is the
**anchor** for the directory `<dir>`. Examples:

- `Root.md` anchors the vault root (`""`).
- `Framna/Framna.md` anchors `Framna/`.
- `Framna/Projects/Projects.md` anchors `Framna/Projects/`.

Anchors are not a special file type — they're just `.md` files at the
doubled-name path. TreeFacts's auto-promotion creates them automatically
when a bullet grows large enough to be split out, but you can hand-author
one in any folder and it picks up the anchor role.

## The parallel files view (per page)

When you open an anchor file, TreeFacts renders the editor in two stacked
sections:

```
┌──────────────────────────────────────┐
│  the file's bullet outline           │   ← the document body
│  (links, sub-bullets, promoted-refs) │
├──────────────────────────────────────┤
│  Files                               │   ← the parallel files footer
│  ├── (sibling .md files in <dir>/)   │
│  └── (subfolders in <dir>/)          │
└──────────────────────────────────────┘
```

The top half is the file's own bullet outline. The bottom half — the
"Files" footer — is a live filesystem listing of the directory that the
file anchors. The listing is filtered so that:

- The anchor file itself is hidden (you wouldn't want to navigate to the
  file you're already in).
- Every file or directory that's already promoted from the outline above
  is hidden (it's not "loose" — the outline already points to it).
- Empty directories give an empty footer, which suppresses the divider
  entirely.

The footer is **per page**. Every anchor file you navigate to renders
*its* sibling files. So:

- At `Root.md`, the footer shows what's directly under the vault root
  that `Root.md` doesn't already link to.
- Navigate into a promoted-ref bullet pointing at `Framna/Framna.md`:
  the footer now shows what's directly under `Framna/` that
  `Framna.md` doesn't link to.
- Navigate deeper into `Framna/Projects/Projects.md`: the footer scopes
  to `Framna/Projects/`.

Non-anchor files (loose `.md` files that don't have the doubled-name
shape) render no footer — they aren't entry points for a directory; they
are just leaves the user opened directly.

## Why this matters

The dual structure is what makes TreeFacts safe to drop into an existing
vault:

- **Files TreeFacts didn't create stay as you wrote them.** A plain `.md`
  file with hand-authored content is treated as opaque foreign markdown.
  TreeFacts renders its link in the bullet that points to it, never
  splices its content, never rewrites it.
- **Files TreeFacts did create are still plain markdown.** Open any
  promoted-ref file in another editor — it's CommonMark with bullet
  lists and links. No frontmatter ceremony, no custom syntax. The
  `#treefacts` URL fragment is an unknown anchor that other tools
  silently ignore.

And it's what makes navigation feel "outline-shaped" without forcing the
user to maintain a perfect outline:

- Anything you've added to the outline is in the outline. Click,
  expand, fold, drag.
- Anything you haven't yet linked is in the footer of whichever anchor
  page covers its directory. You can find it, click into it, edit it,
  and (when ready) hoist it into the outline by adding a link.

The footer is the bridge in the other direction: from "files on disk"
back into "bullets in the outline".

## Insert Link with folder picks

The Insert Link modal (Cmd+K) searches every bullet and every loose file
in the vault — and it also lists **folder stubs**. A folder stub is a
vault directory that contains `.md` files but lacks its own anchor.
Picking a folder stub materialises an empty anchor file
`<dir>/<dir>.md` on disk and inserts a link pointing at it. From that
moment on the directory has an anchor: you can navigate into it, the
footer for that anchor surfaces the directory's contents, and the
folder behaves like any other promoted-ref destination.

This is how you "promote a folder" — without copying its contents, and
without renaming any of its existing `.md` files.

## Summary

- A vault is a tree of `.md` files. Each file is a CommonMark bullet
  outline.
- A bullet can be promoted to its own file via a `#treefacts` link. The
  outline reads as one continuous tree across file boundaries.
- A file at `<dir>/<dir>.md` is the **anchor** for `<dir>/`. Anchors
  render with a parallel "Files" footer below the document body,
  scoped to that directory and filtered against the document's own
  promoted refs.
- Each page (each anchor file you navigate to) has its own parallel
  files structure — the outline above for what's already linked, the
  footer below for what isn't yet.
