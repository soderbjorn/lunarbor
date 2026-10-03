#!/usr/bin/env python3
"""
Fixture tests for scripts/dynalist_to_lunarbor.py (TRF-9).

Run:  python3 -m unittest discover -s scripts -p 'test_*.py'
  or: python3 scripts/test_dynalist_to_lunarbor.py

  - The OPML fixture in fixtures/dynalist/opml/ is imported into an empty
    temp vault and the result must equal fixtures/dynalist/expected/ file
    for file, byte for byte (folder layout, `_node.md` outlines, blocks).
  - Re-running leaves exactly one `Dynalist Import` bullet, moves the old
    import to `.trash/` and touches nothing else in the vault.
  - The folder-name cases in FOLDER_NAME_CASES are pinned on the Kotlin
    side too (client/.../main/DynalistImportTest.kt), so the Python port
    of `FolderName.forTitle` cannot drift from the app unnoticed.

Never touches a real vault: every test works in a temp directory.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import dynalist_to_lunarbor as imp  # noqa: E402

FIXTURE_OPML = HERE / "fixtures" / "dynalist" / "opml"
FIXTURE_EXPECTED = HERE / "fixtures" / "dynalist" / "expected"

# title -> folder name. Keep identical to DynalistImportTest.folderNameCases.
FOLDER_NAME_CASES = [
    ("Q3/Q4 plan", "Q3%2FQ4 plan"),
    ("50% done", "50%25 done"),
    ("50% done...", "50%25 done%2E%2E%2E"),
    ("a/b\\c:d*e?f\"g<h>i|j%k", "a%2Fb%5Cc%3Ad%2Ae%3Ff%22g%3Ch%3Ei%7Cj%25k"),
    (".hidden", "%2Ehidden"),
    ("pad  ", "pad%20%20"),
    ("tab\tin", "tab%09in"),
    ("v1.2 notes", "v1.2 notes"),
    ("Trip to **Lisbon**", "Trip to Lisbon"),
    ("# Heading", "Heading"),
    ("> Quote *it*", "Quote it"),
    ("[site](https://example.com)", "site"),
    ("See [the site](https://example.com) [draft]", "See the site [draft]"),
    ("![](Images/x.png)", "Untitled"),
    ("![alt|300](pic.png) caption", " caption"),
    ("`**code**` and ~~gone~~", "%2A%2Acode%2A%2A and gone"),
    ("*it **bo** it*", "it bo it"),
    ("lonely * star", "lonely %2A star"),
    ("#tag and **#bold**", "#tag and #bold"),
    ("", "Untitled"),
    ("Möten & %2F", "Möten & %252F"),
    ("x" * 200, "x" * 120),
    ("y" * 118 + "/z", "y" * 118),
    ("å" * 100, "å" * 60),
]


def tree(root: Path) -> dict[str, bytes]:
    """Every file under `root`, by POSIX relative path."""
    return {p.relative_to(root).as_posix(): p.read_bytes()
            for p in sorted(root.rglob("*")) if p.is_file()}


class FolderNameTest(unittest.TestCase):

    def test_names_match_the_app(self):
        for title, expected in FOLDER_NAME_CASES:
            with self.subTest(title=title):
                self.assertEqual(expected, imp.folder_name_for_title(title))

    def test_collisions_are_case_insensitive(self):
        used: set[str] = set()
        self.assertEqual("Ideas", imp.unique_name("Ideas", used))
        self.assertEqual("ideas (2)", imp.unique_name("ideas", used))
        self.assertEqual("IDEAS (3)", imp.unique_name("IDEAS", used))

    def test_bullets_escape_what_markdown_would_misread(self):
        # The same cases as SubtreeCodecTest's escaping test.
        self.assertEqual("- 1\\. Picard", imp.format_bullet("1. Picard"))
        self.assertEqual("- \\- not a sub-list", imp.format_bullet("- not a sub-list"))
        self.assertEqual("- \\---", imp.format_bullet("---"))
        self.assertEqual("- # Heading", imp.format_bullet("# Heading"))
        self.assertEqual("-", imp.format_bullet(""))

    def test_child_links(self):
        self.assertEqual("- Q3 [↳](<Q3%252FQ4 plan/_node.md>)", imp.format_bullet("Q3", "Q3%2FQ4 plan"))
        self.assertEqual("Q3%2FQ4 plan", imp.child_link_folder("- Q3 [↳](<Q3%252FQ4 plan/_node.md>)"))
        self.assertEqual("Trip to Lisbon", imp.child_link_folder("* Trip [→](Trip%20to%20Lisbon/_node.md)"))
        self.assertIsNone(imp.child_link_folder("> - X [↳](<X/_node.md>)"))
        self.assertIsNone(imp.child_link_folder("- See [x](Notes/plan.md)"))


class ImportTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.vault = Path(self._tmp.name) / "vault"

    def tearDown(self):
        self._tmp.cleanup()

    def test_fixture_matches_expected_layout(self):
        imp.run_import(FIXTURE_OPML, self.vault)
        self.assertEqual(tree(FIXTURE_EXPECTED), tree(self.vault))

    def test_rerun_replaces_the_import_and_touches_nothing_else(self):
        self.vault.mkdir()
        (self.vault / "_node.md").write_text(
            "- Mine\n- Recipes [↳](<Recipes/_node.md>)\n> - Dynalist Import [↳](<Dynalist Import/_node.md>)\n",
            encoding="utf-8")
        (self.vault / "Recipes").mkdir()
        (self.vault / "Recipes" / "_node.md").write_text("- Pasta\n", encoding="utf-8")
        (self.vault / "Loose.md").write_text("# Loose\n", encoding="utf-8")
        before = {k: v for k, v in tree(self.vault).items() if k != "_node.md"}

        imp.run_import(FIXTURE_OPML, self.vault, now=0)
        (self.vault / imp.IMPORT_NAME / "stale.txt").write_text("old run\n")
        imp.run_import(FIXTURE_OPML, self.vault, now=0)

        root = (self.vault / "_node.md").read_text(encoding="utf-8")
        # The fake bullet inside the block is block content, not a bullet;
        # a blank line keeps the new bullet out of the quote.
        self.assertEqual(
            "- Mine\n- Recipes [↳](<Recipes/_node.md>)\n> - Dynalist Import [↳](<Dynalist Import/_node.md>)\n"
            "\n- Dynalist Import [↳](<Dynalist Import/_node.md>)\n",
            root)
        self.assertEqual(1, len(imp.import_bullet_rows(root.split("\n"))))

        after = tree(self.vault)
        for rel, data in before.items():
            self.assertEqual(data, after[rel], rel)
        imported = {k.split("/", 1)[1]: v for k, v in after.items()
                    if k.startswith(imp.IMPORT_NAME + "/")}
        expected = {k.split("/", 1)[1]: v for k, v in tree(FIXTURE_EXPECTED).items()
                    if k.startswith(imp.IMPORT_NAME + "/")}
        self.assertEqual(expected, imported)
        # Both earlier imports are in the trash; the stale file went with the second.
        trash = sorted(p.name for p in (self.vault / ".trash").iterdir())
        self.assertEqual(["1970-01-01 00.00.00 Dynalist Import"], trash)
        self.assertTrue((self.vault / ".trash" / trash[0] / "stale.txt").exists())
        self.assertFalse((self.vault / imp.STAGING_DIR).exists())

    def test_third_run_gets_a_distinct_trash_name(self):
        for _ in range(3):
            imp.run_import(FIXTURE_OPML, self.vault, now=0)
        trash = sorted(p.name for p in (self.vault / ".trash").iterdir())
        self.assertEqual(["1970-01-01 00.00.00 Dynalist Import",
                          "1970-01-01 00.00.00 Dynalist Import (2)"], trash)
        root = (self.vault / "_node.md").read_text(encoding="utf-8")
        self.assertEqual("- Dynalist Import [↳](<Dynalist Import/_node.md>)\n", root)

    def test_missing_source_changes_nothing(self):
        with self.assertRaises(SystemExit):
            imp.run_import(Path(self._tmp.name) / "nope", self.vault)
        self.assertFalse(self.vault.exists())


if __name__ == "__main__":
    unittest.main()
