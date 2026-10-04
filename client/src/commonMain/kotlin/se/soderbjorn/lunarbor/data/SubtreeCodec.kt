/*
 * SubtreeCodec.kt (commonMain)
 * ----------------------------
 * Pure codec between the editor's composed outline and the on-disk
 * `_node.md` outline files.
 *
 * ### On disk
 *
 * Every node folder holds one `_node.md` file listing only that node's
 * **direct** children, so the file has no indentation. It is plain
 * Markdown — a list, with blockquotes for blocks — so it renders in any
 * Markdown viewer, and survives being reformatted by one:
 *
 * ```
 * - Buy oat milk
 * - Recipes [↳](<Recipes/_node.md>)
 * - Trip to **Lisbon**
 * > **Packing**: passport, charger, adapter
 * ```
 *
 *  - `- text` — a leaf bullet; the text is inline Markdown. `*` and `+`
 *    are read as bullets too (other tools rewrite markers); `-` is
 *    written. Text Markdown would misread (a leading `1.`, `- `, `---` or
 *    `\`) is backslash-escaped ([escapeBulletText]).
 *  - `- title [↳](<folder/_node.md>)` — a folder-backed bullet: the title,
 *    then a **child link** to the child folder's outline. The bullet is
 *    recognised by that trailing link, never by its marker; the folder
 *    name is stored explicitly (`%` written `%25`) and resolved relative to
 *    the file's own folder. In a viewer the link opens the child node.
 *  - `>` lines — a block: one `> ` line per content line (`>` for a blank
 *    one); its content is verbatim Markdown, `* ` lists included. Two
 *    blocks in a row are separated by a blank line.
 *  - A block whose last quote line is only a child link is folder-backed:
 *    a block item with children, which live in that folder like a bullet's.
 *    Its title is the plain text of the content's first line.
 *  - Anything else is kept verbatim as a text line.
 *
 * ### In memory
 *
 * `Document` holds one flat, indented list of lines (the composed
 * outline): bullets as `<indent>* title` — folder-backed bullets too; the
 * document remembers which rows those are — and blocks as one row per
 * content line, `<indent><marker><content>`, where the marker is the
 * hidden [BlockLayout.FIRST] / [BlockLayout.NEXT] character (see
 * `BlockLayout`). The `> ` prefixes exist only on disk: the in-memory
 * marker already delimits the block.
 * [parseComposed] turns the list back into a tree so `NoteRepository.save`
 * can split it into per-folder files.
 *
 * No I/O and no state: `NoteRepository` is the only thing that touches the
 * file system. commonMain only.
 */

package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.main.BlockLayout
import se.soderbjorn.lunarbor.main.DocumentLayout

/**
 * One line (or, for blocks, one run of `>` lines) of a `_node.md` file, as
 * parsed by [SubtreeCodec.parseNodeFile].
 */
sealed class NodeLine {
    /**
     * `- title` — a leaf bullet.
     *
     * @property title Inline-Markdown title, possibly empty.
     */
    data class Leaf(val title: String) : NodeLine()

    /**
     * `- title [↳](<folder/_node.md>)` — a folder-backed bullet.
     *
     * @property title Inline-Markdown title, unescaped.
     * @property folder The backing folder's name, one path segment,
     *   relative to the folder holding this file.
     */
    data class Folder(val title: String, val folder: String) : NodeLine()

    /**
     * A block: a run of `>` lines.
     *
     * @property content The content lines (without `> `), verbatim.
     * @property folder The backing folder's name when the block has
     *   children (its last quote line is a child link), one path segment
     *   relative to the folder holding this file; `null` for a plain block.
     * @property title Plain-text title of a folder-backed block (its first
     *   content line's plain text); empty otherwise.
     */
    data class Block(
        val content: List<String>,
        val folder: String? = null,
        val title: String = "",
    ) : NodeLine()

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
     * An outline item that can have children — a bullet or a block — and
     * so be backed by a folder.
     */
    sealed class Node : ComposedItem() {
        /** Nesting column of the item. */
        abstract val indent: Int

        /** Title its folder is named after (inline Markdown allowed). */
        abstract val title: String

        /** Items nested under it, in order. */
        abstract val children: List<ComposedItem>
    }

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
        override val indent: Int,
        override val title: String,
        override val children: List<ComposedItem>,
    ) : Node()

    /**
     * A block: a run of block rows (see [BlockLayout]) — an outline item
     * whose body is free Markdown — plus any items nested under it.
     *
     * @property row Row of the block's first line.
     * @property endRow Last row of the block and its subtree.
     * @property content Content lines with the block's indent and
     *   markers removed.
     * @property indent Column of the block's marker.
     * @property children Items nested under the block, in order.
     * @property lastRow Row of the block's own last line.
     */
    data class Block(
        override val row: Int,
        override val endRow: Int,
        val content: List<String>,
        override val indent: Int = 0,
        override val children: List<ComposedItem> = emptyList(),
        val lastRow: Int = endRow,
    ) : Node() {
        /** [SubtreeCodec.blockTitleOf] the content. */
        override val title: String get() = SubtreeCodec.blockTitleOf(content)
    }

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
 *   [composeNodeLines] to turn a `_node.md` file into editor lines.
 * - `NoteRepository.save` uses [parseComposed] to split the editor's lines
 *   into folders, and [formatNodeFile] to write each folder's file.
 * - `Document` uses [composedSubtreeEnd] so collapsing a folder-backed
 *   bullet removes exactly the rows save would attribute to it.
 * - `PaneBackingViewModel`, `NoteRepository` (Starred) and the Starred modal use
 *   [titleOf], [parseAnyLinkBullet], [formatPlainLinkBullet],
 *   [escapeLabel] and [formatLinkUrlForLabel].
 */
object SubtreeCodec {

    /** Shortest block fence. */
    const val MIN_FENCE: String = ":::"

    // ------------------------------------------------------------- node file

    /**
     * Link text of the child link that marks a folder-backed item:
     * `[↳](<Folder/_node.md>)`. Only written; the reader accepts any text.
     */
    const val CHILD_LINK_LABEL: String = "↳"

    /**
     * Parses the text of one `_node.md` file (see the file header for the
     * format). Blank lines only separate blocks; a run of `>` lines is one
     * block; `-`, `*` and `+` all start a bullet. Lines of any other shape
     * are kept verbatim as [NodeLine.Text], so hand edits survive.
     */
    fun parseNodeFile(text: String): List<NodeLine> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split("\n").map { it.removeSuffix("\r") }
        val out = ArrayList<NodeLine>(raw.size)
        var i = 0
        while (i < raw.size) {
            val line = raw[i]
            if (isQuoteLine(line)) {
                val content = ArrayList<String>()
                while (i < raw.size && isQuoteLine(raw[i])) content += unquote(raw[i++])
                out += blockOf(content)
                continue
            }
            val bullet = bulletTextOf(line)
            when {
                bullet != null -> out += bulletOf(bullet)
                line.isBlank() -> {}
                else -> out += NodeLine.Text(line)
            }
            i++
        }
        return out
    }

    /** `true` when [line] belongs to a block: a `>` after at most 3 spaces. */
    private fun isQuoteLine(line: String): Boolean {
        val lead = line.indexOfFirst { it != ' ' }
        return lead in 0..3 && line[lead] == '>'
    }

    /** A block content line: the quote line without its `>` and one space. */
    private fun unquote(line: String): String {
        val rest = line.substring(line.indexOf('>') + 1)
        return if (rest.startsWith(" ")) rest.substring(1) else rest
    }

    /**
     * The block a run of quote lines holds. A last line that is only a
     * child link makes it folder-backed; a last line escaped as `\[` (a
     * plain block whose own last line looks like a child link) is
     * unescaped.
     */
    private fun blockOf(lines: List<String>): NodeLine.Block {
        val last = lines.lastOrNull()?.trim()
        if (last != null) {
            val link = parseTrailingChildLink(last)
            if (link != null && link.start == 0) {
                val content = lines.dropLast(1)
                return NodeLine.Block(content, link.folder, FolderName.nameTextOf(blockTitleOf(content)))
            }
            if (last.startsWith("\\[") && parseTrailingChildLink(last.substring(1))?.start == 0) {
                return NodeLine.Block(lines.dropLast(1) + lines.last().replaceFirst("\\[", "["))
            }
        }
        return NodeLine.Block(lines)
    }

    /**
     * The text after a bullet marker (`-`, `*` or `+`, then a space or the
     * end of the line), or `null` when [line] is not a bullet.
     */
    private fun bulletTextOf(line: String): String? {
        if (line.isEmpty() || line[0] !in "-*+") return null
        if (line.length == 1) return ""
        if (line[1] != ' ') return null
        return line.substring(2)
    }

    /**
     * A bullet's text, split into a leaf or a folder bullet and unescaped.
     * The text is kept exactly, trailing spaces included; only the one
     * space [formatBullet] puts before a child link is dropped.
     */
    private fun bulletOf(text: String): NodeLine {
        val link = parseTrailingChildLink(text.trimEnd())
        if (link != null) return NodeLine.Folder(unescapeBulletText(text.substring(0, link.start).removeSuffix(" ")), link.folder)
        return NodeLine.Leaf(unescapeBulletText(text))
    }

    /**
     * A child link at the end of a line: `[text](<Folder/_node.md>)` (or the
     * same with a bare destination), whose target is the outline file of a
     * direct child folder.
     *
     * @property start Index of the link's `[` in the line.
     * @property folder The child folder's name, percent-decoded.
     */
    private data class ChildLink(val start: Int, val folder: String)

    /**
     * Finds a [ChildLink] that ends [line]. The link must start the line or
     * follow a space, and its text may not hold brackets; a `\[` (escaped)
     * link is not one.
     */
    private fun parseTrailingChildLink(line: String): ChildLink? {
        if (!line.endsWith(")")) return null
        val dest: String
        val close: Int // index of the `]` before `(`
        if (line.endsWith(">)")) {
            val open = line.lastIndexOf("](<")
            if (open < 0) return null
            dest = line.substring(open + 3, line.length - 2)
            if ('<' in dest || '>' in dest) return null
            close = open
        } else {
            val open = line.lastIndexOf("](")
            if (open < 0) return null
            dest = line.substring(open + 2, line.length - 1)
            if (dest.any { it == ' ' || it == '(' || it == ')' }) return null
            close = open
        }
        val start = line.lastIndexOf('[', close)
        if (start < 0 || line.substring(start + 1, close).any { it == '[' || it == ']' }) return null
        if (start > 0 && line[start - 1] != ' ') return null
        val suffix = "/" + NoteRepository.OUTLINE_FILE_NAME
        if (!dest.endsWith(suffix)) return null
        val folder = percentDecode(dest.removeSuffix(suffix)) ?: return null
        if (folder.isEmpty() || '/' in folder || folder == "." || folder == "..") return null
        return ChildLink(start, folder)
    }

    /**
     * The child link for [folder]: `[↳](<folder/_node.md>)`. `%` is the only
     * character encoded (as `%25`), so a percent-encoded folder name
     * resolves to that folder in other Markdown viewers too.
     */
    fun formatChildLink(folder: String): String =
        "[$CHILD_LINK_LABEL](<${folder.replace("%", "%25")}/${NoteRepository.OUTLINE_FILE_NAME}>)"

    /**
     * Decodes `%XX` escapes (UTF-8) in a link destination; other tools may
     * write `%20` and friends. `null` when an escape is malformed.
     */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes += v.toByte()
                i += 3
            } else {
                // A run of escapes is one UTF-8 sequence; everything else is
                // copied as is (emoji are two chars, never split).
                if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() }
                out.append(s[i++])
            }
        }
        if (bytes.isNotEmpty()) out.append(bytes.toByteArray().decodeToString())
        return out.toString()
    }

    /** `1.` / `1)` starting a line: an ordered-list marker in Markdown. */
    private val ORDERED_MARKER = Regex("""^\d{1,9}[.)](\s|$)""")

    /** `1\.` / `1\)` / `1\\`: an escaped ordered-list marker, as written. */
    private val ESCAPED_ORDERED = Regex("""^\d{1,9}\\[.)\\]""")

    /** A thematic break (`---`, `* * *`, `___`), which would draw a rule. */
    private val THEMATIC_BREAK = Regex("""^([-*_])( *\1){2,} *$""")

    /**
     * Escapes a bullet's text for the file, so Markdown reads it as the text
     * it is: a leading backslash, bullet marker or thematic break gets a
     * `\` in front; an ordered-list marker gets one before its `.` or `)`;
     * a trailing link that looks like a child link gets one before its `[`.
     * Leading `#` and `>` stay — headings and quotes are Lunarbor line
     * styles and mean the same in Markdown. [unescapeBulletText] undoes it.
     */
    fun escapeBulletText(text: String): String {
        var t = text
        val link = parseTrailingChildLink(t.trimEnd())
        if (link != null) t = t.substring(0, link.start) + "\\" + t.substring(link.start)
        val digits = t.takeWhile { it.isDigit() }.length
        return when {
            t.startsWith("\\") || bulletTextOf(t) != null || THEMATIC_BREAK.matches(t) -> "\\" + t
            ORDERED_MARKER.containsMatchIn(t) || (digits in 1..9 && t.getOrNull(digits) == '\\') ->
                t.substring(0, digits) + "\\" + t.substring(digits)
            else -> t
        }
    }

    /** Inverse of [escapeBulletText]. */
    fun unescapeBulletText(text: String): String {
        var t = text
        when {
            t.length >= 2 && t[0] == '\\' && (t[1] == '\\' || bulletTextOf(t.substring(1)) != null ||
                THEMATIC_BREAK.matches(t.substring(1))) -> t = t.substring(1)
            ESCAPED_ORDERED.containsMatchIn(t) -> {
                val digits = t.takeWhile { it.isDigit() }.length
                t = t.substring(0, digits) + t.substring(digits + 1)
            }
        }
        val escapedLink = t.lastIndexOf("\\[")
        if (escapedLink >= 0) {
            val rest = t.substring(0, escapedLink) + t.substring(escapedLink + 1)
            if (parseTrailingChildLink(rest.trimEnd())?.start == escapedLink) t = rest
        }
        return t
    }

    /**
     * Renders [items] as the text of a `_node.md` file, one line per item
     * (blocks span several), with a trailing newline. Empty [items] render
     * as the empty string. Blank lines appear only where Markdown needs
     * them: between two blocks, and after a block followed by a text line
     * (which would otherwise continue the quote).
     */
    fun formatNodeFile(items: List<NodeLine>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        var afterBlock = false
        for (item in items) {
            if (afterBlock && (item is NodeLine.Block || item is NodeLine.Text)) sb.append('\n')
            when (item) {
                is NodeLine.Leaf -> sb.append(formatBullet(item.title, null)).append('\n')
                is NodeLine.Folder -> sb.append(formatBullet(item.title, item.folder)).append('\n')
                is NodeLine.Block -> {
                    val content = item.content
                    for ((n, c) in content.withIndex()) {
                        // A plain block's last line that reads as a child
                        // link is escaped, so it stays content.
                        val line = if (item.folder == null && n == content.lastIndex &&
                            parseTrailingChildLink(c.trim())?.start == 0
                        ) c.replaceFirst("[", "\\[") else c
                        sb.append(if (line.isEmpty()) ">" else "> $line").append('\n')
                    }
                    if (item.folder != null) sb.append("> ").append(formatChildLink(item.folder)).append('\n')
                    if (content.isEmpty() && item.folder == null) sb.append(">\n")
                }
                is NodeLine.Text -> sb.append(item.raw).append('\n')
            }
            afterBlock = item is NodeLine.Block
        }
        return sb.toString()
    }

    /**
     * One bullet line: `- text`, plus ` [↳](<folder/_node.md>)` when the
     * bullet has a [folder]. An empty leaf is `-`.
     */
    fun formatBullet(title: String, folder: String?): String {
        val text = escapeBulletText(title)
        val link = folder?.let(::formatChildLink)
        return when {
            link == null -> if (text.isEmpty()) "-" else "- $text"
            text.isEmpty() -> "- $link"
            else -> "- $text $link"
        }
    }

    /**
     * The `:::` fence for a block holding [content] in the MCP agent format
     * (`mcp/AgentOutline`) — node files no longer use fences. [MIN_FENCE], lengthened to
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
     *   `+` bullet and every folder-backed block (keyed by its first row).
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
                    // Fenced code inside the block becomes code rows.
                    val content = BlockLayout.rowContentsOf(item.content).ifEmpty { listOf("") }
                    if (item.folder != null) folders[out.size] = item.folder
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
     * preceding bullet or block with a smaller indent. A run of block rows
     * (see [BlockLayout.rangeAt]) is one block; its content is opaque (a
     * `* ` inside a block is not a bullet) and loses the block's indent and
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
                // Code rows go back to fenced code on disk.
                val content = BlockLayout.diskContentOf(block.map { BlockLayout.contentOf(lines[it]) })
                stack.addLast(Frame(indent = indent, row = i, title = "", content = content, ownEnd = block.last))
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

    /**
     * Mutable build frame for one open item in [parseComposed]: a bullet,
     * or a block when [content] is set.
     *
     * @property ownEnd Last row of the item's own text (a block's last row).
     */
    private class Frame(
        val indent: Int,
        val row: Int,
        val title: String,
        val content: List<String>? = null,
        val ownEnd: Int = row,
    ) {
        val children = ArrayList<ComposedItem>()
    }

    /**
     * Pops the top frame of [stack] and appends it, as a finished
     * [ComposedItem.Bullet] or [ComposedItem.Block], to its parent. The
     * item ends at the last row it or any of its children own.
     */
    private fun closeFrame(stack: ArrayDeque<Frame>) {
        val f = stack.removeLast()
        val end = maxOf(f.ownEnd, f.children.maxOfOrNull { it.endRow } ?: f.row)
        stack.last().children += if (f.content != null) {
            ComposedItem.Block(f.row, end, f.content, f.indent, f.children.toList(), f.ownEnd)
        } else {
            ComposedItem.Bullet(f.row, end, f.indent, f.title, f.children.toList())
        }
    }

    /**
     * Title of a block item: its first non-blank content line, without a
     * list-item prefix (`* `, `- `). Headings and inline Markdown stay;
     * [FolderName] strips them when naming the folder. Empty for a blank
     * block.
     */
    fun blockTitleOf(content: List<String>): String {
        // A code fence is not a title; the code's first line is.
        val first = content.firstOrNull { it.isNotBlank() && BlockLayout.codeFenceOf(it) == null }?.trim() ?: return ""
        return if (first.startsWith("* ") || first.startsWith("- ")) first.substring(2).trim() else first
    }

    /**
     * Last row owned by the item (bullet or block) at [row] — its own
     * rows, children, nested blocks and text included — using the same
     * ownership rules as [parseComposed]. Returns [row] when [row] starts
     * no item.
     */
    fun composedSubtreeEnd(lines: List<String>, row: Int): Int {
        fun find(items: List<ComposedItem>): ComposedItem.Node? {
            for (item in items) {
                if (item !is ComposedItem.Node) continue
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

    /**
     * Title of the outline item starting at [row] of composed [lines]:
     * a bullet's [titleOf], or a block's [blockTitleOf] its rows. Empty
     * for any other row. Used where a bullet and a block item are both
     * named by their title (zoom history title paths, breadcrumbs).
     */
    fun itemTitleOf(lines: List<String>, row: Int): String {
        val line = lines.getOrNull(row) ?: return ""
        if (DocumentLayout.bulletAsteriskColumn(line) >= 0) return titleOf(line)
        if (!BlockLayout.startsBlock(lines, row)) return ""
        val range = BlockLayout.rangeAt(lines, row) ?: return ""
        return blockTitleOf(range.map { BlockLayout.textOf(lines[it]) })
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
