/*
 * SubtreeCodec.kt (commonMain)
 * ----------------------------
 * Pure codec between the editor's composed outline and the on-disk
 * `.treefacts` outline files.
 *
 * ### On disk
 *
 * Every node folder holds one hidden `.treefacts` file listing only that
 * node's **direct** children, so the file has no indentation:
 *
 * ```
 * * Buy oat milk
 * + [Recipes](Recipes)
 * * Trip to **Lisbon**
 * :::
 * **Packing**: passport, charger, adapter
 * :::
 * ```
 *
 *  - `* text` — a leaf bullet; the text is inline Markdown.
 *  - `+ [title](folder)` — a folder-backed bullet. The title keeps its
 *    formatting (`[`, `]` and `\` backslash-escaped); the folder name is
 *    stored explicitly and resolved relative to the file's own folder.
 *  - `:::` … `:::` — a block. A block whose content contains a colon-only
 *    line gets a longer fence (`::::`), so the content never closes it.
 *  - Anything else is kept verbatim as a text line.
 *
 * ### In memory
 *
 * `Document` holds one flat, indented list of lines (the composed
 * outline): bullets as `<indent>* title` — folder-backed bullets too; the
 * document remembers which rows those are — and blocks as one row per
 * content line, `<indent><marker><content>`, where the marker is the
 * hidden [BlockLayout.FIRST] / [BlockLayout.NEXT] character (see
 * `BlockLayout`). The fences exist only on disk: the in-memory marker
 * already delimits the block, so content holding a `:::` line needs no
 * special care until [formatNodeFile] picks a longer fence for it.
 * [parseComposed] turns the list back into a tree so `NoteRepository.save`
 * can split it into per-folder files.
 *
 * No I/O and no state: `NoteRepository` is the only thing that touches the
 * file system. commonMain only.
 */

package se.soderbjorn.treefacts.data

import se.soderbjorn.treefacts.main.BlockLayout
import se.soderbjorn.treefacts.main.DocumentLayout

/**
 * One line (or, for blocks, one fenced run of lines) of a `.treefacts`
 * file, as parsed by [SubtreeCodec.parseNodeFile].
 */
sealed class NodeLine {
    /**
     * `* title` — a leaf bullet.
     *
     * @property title Inline-Markdown title, possibly empty.
     */
    data class Leaf(val title: String) : NodeLine()

    /**
     * `+ [title](folder)` — a folder-backed bullet.
     *
     * @property title Inline-Markdown title, unescaped.
     * @property folder The backing folder's name, one path segment,
     *   relative to the folder holding this file.
     */
    data class Folder(val title: String, val folder: String) : NodeLine()

    /**
     * A `:::`-fenced block.
     *
     * @property content The lines between the fences, verbatim.
     */
    data class Block(val content: List<String>) : NodeLine()

    /**
     * Any other line, kept verbatim so hand edits survive a round trip.
     *
     * @property raw The line exactly as it appeared in the file.
     */
    data class Text(val raw: String) : NodeLine()
}

/**
 * One node of the tree [SubtreeCodec.parseComposed] builds from the
 * composed outline.
 */
sealed class ComposedItem {
    /** First row of the item in the composed outline. */
    abstract val row: Int

    /** Last row of the item and everything nested in it (inclusive). */
    abstract val endRow: Int

    /**
     * A bullet row.
     *
     * @property row Row of the bullet line.
     * @property endRow Last row of its subtree.
     * @property indent Column of the `*` marker.
     * @property title Text after `* `.
     * @property children Items nested under it, in order.
     */
    data class Bullet(
        override val row: Int,
        override val endRow: Int,
        val indent: Int,
        val title: String,
        val children: List<ComposedItem>,
    ) : ComposedItem()

    /**
     * A block: a run of block rows (see [BlockLayout]).
     *
     * @property row Row of the block's first line.
     * @property endRow Row of its last line.
     * @property content Content lines with the block's indent and
     *   markers removed.
     */
    data class Block(
        override val row: Int,
        override val endRow: Int,
        val content: List<String>,
    ) : ComposedItem()

    /**
     * Any other non-blank line.
     *
     * @property row Row of the line.
     * @property text The line with its indentation removed.
     */
    data class Text(override val row: Int, val text: String) : ComposedItem() {
        override val endRow: Int get() = row
    }
}

/**
 * Pure helpers for the outline storage format.
 *
 * ### Callers
 * - `NoteRepository.loadFile` / `loadSubtree` use [parseNodeFile] +
 *   [composeNodeLines] to turn a `.treefacts` file into editor lines.
 * - `NoteRepository.save` uses [parseComposed] to split the editor's lines
 *   into folders, and [formatNodeFile] to write each folder's file.
 * - `Document` uses [composedSubtreeEnd] so collapsing a folder-backed
 *   bullet removes exactly the rows save would attribute to it.
 * - `PaneBackingViewModel`, `VaultIndex` and the Starred modal use
 *   [titleOf], [parseAnyLinkBullet], [formatPlainLinkBullet],
 *   [escapeLabel] and [formatLinkUrlForLabel].
 */
object SubtreeCodec {

    /** Shortest block fence. */
    const val MIN_FENCE: String = ":::"

    // ------------------------------------------------------------- node file

    /**
     * Parses the text of one `.treefacts` file. Blank lines are dropped —
     * they carry no meaning in the outline — except inside blocks, whose
     * content is kept verbatim. An opening fence with no matching closing
     * fence is kept as a text line so nothing is lost.
     */
    fun parseNodeFile(text: String): List<NodeLine> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split("\n").map { it.removeSuffix("\r") }
        val out = ArrayList<NodeLine>(raw.size)
        var i = 0
        while (i < raw.size) {
            val line = raw[i]
            if (isFence(line)) {
                val fence = line.trim()
                val close = (i + 1 until raw.size).firstOrNull { raw[it].trim() == fence }
                if (close != null) {
                    out += NodeLine.Block(raw.subList(i + 1, close).toList())
                    i = close + 1
                    continue
                }
            }
            when {
                line.isBlank() -> {}
                line == "*" -> out += NodeLine.Leaf("")
                line.startsWith("* ") -> out += NodeLine.Leaf(line.substring(2))
                line.startsWith("+ ") -> out += parseFolderLine(line) ?: NodeLine.Text(line)
                else -> out += NodeLine.Text(line)
            }
            i++
        }
        return out
    }

    /**
     * Parses `+ [title](folder)`. The title may contain backslash-escaped
     * `[`, `]` and `\`; the folder is everything between `](` and the
     * line's final `)`, so folder names containing parentheses work.
     *
     * @return `null` when [line] does not have that shape.
     */
    private fun parseFolderLine(line: String): NodeLine.Folder? {
        val s = line.trimEnd()
        if (!s.startsWith("+ [")) return null
        val title = StringBuilder()
        var i = 3
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length && s[i + 1] in "[]\\") {
                title.append(s[i + 1])
                i += 2
                continue
            }
            if (ch == ']') break
            title.append(ch)
            i++
        }
        if (i + 1 >= s.length || s[i] != ']' || s[i + 1] != '(') return null
        if (!s.endsWith(")")) return null
        val folder = s.substring(i + 2, s.length - 1)
        if (folder.isEmpty() || '/' in folder || folder == "." || folder == "..") return null
        return NodeLine.Folder(title.toString(), folder)
    }

    /**
     * Renders [items] as the text of a `.treefacts` file, one line per
     * item (blocks span several), with a trailing newline. Empty [items]
     * render as the empty string.
     */
    fun formatNodeFile(items: List<NodeLine>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        for (item in items) {
            when (item) {
                is NodeLine.Leaf -> sb.append("* ").append(item.title).append('\n')
                is NodeLine.Folder -> sb.append(formatFolderLine(item.title, item.folder)).append('\n')
                is NodeLine.Block -> {
                    val fence = fenceFor(item.content)
                    sb.append(fence).append('\n')
                    for (c in item.content) sb.append(c).append('\n')
                    sb.append(fence).append('\n')
                }
                is NodeLine.Text -> sb.append(item.raw).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Renders one folder-backed bullet line, `+ [title](folder)`, escaping
     * `[`, `]` and `\` in the title.
     */
    fun formatFolderLine(title: String, folder: String): String {
        val esc = StringBuilder(title.length)
        for (ch in title) {
            if (ch == '[' || ch == ']' || ch == '\\') esc.append('\\')
            esc.append(ch)
        }
        return "+ [$esc]($folder)"
    }

    /**
     * The fence for a block holding [content]: [MIN_FENCE], lengthened to
     * one colon more than the longest colon-only line in the content so
     * that line can never close the block early.
     */
    fun fenceFor(content: List<String>): String {
        var longest = 0
        for (line in content) {
            val t = line.trim()
            if (t.isNotEmpty() && t.all { it == ':' }) longest = maxOf(longest, t.length)
        }
        return ":".repeat(maxOf(MIN_FENCE.length, longest + 1))
    }

    /** `true` when [line], ignoring surrounding whitespace, is 3+ colons only. */
    fun isFence(line: String): Boolean {
        val t = line.trim()
        return t.length >= MIN_FENCE.length && t.all { it == ':' }
    }

    /**
     * Result of [composeNodeLines].
     *
     * @property lines Editor lines, each prefixed with the requested indent.
     * @property folderByRow Row (in [lines]) → folder name, for every
     *   `+` bullet.
     */
    data class Composed(val lines: List<String>, val folderByRow: Map<Int, String>)

    /**
     * Turns parsed [items] into composed editor lines at [indent]:
     * bullets (leaf and folder alike) become `<indent>* title`, blocks
     * become one block row per content line (an empty block one empty
     * row — see [BlockLayout]), text lines are kept.
     *
     * Every block row, blank content included, carries [indent] spaces
     * before its marker so it still counts as nested under the parent
     * bullet when [composedSubtreeEnd] measures it.
     */
    fun composeNodeLines(items: List<NodeLine>, indent: Int): Composed {
        val pad = " ".repeat(indent)
        val out = ArrayList<String>(items.size)
        val folders = HashMap<Int, String>()
        for (item in items) {
            when (item) {
                is NodeLine.Leaf -> out += "$pad* ${item.title}"
                is NodeLine.Folder -> {
                    folders[out.size] = item.folder
                    out += "$pad* ${item.title}"
                }
                is NodeLine.Block -> {
                    val content = item.content.ifEmpty { listOf("") }
                    out += BlockLayout.firstLine(indent, content.first())
                    for (c in content.drop(1)) out += BlockLayout.nextLine(indent, c)
                }
                is NodeLine.Text -> out += pad + item.raw
            }
        }
        return Composed(out, folders)
    }

    // -------------------------------------------------------- composed tree

    /**
     * Builds the item tree of a composed outline.
     *
     * Ownership follows indentation: an item belongs to the nearest
     * preceding bullet with a smaller indent. A run of block rows (see
     * [BlockLayout.rangeAt]) is one block; its content is opaque (a `* `
     * inside a block is not a bullet) and loses the block's indent and
     * markers. Blank lines outside blocks belong to nobody and are
     * dropped.
     *
     * @return The top-level items, in order.
     */
    fun parseComposed(lines: List<String>): List<ComposedItem> {
        val root = Frame(indent = -1, row = -1, title = "")
        val stack = ArrayDeque<Frame>()
        stack.addLast(root)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val indent = line.indexOfFirst { it != ' ' }
            val block = BlockLayout.rangeAt(lines, i)
            if (block != null) {
                while (stack.last().indent >= indent) closeFrame(stack)
                val content = block.map { BlockLayout.contentOf(lines[it]) }
                stack.last().children += ComposedItem.Block(i, block.last, content)
                i = block.last + 1
                continue
            }
            val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
            while (stack.last().indent >= indent) closeFrame(stack)
            if (bulletCol >= 0) {
                stack.addLast(Frame(indent = bulletCol, row = i, title = line.substring(bulletCol + 2)))
            } else {
                stack.last().children += ComposedItem.Text(i, line.substring(indent))
            }
            i++
        }
        while (stack.size > 1) closeFrame(stack)
        return root.children
    }

    /** Mutable build frame for one open bullet in [parseComposed]. */
    private class Frame(val indent: Int, val row: Int, val title: String) {
        val children = ArrayList<ComposedItem>()
    }

    /**
     * Pops the top frame of [stack] and appends it, as a finished
     * [ComposedItem.Bullet], to its parent. The bullet ends at the last
     * row any of its children own.
     */
    private fun closeFrame(stack: ArrayDeque<Frame>) {
        val f = stack.removeLast()
        val end = f.children.maxOfOrNull { it.endRow } ?: f.row
        stack.last().children += ComposedItem.Bullet(f.row, end, f.indent, f.title, f.children.toList())
    }

    /**
     * Last row owned by the bullet at [row] — its children, nested blocks
     * and text included — using the same ownership rules as
     * [parseComposed]. Returns [row] when the bullet owns nothing, or
     * when [row] is not a bullet.
     */
    fun composedSubtreeEnd(lines: List<String>, row: Int): Int {
        fun find(items: List<ComposedItem>): ComposedItem.Bullet? {
            for (item in items) {
                if (item !is ComposedItem.Bullet) continue
                if (item.row == row) return item
                if (row in item.row..item.endRow) return find(item.children)
            }
            return null
        }
        return find(parseComposed(lines))?.endRow ?: row
    }

    /**
     * `true` when [items] hold real content: anything left after
     * [trimTrailingEmpty].
     */
    fun hasContent(items: List<ComposedItem>): Boolean = trimTrailingEmpty(items).isNotEmpty()

    /**
     * Drops trailing empty leaf bullets (a `* ` with no text and no
     * content of its own) from [items]. The editor freely creates such
     * placeholders — Enter at the end of a list, zooming into a leaf — and
     * they must neither reach disk nor make their parent folder-backed.
     */
    fun trimTrailingEmpty(items: List<ComposedItem>): List<ComposedItem> {
        var end = items.size
        while (end > 0) {
            val last = items[end - 1]
            val empty = last is ComposedItem.Bullet && last.title.isBlank() && !hasContent(last.children)
            if (!empty) break
            end--
        }
        return if (end == items.size) items else items.subList(0, end)
    }

    // -------------------------------------------------------------- titles

    /**
     * The title text of a bullet line: everything after the leading
     * indent and `* ` marker. Empty for non-bullet lines and bullets with
     * no text.
     */
    fun titleOf(line: String): String {
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return ""
        val titleStart = indent + 2
        if (titleStart >= line.length) return ""
        return line.substring(titleStart)
    }

    // --------------------------------------------------- inline link bullets

    /**
     * A `* [Label](url)` bullet's parts, returned by [parseAnyLinkBullet].
     * Used by the Starred bookmarks file, whose entries are plain
     * Markdown-link bullets.
     *
     * @property indent Leading-space count of the line.
     * @property bulletText The bullet's display form (`<indent>* <label>`).
     * @property url The link's URL, including any `#…` fragment.
     */
    data class LinkBullet(
        val indent: Int,
        val bulletText: String,
        val url: String,
    )

    /**
     * Parses a bullet whose whole content is an inline Markdown link,
     * `<indent>* [Label](url)` (URL optionally wrapped in `<…>`).
     *
     * @return The parts, or `null` when [line] is not such a bullet or
     *   the URL is empty.
     */
    fun parseAnyLinkBullet(line: String): LinkBullet? {
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return null
        val afterMarker = indent + 2
        val trimmedRight = line.trimEnd()
        if (trimmedRight.length <= afterMarker) return null
        if (trimmedRight[afterMarker] != '[') return null
        var i = afterMarker + 1
        val labelBuilder = StringBuilder()
        while (i < trimmedRight.length) {
            val ch = trimmedRight[i]
            if (ch == '\\' && i + 1 < trimmedRight.length) {
                val next = trimmedRight[i + 1]
                if (next == '[' || next == ']' || next == '(' || next == ')' || next == '\\') {
                    labelBuilder.append(next)
                    i += 2
                    continue
                }
            }
            if (ch == ']') break
            labelBuilder.append(ch)
            i++
        }
        if (i >= trimmedRight.length || trimmedRight[i] != ']') return null
        if (i + 1 >= trimmedRight.length || trimmedRight[i + 1] != '(') return null
        val urlStart = i + 2
        if (!trimmedRight.endsWith(")")) return null
        val urlEnd = trimmedRight.length - 1
        if (urlEnd <= urlStart) return null
        var url = trimmedRight.substring(urlStart, urlEnd)
        if (url.startsWith("<") && url.endsWith(">")) url = url.substring(1, url.length - 1)
        if (url.isBlank()) return null
        val bulletText = " ".repeat(indent) + "* " + labelBuilder
        return LinkBullet(indent = indent, bulletText = bulletText, url = url)
    }

    /**
     * Renders a plain Markdown-link bullet, `<indent>* [label](href)`.
     * Used for Starred bookmarks.
     *
     * @param indent Leading-space count for the rendered line.
     * @param label Display label; backslash-escaped via [escapeLabel].
     * @param href URL, verbatim; wrapped in `<…>` via
     *   [formatLinkUrlForLabel] when it contains spaces or parentheses.
     */
    fun formatPlainLinkBullet(indent: Int, label: String, href: String): String =
        " ".repeat(indent) + "* [" + escapeLabel(label) + "](" + formatLinkUrlForLabel(href) + ")"

    /**
     * Backslash-escapes the Markdown link-label specials (`\`, `[`, `]`,
     * `(`, `)`) in [label]. Used for inline links and images the editor
     * inserts into titles (`PaneBackingViewModel.insertMarkdownLink`, …).
     */
    fun escapeLabel(label: String): String {
        val sb = StringBuilder(label.length)
        for (ch in label) {
            if (ch == '\\' || ch == '[' || ch == ']' || ch == '(' || ch == ')') sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Wraps an inline link's [url] in `<…>` when it contains a space,
     * parenthesis or angle bracket, as inline Markdown requires; returns
     * it unchanged otherwise. Only for links *inside titles* — folder
     * names in `+` lines are stored bare.
     */
    fun formatLinkUrlForLabel(url: String): String {
        val needsAngle = url.any { it == ' ' || it == '(' || it == ')' || it == '<' || it == '>' }
        return if (needsAngle) "<$url>" else url
    }
}
