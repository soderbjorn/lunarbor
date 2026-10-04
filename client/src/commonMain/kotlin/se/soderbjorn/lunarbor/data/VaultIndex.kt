/*
 * VaultIndex.kt (commonMain)
 * --------------------------
 * App-scoped index behind Lunarbor links (TRF-8). Two parts:
 *
 *  1. **Link targets** — every folder and file a link may point at
 *     ([LinkTarget], from `NoteRepository.listLinkTargets`), searched by
 *     the Insert Link / "Link to node…" / Navigate-to modal ([search]).
 *     Always the whole vault from the root, however deep the pane is
 *     zoomed. Only linkable targets are listed: non-empty folders (node
 *     folders and foreign ones alike) and files; leaf bullets have no
 *     folder and empty folders are skipped, so neither can be offered.
 *     The list is cached and dropped by [invalidateTargets] after saves
 *     and whenever the registry refreshes its listings.
 *
 *  2. **The link index** — which note files contain `lunarbor:` links to which
 *     paths. Built by one scan of the vault the first time it is needed
 *     ([ensureLinkIndex]), then kept current as files load and save: the
 *     repository reports every note file text it reads or writes
 *     ([noteText]), and folder moves carry the index keys along
 *     ([moveKeys]). When a save renames or moves a folder or file,
 *     `DocumentRegistry` asks [filesLinkingInto] which closed files need
 *     their links rewritten.
 *
 *  3. **Node stamps** (LBR-16) — each node folder's `updated` stamp, from
 *     the front matter of the outline texts the link index sees ([noteText],
 *     carried along by [moveKeys]). Navigate to (Cmd-O) orders by it:
 *     [recentNodes] for an empty query, [search] with `byRecency` as the
 *     tie-break. No extra disk reads: [ensureLinkIndex] reads every outline
 *     once, and saves keep it current.
 *
 * Links address folders and files by path, not bullets by title, so
 * there is no title-path resolution here any more: a link either names a
 * path that exists or it is broken.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One folder or file a link can point at.
 *
 * @property pathRel Vault-relative path (on-disk names); `""` is the
 *   vault root. The link is `LunarborLink.format(pathRel)`.
 * @property title What the search list shows and matches: the bullet's
 *   plain-text title for a node folder, the decoded name for another
 *   folder, the basename (minus `.md` for notes) for a file.
 * @property kind Folder, Markdown note, image or other file.
 * @property crumbs Titles of the enclosing folders, outermost first, the
 *   vault root left out.
 */
data class LinkTarget(
    val pathRel: String,
    val title: String,
    val kind: VaultEntryKind,
    val crumbs: List<String> = emptyList(),
)

/**
 * Link-target search and link index over the vault.
 *
 * ### Callers
 * - Created and fed by `DocumentRegistry`, which wires [noteText] to
 *   `NoteRepository.noteTextObserver`.
 * - The web link-search modal calls [search] (through the pane VMs).
 *
 * @param listTargets Returns every linkable target, walking the vault;
 *   production passes `NoteRepository.listLinkTargets`.
 * @param listLinkBearingFiles Returns every note file that can hold links
 *   (all outlines and `.md` notes); production passes
 *   `NoteRepository.listLinkBearingFiles`.
 * @param readText Reads one note file's raw text (`null` when missing);
 *   production passes `NoteRepository.readNoteText`, which also reports
 *   the text back through [noteText].
 */
class VaultIndex(
    private val listTargets: suspend () -> List<LinkTarget>,
    private val listLinkBearingFiles: suspend () -> List<String>,
    private val readText: suspend (String) -> String?,
) {
    /**
     * Paths [search] leaves out: what the app's privacy mode hides (set by
     * `DocumentRegistry`). [targets] still lists everything — wiki-link
     * resolution needs the whole list to tell a unique name from an
     * ambiguous one, and hides its answer itself.
     */
    var isHidden: ((String) -> Boolean)? = null

    private val targetsMutex = Mutex()
    private var targetsCache: List<LinkTarget>? = null

    /** Drops the cached target list; the next [search] walks the vault again. */
    fun invalidateTargets() {
        targetsCache = null
    }

    /** Every linkable target, from the cache or a fresh vault walk. */
    suspend fun targets(): List<LinkTarget> = targetsMutex.withLock {
        targetsCache ?: listTargets().also { targetsCache = it }
    }

    /** The [WikiLink.resolver] of a [targets] list, kept while that list is current. */
    private var resolverCache: Pair<List<LinkTarget>, (String) -> String?>? = null

    /**
     * Resolves `[[wiki]]` link names against [targets] ([WikiLink.resolve]
     * rules) through a lookup table built once per target list, so
     * resolving every wiki name in the vault stays cheap.
     *
     * Called by `DocumentRegistry` for wiki links and backlinks.
     */
    suspend fun wikiResolver(): (String) -> String? {
        val targets = targets()
        resolverCache?.takeIf { it.first === targets }?.let { return it.second }
        return WikiLink.resolver(targets).also { resolverCache = targets to it }
    }

    /**
     * Targets whose title contains [query] (case-insensitive), best first,
     * at most [max]. Ranking: earlier match position; then folders before
     * notes before other files; then shorter titles; with [byRecency],
     * equally good matches then go newest [updatedOf] first (unknown
     * last); then by path. A blank query matches nothing. Paths [isHidden]
     * names are never listed.
     *
     * Called by the Insert Link, "Link to node…" and Navigate-to modal;
     * only Navigate to passes [byRecency].
     */
    suspend fun search(query: String, max: Int = 50, byRecency: Boolean = false): List<LinkTarget> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Long, LinkTarget>>()
        val hidden = isHidden
        for (t in targets()) {
            if (hidden != null && hidden(t.pathRel)) continue
            val pos = t.title.lowercase().indexOf(needle)
            if (pos < 0) continue
            val tier = when (t.kind) {
                VaultEntryKind.FOLDER -> 0
                VaultEntryKind.MARKDOWN -> 1
                else -> 2
            }
            val score = (pos.toLong() shl 32) or (tier.toLong() shl 24) or
                t.title.length.toLong().coerceAtMost(0xFFFFFF)
            scored += score to t
        }
        var order = compareBy<Pair<Long, LinkTarget>> { it.first }
        if (byRecency) order = order.thenByDescending { updatedOf(it.second.pathRel) ?: Long.MIN_VALUE }
        scored.sortWith(order.thenBy { it.second.pathRel })
        return scored.take(max).map { it.second }
    }

    /**
     * What Navigate to lists for an empty query: node folders with a known
     * `updated` stamp, newest first, then the other folders in [targets]
     * order (the vault walk's) — at most [max], hidden paths left out.
     * Files are not listed. Call [ensureLinkIndex] first so every outline's
     * stamp is known.
     */
    suspend fun recentNodes(max: Int = 50): List<LinkTarget> {
        val hidden = isHidden
        val folders = targets().filter { it.kind == VaultEntryKind.FOLDER && (hidden == null || !hidden(it.pathRel)) }
        val (stamped, unknown) = folders.partition { updatedOf(it.pathRel) != null }
        return (stamped.sortedByDescending { updatedOf(it.pathRel) } + unknown).take(max)
    }

    /**
     * The `updated` stamp of the node folder [folderRel] in epoch
     * milliseconds, as last seen in its outline's front matter; `null`
     * when unknown (no stamp, not an ISO instant, or not seen yet).
     */
    fun updatedOf(folderRel: String): Long? = updatedByFolder[folderRel]

    /** Node folder → its outline's `updated` stamp (epoch ms); unknown stamps are absent. */
    private val updatedByFolder: MutableMap<String, Long> = HashMap()

    // ------------------------------------------------------------ link index

    private val indexMutex = Mutex()
    private var indexBuilt = false

    /** Note file → the `lunarbor:` target paths its text links to (files without links are absent). */
    private val linksByFile: MutableMap<String, Set<String>> = HashMap()

    /**
     * Records the current text of the note file [fileRel]; `null` means
     * the file was deleted. Called for every read and write the repository
     * makes (`NoteRepository.noteTextObserver`), so the index follows the
     * files as they load and save.
     */
    fun noteText(fileRel: String, text: String?) {
        if (NoteRepository.isOutlineFile(fileRel)) {
            val folder = NoteRepository.folderOfOutline(fileRel)
            val updated = text?.let { NodeFrontMatter.epochMillisOf(SubtreeCodec.splitFrontMatter(it).frontMatter?.updated) }
            if (updated == null) updatedByFolder.remove(folder) else updatedByFolder[folder] = updated
        }
        val links = if (text == null) emptySet() else LunarborLink.linkPathsIn(text)
        if (links.isEmpty()) linksByFile.remove(fileRel) else linksByFile[fileRel] = links
    }

    /**
     * Builds the index by reading every link-bearing file, once. Later
     * calls return at once; the index then stays current through
     * [noteText] and [moveKeys].
     */
    suspend fun ensureLinkIndex() {
        indexMutex.withLock {
            if (indexBuilt) return
            for (file in listLinkBearingFiles()) noteText(file, readText(file))
            indexBuilt = true
        }
    }

    /**
     * Carries index keys along with folders a save moved: a file at or
     * under a moved folder is now filed under its new path. An entry
     * already recorded at the new path (the save just wrote that file)
     * wins over the moved-along one.
     */
    fun moveKeys(moves: List<PathMove>) {
        if (moves.isEmpty()) return
        moveStamps(moves)
        if (linksByFile.isEmpty()) return
        val moved = HashMap<String, Set<String>>()
        val from = ArrayList<String>()
        for ((file, links) in linksByFile) {
            val to = LunarborLink.remap(file, moves) ?: continue
            if (to == file) continue
            from += file
            moved[to] = links
        }
        for (file in from) linksByFile.remove(file)
        for ((file, links) in moved) if (file !in linksByFile) linksByFile[file] = links
    }

    /**
     * [moveKeys] for [updatedByFolder]: a stamp at or under a moved folder
     * is now filed under its new path (a moved node keeps its stamps);
     * one already recorded at the new path wins.
     */
    private fun moveStamps(moves: List<PathMove>) {
        if (updatedByFolder.isEmpty()) return
        val moved = HashMap<String, Long>()
        val from = ArrayList<String>()
        for ((folder, stamp) in updatedByFolder) {
            val to = LunarborLink.remap(folder, moves) ?: continue
            if (to == folder) continue
            from += folder
            moved[to] = stamp
        }
        for (folder in from) updatedByFolder.remove(folder)
        for ((folder, stamp) in moved) if (folder !in updatedByFolder) updatedByFolder[folder] = stamp
    }

    /**
     * Note files whose links point at or through a path that [moves]
     * renamed or moved (trash moves excluded — see [LunarborLink.rewriteText]).
     * Builds the index first if needed. Keys are the files' current paths.
     */
    suspend fun filesLinkingInto(moves: List<PathMove>): Set<String> {
        val live = moves.filter { !it.touchesTrash }
        if (live.isEmpty()) return emptySet()
        ensureLinkIndex()
        val out = LinkedHashSet<String>()
        for ((file, links) in linksByFile) {
            if (links.any { LunarborLink.remap(it, live) != null }) out += file
        }
        return out
    }

    /** The target paths the index holds for [fileRel]; for tests and diagnostics. */
    fun linksIn(fileRel: String): Set<String> = linksByFile[fileRel] ?: emptySet()
}
