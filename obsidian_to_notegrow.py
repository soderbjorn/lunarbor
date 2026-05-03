#!/usr/bin/env python3
"""
Copies an Obsidian vault verbatim into the Notegrow storage directory.

No transformation: file contents, names, and directory structure are
preserved exactly as-is, including hidden folders such as `.obsidian/`.
File metadata (mtime, mode) is preserved via shutil.copy2.

Usage:
  python3 obsidian_to_notegrow.py
  python3 obsidian_to_notegrow.py /path/to/vault
  python3 obsidian_to_notegrow.py /path/to/vault /path/to/dest
  python3 obsidian_to_notegrow.py --wipe       # clears destination first
"""

import argparse
import shutil
import sys
from pathlib import Path

DEFAULT_SRC = Path("/Users/soderbjorn/Documents/My vault")
DEFAULT_DEST = Path.home() / "notegrow-db"


def copy_vault(src: Path, dest: Path, wipe: bool) -> None:
    if not src.is_dir():
        sys.exit(f"Source vault does not exist or is not a directory: {src}")

    if wipe and dest.exists():
        print(f"Wiping {dest}")
        shutil.rmtree(dest)

    print(f"Copying {src} -> {dest}")
    shutil.copytree(src, dest, dirs_exist_ok=True, copy_function=shutil.copy2)
    print("Done.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("src", nargs="?", type=Path, default=DEFAULT_SRC)
    parser.add_argument("dest", nargs="?", type=Path, default=DEFAULT_DEST)
    parser.add_argument(
        "--wipe",
        action="store_true",
        help="Delete the destination directory first (destructive).",
    )
    args = parser.parse_args()
    copy_vault(args.src, args.dest, args.wipe)


if __name__ == "__main__":
    main()
