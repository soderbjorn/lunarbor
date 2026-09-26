#!/usr/bin/env python3
"""
Seeds a TreeFacts vault with realistic content in the folder-per-bullet
format, for manual testing and isolated agent runs.

Storage model (see NoteRepository.kt):
  - every node folder holds a hidden `.treefacts` outline listing only its
    direct children, with no indentation;
  - `* text` is a leaf bullet, `+ [title](folder)` a folder-backed bullet,
    `:::` ... `:::` a block;
  - the vault root is the root node: `<vault>/.treefacts`;
  - links are `tf:/` vault paths (TfLink.kt); `Starred.md` at the vault
    root holds `* [Label](tf:/...)` bookmarks.

Generates:
  - a root outline with leaves, folder-backed bullets and a block;
  - a `Projects` tree nested seven levels deep (for expand/zoom testing);
  - attachments (a `.md` note, an image, a text file) inside node folders;
  - `tf:` links to a folder, a note and an image, plus a Starred entry;
  - a folder with no outline file (a node with no bullets yet);
  - an encoded folder name (`Q3%2FQ4 plan` for the title `Q3/Q4 plan`).

Usage:
  python3 scripts/seed-vault.py --vault <path>          # adds to <path>
  python3 scripts/seed-vault.py --vault <path> --wipe   # clears it first

Vault resolution without --vault matches the Electron app (RunPaths.kt):
$TREEFACTS_VAULT, else $TREEFACTS_LOCAL_DATA/vault, else $HOME/treefacts-db.
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
    vault = os.environ.get("TREEFACTS_VAULT", "").strip()
    if vault:
        return Path(vault).expanduser().resolve()
    data = os.environ.get("TREEFACTS_LOCAL_DATA", "").strip()
    if data:
        return Path(data).expanduser().resolve() / "vault"
    return Path.home() / "treefacts-db"


def outline(folder: Path, lines: list[str]) -> None:
    """Writes `<folder>/.treefacts` with one outline line per entry."""
    folder.mkdir(parents=True, exist_ok=True)
    (folder / ".treefacts").write_text("\n".join(lines) + "\n", encoding="utf-8")


def seed(vault: Path, wipe: bool) -> None:
    """Writes the sample tree into [vault], deleting it first when [wipe]."""
    if wipe and vault.exists():
        print(f"Wiping {vault}")
        shutil.rmtree(vault)
    vault.mkdir(parents=True, exist_ok=True)
    print(f"Seeding {vault}")

    outline(vault, [
        "* Welcome to **TreeFacts**",
        "+ [Recipes](Recipes)",
        "+ [Projects](Projects)",
        "+ [Trip to **Lisbon**](Trip to Lisbon)",
        "+ [Q3/Q4 plan](Q3%2FQ4 plan)",
        "* Buy oat milk",
        "* See [soups](tf:/Recipes/Soups) and the [shopping notes](tf:/Recipes/Shopping%20notes.md)",
        ":::",
        "**Blocks** hold free Markdown between the bullets:",
        "",
        "* a list inside a block is just a list",
        "* not an outline",
        ":::",
    ])

    # Recipes: two levels, plus a hand-written note next to the outline.
    outline(vault / "Recipes", [
        "+ [Pasta](Pasta)",
        "+ [Soups](Soups)",
        "* Pancakes",
        "* Granola ![](granola.png)",
    ])
    outline(vault / "Recipes" / "Soups", [
        "* Tomato",
        "* Lentil",
    ])
    (vault / "Recipes" / "granola.png").write_bytes(PIXEL_PNG)
    outline(vault / "Recipes" / "Pasta", [
        "* Carbonara",
        "* Cacio e pepe",
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
            lines.append(f"+ [{nxt}]({nxt})")
        lines.append(f"* Notes for {name}")
        lines.append(f"* Level {depth + 1} item")
        outline(folder, lines)
    (vault / "Projects" / "Alpha" / "spec.txt").write_text("attachment\n", encoding="utf-8")

    # A block inside a node.
    outline(vault / "Trip to Lisbon", [
        "* Flights",
        "* Hotel",
        ":::",
        "**Packing**: passport, charger, adapter",
        ":::",
        "* Photo: [granola](tf:/Recipes/granola.png)",
    ])

    # Encoded folder name.
    outline(vault / "Q3%2FQ4 plan", [
        "* Hire",
        "* Ship",
    ])

    # A folder without an outline: a node with no bullets yet.
    inbox = vault / "Inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    (inbox / "Loose note.md").write_text("A loose note.\n", encoding="utf-8")

    # Starred bookmarks: the same tf: paths links use.
    (vault / "Starred.md").write_text(
        "* [Recipes](tf:/Recipes)\n* [Trip to Lisbon](tf:/Trip%20to%20Lisbon)\n", encoding="utf-8")

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
