#!/usr/bin/env python3
"""
Builds the browser demo's vault, demo/vault/, and its initial persister
state, demo/state.json, from tree.txt (an indented outline DSL), starred.txt
and assets/ in this folder. demo/vault/ is generated: edit tree.txt and
re-run this script (python3 demo/source/build_vault.py), then rebuild the
web bundle. See demo/README.md.

DSL (2-space indentation):
  * text {#id}            bullet; deeper-indented lines are its children
  :::                     block opener at item indent; content lines follow at
  ...                     the same indent; closed by ':::' at the same indent
  :::
  @ file.ext              copy assets/file.ext into the enclosing node's folder
  @ name.md <<<           inline file; content at same indent until '>>>'
  >>>
  {open}                  after a bullet: it starts unfolded (demo/state.json)
Links: (lb:id) -> (lunarbor:/path/of/id), (lb:id/file.ext), (lb:/file.ext)
for a vault-root path. Root-level '@' lines attach to the vault root.
"""
import re, shutil, sys
from pathlib import Path

HERE = Path(__file__).parent
SRC = HERE / "tree.txt"
ASSETS = HERE / "assets"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parent / "vault"

ALWAYS = set('/\\:*?"<>|%')

def plain(title):
    t = re.sub(r"\{\{search:.*?\}\}", "", title).strip()
    t = re.sub(r"^#{1,6} |^> ", "", t)
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", t)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = t.replace("**", "").replace("~~", "").replace("`", "")
    t = re.sub(r"(?<!\w)\*(?!\s)(.+?)(?<!\s)\*", r"\1", t)
    return t.strip()

def fname(title):
    p = plain(title) or "Untitled"
    trailing = len(p)
    while trailing > 0 and p[trailing - 1] in ". ":
        trailing -= 1
    out = []
    for i, ch in enumerate(p):
        if ch in ALWAYS or ord(ch) < 0x20 or (i == 0 and ch == ".") or i >= trailing:
            out.append("%%%02X" % ord(ch))
        else:
            out.append(ch)
    return "".join(out)

class Node:
    def __init__(self, kind, text="", indent=-2):
        self.kind = kind          # 'bullet' | 'block' | 'root'
        self.text = text          # bullet text, or block content lines (list)
        self.indent = indent
        self.children = []
        self.files = []           # (name, bytes-or-None-for-asset)
        self.id = None
        self.folder = None        # vault-relative folder path

    @property
    def backed(self):
        return self.kind == "root" or bool(self.children or self.files)

def parse():
    lines = SRC.read_text().split("\n")
    root = Node("root")
    stack = [root]
    i = 0
    while i < len(lines):
        raw = lines[i]
        if not raw.strip() or raw.lstrip().startswith("//"):
            i += 1; continue
        ind = len(raw) - len(raw.lstrip(" "))
        s = raw.strip()
        while stack[-1].indent >= ind:
            stack.pop()
        parent = stack[-1]
        if s.startswith("* ") or s == "*":
            text = s[2:] if s.startswith("* ") else ""
            n = Node("bullet", text, ind)
            n.open = False
            while True:
                m = re.search(r"\s*\{(#[\w-]+|open)\}$", n.text)
                if not m: break
                if m.group(1) == "open": n.open = True
                else: n.id = m.group(1)[1:]
                n.text = n.text[:m.start()]
            parent.children.append(n); stack.append(n)
            i += 1
        elif s.startswith(":::"):
            m = re.search(r"\{#([\w-]+)\}$", s)
            content = []
            i += 1
            while i < len(lines):
                l = lines[i]
                if l.strip() == ":::" and (len(l) - len(l.lstrip(" "))) == ind:
                    break
                content.append(l[ind:] if l[:ind].strip() == "" else l.lstrip())
                i += 1
            i += 1
            n = Node("block", content, ind)
            if m: n.id = m.group(1)
            parent.children.append(n); stack.append(n)
        elif s.startswith("@ "):
            spec = s[2:]
            if spec.endswith("<<<"):
                name = spec[:-3].strip()
                body = []
                i += 1
                while i < len(lines) and lines[i].strip() != ">>>":
                    l = lines[i]
                    body.append(l[ind:] if l[:ind].strip() == "" else l.lstrip())
                    i += 1
                i += 1
                parent.files.append((name, ("\n".join(body).rstrip("\n") + "\n").encode()))
            else:
                parent.files.append((spec.strip(), None)); i += 1
        else:
            raise SystemExit(f"line {i+1}: can't parse: {raw!r}")
    return root

OPEN = []

def assign(node, folder, ids):
    node.folder = folder
    if getattr(node, "open", False): OPEN.append(folder)
    if node.id: ids[node.id] = folder
    used = set()
    for c in node.children:
        if c.backed:
            title = c.text if c.kind == "bullet" else block_title(c)
            base = fname(title); name = base; k = 2
            while name.lower() in used:
                name = f"{base} ({k})"; k += 1
            used.add(name.lower())
            assign(c, f"{folder}/{name}" if folder else name, ids)
        elif c.id:
            raise SystemExit(f"id {c.id} on a leaf")

def block_title(b):
    for l in b.text:
        if l.strip(): return plain(l.strip().lstrip("*- ").strip())
    return "Untitled"

def link_path(rel):
    if rel == "": return "lunarbor:/"
    segs = rel.split("/")
    enc = [enc_seg(s) for s in segs]
    return "lunarbor:/" + "/".join(enc)

def enc_seg(s):
    out = []
    for ch in s:
        if ch.isspace() or ch in '%()<>[]\\#?':
            out.append("".join("%%%02X" % b for b in ch.encode()))
        else:
            out.append(ch)
    return "".join(out)

def resolve_links(text, ids):
    def rep(m):
        ref = m.group(1)
        from urllib.parse import unquote
        if ref == "/": return "(lunarbor:/)"
        if ref.startswith("/"):
            return "(" + link_path(unquote(ref[1:])) + ")"
        if "/" in ref:
            nid, f = ref.split("/", 1)
            base = ids[nid]
            f = unquote(f)
            return "(" + link_path(f"{base}/{f}" if base else f) + ")"
        if ref not in ids: raise SystemExit(f"unknown link id {ref}")
        return "(" + link_path(ids[ref]) + ")"
    return re.sub(r"\(lb:([^)\s]+)\)", rep, text)

OUTLINE = "_node.md"
ORDERED = re.compile(r"^\d{1,9}[.)](\s|$)")
THEMATIC = re.compile(r"^([-*_])( *\1){2,} *$")

def esc_bullet(t):
    """A bullet's text as SubtreeCodec.escapeBulletText writes it."""
    digits = len(t) - len(t.lstrip("0123456789"))
    if t.startswith("\\") or t[:2] in ("- ", "* ", "+ ") or t in ("-", "*", "+") or THEMATIC.match(t):
        return "\\" + t
    if ORDERED.match(t) or (1 <= digits <= 9 and t[digits:digits + 1] == "\\"):
        return t[:digits] + "\\" + t[digits:]
    return t

def child_link(folder):
    """`[↳](<folder/_node.md>)`, `%` written as `%25` (SubtreeCodec.formatChildLink)."""
    return "[↳](<" + folder.split("/")[-1].replace("%", "%25") + "/" + OUTLINE + ">)"

def write(node, ids):
    d = OUT / node.folder if node.folder else OUT
    d.mkdir(parents=True, exist_ok=True)
    out = []
    after_block = False
    for c in node.children:
        if c.kind == "bullet":
            text = esc_bullet(resolve_links(c.text, ids))
            parts = [p for p in (text, child_link(c.folder) if c.backed else "") if p]
            out.append("- " + " ".join(parts) if parts else "-")
            after_block = False
        else:
            # A block is a blockquote; consecutive blocks need a blank line.
            if after_block: out.append("")
            content = [resolve_links(l, ids) for l in c.text]
            out += [">" if l == "" else "> " + l for l in content]
            if c.backed: out.append("> " + child_link(c.folder))
            after_block = True
        if c.backed: write(c, ids)
    if node.children:
        (d / OUTLINE).write_text("\n".join(out) + "\n")
    for name, data in node.files:
        if data is None:
            src = ASSETS / name
            if not src.exists():
                print("MISSING asset", name); continue
            shutil.copy(src, d / name)
        else:
            if name.endswith(".md"):
                data = resolve_links(data.decode(), ids).encode()
            (d / name).write_bytes(data)

def layout_state(ids):
    """
    The initial tabs and windows (layout.json) as the persister keys the
    app writes for them: the tab list (darkness.layout), the window
    geometry (darkness.layoutState) and each window's place
    (lunarborPaneLocations), plus the default theme
    (darkness.theme.v2.selection). A pane's "at" is a node id, "id/file" for a
    file in that node's folder, or "/" (optionally "/file") for the root.
    """
    import json
    spec = json.loads((HERE / "layout.json").read_text())
    def file_of(at):
        if at == "/": return OUTLINE
        if at.startswith("/"): return at[1:]
        nid, _, f = at.partition("/")
        folder = ids[nid]
        return f"{folder}/{f}" if f else f"{folder}/{OUTLINE}"
    tabs, preset, order, geometry, locations = [], {}, {}, {}, {}
    for tab in spec["tabs"]:
        panes = tab["panes"]
        tabs.append({"id": tab["id"], "title": tab["title"], "floatingPanes": [
            {"id": p["id"], "x": p["x"], "y": p["y"], "w": p["w"], "h": p["h"], "z": i + 1}
            for i, p in enumerate(panes)]})
        preset[tab["id"]] = tab.get("preset", "auto")
        order[tab["id"]] = [p["id"] for p in panes]
        geometry[tab["id"]] = {p["id"]: {
            "xPct": p["x"], "yPct": p["y"], "widthPct": p["w"], "heightPct": p["h"],
            "zIndex": i + 1, "isMaximized": False, "isMinimized": False}
            for i, p in enumerate(panes)}
        for p in panes:
            locations[p["id"]] = {"file": file_of(p["at"]), "zoom": []}
    return {
        "darkness.layout": {
            "schemaVersion": 1,
            "leftSidebar": {"widthPx": 260, "visible": True, "collapsed": False},
            "rightSidebar": {"widthPx": 260, "visible": False, "collapsed": False},
            "activeTabId": spec["activeTab"],
            "tabs": tabs,
        },
        "darkness.layoutState": {"presetByTab": preset, "paneOrderByTab": order, "geometryByTab": geometry},
        "lunarborPaneLocations": locations,
        "darkness.theme.v2.selection": spec["theme"],
    }

def main():
    root = parse()
    ids = {}
    assign(root, "", ids)
    if OUT.exists(): shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    write(root, ids)
    starred = HERE / "starred.txt"
    if starred.exists():
        lines = [resolve_links(l, ids) for l in starred.read_text().splitlines() if l.strip()]
        (OUT / "Starred.md").write_text("\n".join(lines) + "\n")
    import json
    state = {"lunarborOpenFolders": {"vault": "/demo-vault", "folders": sorted(OPEN)}}
    state.update(layout_state(ids))
    (OUT.parent / "state.json").write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n")
    n = sum(1 for _ in OUT.rglob("*") if _.is_file())
    print(f"wrote {n} files, {len(ids)} ids")

main()
