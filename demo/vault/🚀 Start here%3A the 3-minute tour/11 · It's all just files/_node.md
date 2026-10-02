> The demo runs in your browser's memory, but the real app keeps everything in an ordinary folder of Markdown files:
>
> ```
> vault/
>   _node.md               ← this page's bullets
>   Retro Lab/
>     _node.md
>     Amiga 500 "Denise"/
>       _node.md
>       boing-ball.svg
>       Amiga chipset.excalidraw
> ```
>
> Each `_node.md` is plain Markdown: open it on GitHub or in any Markdown app and the bullets are a list, the blocks are quotes, and a ↳ link opens the node underneath. Sync it, grep it, back it up, point a coding agent at it — the desktop app even has a built-in **MCP server** so Claude can read and edit your tree.
