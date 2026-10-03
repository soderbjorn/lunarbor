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

    /**
     * Targets whose title contains [query] (case-insensitive), best first,
     * at most [max]. Ranking: earlier match position; then folders before
     * notes before other files; then shorter titles. A blank query matches
     * nothing. Paths [isHidden] names are never listed.
     *
     * Called by the Insert Link, "Link to node…" and Navigate-to modal.
     */
    suspend fun search(query: String, max: Int = 50): List<LinkTarget> {
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
        scored.sortWith(compareBy<Pair<Long, LinkTarget>> { it.first }.thenBy { it.second.pathRel })
        return scored.take(max).map { it.second }
    }

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
        if (moves.isEmpty() || linksByFile.isEmpty()) return
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
