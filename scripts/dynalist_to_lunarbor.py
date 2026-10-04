#!/usr/bin/env python3
"""
Imports a Dynalist OPML backup into a Lunarbor vault, in the
folder-per-bullet format (TRF-3). Terminal-only: there is no in-app import.

Usage:
  python3 scripts/dynalist_to_lunarbor.py <opml-folder> [<vault>]

  <opml-folder>  Folder of Dynalist `.opml` files. Subfolders (Dynalist
                 folders) are imported as parent bullets holding their
                 documents.
  <vault>        Vault root. Defaults like the Electron app (RunPaths.kt):
                 $LUNARBOR_VAULT, else $LUNARBOR_LOCAL_DATA/vault, else
                 ~/lunarbor-db.

Quit Lunarbor (or at least close the vault root) before running it: an
open root outline with unsaved edits would be saved over the patched file.

Output, under the vault root:

  <vault>/_node.md                   root outline; gets one line appended:
                                     `- Dynalist Import [↳](<Dynalist Import/_node.md>)`
  <vault>/Dynalist Import/_node.md   one bullet per OPML document / folder
  <vault>/Dynalist Import/<doc>/...  one folder per item with children

Storage rules mirrored from the app (NoteRepository.kt, SubtreeCodec.kt):
  - every node folder holds a `_node.md` outline (plain Markdown) listing
    only its direct children, with no indentation;
  - `- text` is a leaf; a folder-backed bullet ends in a child link,
    `- title [↳](<folder/_node.md>)` (`%` in the folder written `%25`);
    text Markdown would misread (`1. `, `- `, `---`, a leading `\\`) is
    backslash-escaped as `SubtreeCodec.escapeBulletText` does;
  - an item with children, or with a note, gets a folder;
  - a Dynalist `_note` becomes a block — a blockquote, `> ` before each
    line — as the item's first child;
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

The fixture test is scripts/test_dynalist_to_lunarbor.py.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

IMPORT_NAME = "Dynalist Import"
OUTLINE_FILE_NAME = "_node.md"
TRASH_DIR = ".trash"
STAGING_DIR = ".dynalist-import-staging"


# --------------------------------------------------------------- vault path

def default_vault() -> Path:
    """Resolves the vault the same way the Electron app does (RunPaths.kt)."""
    vault = os.environ.get("LUNARBOR_VAULT", "").strip()
    if vault:
        return Path(vault).expanduser().resolve()
    data = os.environ.get("LUNARBOR_LOCAL_DATA", "").strip()
    if data:
        return Path(data).expanduser().resolve() / "vault"
    return Path.home() / "lunarbor-db"


# ------------------------------------------------------ folder-name codec
# A port of FolderName.kt, InlineMarkdownTokenizer.kt (display text only)
# and LineMarkdownPrefix.kt. Keep it in step with them: the golden cases in
# test_dynalist_to_lunarbor.py are pinned on the Kotlin side too
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


def _is_tag_name_char(ch: str) -> bool:
    """Mirror of the tokenizer's `isTagNameChar`."""
    return ch.isalnum() or ch in "_-"


def _display_and_tags(text: str) -> tuple[str, list[tuple[int, int]]]:
    """Mirror of `InlineMarkdownTokenizer.tokenize(text)`: the visible text
    of one line with bold / italic / strike / code markers, link syntax and
    images removed, plus the display ranges `(start, end)` of its `#tags`
    (which stay visible)."""
    out: list[str] = []
    tags: list[tuple[int, int]] = []
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
        if (text[pos] == "#" and pos + 1 < n and text[pos + 1].isalpha()
                and not (pos > 0 and _is_tag_name_char(text[pos - 1]))):
            end = pos + 2
            while end < n and _is_tag_name_char(text[end]):
                end += 1
            start = len("".join(out))
            out.append(text[pos:end])
            tags.append((start, start + end - pos))
            pos = end
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
    return "".join(out), tags


def inline_display_text(text: str) -> str:
    """Mirror of `InlineMarkdownTokenizer.tokenize(text).displayText`."""
    return _display_and_tags(text)[0]


def _strip_line_prefix(title: str) -> str:
    for prefix in _LINE_PREFIXES:
        if title.startswith(prefix):
            return title[len(prefix):]
    return title


def plain_text_of(title: str) -> str:
    """Mirror of `FolderName.plainTextOf`."""
    return inline_display_text(_strip_line_prefix(title))


def name_text_of(title: str) -> str:
    """Mirror of `FolderName.nameTextOf`: the plain text without its `#tags`,
    each taken out with one adjoining space."""
    shown, tags = _display_and_tags(_strip_line_prefix(title))
    for start, end in reversed(tags):
        space_after = end == len(shown) or shown[end] == " "
        if start > 0 and shown[start - 1] == " " and space_after:
            start -= 1
        elif start == 0 and end < len(shown) and shown[end] == " ":
            end += 1
        shown = shown[:start] + shown[end:]
    return shown


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
    plain = name_text_of(title)
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

ORDERED_MARKER = re.compile(r"^\d{1,9}[.)](\s|$)")
THEMATIC_BREAK = re.compile(r"^([-*_])( *\1){2,} *$")
CHILD_LINK = re.compile(r"(?:^| )\[[^\[\]]*\]\((?:<([^<>]*)>|([^\s()]*))\)$")


def escape_bullet_text(text: str) -> str:
    """Mirror of `SubtreeCodec.escapeBulletText` (titles here never end in
    a child-link lookalike, so that rule is left out)."""
    digits = len(text) - len(text.lstrip("0123456789"))
    if (text.startswith("\\") or text[:2] in ("- ", "* ", "+ ") or text in ("-", "*", "+")
            or THEMATIC_BREAK.match(text)):
        return "\\" + text
    if ORDERED_MARKER.match(text) or (1 <= digits <= 9 and text[digits:digits + 1] == "\\"):
        return text[:digits] + "\\" + text[digits:]
    return text


def child_link(folder: str) -> str:
    """Mirror of `SubtreeCodec.formatChildLink`."""
    return f"[↳](<{folder.replace('%', '%25')}/{OUTLINE_FILE_NAME}>)"


def format_bullet(title: str, folder: str | None = None) -> str:
    """Mirror of `SubtreeCodec.formatBullet`: `- text`, plus a child link
    when the bullet has a folder; an empty leaf is `-`."""
    parts = [p for p in (escape_bullet_text(title), child_link(folder) if folder else "") if p]
    return "- " + " ".join(parts) if parts else "-"


def child_link_folder(line: str) -> str | None:
    """The folder a bullet line's trailing child link points at, or None."""
    s = line.rstrip()
    if not s or s[0] not in "-*+" or (len(s) > 1 and s[1] != " "):
        return None
    m = CHILD_LINK.search(s)
    if not m:
        return None
    dest = m.group(1) if m.group(1) is not None else m.group(2)
    suffix = "/" + OUTLINE_FILE_NAME
    if not dest.endswith(suffix):
        return None
    folder = urllib.parse.unquote(dest[: -len(suffix)])
    return folder if folder and "/" not in folder and folder not in (".", "..") else None


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
    """Writes `folder/_node.md` for a node whose content is the block
    `note` (if any) followed by `children`, recursing into a folder for
    every child with content. Returns the number of bullets written."""
    folder.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    if note:
        lines += [">" if line == "" else "> " + line for line in note]
    used: set[str] = set()
    count = 0
    for child in children:
        count += 1
        if child.has_content:
            name = unique_name(folder_name_for_title(child.title), used)
            lines.append(format_bullet(child.title, name))
            count += write_node(folder / name, child.note, child.children)
        else:
            lines.append(format_bullet(child.title))
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
    """Indices of the bullet lines in an outline whose child link points at
    the import folder (case-insensitive). Block lines (`>`) are never
    bullets, so block content is never touched."""
    return [
        i for i, line in enumerate(lines)
        if (child_link_folder(line.rstrip("\r")) or "").lower() == IMPORT_NAME.lower()
    ]


def patch_root(vault: Path) -> None:
    """Drops every root `+` line pointing at the import folder and appends
    one fresh line. Every other line, block content included, is kept byte
    for byte. Written through a temp file + rename."""
    path = vault / OUTLINE_FILE_NAME
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    if text and not text.endswith("\n"):
        text += "\n"
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    drop = set(import_bullet_rows(lines))
    kept = [line for i, line in enumerate(lines) if i not in drop]
    # After a block, a blank line keeps the new bullet out of the quote.
    if kept and kept[-1].lstrip().startswith(">"):
        kept.append("")
    kept.append(format_bullet(IMPORT_NAME, IMPORT_NAME))
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
                        help="vault root (default: $LUNARBOR_VAULT, "
                             "$LUNARBOR_LOCAL_DATA/vault, ~/lunarbor-db)")
    args = parser.parse_args(argv)
    source = args.opml_folder.expanduser().resolve()
    vault = args.vault.expanduser().resolve() if args.vault else default_vault()
    print(f"Importing {source}\n     into {vault / IMPORT_NAME}")
    result = run_import(source, vault)
    for t in result["trashed"]:
        print(f"Previous import moved to {t}")
    print(f"Done: {result['entries']} top-level entries, {result['bullets']} bullets.")
    print(f"Root outline {vault / OUTLINE_FILE_NAME} ends with "
          f"{format_bullet(IMPORT_NAME, IMPORT_NAME)}")


if __name__ == "__main__":
    main(sys.argv[1:])
