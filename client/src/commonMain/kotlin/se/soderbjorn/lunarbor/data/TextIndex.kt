/*
 * TextIndex.kt (commonMain)
 * -------------------------
 * App-scoped full-text index behind the pane search: every line of every
 * note file in the vault (`_node.md` outlines and `.md` notes), as
 * the reader sees it, so a search is an in-memory scan instead of a walk
 * over the disk.
 *
 * Built by one scan of the vault the first time a search runs
 * ([ensureBuilt]), then kept current like the link index: the repository
 * reports every note text it reads or writes (`NoteRepository.noteTextObserver`,
 * fanned out by `DocumentRegistry` to this index and `VaultIndex`), and
 * folder moves carry the keys along ([moveKeys]). Autosave writes an edit
 * within a few seconds, so the index trails typing by at most that much.
 *
 * An outline file only holds its node's direct children (each child
 * node's children are in that child's folder), so indexing each file on
 * its own covers every bullet and block of the vault exactly once.
 *
 * Each line also keeps the `#tags` it holds (as `InlineMarkdownTokenizer`
 * recognises them; code rows have none), so [tags] can list a tree's tags
 * with their counts for the search field's autocomplete, and each outline
 * file records the tags of its folder-backed items, so a search can count a
 * parent's tags for every line under it (`SearchQuery`: a tag on a parent
 * counts for its children). A search node's `{{search: …}}` is not indexed
 * ([SearchNode.stripQuery]).
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.soderbjorn.lunarbor.main.BlockLayout
import se.soderbjorn.lunarbor.main.DocumentLayout
import se.soderbjorn.lunarbor.platform.toNfc

/**
 * One search result: a line of a note file.
 *
 * @property fileRel Vault-relative path of the `_node.md` outline or
 *   `.md` note holding the line.
 * @property itemIndex In an outline, the index of the line's item among
 *   the file's items (bullets and blocks, in order); in a note, the line
 *   number. Item indices survive children being spliced in, unlike rows.
 * @property rowOffset Row of the line within its item: `0` for a bullet,
 *   the content row for a line of a block; always `0` in a note.
 * @property text The line as the reader sees it (inline Markdown
 *   collapsed, line-level prefix dropped).
 */
data class TextHit(
    val fileRel: String,
    val itemIndex: Int,
    val rowOffset: Int,
    val text: String,
)

/**
 * What a search covers: a node's whole tree, or one note.
 */
sealed class TextScope {
    /** The folder [folderRel] (`""`: the vault) and everything under it. */
    data class Tree(val folderRel: String) : TextScope()

    /** Only the note file [fileRel]. */
    data class File(val fileRel: String) : TextScope()
}

/**
 * One tag of a tree, for the search field's autocomplete ([TextIndex.tags]).
 *
 * @property tag The tag with its `#`, spelled as it first appears.
 * @property count How many lines hold it.
 */
data class TagCount(val tag: String, val count: Int)

/**
 * Result of [TextIndex.search].
 *
 * @property hits The first matching lines, shallowest files first, in
 *   file order within a file; at most the requested maximum.
 * @property total How many lines match in all.
 */
data class TextSearchResult(val hits: List<TextHit>, val total: Int)

/**
 * The full-text index.
 *
 * ### Callers
 * - Created and fed by `DocumentRegistry` ([noteText] from the repository's
 *   observer, [moveKeys] after saves that moved folders).
 * - `DocumentRegistry.searchText`, for `PaneBackingViewModel.setSearchQuery`.
 *
 * @param listFiles Every note file in the vault (vault-relative); production
 *   passes `NoteRepository.listLinkBearingFiles`.
 * @param readText Reads one note file's raw text (`null` when missing);
 *   production passes `NoteRepository.readNoteText`, which also reports the
 *   text back through [noteText].
 */
class TextIndex(
    private val listFiles: suspend () -> List<String>,
    private val readText: suspend (String) -> String?,
) {
    /**
     * One indexed line: [TextHit] fields plus the normalized text matched
     * against and the tags it holds (each with its `#`, as written).
     */
    private class Line(
        val key: String,
        val itemIndex: Int,
        val rowOffset: Int,
        val text: String,
        val tags: List<String>,
        /**
         * Normalized tags (no `#`) of this line. (A block's rows each keep
         * their own — a tag in one row does not make every row a hit — but
         * the items under a block inherit all of them: [Entry.owned].)
         */
        val lineTags: Set<String>,
        /** The folder (name, relative to the file's) of the line's folder-backed item, or `null`. */
        val ownedFolder: String?,
    )

    /**
     * One indexed file: its lines, and the normalized tags of each of its
     * folder-backed items by folder name — what the files in that folder
     * inherit.
     */
    private class Entry(val lines: List<Line>, val owned: Map<String, Set<String>>)

    private val linesByFile: MutableMap<String, Entry> = HashMap()
    private val buildMutex = Mutex()
    private var built = false

    /** `true` once [ensureBuilt] has scanned the vault. */
    val isBuilt: Boolean get() = built

    /**
     * Told after every change to the index (a file's text, a folder move).
     * `DocumentRegistry` uses it to re-run the queries of search nodes on
     * screen ([SearchNode]).
     */
    var onChanged: (() -> Unit)? = null

    /**
     * Records the current text of the note file [fileRel]; `null` means the
     * file was deleted. Called for every read and write the repository
     * makes. App files other than outlines (`Starred.md`) are left out.
     */
    fun noteText(fileRel: String, text: String?) {
        if (text == null || (NoteRepository.isAppFile(fileRel) && !NoteRepository.isOutlineFile(fileRel))) {
            if (linesByFile.remove(fileRel) != null) onChanged?.invoke()
            return
        }
        linesByFile[fileRel] = if (NoteRepository.isOutlineFile(fileRel)) outlineEntry(text) else noteEntry(text)
        onChanged?.invoke()
    }

    /**
     * Reads every note file once, the first time; later calls return at
     * once. Files already reported through [noteText] are read again
     * anyway — cheap, and it keeps the scan simple.
     */
    suspend fun ensureBuilt() {
        buildMutex.withLock {
            if (built) return
            for (file in listFiles()) noteText(file, readText(file))
            built = true
        }
    }

    /**
     * Carries index keys along with folders a save moved: a file at or
     * under a moved folder is now filed under its new path. An entry
     * already at the new path (the save just wrote it) wins.
     */
    fun moveKeys(moves: List<PathMove>) {
        if (moves.isEmpty() || linesByFile.isEmpty()) return
        val moved = HashMap<String, Entry>()
        val from = ArrayList<String>()
        for ((file, lines) in linesByFile) {
            val to = LunarborLink.remap(file, moves) ?: continue
            if (to == file) continue
            from += file
            moved[to] = lines
        }
        for (file in from) linesByFile.remove(file)
        for ((file, lines) in moved) if (file !in linesByFile) linesByFile[file] = lines
        if (from.isNotEmpty()) onChanged?.invoke()
    }

    /**
     * The lines in [scope] — a tree, never anything above it, or one note —
     * meeting [expr] (`SearchQuery`). A line's tags for the test are its
     * item's own plus every ancestor item's, across files. A line under an
     * item that is itself a hit is left out: the item stands for it (so a
     * `#project` node does not list every line inside it again).
     *
     * Hits come in reading order: down the outline, a folder-backed item's
     * lines right after the item itself (depth first, in the outline's
     * order), then the folder's `.md` notes, then any subfolder no item
     * names, by name. Files in dot folders (the trash) are skipped.
     *
     * @param reversed List the hits in reverse order: the last ones first
     *   (of all hits, so with more than [max] the list is the tail, not the
     *   head turned round).
     * @param max Most hits returned; [TextSearchResult.total] counts all.
     */
    fun search(scope: TextScope, expr: SearchQuery.Expr?, max: Int = 300, reversed: Boolean = false): TextSearchResult {
        if (expr == null) return TextSearchResult(emptyList(), 0)
        val hits = ArrayList<TextHit>()
        var total = 0
        val inherited = HashMap<String, Set<String>>()

        fun scan(file: String, folder: String, onOwned: (folder: String, hit: Boolean) -> Unit) {
            val above = inheritedTags(folder, inherited)
            for (line in linesByFile[file]?.lines.orEmpty()) {
                val tags = if (above.isEmpty()) line.lineTags else line.lineTags + above
                val hit = expr.matches(line.key, tags)
                if (hit) {
                    total++
                    if (reversed || hits.size < max) hits += TextHit(file, line.itemIndex, line.rowOffset, line.text)
                }
                line.ownedFolder?.let { onOwned(if (folder.isEmpty()) it else "$folder/$it", hit) }
            }
        }

        if (scope is TextScope.File) {
            if (scope.fileRel in linesByFile) scan(scope.fileRel, folderOfFile(scope.fileRel)) { _, _ -> }
        } else {
            val tree = treeIn((scope as TextScope.Tree).folderRel)
            // Folders walked, or left out under an item that is a hit.
            val done = HashSet<String>()
            fun walk(folder: String) {
                if (!done.add(folder)) return
                val node = tree[folder] ?: return
                node.outline?.let { outline ->
                    scan(outline, folder) { owned, hit -> if (hit) done += owned else walk(owned) }
                }
                for (note in node.notes) scan(note, folder) { _, _ -> }
                for (child in node.children) walk(child)
            }
            walk(scope.folderRel)
        }
        return TextSearchResult(if (reversed) hits.asReversed().take(max) else hits, total)
    }

    /**
     * One folder of a [treeIn]: its outline file (if indexed), its `.md`
     * notes and its subfolders, each by name.
     */
    private class FolderNode {
        var outline: String? = null
        val notes = ArrayList<String>()
        val children = ArrayList<String>()
    }

    /**
     * The indexed files at or under [folderRel] (none in dot folders), as
     * folders: every folder on the way to a file is there, even one with
     * no file of its own.
     */
    private fun treeIn(folderRel: String): Map<String, FolderNode> {
        val prefix = if (folderRel.isEmpty()) "" else "$folderRel/"
        val tree = HashMap<String, FolderNode>()
        tree[folderRel] = FolderNode()
        fun nodeOf(folder: String): FolderNode = tree[folder] ?: FolderNode().also { node ->
            tree[folder] = node
            nodeOf(folder.substringBeforeLast('/', "")).children += folder
        }
        for (file in linesByFile.keys.sorted()) {
            if (!file.startsWith(prefix) || file.split('/').any { it.startsWith(".") }) continue
            val folder = folderOfFile(file)
            if (NoteRepository.isOutlineFile(file)) nodeOf(folder).outline = file else nodeOf(folder).notes += file
        }
        for (node in tree.values) node.children.sort()
        return tree
    }

    /** The folder a note file is in (an outline: its node's folder). */
    private fun folderOfFile(file: String): String =
        if (NoteRepository.isOutlineFile(file)) NoteRepository.folderOfOutline(file) else file.substringBeforeLast('/', "")

    /**
     * Normalized tags the lines of files in [folder] inherit: the tags of
     * the item backing [folder] (in its parent's outline) and of every item
     * above it. Memoized in [memo] for one search.
     */
    private fun inheritedTags(folder: String, memo: MutableMap<String, Set<String>>): Set<String> {
        if (folder.isEmpty()) return emptySet()
        memo[folder]?.let { return it }
        val parent = folder.substringBeforeLast('/', "")
        val name = folder.substringAfterLast('/')
        val own = linesByFile[NoteRepository.outlineFileOf(parent)]?.owned?.get(name).orEmpty()
        val result = own + inheritedTags(parent, memo)
        memo[folder] = result
        return result
    }

    /**
     * The tags used in [scope] that start
     * with [prefix] (with or without its `#`; case-insensitive, `""` lists
     * all), most used first, then alphabetically. A tag's spelling is the
     * one met first, shallowest files first. Dot folders are skipped.
     *
     * Called through `PaneBackingViewModel.tagSuggestions` for the search
     * field's autocomplete.
     *
     * @param max Most tags returned.
     */
    fun tags(scope: TextScope, prefix: String, max: Int = 50): List<TagCount> {
        val want = prefix.removePrefix("#").toNfc().lowercase()
        val counts = LinkedHashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (file in filesIn(scope)) {
            for (line in linesByFile[file]?.lines.orEmpty()) {
                for (tag in line.tags) {
                    val key = tag.substring(1).toNfc().lowercase()
                    if (!key.startsWith(want)) continue
                    counts[key] = (counts[key] ?: 0) + 1
                    if (key !in spelling) spelling[key] = tag
                }
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(max)
            .map { TagCount(spelling.getValue(it.key), it.value) }
    }

    /**
     * Indexed files in [scope]: the one note, or every file at or under the
     * tree's folder — dot folders left out, shallowest first, then by
     * folder, a folder's outline before its notes.
     */
    private fun filesIn(scope: TextScope): List<String> {
        if (scope is TextScope.File) return if (scope.fileRel in linesByFile) listOf(scope.fileRel) else emptyList()
        val folderRel = (scope as TextScope.Tree).folderRel
        val prefix = if (folderRel.isEmpty()) "" else "$folderRel/"
        return linesByFile.keys
            .filter { it.startsWith(prefix) && it.split('/').none { seg -> seg.startsWith(".") } }
            .sortedWith(
                compareBy<String> { it.count { c -> c == '/' } }
                    .thenBy { it.substringBeforeLast('/', "") }
                    .thenBy { !NoteRepository.isOutlineFile(it) }
                    .thenBy { it },
            )
    }

    companion object {
        /**
         * What the reader sees of [raw] — line prefix dropped, inline
         * Markdown collapsed, a search node's query left out — and the
         * tags in it.
         */
        private fun visibleText(raw: String): Pair<String, List<String>> {
            val prefix = LineMarkdownPrefix.detect(raw, 0)
            val tokens = InlineMarkdownTokenizer.tokenize(SearchNode.stripQuery(raw.substring(prefix.markerEnd)))
            return tokens.displayText.trim() to tokens.runs.filter { it.isTag }.map { it.text }
        }

        private fun tagKey(tag: String): String = SearchQuery.normalize(tag.removePrefix("#"))

        /**
         * An outline's lines: each bullet's title, and each block's rows
         * (code verbatim), numbered by item the way [TextHit] describes;
         * plus the tags of each folder-backed item, by folder.
         */
        private fun outlineEntry(text: String): Entry {
            val composed = SubtreeCodec.composeNodeLines(SubtreeCodec.parseNodeFile(text), 0)
            val rows = composed.lines
            val out = ArrayList<Line>()
            val owned = HashMap<String, Set<String>>()
            var item = 0
            var row = 0
            while (row < rows.size) {
                val last = DocumentLayout.itemLastRow(rows, row).coerceAtLeast(row)
                // Only items are numbered and indexed (a stray text line has
                // no item to open at).
                val isItem = DocumentLayout.itemColumn(rows, row) >= 0
                if (isItem) {
                    val shownRows = (row..last).map { r -> r to shownOf(rows[r]) }
                    val itemTags = shownRows.flatMap { it.second.second }.map(::tagKey).toSet()
                    val folder = composed.folderByRow[row]
                    if (folder != null) owned[folder] = itemTags
                    for ((r, shown) in shownRows) {
                        if (shown.first.isBlank()) continue
                        out += Line(
                            SearchQuery.normalize(shown.first), item, r - row, shown.first, shown.second,
                            shown.second.map(::tagKey).toSet(), folder,
                        )
                    }
                }
                if (isItem) item++
                row = last + 1
            }
            return Entry(out, owned)
        }

        /** What one composed outline row shows, and its tags. */
        private fun shownOf(raw: String): Pair<String, List<String>> = when {
            BlockLayout.isCodeLine(raw) -> BlockLayout.textOf(raw).trim() to emptyList()
            // A list item's `* ` / `- ` is drawn as a dot, not text.
            BlockLayout.isBlockLine(raw) -> visibleText(
                BlockLayout.textOf(raw).trimStart().let {
                    if (it.startsWith("* ") || it.startsWith("- ")) it.substring(2) else it
                },
            )
            else -> visibleText(SubtreeCodec.titleOf(raw))
        }

        /** A note's lines, numbered by line; each line's tags are its own. */
        private fun noteEntry(text: String): Entry = Entry(
            text.replace("\r\n", "\n").split('\n').mapIndexedNotNull { i, raw ->
                val (shown, tags) = visibleText(raw)
                if (shown.isBlank()) null
                else Line(SearchQuery.normalize(shown), i, 0, shown, tags, tags.map(::tagKey).toSet(), null)
            },
            emptyMap(),
        )
    }
}
