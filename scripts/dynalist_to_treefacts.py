#!/usr/bin/env python3
"""
Imports a Dynalist OPML backup into a TreeFacts vault, in the
folder-per-bullet format (TRF-3). Terminal-only: there is no in-app import.

Usage:
  python3 scripts/dynalist_to_treefacts.py <opml-folder> [<vault>]

  <opml-folder>  Folder of Dynalist `.opml` files. Subfolders (Dynalist
                 folders) are imported as parent bullets holding their
                 documents.
  <vault>        Vault root. Defaults like the Electron app (RunPaths.kt):
                 $TREEFACTS_VAULT, else $TREEFACTS_LOCAL_DATA/vault, else
                 ~/treefacts-db.

Quit TreeFacts (or at least close the vault root) before running it: an
open root outline with unsaved edits would be saved over the patched file.

Output, under the vault root:

  <vault>/.treefacts                 root outline; gets one line appended:
                                     `+ [Dynalist Import](Dynalist Import)`
  <vault>/Dynalist Import/.treefacts one bullet per OPML document / folder
  <vault>/Dynalist Import/<doc>/...  one folder per item with children

Storage rules mirrored from the app (NoteRepository.kt, SubtreeCodec.kt):
  - every node folder holds a hidden `.treefacts` outline listing only its
    direct children, with no indentation;
  - `* text` is a leaf; `+ [title](folder)` a folder-backed bullet, the
    title's `[`, `]` and `\\` backslash-escaped;
  - an item with children, or with a note, gets a folder;
  - a Dynalist `_note` becomes a `:::`-fenced block as the item's first
    child (a longer fence when the note holds a colon-only line);
  - folder names are encoded exactly as `FolderName.forTitle` does: the
    title's plain text (inline Markdown markers and `# ` / `> ` prefixes
    removed), percent-encoding `/ \\ : * ? " < > |` and `%`, a leading dot,
    trailing dots and spaces and control characters, capped at 120 UTF-8
    bytes; empty names become `Untitled`; sibling collisions get ` (2)`,
    ` (3)`, ... case-insensitively.

Re-running replaces the previous import: the old `Dynalist Import` folder
is moved to `<vault>/.trash/<UTC timestamp> Dynalist Import/` (the same
naming the app uses; the trash is never emptied automatically), the old
root line is dropped and a fresh one appended. Nothing else in the vault is
read or written.

The fixture test is scripts/test_dynalist_to_treefacts.py.
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

IMPORT_NAME = "Dynalist Import"
OUTLINE_FILE_NAME = ".treefacts"
TRASH_DIR = ".trash"
STAGING_DIR = ".dynalist-import-staging"
MIN_FENCE = ":::"


# --------------------------------------------------------------- vault path

def default_vault() -> Path:
    """Resolves the vault the same way the Electron app does (RunPaths.kt)."""
    vault = os.environ.get("TREEFACTS_VAULT", "").strip()
    if vault:
        return Path(vault).expanduser().resolve()
    data = os.environ.get("TREEFACTS_LOCAL_DATA", "").strip()
    if data:
        return Path(data).expanduser().resolve() / "vault"
    return Path.home() / "treefacts-db"


# ------------------------------------------------------ folder-name codec
# A port of FolderName.kt, InlineMarkdownTokenizer.kt (display text only)
# and LineMarkdownPrefix.kt. Keep it in step with them: the golden cases in
# test_dynalist_to_treefacts.py are pinned on the Kotlin side too
# (DynalistImportTest.kt), so a drift fails one of the two tests.

MAX_NAME_BYTES = 120
UNTITLED = "Untitled"
_ALWAYS_ENCODED = "/\\:*?\"<>|%"
_LINE_PREFIXES = ("###### ", "##### ", "#### ", "### ", "## ", "# ", "> ")
_CODE = ("`", "`")
# (open, close) in InlineStyle precedence order: code, bold, strike, italic.
_STYLES = (_CODE, ("**", "**"), ("~~", "~~"), ("*", "*"))


def _parse_link_syntax(text: str, bracket: int):
    """Mirror of `parseLinkSyntaxAtTopLevel`: `(label_end, closing_paren,
    destination)` for a `[label](dest)` starting at `bracket`, else None."""
    if bracket >= len(text) or text[bracket] != "[":
        return None
    i = bracket + 1
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            i += 2
            continue
        if c == "]":
            break
        if c == "[":
            return None
        i += 1
    if i >= len(text) or text[i] != "]":
        return None
    label_end = i
    if label_end + 1 >= len(text) or text[label_end + 1] != "(":
        return None
    url_open = label_end + 2
    if url_open < len(text) and text[url_open] == "<":
        angle_end = text.find(">", url_open + 1)
        if angle_end < 0 or angle_end + 1 >= len(text) or text[angle_end + 1] != ")":
            return None
        return label_end, angle_end + 1, text[url_open + 1:angle_end]
    paren_end = text.find(")", url_open)
    if paren_end < 0:
        return None
    return label_end, paren_end, text[url_open:paren_end]


def _has_matching_closer(text: str, start: int, closer: str, italic: bool) -> bool:
    """Mirror of `Parser.hasMatchingCloser`."""
    i = start
    while i <= len(text) - len(closer):
        if italic and text[i] == "*" and i + 1 < len(text) and text[i + 1] == "*":
            i += 2
            continue
        if text.startswith(closer, i):
            return True
        i += 1
    return False


def inline_display_text(text: str) -> str:
    """Mirror of `InlineMarkdownTokenizer.tokenize(text).displayText`: the
    visible text of one line with bold / italic / strike / code markers,
    link syntax and images removed. Hashtags stay visible, so they need no
    case of their own here."""
    out: list[str] = []
    active: list[tuple[str, str]] = []
    pos = 0
    n = len(text)
    while pos < n:
        # Inline code is opaque: only its closing backtick ends it.
        if _CODE in active:
            if text[pos] == "`":
                active.pop()
            else:
                out.append(text[pos])
            pos += 1
            continue
        if text[pos] == "!" and pos + 1 < n and text[pos + 1] == "[":
            parsed = _parse_link_syntax(text, pos + 1)
            if parsed is not None:
                label_end, close, dest = parsed
                if not (label_end == pos + 2 and dest == ""):
                    pos = close + 1
                    continue
        if text[pos] == "[":
            parsed = _parse_link_syntax(text, pos)
            if parsed is not None:
                label_end, close, _dest = parsed
                out.append(text[pos + 1:label_end])
                pos = close + 1
                continue
        opener = None
        for style in _STYLES:
            if style in active:
                continue
            op = style[0]
            if not text.startswith(op, pos):
                continue
            if op == "*" and pos + 1 < n and text[pos + 1] == "*":
                continue
            if _has_matching_closer(text, pos + len(op), style[1], italic=(op == "*")):
                opener = style
                break
        if opener is not None:
            active.append(opener)
            pos += len(opener[0])
            continue
        if active and text.startswith(active[-1][1], pos):
            pos += len(active[-1][1])
            active.pop()
            continue
        out.append(text[pos])
        pos += 1
    return "".join(out)


def plain_text_of(title: str) -> str:
    """Mirror of `FolderName.plainTextOf`."""
    for prefix in _LINE_PREFIXES:
        if title.startswith(prefix):
            title = title[len(prefix):]
            break
    return inline_display_text(title)


def _encode_uncapped(plain: str) -> str:
    trailing = len(plain)
    while trailing > 0 and plain[trailing - 1] in ". ":
        trailing -= 1
    out = []
    for i, ch in enumerate(plain):
        code = ord(ch)
        if (ch in _ALWAYS_ENCODED or code < 0x20 or code == 0x7F
                or (i == 0 and ch == ".") or i >= trailing):
            out.append("%%%02X" % code)
        else:
            out.append(ch)
    return "".join(out)


def encode_name(plain: str) -> str:
    """Mirror of `FolderName.encode`: percent-encode, then drop whole
    characters from the end of the plain text until the result fits
    MAX_NAME_BYTES."""
    text = plain
    encoded = _encode_uncapped(text)
    while len(encoded.encode("utf-8")) > MAX_NAME_BYTES and text:
        text = text[:-1]
        encoded = _encode_uncapped(text)
    return encoded


def folder_name_for_title(title: str) -> str:
    """Mirror of `FolderName.forTitle`."""
    plain = plain_text_of(title)
    return encode_name(plain) if plain else UNTITLED


def unique_name(base: str, used_lower: set[str]) -> str:
    """Mirror of `FolderName.unique`; also records the result in `used_lower`."""
    candidate = base
    n = 2
    while candidate.lower() in used_lower:
        candidate = f"{base} ({n})"
        n += 1
    used_lower.add(candidate.lower())
    return candidate


# ------------------------------------------------------------ outline file

def format_folder_line(title: str, folder: str) -> str:
    """Mirror of `SubtreeCodec.formatFolderLine`."""
    esc = "".join("\\" + ch if ch in "[]\\" else ch for ch in title)
    return f"+ [{esc}]({folder})"


def fence_for(content: list[str]) -> str:
    """Mirror of `SubtreeCodec.fenceFor`."""
    longest = 0
    for line in content:
        t = line.strip()
        if t and all(c == ":" for c in t):
            longest = max(longest, len(t))
    return ":" * max(len(MIN_FENCE), longest + 1)


def is_fence(line: str) -> bool:
    """Mirror of `SubtreeCodec.isFence`."""
    t = line.strip()
    return len(t) >= len(MIN_FENCE) and all(c == ":" for c in t)


def parse_folder_line(line: str) -> tuple[str, str] | None:
    """Mirror of `SubtreeCodec.parseFolderLine`: `(title, folder)` or None."""
    s = line.rstrip()
    if not s.startswith("+ ["):
        return None
    title = []
    i = 3
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s) and s[i + 1] in "[]\\":
            title.append(s[i + 1])
            i += 2
            continue
        if ch == "]":
            break
        title.append(ch)
        i += 1
    if i + 1 >= len(s) or s[i] != "]" or s[i + 1] != "(" or not s.endswith(")"):
        return None
    folder = s[i + 2:-1]
    if not folder or "/" in folder or folder in (".", ".."):
        return None
    return "".join(title), folder


# --------------------------------------------------------------- the tree

@dataclass
class Item:
    """One imported bullet.

    title: inline-Markdown title, one line.
    note: the Dynalist `_note` lines, written as a block first child;
          empty when the item has no note.
    children: nested items, in order.
    """
    title: str
    note: list[str] = field(default_factory=list)
    children: list["Item"] = field(default_factory=list)

    @property
    def has_content(self) -> bool:
        """An item with content is folder-backed, as in the app."""
        return bool(self.note or self.children)


def _one_line(text: str) -> str:
    """Titles are one outline line: newlines become spaces, ends trimmed."""
    return " ".join(text.replace("\r", "").split("\n")).strip()


def _note_lines(note: str) -> list[str]:
    """Note text as block lines: verbatim, minus `\\r` and blank lines at
    either end. Empty when the note is blank."""
    lines = [ln.rstrip("\r") for ln in note.split("\n")]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return lines


def _from_outline(node: ET.Element) -> Item:
    item = Item(_one_line(node.get("text") or ""), _note_lines(node.get("_note") or ""))
    item.children = [_from_outline(c) for c in node if c.tag == "outline"]
    return item


def parse_opml(path: Path) -> Item:
    """One OPML document as an item titled by `<head><title>` (else the file
    name), holding the document's top-level outlines."""
    root = ET.parse(path).getroot()
    title = ""
    head = root.find("head")
    title_el = head.find("title") if head is not None else None
    if title_el is not None:
        title = _one_line(title_el.text or "")
    body = root.find("body")
    children = [] if body is None else [_from_outline(c) for c in body if c.tag == "outline"]
    return Item(title or path.stem, children=children)


def parse_source(folder: Path) -> list[Item]:
    """Every `.opml` file and every subfolder holding some, sorted by name
    (case-insensitive). A subfolder becomes an item titled by its name."""
    entries = sorted(folder.iterdir(), key=lambda p: (p.name.lower(), p.name))
    out = []
    for entry in entries:
        if entry.name.startswith("."):
            continue
        if entry.is_dir():
            kids = parse_source(entry)
            if kids:
                out.append(Item(entry.name, children=kids))
        elif entry.suffix.lower() == ".opml":
            out.append(parse_opml(entry))
    return out


def write_node(folder: Path, note: list[str], children: list[Item]) -> int:
    """Writes `folder/.treefacts` for a node whose content is the block
    `note` (if any) followed by `children`, recursing into a folder for
    every child with content. Returns the number of bullets written."""
    folder.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    if note:
        fence = fence_for(note)
        lines += [fence, *note, fence]
    used: set[str] = set()
    count = 0
    for child in children:
        count += 1
        if child.has_content:
            name = unique_name(folder_name_for_title(child.title), used)
            lines.append(format_folder_line(child.title, name))
            count += write_node(folder / name, child.note, child.children)
        else:
            lines.append("* " + child.title)
    if lines:
        (folder / OUTLINE_FILE_NAME).write_text("\n".join(lines) + "\n", encoding="utf-8")
    return count


# ------------------------------------------------------ replacing an import

def trash_stamp(now: float) -> str:
    """Mirror of `NoteRepository.formatTimestamp` (UTC)."""
    return time.strftime("%Y-%m-%d %H.%M.%S", time.gmtime(now))


def trash_previous(vault: Path, now: float) -> list[Path]:
    """Moves every root entry named `Dynalist Import` (case-insensitive) to
    `.trash/<stamp> <name>`. Returns the trash paths."""
    moved = []
    for entry in sorted(vault.iterdir()):
        if entry.name.lower() != IMPORT_NAME.lower():
            continue
        trash = vault / TRASH_DIR
        trash.mkdir(exist_ok=True)
        taken = {p.name.lower() for p in trash.iterdir()}
        dest = trash / unique_name(f"{trash_stamp(now)} {entry.name}", taken)
        entry.rename(dest)
        moved.append(dest)
    return moved


def import_bullet_rows(lines: list[str]) -> list[int]:
    """Indices of the `+` lines in an outline that point at the import
    folder (case-insensitive), skipping block content: a line inside a
    `:::` fence is never a bullet."""
    rows = []
    fence = None
    for i, line in enumerate(lines):
        if fence is not None:
            if line.strip() == fence:
                fence = None
        elif is_fence(line):
            fence = line.strip()
        else:
            parsed = parse_folder_line(line.rstrip("\r"))
            if parsed is not None and parsed[1].lower() == IMPORT_NAME.lower():
                rows.append(i)
    return rows


def patch_root(vault: Path) -> None:
    """Drops every root `+` line pointing at the import folder and appends
    one fresh line. Every other line, block content included, is kept byte
    for byte. Written through a temp file + rename."""
    path = vault / OUTLINE_FILE_NAME
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    drop = set(import_bullet_rows(lines))
    kept = [line for i, line in enumerate(lines) if i not in drop]
    kept.append(format_folder_line(IMPORT_NAME, IMPORT_NAME))
    tmp = vault / (OUTLINE_FILE_NAME + ".import-tmp")
    tmp.write_text("\n".join(kept) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def run_import(source: Path, vault: Path, now: float | None = None) -> dict:
    """Imports `source` into `vault`, replacing any previous import.

    The new tree is built in a hidden staging folder first, so a parse or
    write error leaves the vault as it was. `now` (epoch seconds) stamps
    the trash folder; defaults to the wall clock. Returns counts for the
    summary.
    """
    if not source.is_dir():
        raise SystemExit(f"OPML folder not found: {source}")
    docs = parse_source(source)
    if not docs:
        raise SystemExit(f"No .opml files in {source}")
    vault.mkdir(parents=True, exist_ok=True)
    staging = vault / STAGING_DIR
    if staging.exists():
        shutil.rmtree(staging)
    try:
        count = write_node(staging, [], docs)
        trashed = trash_previous(vault, time.time() if now is None else now)
        staging.rename(vault / IMPORT_NAME)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    patch_root(vault)
    return {"entries": len(docs), "bullets": count, "trashed": trashed}


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("opml_folder", type=Path, help="folder of Dynalist .opml files")
    parser.add_argument("vault", type=Path, nargs="?", default=None,
                        help="vault root (default: $TREEFACTS_VAULT, "
                             "$TREEFACTS_LOCAL_DATA/vault, ~/treefacts-db)")
    args = parser.parse_args(argv)
    source = args.opml_folder.expanduser().resolve()
    vault = args.vault.expanduser().resolve() if args.vault else default_vault()
    print(f"Importing {source}\n     into {vault / IMPORT_NAME}")
    result = run_import(source, vault)
    for t in result["trashed"]:
        print(f"Previous import moved to {t}")
    print(f"Done: {result['entries']} top-level entries, {result['bullets']} bullets.")
    print(f"Root outline {vault / OUTLINE_FILE_NAME} ends with "
          f"{format_folder_line(IMPORT_NAME, IMPORT_NAME)}")


if __name__ == "__main__":
    main(sys.argv[1:])
