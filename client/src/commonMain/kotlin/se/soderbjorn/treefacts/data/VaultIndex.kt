/*
 * VaultIndex.kt
 * -------------
 * App-scoped, lazy outline index used by the Insert Link feature.
 *
 * TreeFacts's "vault" is a tree of `.md` files connected by promoted-ref
 * markdown links (`[Title](path#treefacts)`). Logically, all of those files
 * compose into a single outline tree rooted at `Home.md`: a file's
 * children are its top-level bullets, and a bullet that is a promoted-ref
 * has its children replaced by the linked file's top-level bullets.
 *
 * `VaultIndex` exposes that unified outline as an addressable space:
 *
 *   - `search(query)` — fuzzy title matches across the entire reachable
 *     tree. Used to populate the Insert Link modal's result list.
 *   - `resolve(url, …)` — deterministic title-path walk for a URL the
 *     user clicks. Returns the file + in-file title path of the target,
 *     or [Resolution.NotFound].
 *   - `shortestUrlFor(…)` — converts a search hit into the shortest URL
 *     that resolves back to it from the cursor's position. The modal
 *     embeds this URL into the inserted markdown link.
 *
 * ### Cache strategy
 *
 * TreeFacts autosaves continuously; a cache that invalidated on every
 * save would thrash. Two-tier strategy avoids that:
 *
 *   1. Files with an open `Document` (held by `DocumentRegistry`) are
 *      read live from `Document.stateFlow.value.lines`. The cache is
 *      not consulted for these — saves are no-ops for the index.
 *   2. Files with no open `Document` are parsed once on first lookup
 *      and stashed in [cache]. The cache is dropped per-file by
 *      [invalidate] (called by `DocumentRegistry` after a save) or
 *      wholesale by [invalidateAll].
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.treefacts.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.soderbjorn.treefacts.main.Document
import se.soderbjorn.treefacts.main.DocumentLayout

/**
 * Lazy, app-scoped index over the vault's outline tree.
 *
 * The vault root's logical children are:
 *
 *  1. The configured root file's top-level bullets (Home.md
 *     contributes its bullets directly into the vault root, so a
 *     bullet "Recipes" at Home.md's top level is reachable as
 *     `/Recipes`).
 *  2. Every other `.md` file in the vault, treated as a node whose
 *     title is the file's basename (without `.md`). These "loose"
 *     files are not promoted-ref children of any other file but the
 *     user still wants them in search and as link targets.
 *
 * @param loadFromDisk Cold-read entry point: returns a file's parsed
 *   [NoteRepository.Loaded] from disk. Production callers pass
 *   `repository::loadFile`; tests pass a map-backed fake so the index
 *   can be exercised without a real filesystem.
 * @param listAllMdFiles Returns every `.md` file in the vault, by
 *   vault-relative path. Used to discover loose files that the
 *   Home.md → promoted-ref walk doesn't reach. Production callers pass
 *   `repository::listAllMdFiles`; tests pass a list of known fakes.
 * @param rootFileName Vault-relative path of the configured root file
 *   — typically `Home.md`. The vault root's children are this file's
 *   top-level bullets plus every other `.md` file under the vault.
 * @param openDocuments Snapshot accessor: returns the live `Document`
 *   for each `fileRel` currently held by `DocumentRegistry`. Wired up
 *   by the registry; `VaultIndex` calls it on every lookup so the live
 *   bypass picks up newly-acquired and newly-released files.
 */
class VaultIndex(
    private val loadFromDisk: suspend (String) -> NoteRepository.Loaded,
    private val listAllMdFiles: suspend () -> List<String>,
    private val rootFileName: String,
    private val openDocuments: () -> Map<String, Document>,
) {

    /**
     * Result of [resolve]. Either the URL walked to a real node in the
     * outline tree, or it didn't.
     */
    sealed class Resolution {
        /**
         * The URL resolved to a node. The view layer should:
         *  1. Acquire the file at [fileRel] (via `navigateToVaultFile`).
         *  2. Translate [titlePathInFile] into a live `LineId` by walking
         *     the in-file bullet tree once the document is loaded.
         *  3. Call `zoomTo(lineId)`.
         *
         * @property fileRel Vault-relative path of the file that hosts
         *   this node.
         * @property titlePathInFile The `/`-separated title sequence from
         *   the file's root to the target node. Empty when the target
         *   *is* the file's root (a file-level link).
         */
        data class Found(
            val fileRel: String,
            val titlePathInFile: List<String>,
        ) : Resolution()

        /**
         * The URL did not walk cleanly to any node. View layer should
         * surface this as a non-fatal "link target not found" message.
         */
        object NotFound : Resolution()
    }

    /**
     * One node in the outline tree, returned by [search].
     *
     * @property title The node's own title — what the modal shows in the
     *   primary line.
     * @property titlePathFromRoot Full path from the vault root, ending
     *   with [title]. Used by [shortestUrlFor] for relative-vs-absolute
     *   choice and to render the breadcrumb.
     * @property fileRel File the node currently lives in. The same node
     *   may move to a different file after promotion/demotion; the URL
     *   embedded in markdown links does not depend on this — it is
     *   recomputed at click time from [titlePathFromRoot].
     * @property titlePathInFile Path inside [fileRel] from the file's
     *   root to this node. Empty when the hit is the file's own root
     *   (the bullet that promotes the file lives in its parent file —
     *   this hit represents the linked file's "I'm here" entry).
     */
    /**
     * @property title The node's own title — what the modal shows in
     *   the primary line.
     * @property titlePathFromRoot Full path from the vault root, ending
     *   with [title].
     * @property fileRel File the node currently lives in.
     * @property titlePathInFile Path inside [fileRel] from the file's
     *   root to this node. Empty for the root of a loose file.
     * @property descendantCount Total number of nodes nested under this
     *   one in the unified outline tree (transitively, across
     *   promoted-ref boundaries). Used for "is this a content page?"
     *   ranking — higher = more page-like.
     * @property isFileBoundary `true` when this node is a `.md` file
     *   in its own right: a loose file root, or a promoted-ref bullet
     *   whose subtree lives in a separate file. Drives the
     *   "external files rank above sparse bullets" tier.
     * @property isFolderStub `true` when this hit represents a vault
     *   directory that does **not yet** have its own doubled-name anchor
     *   file `<dir>/<dir>.md`. [fileRel] is the would-be path of that
     *   anchor; the caller must materialise it before treating the hit
     *   as a real link target (see `DocumentRegistry.ensureFolderStub`).
     *   Mutually exclusive with [isFileBoundary] in practice — once the
     *   anchor file exists the directory ceases to be a stub and the
     *   loose-file enumeration produces a normal file-boundary hit
     *   instead.
     */
    data class SearchHit(
        val title: String,
        val titlePathFromRoot: List<String>,
        val fileRel: String,
        val titlePathInFile: List<String>,
        val descendantCount: Int = 0,
        val isFileBoundary: Boolean = false,
        val isFolderStub: Boolean = false,
    )

    /** One file's parsed view, used as input to every other walker. */
    private data class FileEntry(
        val fileRel: String,
        val lines: List<String>,
        val promotedByRow: Map<Int, PromotedRef>,
    )

    private val cacheMutex = Mutex()
    private val cache: MutableMap<String, FileEntry> = mutableMapOf()

    /**
     * Drops the cached parse for [fileRel]. Called by `DocumentRegistry`
     * after a save tick (so a future search picks up disk changes the
     * save just wrote) and after `Document.shutdown` (so the next lookup
     * for that file goes back to disk instead of using a stale entry
     * captured before the file was opened as a `Document`).
     */
    suspend fun invalidate(fileRel: String) {
        cacheMutex.withLock { cache.remove(fileRel) }
    }

    /** Drops every cached entry. Used on bulk reshapes (vault-wide rename). */
    suspend fun invalidateAll() {
        cacheMutex.withLock { cache.clear() }
    }

    private suspend fun loadFileEntry(fileRel: String): FileEntry {
        openDocuments()[fileRel]?.let { doc ->
            val state = doc.stateFlow.value
            if (state.isLoaded) {
                return FileEntry(fileRel, state.lines, doc.promotedByRow())
            }
        }
        cacheMutex.withLock { cache[fileRel] }?.let { return it }
        val loaded = loadFromDisk(fileRel)
        val entry = FileEntry(fileRel, loaded.lines, loaded.promotedByRow)
        cacheMutex.withLock {
            return cache.getOrPut(fileRel) { entry }
        }
    }

    // ----------------------------------------------------------------- search

    /**
     * Returns title matches for [query] across the entire outline tree
     * reachable from the root file, ranked by closeness of the match.
     * Up to [max] hits.
     *
     * Matching is case-insensitive substring with simple ranking:
     * earlier match position beats later, and shorter title beats
     * longer for ties. No fuzzy / typo tolerance for v1 — substrings
     * suffice given how short most note titles are.
     */
    suspend fun search(query: String, max: Int = 50): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val all = enumerateAllNodes()
        val scored = ArrayList<Pair<Long, SearchHit>>(all.size)
        for (hit in all) {
            // Drop leaf bullets entirely. They're rarely useful as link
            // targets and crowd the result list. External file boundaries
            // (loose files, promoted-ref bullets) stay even when they're
            // empty — the user explicitly wanted those to surface.
            if (hit.descendantCount == 0 && !hit.isFileBoundary) continue
            val title = hit.title.lowercase()
            val pos = title.indexOf(needle)
            if (pos < 0) continue
            scored += scoreOf(pos, hit, needle.length) to hit
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortBy { it.first }
        // Dedupe: two hits with the same `(fileRel, titlePathInFile)`
        // address the same navigable target. Preserve the first-by-score
        // occurrence so the user always sees the canonical one.
        val seen = HashSet<Pair<String, List<String>>>()
        val out = ArrayList<SearchHit>(scored.size.coerceAtMost(max))
        for ((_, hit) in scored) {
            if (!seen.add(hit.fileRel to hit.titlePathInFile)) continue
            out += hit
            if (out.size >= max) break
        }
        return out
    }

    /**
     * Composite ranking score; **lower is better**. Sort keys, in
     * descending priority:
     *
     *  1. **Match position.** A title that starts with the query beats
     *     one that contains it later. Position is in characters.
     *  2. **Content tier** — the user's "page-likeness" intuition:
     *      - tier 0: bullets with many descendants (≥ [MANY_CHILDREN_THRESHOLD]).
     *        These are topic / section pages.
     *      - tier 1: external file boundaries (loose `.md` files and
     *        promoted-ref bullets), regardless of descendant count.
     *        Even an empty file is a "page" the user may want to
     *        navigate to or fill.
     *      - tier 2: bullets with at least one descendant but fewer
     *        than the many-children threshold.
     *      - tier 3: leaves (no descendants). Often single-line notes;
     *        less useful as link targets.
     *  3. **Title-length tiebreak.** Among hits that match at the same
     *     position and tier, shorter titles rank first — they're the
     *     more canonical hit for a given prefix.
     *
     * The score is packed into a [Long] to keep the comparators trivial.
     */
    private fun scoreOf(matchPos: Int, hit: SearchHit, needleLen: Int): Long {
        val tier = when {
            hit.descendantCount >= MANY_CHILDREN_THRESHOLD -> 0
            hit.isFileBoundary -> 1
            hit.descendantCount >= 1 -> 2
            else -> 3
        }
        val lenSlop = (hit.title.length - needleLen).coerceAtLeast(0)
        // Bit layout: matchPos (high) | tier | lenSlop (low). The shifts
        // are wide enough that no field overflows for any plausible
        // vault — titles aren't longer than a few thousand chars, the
        // tier is 0..3, and matchPos is bounded by title length.
        return (matchPos.toLong() shl 32) or
            (tier.toLong() shl 24) or
            (lenSlop.toLong().coerceAtMost(0xFFFFFF))
    }

    companion object {
        /**
         * Cap on how many `..` segments [shortestUrlFor] is willing to
         * emit before giving up on the relative form.
         */
        private const val MAX_PARENT_HOPS: Int = 3

        /**
         * Descendant count at which a node is considered a "content
         * page" for ranking — gets the top tier in [scoreOf]. Picked
         * to be small enough that a meaningful topic with a handful of
         * sub-bullets qualifies, large enough that a stray `* foo` with
         * one child stays in the lower-priority tier.
         */
        private const val MANY_CHILDREN_THRESHOLD: Int = 5
    }

    /**
     * DFS the outline tree starting from the vault root, collecting
     * every reachable node into a flat list. Promoted-ref boundaries
     * are followed transparently; cycles (a malformed vault that links
     * back to itself) are broken by a per-walk visited set on `fileRel`.
     *
     * After the Home.md walk, every remaining `.md` file in the vault
     * is enumerated as a loose file: its file root is emitted as a
     * single hit (title = basename, fileRel = its path,
     * titlePathInFile = empty), then its bullets are walked under
     * that title.
     *
     * Finally, every vault directory that holds at least one `.md`
     * descendant but does **not** have its own doubled-name anchor file
     * is emitted as a "folder stub" hit (`isFolderStub = true`). Picking
     * one of these in the Insert Link modal materialises the anchor
     * file on the fly — see `DocumentRegistry.ensureFolderStub`.
     */
    private suspend fun enumerateAllNodes(): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        val visited = HashSet<String>()
        walkFile(
            fileRel = rootFileName,
            parentTitlePathFromRoot = emptyList(),
            visited = visited,
            visit = { out += it },
        )
        val allMdFiles = listAllMdFiles()
        for (fileRel in allMdFiles) {
            if (fileRel in visited) continue
            if (fileRel == rootFileName) continue
            val titlePath = titlePathForLooseFile(fileRel)
            val title = titlePath.last()
            out += SearchHit(
                title = title,
                titlePathFromRoot = titlePath,
                fileRel = fileRel,
                titlePathInFile = emptyList(),
                isFileBoundary = true,
            )
            walkFile(
                fileRel = fileRel,
                parentTitlePathFromRoot = titlePath,
                visited = visited,
                visit = { out += it },
            )
        }
        emitFolderStubs(allMdFiles, out)
        return populateDescendantCounts(out)
    }

    /**
     * Emits one [SearchHit] per vault directory that lacks its own
     * doubled-name anchor file. A directory's anchor is
     * `<dir>/<basename>.md`; when it exists the directory is already
     * reachable as a loose-file hit via the regular enumeration and a
     * stub would just duplicate it.
     *
     * Directories are derived from [allMdFiles] — every parent segment
     * of every `.md` file path is a directory that exists on disk. This
     * misses completely empty folders, which is fine: they have nothing
     * to surface and the user can still hand-author a link to them if
     * needed.
     *
     * The stub's [SearchHit.fileRel] is the **would-be** anchor path.
     * It does not exist yet; the Insert Link pick handler must
     * materialise it before the resolver can walk to it.
     */
    private fun emitFolderStubs(allMdFiles: List<String>, out: MutableList<SearchHit>) {
        val mdFileSet = allMdFiles.toHashSet()
        val directories = HashSet<String>()
        for (file in allMdFiles) {
            var dir = file.substringBeforeLast('/', missingDelimiterValue = "")
            while (dir.isNotEmpty()) {
                if (!directories.add(dir)) break
                dir = dir.substringBeforeLast('/', missingDelimiterValue = "")
            }
        }
        for (dir in directories) {
            val basename = dir.substringAfterLast('/')
            val anchorPath = "$dir/$basename.md"
            if (anchorPath in mdFileSet) continue
            val titlePath = dir.split('/')
            out += SearchHit(
                title = basename,
                titlePathFromRoot = titlePath,
                fileRel = anchorPath,
                titlePathInFile = emptyList(),
                isFileBoundary = true,
                isFolderStub = true,
            )
        }
    }

    /**
     * Single-pass post-processor: hits arrive in DFS document order, so
     * a node's descendants always sit contiguously after it until the
     * first hit whose path is not a strict descendant. We maintain an
     * "ancestor stack" indexed into [hits]; for each new hit, anything
     * on the stack that isn't a strict ancestor pops, then every
     * remaining ancestor's count gets bumped.
     *
     * Returns a fresh list with [SearchHit.descendantCount] filled in.
     */
    private fun populateDescendantCounts(hits: List<SearchHit>): List<SearchHit> {
        if (hits.isEmpty()) return hits
        val counts = IntArray(hits.size)
        val stack = ArrayDeque<Int>()
        for (i in hits.indices) {
            val path = hits[i].titlePathFromRoot
            while (stack.isNotEmpty()) {
                val topPath = hits[stack.last()].titlePathFromRoot
                val ancestor = path.size > topPath.size &&
                    topPath.indices.all { path[it].equals(topPath[it], ignoreCase = true) }
                if (ancestor) break
                stack.removeLast()
            }
            for (j in stack) counts[j]++
            stack.addLast(i)
        }
        return List(hits.size) { hits[it].copy(descendantCount = counts[it]) }
    }

    /**
     * Vault-root title path for a loose file — the directory chain
     * leading to the file plus the filename basename. Doubled-name
     * pairs (TreeFacts's own promoted-file convention `<X>/<X>.md`) are
     * collapsed so the path doesn't carry a redundant duplicate of
     * the parent directory's name.
     *
     * Examples:
     *   - `Starred.md` → `["Starred"]`
     *   - `Framna/Framna.md` → `["Framna"]` (doubled-name collapse)
     *   - `Work/Framna/Framna.md` → `["Work", "Framna"]`
     *   - `Personal/Tech & programming.md` → `["Personal", "Tech & programming"]`
     *
     * Including the directory chain makes each loose file uniquely
     * addressable even when basenames collide across directories
     * (the user's vault has both `Framna/Framna.md` and
     * `Work/Framna/Framna.md`).
     */
    private fun titlePathForLooseFile(fileRel: String): List<String> {
        val parts = fileRel.split('/')
        val fileName = parts.last().removeSuffix(".md")
        val out = ArrayList<String>(parts.size)
        out.addAll(parts.dropLast(1))
        out.add(fileName)
        if (out.size >= 2 &&
            out[out.size - 1].equals(out[out.size - 2], ignoreCase = true)
        ) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    private suspend fun walkFile(
        fileRel: String,
        parentTitlePathFromRoot: List<String>,
        visited: MutableSet<String>,
        visit: (SearchHit) -> Unit,
    ) {
        if (!visited.add(fileRel)) return
        val entry = loadFileEntry(fileRel)
        val baseline = topLevelBulletIndent(entry)
        if (baseline < 0) return
        walkRange(
            entry = entry,
            startRow = 0,
            endExclusive = entry.lines.size,
            parentIndent = baseline - 1,
            parentTitlePathFromRoot = parentTitlePathFromRoot,
            inFilePath = emptyList(),
            visited = visited,
            visit = visit,
        )
    }

    /**
     * Walks bullets in `[startRow, endExclusive)` whose indent is
     * strictly greater than [parentIndent], emitting one [SearchHit]
     * per bullet and recursing into each subtree.
     */
    private suspend fun walkRange(
        entry: FileEntry,
        startRow: Int,
        endExclusive: Int,
        parentIndent: Int,
        parentTitlePathFromRoot: List<String>,
        inFilePath: List<String>,
        visited: MutableSet<String>,
        visit: (SearchHit) -> Unit,
    ) {
        var i = startRow
        while (i < endExclusive) {
            val line = entry.lines[i]
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0) { i++; continue }
            // A bullet at indent <= parentIndent escapes this scope; stop.
            if (indent <= parentIndent) return
            val title = SubtreeCodec.titleOf(line)
            val newPathFromRoot = parentTitlePathFromRoot + title
            val newInFilePath = inFilePath + title
            val ref = entry.promotedByRow[i]
            visit(
                SearchHit(
                    title = title,
                    titlePathFromRoot = newPathFromRoot,
                    fileRel = entry.fileRel,
                    titlePathInFile = newInFilePath,
                    isFileBoundary = ref != null,
                )
            )
            val end = DocumentLayout.subtreeEnd(entry.lines, i, indent)
            if (ref != null) {
                // The bullet is a promoted-ref boundary: its children come
                // from the linked file, not from in-file deeper indents.
                // Visit those children (and grandchildren) by walking the
                // linked file fresh.
                walkFile(
                    fileRel = ref.fileRel,
                    parentTitlePathFromRoot = newPathFromRoot,
                    visited = visited,
                    visit = visit,
                )
            } else {
                // Plain inline bullet — children come from deeper-indent
                // bullets immediately after this row.
                walkRange(
                    entry = entry,
                    startRow = i + 1,
                    endExclusive = end + 1,
                    parentIndent = indent,
                    parentTitlePathFromRoot = newPathFromRoot,
                    inFilePath = newInFilePath,
                    visited = visited,
                    visit = visit,
                )
            }
            i = end + 1
        }
    }

    /** Smallest bullet indent in [entry], or -1 when [entry] has no bullets. */
    private fun topLevelBulletIndent(entry: FileEntry): Int {
        var min = Int.MAX_VALUE
        for (line in entry.lines) {
            val c = DocumentLayout.bulletAsteriskColumn(line)
            if (c >= 0 && c < min) min = c
        }
        return if (min == Int.MAX_VALUE) -1 else min
    }

    // ------------------------------------------------------------ full path

    /**
     * Returns the vault-root-relative title path that points at the
     * bullet at `(fileRel, inFilePath)` *within the current outline
     * tree*, or `null` when [fileRel] is not reachable from the vault
     * root.
     *
     * "Reachable" means: there is a chain of promoted-ref bullets
     * starting at the configured root file that lands on [fileRel].
     * Files outside that tree (e.g. `Starred.md`, hand-authored loose
     * files) currently produce `null` — see the v1 scope decision in
     * the plan.
     *
     * The view layer calls this to convert its
     * `(activeFileRel, inFileTitlePath)` into the absolute path the
     * resolver uses for relative-URL math.
     */
    suspend fun fullPathFor(fileRel: String, inFilePath: List<String>): List<String>? {
        val map = buildFileToFullPath()
        val basePath = map[fileRel] ?: return null
        return basePath + inFilePath
    }

    /**
     * Build (or cheaply rebuild — closed-file entries come from cache,
     * open files from live state) a map from every vault-reachable
     * `fileRel` to the title path of the bullet that hosts that file.
     *
     * For the root file, the host path is the empty list (it sits at
     * the vault root). For each promoted-ref child file, the host path
     * is `parentHost + parentRefTitle`, recursively.
     */
    private suspend fun buildFileToFullPath(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        val visited = HashSet<String>()
        populateFileMap(rootFileName, fileHostPath = emptyList(), out, visited)
        // Augment with loose files: every `.md` file in the vault that
        // wasn't reached by the Home.md → promoted-ref walk above. Their
        // host path is `[basename]` so bullets inside them resolve with
        // the file's title as the leading vault-root segment.
        for (fileRel in listAllMdFiles()) {
            if (fileRel in visited) continue
            populateFileMap(
                fileRel = fileRel,
                fileHostPath = titlePathForLooseFile(fileRel),
                out = out,
                visited = visited,
            )
        }
        return out
    }

    private suspend fun populateFileMap(
        fileRel: String,
        fileHostPath: List<String>,
        out: MutableMap<String, List<String>>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(fileRel)) return
        out[fileRel] = fileHostPath
        val entry = loadFileEntry(fileRel)
        for ((row, ref) in entry.promotedByRow) {
            val line = entry.lines.getOrNull(row) ?: continue
            val title = SubtreeCodec.titleOf(line)
            // Build the title path *in the parent file* up to this row,
            // so the child file's host path is correctly anchored under
            // any inline ancestor bullets in the parent.
            val ancestorTitles = ancestorTitlesUpTo(entry, row)
            val childHost = fileHostPath + ancestorTitles + title
            populateFileMap(ref.fileRel, childHost, out, visited)
        }
    }

    /**
     * Returns the title-path of the inline ancestors of [row] inside
     * [entry], from the file's top down to (but not including) [row].
     * The result is empty when [row] sits at the file's top-level
     * indent.
     */
    private fun ancestorTitlesUpTo(entry: FileEntry, row: Int): List<String> {
        val rowIndent = DocumentLayout.bulletAsteriskColumn(entry.lines[row])
        if (rowIndent < 0) return emptyList()
        val baseline = topLevelBulletIndent(entry)
        if (baseline < 0 || rowIndent <= baseline) return emptyList()
        // Walk up from row collecting strictly-shallower bullets.
        val stack = ArrayDeque<String>()
        var lookingFor = rowIndent - 1
        var r = row - 1
        while (r >= 0 && lookingFor >= baseline) {
            val ind = DocumentLayout.bulletAsteriskColumn(entry.lines[r])
            if (ind in baseline..lookingFor) {
                stack.addFirst(SubtreeCodec.titleOf(entry.lines[r]))
                lookingFor = ind - 1
            }
            r--
        }
        return stack.toList()
    }

    // --------------------------------------------------------------- resolve

    /**
     * Walks [url] starting from the appropriate scope and returns the
     * resulting node, or [Resolution.NotFound] if the walk fails.
     *
     * - When [url].isAbsolute is `true`: starts from the vault root.
     * - Otherwise: starts from the parent of [cursorTitlePath], so a
     *   single-segment relative URL walks among the cursor's siblings.
     *
     * `..` segments walk one level up before consuming further segments.
     */
    suspend fun resolve(
        url: LinkUrl,
        cursorTitlePath: List<String>,
    ): Resolution {
        val strict = resolveStrict(url, cursorTitlePath)
        if (strict is Resolution.Found) return strict
        // Strict walk failed — typically because the URL is under-qualified
        // (was authored when the target had a shorter outline path, or by
        // an earlier modal version that emitted truncated paths). Fall
        // back to a suffix match: if exactly one node in the vault has a
        // full path ending with the URL's segments, treat that as the
        // intended target. Ambiguous (multiple matches) and zero matches
        // both return NotFound — we never silently navigate somewhere
        // unrelated.
        return resolveBySuffixFallback(url)
    }

    /**
     * Deterministic walk implementation. Returns [Resolution.NotFound]
     * the moment any segment fails to match a child in the current
     * scope; the caller layers a suffix-match fallback on top.
     */
    private suspend fun resolveStrict(
        url: LinkUrl,
        cursorTitlePath: List<String>,
    ): Resolution {
        val startPath: List<String> = if (url.isAbsolute) {
            emptyList()
        } else {
            if (cursorTitlePath.isEmpty()) emptyList()
            else cursorTitlePath.dropLast(1)
        }
        var current: WalkPosition = WalkPosition.VaultRoot
        for (seg in startPath) {
            current = childByTitle(current, seg) ?: return Resolution.NotFound
        }
        for (seg in url.segments) {
            current = if (seg == "..") {
                walkUp(current) ?: return Resolution.NotFound
            } else {
                childByTitle(current, seg) ?: return Resolution.NotFound
            }
        }
        return when (val pos = current) {
            is WalkPosition.VaultRoot -> Resolution.NotFound
            // A directory segment with no `.md` file at exactly that
            // path isn't a navigable target; let the suffix fallback
            // try, in case the URL was authored to point at one of the
            // directory's descendants.
            is WalkPosition.DirectoryNode -> Resolution.NotFound
            is WalkPosition.FileRoot -> Resolution.Found(
                fileRel = pos.fileRel,
                titlePathInFile = emptyList(),
            )
            is WalkPosition.Bullet -> Resolution.Found(
                fileRel = pos.fileRel,
                titlePathInFile = pos.inFilePath,
            )
        }
    }

    /**
     * Last-resort resolution: walk every reachable node and pick the
     * one whose full title path *ends* with [url]'s segments. Returns
     * [Resolution.Found] only when exactly one node matches, so an
     * ambiguous URL still surfaces as NotFound rather than navigating
     * somewhere surprising.
     *
     * Used when [resolve]'s deterministic walk fails — usually because
     * the user has reorganized the vault and a previously-correct URL
     * is now under-qualified.
     */
    private suspend fun resolveBySuffixFallback(url: LinkUrl): Resolution {
        if (url.segments.isEmpty()) return Resolution.NotFound
        // A relative URL with `..` segments has its own walk semantics; the
        // suffix match isn't meaningful for those. Only run for paths made
        // entirely of titles.
        if (url.segments.any { it == ".." }) return Resolution.NotFound
        val needle = url.segments
        // Dedupe by `(fileRel, titlePathInFile)` — `enumerateAllNodes`
        // doesn't dedupe (`search()` does that downstream), and a file
        // that has two bullets with the same title (e.g. one inline
        // and one promoted-ref both reading `* Foo`) would otherwise
        // count as two distinct matches even though they resolve to
        // the exact same target via the document-order match rule.
        val seen = HashSet<Pair<String, List<String>>>()
        var unique: SearchHit? = null
        for (hit in enumerateAllNodes()) {
            val full = hit.titlePathFromRoot
            if (full.size < needle.size) continue
            val tail = full.subList(full.size - needle.size, full.size)
            val matches = tail.zip(needle).all { (a, b) -> a.equals(b, ignoreCase = true) }
            if (!matches) continue
            if (!seen.add(hit.fileRel to hit.titlePathInFile)) continue
            if (unique != null) return Resolution.NotFound
            unique = hit
        }
        val only = unique ?: return Resolution.NotFound
        return Resolution.Found(fileRel = only.fileRel, titlePathInFile = only.titlePathInFile)
    }

    /**
     * Position during a title-path walk. A walk-cursor is either the
     * synthetic vault root, an intermediate directory segment, the
     * root of a loose file, or a specific bullet inside a file.
     */
    private sealed class WalkPosition {
        object VaultRoot : WalkPosition()

        /**
         * An intermediate directory segment in a loose-file path that
         * has no `.md` file at exactly this path but does contain
         * deeper loose-file descendants (e.g. `Work/` when the only
         * file is `Work/Framna/Framna.md`). Its children come purely
         * from those deeper paths.
         *
         * @property fullPath Title path from the vault root to this
         *   directory.
         */
        data class DirectoryNode(
            val fullPath: List<String>,
        ) : WalkPosition()

        /**
         * The file root of a loose `.md` file (one not reachable from
         * the configured root via promoted-ref links). Its children
         * are the file's top-level bullets *plus* any deeper loose-file
         * descendants whose path extends this one (so a doubled-name
         * file like `Framna/Framna.md` can host both its own bullets
         * AND a sibling like `Framna/Projects/...`).
         *
         * @property fileRel Vault-relative path of the file.
         * @property fullPath Title path from the vault root to this
         *   file root.
         */
        data class FileRoot(
            val fileRel: String,
            val fullPath: List<String>,
        ) : WalkPosition()

        /**
         * @property fileRel File hosting this bullet.
         * @property rowInFile Bullet's row index inside that file.
         * @property indent Bullet's indent — needed by the in-file
         *   children walk to find rows at strictly greater indent.
         * @property inFilePath Title path from the file's root to here.
         * @property fullPath Title path from the vault root to here.
         */
        data class Bullet(
            val fileRel: String,
            val rowInFile: Int,
            val indent: Int,
            val inFilePath: List<String>,
            val fullPath: List<String>,
        ) : WalkPosition()
    }

    /** Walks from [pos] one level up, or `null` when [pos] is already the vault root. */
    private suspend fun walkUp(pos: WalkPosition): WalkPosition? = when (pos) {
        is WalkPosition.VaultRoot -> null
        is WalkPosition.DirectoryNode -> {
            if (pos.fullPath.size <= 1) WalkPosition.VaultRoot
            else findByFullPath(pos.fullPath.dropLast(1))
        }
        is WalkPosition.FileRoot -> {
            if (pos.fullPath.size <= 1) WalkPosition.VaultRoot
            else findByFullPath(pos.fullPath.dropLast(1))
        }
        is WalkPosition.Bullet -> {
            if (pos.fullPath.size <= 1) WalkPosition.VaultRoot
            else findByFullPath(pos.fullPath.dropLast(1))
        }
    }

    /**
     * Walks the outline tree from the vault root by [path] segments and
     * returns the resulting position, or `null` when the walk fails.
     * Used by [walkUp] to backtrack to a parent scope after a `..` and
     * by [shortestUrlFor]'s relative-URL verification.
     */
    private suspend fun findByFullPath(path: List<String>): WalkPosition? {
        var current: WalkPosition = WalkPosition.VaultRoot
        for (seg in path) {
            current = childByTitle(current, seg) ?: return null
        }
        return current
    }

    /**
     * Returns the child of [pos] whose title matches [title]
     * (case-insensitive, exact). Promoted-ref boundaries are crossed
     * transparently — when [pos] is a promoted-ref bullet, its
     * "children" are the linked file's top-level bullets. The vault
     * root's children include the root file's top-level bullets *and*
     * any loose `.md` files (resolved via [findLooseFileChild]).
     */
    private suspend fun childByTitle(pos: WalkPosition, title: String): WalkPosition? {
        return when (pos) {
            is WalkPosition.VaultRoot -> {
                // Vault root's children = top-level bullets of the root
                // file plus loose files (or intermediate directories) at
                // any depth in the vault dir.
                findTopLevelChild(rootFileName, title, parentFullPath = emptyList())
                    ?: findLooseChild(parentPath = emptyList(), title)
            }
            is WalkPosition.DirectoryNode -> {
                findLooseChild(parentPath = pos.fullPath, title)
            }
            is WalkPosition.FileRoot -> {
                // A loose file root's children are its top-level bullets
                // *and* any deeper loose-file descendants whose host path
                // extends this one (e.g. Framna/Framna.md hosts both its
                // own bullets and Framna/Projects/...).
                findTopLevelChild(pos.fileRel, title, parentFullPath = pos.fullPath)
                    ?: findLooseChild(parentPath = pos.fullPath, title)
            }
            is WalkPosition.Bullet -> {
                val entry = loadFileEntry(pos.fileRel)
                val ref = entry.promotedByRow[pos.rowInFile]
                if (ref != null) {
                    // Children come from the linked file.
                    findTopLevelChild(ref.fileRel, title, parentFullPath = pos.fullPath)
                } else {
                    // Children come from deeper-indent rows immediately after.
                    findInlineChild(entry, pos, title)
                }
            }
        }
    }

    /**
     * Returns a loose-file walk position whose host path is exactly
     * `parentPath + [title]`, or — when no file sits at that exact
     * path but at least one deeper file's host path extends through it
     * — a [WalkPosition.DirectoryNode] for the intermediate directory
     * segment. Returns `null` when nothing is at or below this
     * extended path.
     *
     * The map iteration is per-call (relies on
     * [buildFileToFullPath]'s own cache for amortized cost). The
     * configured root file is excluded — its bullets are vault-root
     * direct children, not a separate FileRoot node.
     */
    private suspend fun findLooseChild(
        parentPath: List<String>,
        title: String,
    ): WalkPosition? {
        val target = parentPath + title
        val map = buildFileToFullPath()
        var fileMatch: String? = null
        var hasDeeperDescendant = false
        for ((fileRel, hostPath) in map) {
            if (fileRel == rootFileName) continue
            if (hostPath.size < target.size) continue
            val prefixMatches = target.indices.all {
                hostPath[it].equals(target[it], ignoreCase = true)
            }
            if (!prefixMatches) continue
            if (hostPath.size == target.size) {
                fileMatch = fileRel
            } else {
                hasDeeperDescendant = true
            }
        }
        return when {
            fileMatch != null -> WalkPosition.FileRoot(
                fileRel = fileMatch,
                fullPath = target,
            )
            hasDeeperDescendant -> WalkPosition.DirectoryNode(fullPath = target)
            else -> null
        }
    }

    /**
     * Returns the top-level bullet of [fileRel] whose title is [title],
     * tagged with the title path that would lead to it from the vault
     * root: `parentFullPath + [matching title]`.
     */
    private suspend fun findTopLevelChild(
        fileRel: String,
        title: String,
        parentFullPath: List<String>,
    ): WalkPosition.Bullet? {
        val entry = loadFileEntry(fileRel)
        val baseline = topLevelBulletIndent(entry)
        if (baseline < 0) return null
        val target = title.lowercase()
        for ((row, line) in entry.lines.withIndex()) {
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent != baseline) continue
            val rowTitle = SubtreeCodec.titleOf(line)
            if (rowTitle.lowercase() != target) continue
            return WalkPosition.Bullet(
                fileRel = entry.fileRel,
                rowInFile = row,
                indent = indent,
                inFilePath = listOf(rowTitle),
                fullPath = parentFullPath + rowTitle,
            )
        }
        return null
    }

    /**
     * Returns the first deeper-indent child of [parent] in its file
     * whose title matches [title]. Walks immediately after the parent
     * row, stops when a same-or-lower-indent row appears.
     */
    private suspend fun findInlineChild(
        entry: FileEntry,
        parent: WalkPosition.Bullet,
        title: String,
    ): WalkPosition.Bullet? {
        val target = title.lowercase()
        val parentIndent = parent.indent
        val parentEnd = DocumentLayout.subtreeEnd(entry.lines, parent.rowInFile, parentIndent)
        // The first bullet at indent > parentIndent immediately under [parent]
        // sets the "child indent". Subsequent siblings share that indent.
        var childIndent: Int = -1
        var row = parent.rowInFile + 1
        while (row <= parentEnd) {
            val line = entry.lines[row]
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0 || indent <= parentIndent) { row++; continue }
            if (childIndent < 0) childIndent = indent
            if (indent != childIndent) { row++; continue }
            val rowTitle = SubtreeCodec.titleOf(line)
            if (rowTitle.lowercase() == target) {
                return WalkPosition.Bullet(
                    fileRel = entry.fileRel,
                    rowInFile = row,
                    indent = indent,
                    inFilePath = parent.inFilePath + rowTitle,
                    fullPath = parent.fullPath + rowTitle,
                )
            }
            row++
        }
        return null
    }

    // -------------------------------------------------------- shortestUrlFor

    /**
     * Returns the URL the modal should embed in a markdown link from
     * the cursor's position to [target]. Picks the shortest form that
     * walks unambiguously back to the same target via [resolve]:
     *
     *  1. **Relative** path from the parent of the cursor's bullet,
     *     using `..` to escape upward when needed. Capped at three
     *     `..` levels — beyond that, the absolute form is shorter and
     *     more legible anyway.
     *  2. **Absolute** path from the vault root.
     *
     * Whichever resolves to [target] *and* has fewer total segments
     * wins; ties go to the relative form.
     */
    suspend fun shortestUrlFor(
        target: SearchHit,
        cursorTitlePath: List<String>,
    ): LinkUrl {
        val targetPath = target.titlePathFromRoot
        val parentPath = if (cursorTitlePath.isEmpty()) emptyList()
                         else cursorTitlePath.dropLast(1)
        // Absolute form is always available.
        val absolute = LinkUrl(targetPath, isAbsolute = true)
        // Relative form: longest common prefix between parentPath and target,
        // then `..` for each remaining cursorParent segment, then the target tail.
        val common = commonPrefixLength(parentPath, targetPath)
        val upCount = parentPath.size - common
        val downSegments = targetPath.subList(common, targetPath.size)
        if (upCount <= MAX_PARENT_HOPS && downSegments.isNotEmpty()) {
            val relativeSegs = ArrayList<String>(upCount + downSegments.size)
            repeat(upCount) { relativeSegs += ".." }
            relativeSegs += downSegments
            val relative = LinkUrl(relativeSegs, isAbsolute = false)
            // Verify the relative URL walks back to the same target.
            val verified = resolve(relative, cursorTitlePath)
            val expectedFile = target.fileRel
            val expectedInFile = target.titlePathInFile
            val matches = verified is Resolution.Found &&
                verified.fileRel == expectedFile &&
                verified.titlePathInFile == expectedInFile
            if (matches) {
                return if (relative.segments.size <= absolute.segments.size) relative
                else absolute
            }
        }
        return absolute
    }

    private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
        var n = 0
        val limit = minOf(a.size, b.size)
        while (n < limit && a[n].equals(b[n], ignoreCase = true)) n++
        return n
    }

}
