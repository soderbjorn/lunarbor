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
 * Each line also keeps its done flag (LBR-24, [DoneState]): computed from
 * the raw line — the normalized key has lost the `~~` — at index time, so
 * `is:done` costs a search nothing. Like tags, each outline records the
 * done flag of its folder-backed items, so the lines in their folders
 * inherit it: a line is done when its item or any item above it is.
 *
 * Each line also keeps the link targets and `[[wiki]]` names it
 * links to, so [backlinks] can list the lines linking to a page (LBR-7).
 *
 * The same tags decide what a privacy mode hides (LBR-10, [PrivacyFilter]):
 * [search] and [tags] leave out every line under an item carrying a hidden
 * tag (and whole notes carrying one), and [isPathHidden] /
 * [hasHiddenUnder] answer it for folders and files across the vault.
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
 * @property done `true` when the line is done (LBR-24): its item's whole
 *   title is struck through, or an item above it is done, across files
 *   ([DoneState]). Lets result lists dim done lines and LBR-22's Toggle
 *   done on a hit know which way to toggle.
 * @property canToggleDone `true` when Toggle done may act on the line from
 *   a result list (LBR-22, `DocumentRegistry.toggleDoneOnHit`): a line of
 *   an outline that is not a code row. `false` for `.md` note lines
 *   (Markdown mode has no done state) and code rows.
 */
data class TextHit(
    val fileRel: String,
    val itemIndex: Int,
    val rowOffset: Int,
    val text: String,
    val done: Boolean = false,
    val canToggleDone: Boolean = false,
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
        /**
         * Normalized tags of the line's whole item — every row of a block —
         * which a privacy mode tests ([PrivacyFilter]). In a note, the line's own.
         */
        val itemTags: Set<String>,
        /** Targets of the line's vault links ([LunarborLink.linkPathsIn]). */
        val links: Set<String> = emptySet(),
        /** Target names of the line's `[[wiki]]` links ([WikiLink.namesIn]). */
        val wikiNames: List<String> = emptyList(),
        /**
         * `true` when the line's item is done by its own title, or an item
         * above it in the same file is ([DoneState]). Items in other files
         * above it count through [Entry.ownedDone] at search time.
         */
        val done: Boolean = false,
        /** See [TextHit.canToggleDone]: an outline line that is not a code row. */
        val canToggleDone: Boolean = false,
    ) {
        /** This line as a hit in [file], [aboveDone] telling whether an item in a file above is done. */
        fun hit(file: String, aboveDone: Boolean) = TextHit(file, itemIndex, rowOffset, text, done || aboveDone, canToggleDone)
    }

    /**
     * One indexed file: its lines, and the normalized tags of each of its
     * folder-backed items by folder name — what the files in that folder
     * inherit. [noteTags] are every tag of a `.md` note (empty for an
     * outline): a note carrying a hidden tag anywhere is hidden whole.
     */
    private class Entry(
        val lines: List<Line>,
        val owned: Map<String, Set<String>>,
        val noteTags: Set<String> = emptySet(),
        /** Folders (by name) of this outline's folder-backed items that are done (LBR-24). */
        val ownedDone: Set<String> = emptySet(),
    )

    /** [inheritedTags] by folder, for [isPathHidden]; dropped on every change. */
    private val inheritCache = HashMap<String, Set<String>>()

    /** One line of one file, as [backlinks] finds it; [ordinal] is its place in the file's lines. */
    private class LineRef(val file: String, val ordinal: Int, val line: Line)

    /**
     * The index read the other way round, for [backlinks]: [byPath] maps a
     * page (a folder, or a file — an outline link counts as its folder) to
     * the lines with a vault link to it, [byWiki] a wiki name's key
     * ([WikiLink.keyOf]) to the lines naming it. Dot folders are left out.
     */
    private class LinkRefs(val byPath: Map<String, List<LineRef>>, val byWiki: Map<String, List<LineRef>>)

    /** [LinkRefs] of the index as it is; built on first use, dropped on every change. */
    private var linkRefs: LinkRefs? = null

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
            if (linesByFile.remove(fileRel) != null) {
                dropDerived()
                onChanged?.invoke()
            }
            return
        }
        linesByFile[fileRel] = if (NoteRepository.isOutlineFile(fileRel)) outlineEntry(text, LunarborLink.baseOfFile(fileRel))
            else noteEntry(text, LunarborLink.baseOfFile(fileRel))
        dropDerived()
        onChanged?.invoke()
    }

    /** Drops what is derived from [linesByFile]; called on every change to it. */
    private fun dropDerived() {
        inheritCache.clear()
        linkRefs = null
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
        if (from.isNotEmpty()) {
            dropDerived()
            onChanged?.invoke()
        }
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
     * Under a privacy [filter] the lines it hides do not exist: a line
     * whose item (or an item above it) carries a hidden tag is skipped and
     * its item's folder not walked, and a note carrying one is skipped
     * whole.
     *
     * @param reversed List the hits in reverse order: the last ones first
     *   (of all hits, so with more than [max] the list is the tail, not the
     *   head turned round).
     * @param max Most hits returned; [TextSearchResult.total] counts all.
     * @param filter The privacy mode applied ([PrivacyFilter.NONE]: none).
     * @param tagSort `sort:#a,#b,…`'s order ([SearchQuery.TagSort]), applied to
     *   all hits before [reversed] and [max]; `null` keeps reading order.
     */
    fun search(
        scope: TextScope,
        expr: SearchQuery.Expr?,
        max: Int = 300,
        reversed: Boolean = false,
        filter: PrivacyFilter = PrivacyFilter.NONE,
        tagSort: SearchQuery.TagSort? = null,
    ): TextSearchResult {
        if (expr == null) return TextSearchResult(emptyList(), 0)
        val hits = ArrayList<TextHit>()
        // Under `sort:` every hit is kept, with its rank, and cut after sorting.
        val keyed = ArrayList<Pair<Int?, TextHit>>()
        var total = 0
        val inherited = HashMap<String, Set<String>>()
        val inheritedDone = HashMap<String, Boolean>()

        fun scan(file: String, folder: String, onOwned: (folder: String, hit: Boolean) -> Unit) {
            val above = inheritedTags(folder, inherited)
            val aboveDone = isDoneAbove(folder, inheritedDone)
            val entry = linesByFile[file] ?: return
            if (filter.hides(above) || filter.hides(entry.noteTags)) return
            for (line in entry.lines) {
                val ownedPath = line.ownedFolder?.let { if (folder.isEmpty()) it else "$folder/$it" }
                if (filter.hides(line.itemTags)) {
                    // A hidden item: neither it nor anything in its folder.
                    ownedPath?.let { onOwned(it, true) }
                    continue
                }
                val tags = if (above.isEmpty()) line.lineTags else line.lineTags + above
                val hit = expr.matches(line.key, tags, line.done || aboveDone)
                if (hit) {
                    total++
                    if (tagSort != null) keyed += tagSort.rankOf(line.tags) to line.hit(file, aboveDone)
                    else if (reversed || hits.size < max) hits += line.hit(file, aboveDone)
                }
                ownedPath?.let { onOwned(it, hit) }
            }
        }

        if (scope is TextScope.File) {
            if (scope.fileRel in linesByFile) scan(scope.fileRel, folderOfFile(scope.fileRel)) { _, _ -> }
        } else if (!filter.hides(inheritedTags((scope as TextScope.Tree).folderRel, inherited))) {
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
        if (tagSort != null) {
            // Stable: equal ranks keep reading order; lines without one last.
            keyed.sortBy { it.first ?: Int.MAX_VALUE }
            keyed.mapTo(hits) { it.second }
        }
        return TextSearchResult(if (reversed) hits.asReversed().take(max) else hits.take(max), total)
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
     * `true` when the lines of files in [folder] are done by inheritance:
     * the item backing [folder] (in its parent's outline) or any item above
     * it is done ([Entry.ownedDone]). Memoized in [memo] for one search.
     */
    private fun isDoneAbove(folder: String, memo: MutableMap<String, Boolean>): Boolean {
        if (folder.isEmpty()) return false
        memo[folder]?.let { return it }
        val parent = folder.substringBeforeLast('/', "")
        val name = folder.substringAfterLast('/')
        val own = linesByFile[NoteRepository.outlineFileOf(parent)]?.ownedDone?.contains(name) == true
        val result = own || isDoneAbove(parent, memo)
        memo[folder] = result
        return result
    }

    /**
     * `true` when the folder [folderRel] is done by inheritance: the item
     * backing it, or an item above it, is done (LBR-24). `false` for the
     * root and for a folder whose parent outline was never indexed.
     */
    fun isFolderDone(folderRel: String): Boolean = isDoneAbove(folderRel, HashMap())

    /**
     * The tags used in [scope] that start
     * with [prefix] (with or without its `#`; case-insensitive, `""` lists
     * all), most used first, then alphabetically. A tag's spelling is the
     * one met first, shallowest files first. Dot folders are skipped.
     *
     * Called through `PaneBackingViewModel.tagSuggestions` for the search
     * field's autocomplete, by the privacy dialog's tag field and by the
     * agents' `list_tags`.
     *
     * Under a privacy [filter] the lines it hides are left out — so its own
     * tags, which only hidden lines carry, are never listed.
     *
     * @param max Most tags returned.
     * @param filter The privacy mode applied ([PrivacyFilter.NONE]: none).
     */
    fun tags(scope: TextScope, prefix: String, max: Int = 50, filter: PrivacyFilter = PrivacyFilter.NONE): List<TagCount> {
        val want = prefix.removePrefix("#").toNfc().lowercase()
        val counts = LinkedHashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (file in filesIn(scope)) {
            if (filter.isActive && isPathHidden(file, filter)) continue
            for (line in linesByFile[file]?.lines.orEmpty()) {
                if (filter.hides(line.itemTags)) continue
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
     * `true` when [filter] hides the vault path [pathRel] — a folder, a
     * node's outline, a note or any other file: the item backing it or any
     * folder above it carries a hidden tag ([Entry.owned], across files),
     * or it is a `.md` note carrying one anywhere. `false` for every path
     * when the filter hides nothing. Needs the index built ([ensureBuilt]):
     * a folder whose parent outline was never read counts as visible.
     *
     * Called by `DocumentRegistry.isPathHidden` for the folder contents
     * list, link search, link status, Starred, pane locations and agents.
     */
    fun isPathHidden(pathRel: String, filter: PrivacyFilter): Boolean {
        if (!filter.isActive) return false
        val path = if (NoteRepository.isOutlineFile(pathRel)) NoteRepository.folderOfOutline(pathRel) else pathRel.trimEnd('/')
        if (filter.hides(inheritedTags(path, inheritCache))) return true
        val note = linesByFile[path]
        return note != null && !NoteRepository.isOutlineFile(path) && filter.hides(note.noteTags)
    }

    /**
     * `true` when something under the folder [folderRel] — an item of its
     * outline or of any outline below it, or a note — is hidden by
     * [filter]. Such a node must not be deleted while the mode is on: its
     * folder would take the hidden content to the trash with it.
     *
     * Called by `DocumentRegistry.hasHiddenUnder` ("Delete this node",
     * deleting a folder-backed item in a pane, agents' `delete` / `edit`).
     */
    fun hasHiddenUnder(folderRel: String, filter: PrivacyFilter): Boolean {
        if (!filter.isActive) return false
        val prefix = if (folderRel.isEmpty()) "" else "$folderRel/"
        for ((file, entry) in linesByFile) {
            if (!file.startsWith(prefix)) continue
            if (filter.hides(entry.noteTags)) return true
            if (entry.lines.any { filter.hides(it.itemTags) }) return true
        }
        return false
    }

    /**
     * The lines linking to the page at [target] — a folder (a node's, or any
     * other) or a file — as hits to list and open: lines whose vault
     * link points exactly at [target] (an outline path counts as its
     * folder), or whose `[[wiki]]` link [resolveWiki] resolves to it. Links
     * to things inside [target] do not count, nor do lines inside the page
     * itself (in its folder or below, or in the note itself), nor lines
     * [filter] hides. Ordered by file path (ignoring case), then line.
     *
     * Called by `DocumentRegistry.requestBacklinks`.
     *
     * @param resolveWiki The path a wiki link name resolves to, or `null`
     *   (none, or ambiguous) — `VaultIndex.wikiResolver`; `null` when the
     *   vault has no wiki links ([hasWikiLinks]). Called once per distinct
     *   wiki name in the vault.
     */
    fun backlinks(target: String, resolveWiki: ((String) -> String?)?, filter: PrivacyFilter = PrivacyFilter.NONE): List<TextHit> {
        val page = if (NoteRepository.isOutlineFile(target)) NoteRepository.folderOfOutline(target) else target
        val isFile = page in linesByFile && !NoteRepository.isOutlineFile(page)
        val inside = if (page.isEmpty()) "" else "$page/"
        val refs = linkRefsNow()
        val candidates = LinkedHashSet<LineRef>()
        refs.byPath[page]?.let { candidates += it }
        if (resolveWiki != null) {
            for ((key, lines) in refs.byWiki) if (resolveWiki(key) == page) candidates += lines
        }
        val inherited = HashMap<String, Set<String>>()
        val inheritedDone = HashMap<String, Boolean>()
        return candidates
            .filter { ref ->
                val file = ref.file
                if (if (isFile) file == page else (page.isEmpty() || file.startsWith(inside))) return@filter false
                if (filter.hides(ref.line.itemTags)) return@filter false
                !filter.isActive ||
                    !(filter.hides(inheritedTags(folderOfFile(file), inherited)) || filter.hides(linesByFile.getValue(file).noteTags))
            }
            .sortedWith(compareBy<LineRef>({ it.file.lowercase() }, { it.file }, { it.ordinal }))
            .map { it.line.hit(it.file, isDoneAbove(folderOfFile(it.file), inheritedDone)) }
    }

    /**
     * `true` when any indexed line holds a `[[wiki]]` link — only then does
     * [backlinks] need a wiki resolver (which walks the vault for its
     * targets). Called by `DocumentRegistry`.
     */
    fun hasWikiLinks(): Boolean = linkRefsNow().byWiki.isNotEmpty()

    /** [linkRefs], built from [linesByFile] when it was dropped. */
    private fun linkRefsNow(): LinkRefs {
        linkRefs?.let { return it }
        val byPath = HashMap<String, MutableList<LineRef>>()
        val byWiki = HashMap<String, MutableList<LineRef>>()
        for ((file, entry) in linesByFile) {
            if (file.split('/').any { it.startsWith(".") }) continue
            entry.lines.forEachIndexed { i, line ->
                if (line.links.isEmpty() && line.wikiNames.isEmpty()) return@forEachIndexed
                val ref = LineRef(file, i, line)
                for (link in line.links) {
                    val path = if (NoteRepository.isOutlineFile(link)) NoteRepository.folderOfOutline(link) else link
                    val list = byPath.getOrPut(path) { ArrayList() }
                    if (list.lastOrNull() !== ref) list += ref
                }
                for (name in line.wikiNames) {
                    val list = byWiki.getOrPut(WikiLink.keyOf(name)) { ArrayList() }
                    if (list.lastOrNull() !== ref) list += ref
                }
            }
        }
        return LinkRefs(byPath, byWiki).also { linkRefs = it }
    }

    /**
     * `false` when the node folder [folderRel]'s outline is known and every
     * item in it is hidden by [filter] (or it has none); `true` otherwise —
     * also when the outline was never read. Lets a pane leave the fold
     * control off a folded item whose children are all hidden.
     */
    fun hasVisibleItems(folderRel: String, filter: PrivacyFilter): Boolean {
        val entry = linesByFile[NoteRepository.outlineFileOf(folderRel)] ?: return true
        return entry.lines.any { !filter.hides(it.itemTags) }
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
            val tokens = InlineMarkdownTokenizer.tokenize(LunicleNode.stripQueries(raw.substring(prefix.markerEnd)))
            return tokens.displayText.trim() to tokens.runs.filter { it.isTag }.map { it.text }
        }

        private fun tagKey(tag: String): String = SearchQuery.normalize(tag.removePrefix("#"))

        /**
         * An outline's lines: each bullet's title, and each block's rows
         * (code verbatim), numbered by item the way [TextHit] describes;
         * plus the tags of each folder-backed item, by folder.
         */
        private fun outlineEntry(text: String, base: String): Entry {
            val composed = SubtreeCodec.composeNodeLines(SubtreeCodec.parseNodeFile(text), 0)
            val rows = composed.lines
            val out = ArrayList<Line>()
            val owned = HashMap<String, Set<String>>()
            val ownedDone = HashSet<String>()
            var item = 0
            var row = 0
            // Rows up to this one are under a done item of this file.
            var doneThrough = -1
            while (row < rows.size) {
                val last = DocumentLayout.itemLastRow(rows, row).coerceAtLeast(row)
                // Only items are numbered and indexed (a stray text line has
                // no item to open at).
                val col = DocumentLayout.itemColumn(rows, row)
                val isItem = col >= 0
                if (isItem) {
                    if (row > doneThrough && DoneState.isDoneRow(rows[row])) {
                        doneThrough = DocumentLayout.subtreeEnd(rows, row, col)
                    }
                    val done = row <= doneThrough
                    val shownRows = (row..last).map { r -> r to shownOf(rows[r]) }
                    val itemTags = shownRows.flatMap { it.second.second }.map(::tagKey).toSet()
                    val folder = composed.folderByRow[row]
                    if (folder != null) owned[folder] = itemTags
                    if (folder != null && done) ownedDone += folder
                    for ((r, shown) in shownRows) {
                        if (shown.first.isBlank()) continue
                        val raw = rows[r]
                        val code = BlockLayout.isCodeLine(raw)
                        out += Line(
                            SearchQuery.normalize(shown.first), item, r - row, shown.first, shown.second,
                            shown.second.map(::tagKey).toSet(), folder, itemTags,
                            if (code) emptySet() else LunarborLink.linkPathsIn(raw, base),
                            if (code) emptyList() else WikiLink.namesIn(raw),
                            done,
                            canToggleDone = !code,
                        )
                    }
                }
                if (isItem) item++
                row = last + 1
            }
            return Entry(out, owned, ownedDone = ownedDone)
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
        private fun noteEntry(text: String, base: String): Entry {
            val lines = text.replace("\r\n", "\n").split('\n').mapIndexedNotNull { i, raw ->
                val (shown, tags) = visibleText(raw)
                if (shown.isBlank()) null
                else tags.map(::tagKey).toSet().let { keys ->
                    Line(
                        SearchQuery.normalize(shown), i, 0, shown, tags, keys, null, keys,
                        LunarborLink.linkPathsIn(raw, base), WikiLink.namesIn(raw),
                        DoneState.isDoneNoteLine(raw),
                    )
                }
            }
            return Entry(lines, emptyMap(), lines.flatMapTo(HashSet()) { it.lineTags })
        }

        /**
         * Normalized tags (no `#`) on one row of an open document, as the
         * index reads them: a bullet's title, a block row's content (none on
         * a code row). Cheap for a row without a `#`.
         *
         * Called by `PrivacyLayout` to find the rows a privacy mode hides.
         */
        fun tagKeysOfRow(raw: String): Set<String> {
            if ('#' !in raw) return emptySet()
            return shownOf(raw).second.mapTo(HashSet(), ::tagKey)
        }

        /**
         * Normalized tags of every line of a note's [text]. Called by the
         * agent tools to hide a whole note the way [isPathHidden] does.
         */
        fun tagKeysOfText(text: String): Set<String> =
            text.split('\n').flatMapTo(HashSet()) { if ('#' in it) visibleText(it).second.map(::tagKey) else emptyList() }
    }
}
