#!/usr/bin/env python3
"""Convert a Dynalist OPML backup folder into the live Notegrow database.

Writes the imported outline as one promoted child folder under the live
Notegrow on-disk database at NOTEGROW_DB. The wrapper folder name is
IMPORT_NAME ("Dynalist Import" by default). Existing notes are not touched.
The script also patches NOTEGROW_DB/root.nogr (with a timestamped backup) so
the wrapper bullet is referenced and visible in the running app.

Promotion thresholds mirror `auto-promote-plan.md`:

    PROMOTE_MIN_DESCENDANTS = 40
    MAX_DEPTH_TO_PROMOTE    = 4
    MIN_TITLE_LENGTH        = 1

Note: the live app currently runs with PromotionPolicy.DEBUG = true (threshold
3). The first edit-and-save inside Notegrow will reshard the imported tree to
those thresholds. Content is preserved; only on-disk layout shifts.

On-disk layout produced under the live database:

    NOTEGROW_DB/
        root.nogr                            (patched: wrapper bullet appended)
        Dynalist Import/
            Dynalist Import.nogr
            Bontouch/
                Bontouch.nogr
                Möten/
                    Möten.nogr
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

Re-running is idempotent: any existing NOTEGROW_DB/<IMPORT_NAME>/ tree is
removed before writing fresh files, and any pre-existing wrapper ref line in
root.nogr is stripped before the new one is appended.
"""
from __future__ import annotations

import re
import shutil
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

# --- Config -------------------------------------------------------------------

SOURCE = Path(__file__).resolve().parent / "dynalist-opml-snapshot"
NOTEGROW_DB = Path("/Users/soderbjorn/notegrow-db")
IMPORT_NAME = "Dynalist Import"
IMPORT_DIR = NOTEGROW_DB / IMPORT_NAME
ROOT_FILE = NOTEGROW_DB / "root.nogr"
INDENT = "  "  # 2 spaces per depth level

PROMOTE_MIN_DESCENDANTS = 40
MAX_DEPTH_TO_PROMOTE = 4
MIN_TITLE_LENGTH = 1

# Must match SubtreeCodec.REF_SEPARATOR ("  ") so NoteRepository parses the ref.
WRAPPER_REF_PATH = f"{IMPORT_NAME}/{IMPORT_NAME}.nogr"
WRAPPER_LINE = f"* {IMPORT_NAME}  [[{WRAPPER_REF_PATH}]]"


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

def patch_root_file() -> str:
    """Backup and patch NOTEGROW_DB/root.nogr so the wrapper bullet is visible.

    Reads the existing root.nogr (empty if missing), removes any pre-existing
    line that ends in `[[<WRAPPER_REF_PATH>]]` (idempotency for re-runs),
    appends a single fresh wrapper line, and writes the result back. A
    timestamped backup is created if the file existed.

    @return Description of the backup taken (or a "no prior" sentinel) so
    main() can print it in the summary.
    """
    existed = ROOT_FILE.exists()
    backup_msg: str
    if existed:
        ts = datetime.now().strftime("%Y%m%d-%H%M%S")
        backup_path = ROOT_FILE.with_name(f"root.nogr.bak-{ts}")
        shutil.copy2(ROOT_FILE, backup_path)
        backup_msg = str(backup_path)
        existing = ROOT_FILE.read_text()
    else:
        backup_msg = "no prior root.nogr — creating fresh"
        existing = ""

    # Drop any prior wrapper-ref lines so the file stays at exactly one ref.
    ref_suffix = f"[[{WRAPPER_REF_PATH}]]"
    kept = [ln for ln in existing.splitlines() if not ln.rstrip().endswith(ref_suffix)]

    new_text = "\n".join(kept).rstrip("\n")
    if new_text:
        new_text += "\n"
    new_text += WRAPPER_LINE + "\n"

    ROOT_FILE.write_text(new_text)
    return backup_msg


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Source folder missing: {SOURCE}")
    if not NOTEGROW_DB.exists():
        raise SystemExit(
            f"Notegrow database missing: {NOTEGROW_DB}\n"
            "Refusing to auto-create the live database."
        )

    # Wipe any prior import only — never touch the rest of NOTEGROW_DB.
    if IMPORT_DIR.exists():
        shutil.rmtree(IMPORT_DIR)

    # Build the combined outline: one heading bullet per OPML doc, each holding
    # that doc's outline at depth+1.
    combined: list[Bullet] = []
    docs = sorted(SOURCE.glob("*.opml"))
    for opml in docs:
        title = get_doc_title(opml)
        combined.append(Bullet(0, title))
        for b in parse_opml(opml):
            combined.append(Bullet(b.depth + 1, b.text))

    # Wrap the combined outline as one promoted child of root: it lands at
    # NOTEGROW_DB/<IMPORT_NAME>/<IMPORT_NAME>.nogr with descendants nested
    # underneath. depth_offset=1 mirrors how the recursion treats top-level
    # promoted children (their content is globally one level deep).
    files_to_write: list[tuple[str, list[Bullet], dict[int, str]]] = []
    used_per_dir: dict[str, set[str]] = {}
    split_and_emit(
        combined, files_to_write,
        current_dir_rel=IMPORT_NAME, file_basename=IMPORT_NAME,
        depth_offset=1, used_per_dir=used_per_dir,
    )

    total_bytes = 0
    for rel_path, bullets, refs in files_to_write:
        full_path = NOTEGROW_DB / rel_path
        full_path.parent.mkdir(parents=True, exist_ok=True)
        text = render(bullets, refs)
        full_path.write_text(text)
        total_bytes += len(text.encode("utf-8"))

    backup_msg = patch_root_file()

    wrapper_rel = f"{IMPORT_NAME}/{IMPORT_NAME}.nogr"
    wrapper_entry = next(f for f in files_to_write if f[0] == wrapper_rel)
    children = [f for f in files_to_write if f[0] != wrapper_rel]

    print(f"Wrote {IMPORT_DIR}")
    print(f"  source docs:    {len(docs)}")
    print(f"  combined input: {len(combined)} bullets")
    print(f"  wrapper file:   {wrapper_rel} — "
          f"{len(wrapper_entry[1])} bullets, {len(wrapper_entry[2])} promoted refs")
    print(f"  child files:    {len(children)}")
    print(f"  total bytes:    {total_bytes}")
    if children:
        sizes = sorted(len(b) for _, b, _ in children)
        print(f"    child file sizes — min={sizes[0]} med={sizes[len(sizes)//2]} "
              f"p90={sizes[int(0.9*len(sizes))-1]} max={sizes[-1]} mean={sum(sizes)//len(sizes)}")
        print(f"    files with further-promoted children: "
              f"{sum(1 for _, _, r in children if r)}")
        max_dirs = max(p.count("/") for p, _, _ in files_to_write)
        print(f"    deepest nesting (path separators): {max_dirs}")
    print(f"Patched {ROOT_FILE}")
    print(f"  backup:         {backup_msg}")
    print(f"  appended line:  {WRAPPER_LINE}")


if __name__ == "__main__":
    main()
