/*
 * VaultIndex.kt
 * -------------
 * App-scoped, lazy outline index used by the Insert Link feature.
 *
 * Notegrow's "vault" is a tree of `.md` files connected by promoted-ref
 * markdown links (`[Title](path#notegrow)`). Logically, all of those files
 * compose into a single outline tree rooted at `Root.md`: a file's
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
 * Notegrow autosaves continuously; a cache that invalidated on every
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

package se.soderbjorn.notegrow.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.soderbjorn.notegrow.main.Document
import se.soderbjorn.notegrow.main.DocumentLayout

/**
 * Lazy, app-scoped index over the vault's outline tree.
 *
 * The vault root's logical children are:
 *
 *  1. The configured root file's top-level bullets (Root.md
 *     contributes its bullets directly into the vault root, so a
 *     bullet "Recipes" at Root.md's top level is reachable as
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
 *   Root.md → promoted-ref walk doesn't reach. Production callers pass
 *   `repository::listAllMdFiles`; tests pass a list of known fakes.
 * @param rootFileName Vault-relative path of the configured root file
 *   — typically `Root.md`. The vault root's children are this file's
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
    data class SearchHit(
        val title: String,
        val titlePathFromRoot: List<String>,
        val fileRel: String,
        val titlePathInFile: List<String>,
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
        val scored = ArrayList<Pair<Int, SearchHit>>(all.size)
        for (hit in all) {
            val title = hit.title.lowercase()
            val pos = title.indexOf(needle)
            if (pos < 0) continue
            // Lower scores rank higher. Position dominates; length tiebreaks.
            val score = pos * 1_000 + (title.length - needle.length).coerceAtLeast(0)
            scored += score to hit
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortBy { it.first }
        return scored.asSequence().map { it.second }.take(max).toList()
    }

    /**
     * DFS the outline tree starting from the vault root, collecting
     * every reachable node into a flat list. Promoted-ref boundaries
     * are followed transparently; cycles (a malformed vault that links
     * back to itself) are broken by a per-walk visited set on `fileRel`.
     *
     * After the Root.md walk, every remaining `.md` file in the vault
     * is enumerated as a loose file: its file root is emitted as a
     * single hit (title = basename, fileRel = its path,
     * titlePathInFile = empty), then its bullets are walked under
     * that title.
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
        for (fileRel in listAllMdFiles()) {
            if (fileRel in visited) continue
            if (fileRel == rootFileName) continue
            val title = fileTitleFromPath(fileRel)
            out += SearchHit(
                title = title,
                titlePathFromRoot = listOf(title),
                fileRel = fileRel,
                titlePathInFile = emptyList(),
            )
            walkFile(
                fileRel = fileRel,
                parentTitlePathFromRoot = listOf(title),
                visited = visited,
                visit = { out += it },
            )
        }
        return out
    }

    /** Filename basename without the `.md` extension; used as a loose file's title. */
    private fun fileTitleFromPath(fileRel: String): String =
        fileRel.substringAfterLast('/').removeSuffix(".md")

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
            visit(
                SearchHit(
                    title = title,
                    titlePathFromRoot = newPathFromRoot,
                    fileRel = entry.fileRel,
                    titlePathInFile = newInFilePath,
                )
            )
            val ref = entry.promotedByRow[i]
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
        // wasn't reached by the Root.md → promoted-ref walk above. Their
        // host path is `[basename]` so bullets inside them resolve with
        // the file's title as the leading vault-root segment.
        for (fileRel in listAllMdFiles()) {
            if (fileRel in visited) continue
            populateFileMap(
                fileRel = fileRel,
                fileHostPath = listOf(fileTitleFromPath(fileRel)),
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
        val startPath: List<String> = if (url.isAbsolute) {
            emptyList()
        } else {
            // Parent of the cursor's bullet: drop the last segment.
            // Empty when the cursor is at file root or has no bullet path.
            if (cursorTitlePath.isEmpty()) emptyList()
            else cursorTitlePath.dropLast(1)
        }
        // Walk from vault root to startPath to anchor ourselves.
        var current: WalkPosition? = WalkPosition.VaultRoot
        for (seg in startPath) {
            current = childByTitle(current!!, seg) ?: return Resolution.NotFound
        }
        // Apply each URL segment.
        for (seg in url.segments) {
            current = if (seg == "..") {
                walkUp(current!!) ?: return Resolution.NotFound
            } else {
                childByTitle(current!!, seg) ?: return Resolution.NotFound
            }
        }
        return when (val pos = current!!) {
            is WalkPosition.VaultRoot -> Resolution.NotFound
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
     * Position during a title-path walk. A walk-cursor is either the
     * synthetic vault root, the root of a loose file, or a specific
     * bullet inside a file.
     */
    private sealed class WalkPosition {
        object VaultRoot : WalkPosition()

        /**
         * The file root of a loose `.md` file (one not reachable from
         * the configured root via promoted-ref links). Its children
         * are the file's top-level bullets.
         *
         * @property fileRel Vault-relative path of the file.
         * @property fullPath Title path from the vault root to this
         *   file root (a single segment, the file's basename).
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
        is WalkPosition.FileRoot -> WalkPosition.VaultRoot
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
                // file plus loose files at any depth in the vault dir.
                findTopLevelChild(rootFileName, title, parentFullPath = emptyList())
                    ?: findLooseFileChild(title)
            }
            is WalkPosition.FileRoot -> {
                // Loose file root's children = its top-level bullets.
                findTopLevelChild(pos.fileRel, title, parentFullPath = pos.fullPath)
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
     * Returns a loose-file [WalkPosition.FileRoot] whose basename
     * matches [title] (case-insensitive). "Loose" = present in
     * [listAllMdFiles] output but not reachable from the configured
     * root via promoted-ref links. The configured root file itself is
     * never returned here — its bullets are the vault root's direct
     * children, not a separate FileRoot node.
     */
    private suspend fun findLooseFileChild(title: String): WalkPosition.FileRoot? {
        val target = title.lowercase()
        val map = buildFileToFullPath()
        for ((fileRel, hostPath) in map) {
            if (fileRel == rootFileName) continue
            // Loose files have host path of length 1 (just the file's
            // own title) — promoted children have longer paths.
            if (hostPath.size != 1) continue
            if (hostPath[0].lowercase() == target) {
                return WalkPosition.FileRoot(fileRel = fileRel, fullPath = hostPath)
            }
        }
        return null
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

    companion object {
        /**
         * Cap on how many `..` segments [shortestUrlFor] is willing to
         * emit before giving up on the relative form. Beyond three, the
         * absolute path is usually shorter *and* easier for a human to
         * read.
         */
        private const val MAX_PARENT_HOPS: Int = 3
    }
}
