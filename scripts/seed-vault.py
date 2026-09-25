#!/usr/bin/env python3
"""
Seeds the TreeFacts storage directory with a realistic mix of markdown files
for testing the filesystem-tree footer.

TreeFacts distinguishes its own promoted-ref bullets by appending `#treefacts`
to the URL of each markdown link bullet that should auto-splice. Files
themselves carry no TreeFacts-specific syntax — they're plain markdown.

Generates:
  - A TreeFacts tree linked from Root.md via `[Title](path#treefacts)` bullets,
    nested two levels deep.
  - An orphan TreeFacts tree (`Books/Books.md`) — exists on disk but isn't
    linked from anywhere; click in the footer should adopt it.
  - Foreign markdown files at arbitrary paths (no `#treefacts` fragments,
    with Obsidian-style frontmatter, headers, bold/italic, code blocks).
    Click in the footer should adopt them as TreeFacts promoted refs.

Usage:
  python3 seed-vault.py                  # writes to the resolved vault
  python3 seed-vault.py --vault <path>   # writes to <path>
  python3 seed-vault.py --wipe           # clears the vault first

Vault resolution matches the Electron app (RunPaths.kt): --vault, else
$TREEFACTS_VAULT, else $TREEFACTS_LOCAL_DATA/vault, else $HOME/treefacts-db.
"""

import argparse
import os
import shutil
import sys
from pathlib import Path

def default_vault() -> Path:
    """Resolves the vault the same way the Electron app does when no
    --vault argument is given."""
    vault = os.environ.get("TREEFACTS_VAULT", "").strip()
    if vault:
        return Path(vault).expanduser().resolve()
    data = os.environ.get("TREEFACTS_LOCAL_DATA", "").strip()
    if data:
        return Path(data).expanduser().resolve() / "vault"
    return Path.home() / "treefacts-db"


def write_treefacts(path: Path, body: str) -> None:
    """Writes a TreeFacts-managed markdown file. No frontmatter — TreeFacts
    relies on per-link `#treefacts` fragments, not per-file markers."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


def write_foreign(path: Path, body: str, frontmatter: dict | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    parts = []
    if frontmatter:
        parts.append("---")
        for k, v in frontmatter.items():
            if isinstance(v, list):
                parts.append(f"{k}:")
                for item in v:
                    parts.append(f"  - {item}")
            else:
                parts.append(f"{k}: {v}")
        parts.append("---")
        parts.append("")
    parts.append(body)
    path.write_text("\n".join(parts), encoding="utf-8")


def seed(vault: Path, wipe: bool) -> None:
    if wipe and vault.exists():
        print(f"Wiping {vault}")
        shutil.rmtree(vault)
    vault.mkdir(parents=True, exist_ok=True)
    print(f"Seeding {vault}")

    # ----- Root.md: links to Recipes and Travel as TreeFacts subtrees -----
    root_body = (
        "* Welcome to my notes\n"
        "  * This is the home of all my outlines\n"
        "  * It links to top-level TreeFacts subtrees below\n"
        "* [Recipes](Recipes/Recipes.md#treefacts)\n"
        "* [Travel](Travel/Travel.md#treefacts)\n"
        "* Daily\n"
        "  * Things to remember today\n"
        "  * Buy milk\n"
        "  * Renew library card\n"
    )
    write_treefacts(vault / "Root.md", root_body)

    # ----- Recipes (TreeFacts, nested with Pasta promoted) ----------------
    recipes_body = (
        "* Bread\n"
        "  * Sourdough starter takes about a week\n"
        "  * 80% hydration\n"
        "* [Pasta](Pasta/Pasta.md#treefacts)\n"
        "* Salads\n"
        "  * Caesar dressing\n"
        "    * Egg yolk, anchovy, garlic, lemon, parmesan, oil\n"
        "  * Greek\n"
    )
    write_treefacts(vault / "Recipes" / "Recipes.md", recipes_body)

    # Pasta — nested under Recipes, contains its own promoted subtree (Sauces)
    pasta_body = (
        "* Cacio e Pepe\n"
        "  * Pecorino, black pepper, pasta water\n"
        "* Carbonara\n"
        "  * Guanciale, eggs, pecorino\n"
        "* [Sauces](Sauces/Sauces.md#treefacts)\n"
    )
    write_treefacts(vault / "Recipes" / "Pasta" / "Pasta.md", pasta_body)

    # Sauces — nested two levels deep under Pasta
    sauces_body = (
        "* Tomato\n"
        "  * San Marzano + garlic + basil\n"
        "* Pesto\n"
        "  * Basil, pine nuts, parmesan, garlic, olive oil\n"
        "* Browned butter sage\n"
    )
    write_treefacts(vault / "Recipes" / "Pasta" / "Sauces" / "Sauces.md", sauces_body)

    # ----- Travel (TreeFacts) ---------------------------------------------
    travel_body = (
        "* Trips\n"
        "  * Lisbon, May 2026\n"
        "  * Tokyo someday\n"
        "* Packing list\n"
        "  * Passport\n"
        "  * Adapters\n"
        "  * Comfortable shoes\n"
    )
    write_treefacts(vault / "Travel" / "Travel.md", travel_body)

    # ----- Orphan TreeFacts file (conforms to shape, not linked from root) -
    # Clicking this in the footer should trigger the "adopt orphan" path.
    orphan_body = (
        "* Reading list\n"
        "  * Gödel, Escher, Bach\n"
        "  * The Rust Book\n"
        "  * Designing Data-Intensive Applications\n"
        "* Started but not finished\n"
        "  * Anna Karenina\n"
    )
    write_treefacts(vault / "Books" / "Books.md", orphan_body)

    # ----- Foreign Markdown files (Obsidian-style) -----------------------
    # These have headers, styles, code blocks — TreeFacts doesn't render
    # them specially but should still list them in the footer.

    obsidian_journal = """# Journal — 2026-05-03

A quick brain-dump from a long Sunday.

## Morning

Coffee, a slow walk to the bakery, and 40 minutes of reading. The book is
*Designing Data-Intensive Applications* and the chapter on **replication**
finally made the leader/follower trade-offs click.

## Afternoon

Worked on the TreeFacts filesystem footer. Some highlights:

- The lazy-load model means deep vaults stay snappy
- Foreign markdown files coexist with TreeFacts's own format

```kotlin
suspend fun listVaultLevel(dirRel: String): List<VaultEntry> {
    // …reads one folder, peeks frontmatter on each .md
}
```

## Links

See [[Recipes]] for the bread experiments. Also worth a re-read:
[How to Read a Book](https://example.com/htrab).
"""
    write_foreign(
        vault / "Journal" / "2026-05-03.md",
        obsidian_journal,
        frontmatter={"date": "2026-05-03", "tags": ["journal", "weekend"]},
    )

    obsidian_meeting = """# Standup — Monday

## Attendees

- Robert
- (async) the rest of the team

## Updates

### Done last week

1. Shipped the auto-promote pipeline
2. Reviewed two PRs
3. Wrote up the architecture doc

### Plan for this week

- [ ] Filesystem-tree footer
- [ ] Vault listing IPC
- [x] Plan in writing

> Note: avoid touching the autosave path while the footer lands.

## Risks

| Area              | Severity | Mitigation                |
| ----------------- | -------- | ------------------------- |
| Foreign-file edit | Med      | Add frontmatter on adopt  |
| Nested expand     | Low      | Walk dirRel chain in VM   |
"""
    write_foreign(
        vault / "Work" / "Standups" / "2026-05-04.md",
        obsidian_meeting,
        frontmatter={"type": "standup", "tags": ["work", "weekly"]},
    )

    obsidian_recipe_loose = """# Quick Granola

A foreign markdown recipe — sits next to the TreeFacts Recipes tree but
isn't part of it.

## Ingredients

- 3 cups rolled oats
- 1 cup nuts (almonds, pecans, whatever)
- ½ cup maple syrup
- ¼ cup olive oil
- 1 tsp salt
- 1 tsp cinnamon

## Method

1. Mix everything in a big bowl
2. Spread onto a parchment-lined sheet
3. Bake at **150 °C / 300 °F** for 35–40 min, stirring twice
4. Cool fully before storing

> Doubles easily. Add dried fruit *after* baking or it'll burn.
"""
    write_foreign(
        vault / "Recipes" / "Quick Granola.md",
        obsidian_recipe_loose,
        frontmatter={"tags": ["recipe", "breakfast"]},
    )

    obsidian_link_dump = """# Things to read later

A flat list, no TreeFacts structure. Just links.

- [Hillel Wayne — Computer Things](https://buttondown.email/hillelwayne)
- [Julia Evans — wizardzines](https://wizardzines.com)
- [Drew DeVault](https://drewdevault.com)
- [Dan Luu](https://danluu.com)

## Code I want to study

```python
def fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a
```

```rust
fn main() {
    println!("hello, vault");
}
```
"""
    write_foreign(vault / "links.md", obsidian_link_dump)

    # A deeply-nested foreign file to exercise the lazy expansion in the footer
    write_foreign(
        vault / "Archive" / "2024" / "Q4" / "retrospective.md",
        """# 2024 Q4 retrospective

Some thoughts looking back at the quarter.

## What worked

- Shipping early, even when rough
- Writing things down

## What didn't

- Letting docs go stale
- Skipping standups when busy
""",
        frontmatter={"year": 2024, "quarter": "Q4"},
    )

    print()
    print("Done. Files written:")
    for path in sorted(vault.rglob("*.md")):
        rel = path.relative_to(vault)
        print(f"  {rel}")
    print()
    print("All files are plain markdown. TreeFacts distinguishes its own promoted")
    print("refs by the `#treefacts` URL fragment on each link bullet, not by any")
    print("per-file marker. Click any file in the footer to navigate.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--wipe",
        action="store_true",
        help="Delete the vault directory first (destructive).",
    )
    parser.add_argument(
        "--vault",
        type=Path,
        default=None,
        help="Vault directory to seed (default: $TREEFACTS_VAULT, else "
        "$TREEFACTS_LOCAL_DATA/vault, else ~/treefacts-db).",
    )
    args = parser.parse_args()
    vault = args.vault.expanduser().resolve() if args.vault else default_vault()
    seed(vault, args.wipe)
    return 0


if __name__ == "__main__":
    sys.exit(main())
