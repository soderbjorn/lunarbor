#!/usr/bin/env python3
"""
Seeds a Lunarbor vault with realistic content in the folder-per-bullet
format, for manual testing and isolated agent runs.

Storage model (see NoteRepository.kt):
  - every node folder holds a `_node.md` outline (plain Markdown) listing
    only its direct children, with no indentation;
  - `- text` is a leaf bullet; a folder-backed bullet ends in a child link,
    `- title [↳](<folder/_node.md>)`; a block is a blockquote (`> ` lines);
  - the vault root is the root node: `<vault>/_node.md`;
  - links are `lunarbor:/` vault paths (LunarborLink.kt); `Starred.md` at the vault
    root holds `* [Label](lunarbor:/...)` bookmarks.

Generates:
  - a root outline with leaves, folder-backed bullets and a block;
  - a `Projects` tree nested seven levels deep (for expand/zoom testing);
  - attachments (a `.md` note, an image, a text file) inside node folders;
  - `lunarbor:` links to a folder, a note and an image, plus a Starred entry;
  - a folder with no outline file (a node with no bullets yet);
  - an encoded folder name (`Q3%2FQ4 plan` for the title `Q3/Q4 plan`).

Usage:
  python3 scripts/seed-vault.py --vault <path>          # adds to <path>
  python3 scripts/seed-vault.py --vault <path> --wipe   # clears it first

Vault resolution without --vault matches the Electron app (RunPaths.kt):
$LUNARBOR_VAULT, else $LUNARBOR_LOCAL_DATA/vault, else $HOME/lunarbor-db.
"""

import argparse
import os
import base64
import shutil
from pathlib import Path

# A 1x1 PNG, so the folder contents list and image rows have something real.
PIXEL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)


def default_vault() -> Path:
    """Resolves the vault the same way the Electron app does."""
    vault = os.environ.get("LUNARBOR_VAULT", "").strip()
    if vault:
        return Path(vault).expanduser().resolve()
    data = os.environ.get("LUNARBOR_LOCAL_DATA", "").strip()
    if data:
        return Path(data).expanduser().resolve() / "vault"
    return Path.home() / "lunarbor-db"


def outline(folder: Path, lines: list[str]) -> None:
    """Writes `<folder>/_node.md` with one outline line per entry."""
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "_node.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def child(title: str, folder: str) -> str:
    """A folder-backed bullet: the title, then a link to the child's outline."""
    return f"- {title} [↳](<{folder.replace('%', '%25')}/_node.md>)"


def seed(vault: Path, wipe: bool) -> None:
    """Writes the sample tree into [vault], deleting it first when [wipe]."""
    if wipe and vault.exists():
        print(f"Wiping {vault}")
        shutil.rmtree(vault)
    vault.mkdir(parents=True, exist_ok=True)
    print(f"Seeding {vault}")

    outline(vault, [
        "- Welcome to **Lunarbor**",
        child("Recipes", "Recipes"),
        child("Projects", "Projects"),
        child("Trip to **Lisbon**", "Trip to Lisbon"),
        child("Q3/Q4 plan", "Q3%2FQ4 plan"),
        "- Buy oat milk",
        "- See [soups](lunarbor:/Recipes/Soups) and the [shopping notes](lunarbor:/Recipes/Shopping%20notes.md)",
        "> **Blocks** hold free Markdown between the bullets:",
        ">",
        "> * a list inside a block is just a list",
        "> * not an outline",
    ])

    # Recipes: two levels, plus a hand-written note next to the outline.
    outline(vault / "Recipes", [
        child("Pasta", "Pasta"),
        child("Soups", "Soups"),
        "- Pancakes",
        "- Granola ![](granola.png)",
    ])
    outline(vault / "Recipes" / "Soups", [
        "- Tomato",
        "- Lentil",
    ])
    (vault / "Recipes" / "granola.png").write_bytes(PIXEL_PNG)
    outline(vault / "Recipes" / "Pasta", [
        "- Carbonara",
        "- Cacio e pepe",
    ])
    (vault / "Recipes" / "Shopping notes.md").write_text(
        "# Shopping notes\n\nHand-written Markdown next to the outline.\n", encoding="utf-8")

    # Projects: seven levels deep.
    chain = ["Projects", "Alpha", "Design", "UI", "Buttons", "Primary", "States"]
    folder = vault
    for depth, name in enumerate(chain):
        folder = folder / name
        lines = []
        if depth + 1 < len(chain):
            nxt = chain[depth + 1]
            lines.append(child(nxt, nxt))
        lines.append(f"- Notes for {name}")
        lines.append(f"- Level {depth + 1} item")
        outline(folder, lines)
    (vault / "Projects" / "Alpha" / "spec.txt").write_text("attachment\n", encoding="utf-8")

    # A block inside a node.
    outline(vault / "Trip to Lisbon", [
        "- Flights",
        "- Hotel",
        "> **Packing**: passport, charger, adapter",
        "- Photo: [granola](lunarbor:/Recipes/granola.png)",
    ])

    # Encoded folder name.
    outline(vault / "Q3%2FQ4 plan", [
        "- Hire",
        "- Ship",
    ])

    # A folder without an outline: a node with no bullets yet.
    inbox = vault / "Inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    (inbox / "Loose note.md").write_text("A loose note.\n", encoding="utf-8")

    # Starred bookmarks: the same lunarbor: paths links use.
    (vault / "Starred.md").write_text(
        "* [Recipes](lunarbor:/Recipes)\n* [Trip to Lisbon](lunarbor:/Trip%20to%20Lisbon)\n", encoding="utf-8")

    print("Done.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--vault", type=Path, default=None, help="vault directory to seed")
    parser.add_argument("--wipe", action="store_true", help="delete the vault first")
    args = parser.parse_args()
    vault = (args.vault.expanduser().resolve() if args.vault else default_vault())
    seed(vault, args.wipe)


if __name__ == "__main__":
    main()
