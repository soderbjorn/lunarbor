#!/usr/bin/env python3
"""Convert a Dynalist OPML backup folder to Notegrow on-disk format.

Produces the structure that auto-promotion would have settled into if the user
had typed every Dynalist outline by hand into Notegrow's editor under the
production thresholds defined in `auto-promote-plan.md`:

    PROMOTE_MIN_DESCENDANTS = 40
    DEMOTE_MAX_DESCENDANTS  = 15  (irrelevant for a one-shot batch import)
    MAX_DEPTH_TO_PROMOTE    = 4
    MIN_TITLE_LENGTH        = 1

On-disk layout (no wrapper folder — `root.nogr` sits next to its children):

    OUTPUT/
        root.nogr
        Bontouch/
            Bontouch.nogr
            Möten/
                Möten.nogr
            Tidigare sammanfattning/
                Tidigare sammanfattning.nogr
        Privat/
            Privat.nogr
            ...

Each promoted bullet creates a sibling `<Title>/` directory containing
`<Title>.nogr`; further-promoted descendants nest inside, mirroring the
outline. References inside a file are always relative to that file's own
directory and follow the form `<Title>/<Title>.nogr`.

Each bullet is one line: leading "  " * depth + "* " + title.
Dynalist `_note` text is preserved as additional sub-bullets (one per line),
since Notegrow has no first-class note concept yet.

Filenames preserve the original bullet title verbatim (case, spaces, non-ASCII)
except where the filesystem disallows it. Collisions inside the same directory
are resolved by appending " 2", " 3", … to the second and later occurrences.
"""
from __future__ import annotations

import re
import shutil
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

# --- Config -------------------------------------------------------------------

SOURCE = Path("/Users/soderbjorn/Downloads/dynalist-backup-opml-2023-02-04")
OUTPUT = Path("/Users/soderbjorn/repo/notegrow/main/dynalist-import")
INDENT = "  "  # 2 spaces per depth level

PROMOTE_MIN_DESCENDANTS = 40
MAX_DEPTH_TO_PROMOTE = 4
MIN_TITLE_LENGTH = 1


# --- Data ---------------------------------------------------------------------

@dataclass
class Bullet:
    depth: int  # depth within its containing file (root of a file = 0)
    text: str


# --- OPML parsing -------------------------------------------------------------

def get_doc_title(path: Path) -> str:
    tree = ET.parse(path)
    root = tree.getroot()
    head = root.find("head")
    if head is not None:
        title_el = head.find("title")
        if title_el is not None and (title_el.text or "").strip():
            return title_el.text.strip()
    return path.stem


def parse_opml(path: Path) -> list[Bullet]:
    """Return the OPML's outline as a flat list of bullets at depths 0+."""
    tree = ET.parse(path)
    body = tree.getroot().find("body")
    bullets: list[Bullet] = []
    if body is not None:
        for child in body:
            if child.tag == "outline":
                _walk_opml(child, depth=0, out=bullets)
    return bullets


def _walk_opml(node: ET.Element, depth: int, out: list[Bullet]) -> None:
    text = (node.get("text") or "").strip()
    out.append(Bullet(depth, text))
    note = node.get("_note") or ""
    if note:
        # One sub-bullet per non-empty line of note text.
        for line in note.splitlines():
            stripped = line.strip()
            if stripped:
                out.append(Bullet(depth + 1, stripped))
    for child in node:
        if child.tag == "outline":
            _walk_opml(child, depth + 1, out)


# --- Filename helpers ---------------------------------------------------------

# Strip only what the filesystem cannot handle. macOS/APFS forbids '/' and NUL;
# leading dots make the file hidden in most tools so we trim them too.
_FS_ILLEGAL = re.compile(r"[\x00/]")


def safe_filename(title: str) -> str:
    """Return the title as a filesystem-safe filename, preserving as much of the
    original (case, spaces, non-ASCII) as possible."""
    s = title.strip()
    s = _FS_ILLEGAL.sub("-", s)
    s = s.lstrip(".")
    s = re.sub(r"\s+", " ", s)
    if len(s.encode("utf-8")) > 200:
        while s and len(s.encode("utf-8")) > 200:
            s = s[:-1]
        s = s.rstrip()
    return s or "untitled"


def unique_in(base: str, used: set[str]) -> str:
    """Return `base` if it isn't taken in `used`, else `base 2`, `base 3`, …
    Mutates `used` to record the chosen name."""
    if base not in used:
        used.add(base)
        return base
    n = 2
    while True:
        candidate = f"{base} {n}"
        if candidate not in used:
            used.add(candidate)
            return candidate
        n += 1


# --- Promotion ----------------------------------------------------------------

def _spans(bullets: list[Bullet]) -> list[tuple[int, int, int, int]]:
    """For each bullet i, return (i, j_exclusive, depth, descendant_count)."""
    n = len(bullets)
    out = []
    for i in range(n):
        d = bullets[i].depth
        j = i + 1
        while j < n and bullets[j].depth > d:
            j += 1
        out.append((i, j, d, j - i - 1))
    return out


def split_and_emit(
    bullets: list[Bullet],
    files_to_write: list[tuple[str, list[Bullet], dict[int, str]]],
    current_dir_rel: str,
    file_basename: str,
    depth_offset: int,
    used_per_dir: dict[str, set[str]],
) -> None:
    """Process `bullets`, schedule writing this file at
    `current_dir_rel/<file_basename>.nogr`, and recurse into each promoted
    subtree (which becomes its own file under `current_dir_rel/<unique>/`).

    Names are unique-resolved per-directory: two files would collide only if
    they live in the same parent directory, so collision tracking is scoped to
    `current_dir_rel`.
    """
    spans = _spans(bullets)

    candidates = []
    for (i, j, d, descendants) in spans:
        global_depth = d + depth_offset
        title = bullets[i].text.strip()
        if (descendants >= PROMOTE_MIN_DESCENDANTS
                and global_depth <= MAX_DEPTH_TO_PROMOTE
                and len(title) >= MIN_TITLE_LENGTH):
            candidates.append((i, j, d, descendants))

    candidates.sort(key=lambda x: x[0])
    chosen: list[tuple[int, int, int, int]] = []
    last_end = -1
    for s in candidates:
        if s[0] >= last_end:
            chosen.append(s)
            last_end = s[1]

    used = used_per_dir.setdefault(current_dir_rel, set())
    used.add(file_basename)  # this file occupies its own basename slot

    new_bullets: list[Bullet] = []
    refs: dict[int, str] = {}
    cursor = 0
    for (i, j, d, _c) in chosen:
        new_bullets.extend(bullets[cursor:i])
        head = bullets[i]

        title = head.text.strip() or "untitled"
        unique = unique_in(safe_filename(title), used)

        # Ref is always relative to this file's directory; symmetric for every file.
        ref_path = f"{unique}/{unique}.nogr"
        refs[len(new_bullets)] = ref_path
        new_bullets.append(head)

        # Subtree content rebased to depth 0 in the new child file.
        subtree = [Bullet(b.depth - (d + 1), b.text) for b in bullets[i + 1:j]]
        child_dir_rel = f"{current_dir_rel}/{unique}" if current_dir_rel else unique
        split_and_emit(
            subtree, files_to_write, child_dir_rel, unique,
            depth_offset=(d + 1 + depth_offset),
            used_per_dir=used_per_dir,
        )

        cursor = j

    new_bullets.extend(bullets[cursor:])

    file_rel = (
        f"{current_dir_rel}/{file_basename}.nogr"
        if current_dir_rel else f"{file_basename}.nogr"
    )
    files_to_write.append((file_rel, new_bullets, refs))


# --- Rendering ----------------------------------------------------------------

def render(bullets: list[Bullet], refs: dict[int, str]) -> str:
    out = []
    for idx, b in enumerate(bullets):
        line = INDENT * b.depth + "* " + b.text
        if idx in refs:
            line += f"  [[{refs[idx]}]]"
        out.append(line)
    return "\n".join(out) + ("\n" if out else "")


# --- Entry point --------------------------------------------------------------

def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Source folder missing: {SOURCE}")

    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)

    # Build the combined outline: root contains one heading bullet per OPML doc,
    # each holding that doc's outline at depth+1.
    combined: list[Bullet] = []
    docs = sorted(SOURCE.glob("*.opml"))
    for opml in docs:
        title = get_doc_title(opml)
        combined.append(Bullet(0, title))
        for b in parse_opml(opml):
            combined.append(Bullet(b.depth + 1, b.text))

    files_to_write: list[tuple[str, list[Bullet], dict[int, str]]] = []
    used_per_dir: dict[str, set[str]] = {}
    split_and_emit(
        combined, files_to_write,
        current_dir_rel="", file_basename="root",
        depth_offset=0, used_per_dir=used_per_dir,
    )

    for rel_path, bullets, refs in files_to_write:
        full_path = OUTPUT / rel_path
        full_path.parent.mkdir(parents=True, exist_ok=True)
        full_path.write_text(render(bullets, refs))

    print(f"Wrote {OUTPUT}")
    print(f"  source docs:    {len(docs)}")
    print(f"  combined input: {len(combined)} bullets")
    root_entry = next(f for f in files_to_write if f[0] == "root.nogr")
    print(f"  root.nogr:      {len(root_entry[1])} bullets, {len(root_entry[2])} promoted refs")
    children = [f for f in files_to_write if f[0] != "root.nogr"]
    print(f"  child files:    {len(children)}")
    if children:
        sizes = sorted(len(b) for _, b, _ in children)
        print(f"    child file sizes — min={sizes[0]} med={sizes[len(sizes)//2]} "
              f"p90={sizes[int(0.9*len(sizes))-1]} max={sizes[-1]} mean={sum(sizes)//len(sizes)}")
        print(f"    files with further-promoted children: "
              f"{sum(1 for _, _, r in children if r)}")
        # Maximum directory nesting depth (number of '/' in any rel path).
        max_dirs = max(p.count("/") for p, _, _ in files_to_write)
        print(f"    deepest nesting (path separators): {max_dirs}")


if __name__ == "__main__":
    main()
