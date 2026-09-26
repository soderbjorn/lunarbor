/*
 * NoteRepository.kt (commonMain)
 * ------------------------------
 * Persistence boundary for TreeFacts, and the only class that touches
 * [FileSystem].
 *
 * ### Storage model: one folder per parent bullet
 *
 *  - A bullet is backed by a folder if and only if it has content: child
 *    bullets, blocks, or files in that folder.
 *  - Every node folder holds one hidden outline file, `.treefacts`, with
 *    that node's direct children only (format: [SubtreeCodec]). The vault
 *    root is the root node; its outline is `<vault>/.treefacts`.
 *  - Leaf bullets are lines in their parent's outline file.
 *  - Every folder is a node: a folder without an outline file is a node
 *    with no bullets yet.
 *
 * In memory the editor still works on one flat, indented outline per open
 * document. [loadFile] reads one node file; [loadSubtree] reads a child
 * node for splicing under its `+` bullet when the user expands it. [save]
 * takes the flat outline back and, in one pass, compares it with the
 * folders on disk and applies every promotion (leaf gets its first child),
 * demotion (last child removed), rename (title edited), move (subtree
 * indented, outdented, moved or cut and pasted) and trash (folder-backed
 * bullet deleted). The rules are documented on [save].
 *
 * Files that are not `.treefacts` outlines (`Starred.md`, other `.md`
 * notes) are loaded and saved as plain lines: no folders, no promotion.
 *
 * Pure parsing/formatting lives in [SubtreeCodec]; folder naming in
 * [FolderName].
 */

package se.soderbjorn.treefacts.data

import se.soderbjorn.treefacts.main.DocumentLayout
import se.soderbjorn.treefacts.platform.FileSystem
import se.soderbjorn.treefacts.platform.VaultDirectoryEntry
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One entry in the filesystem-tree footer's lazy-loaded directory listing.
 *
 * @property name Display name. For directories, the decoded folder name
 *   ([FolderName.decode]); for `.md` files the basename minus the
 *   extension; for images the full basename.
 * @property pathRel Path relative to the vault root.
 * @property isDirectory `true` for subdirectories, `false` for files.
 * @property isImage `true` when [pathRel] points at an image file
 *   (extension in [NoteRepository.IMAGE_EXTENSIONS]).
 * @property lastEditedMs Last-modified timestamp of the underlying file in
 *   milliseconds since the Unix epoch. `0` for directories and on platforms
 *   that cannot provide one. Used by the footer's last-edit sort mode.
 */
data class VaultEntry(
    val name: String,
    val pathRel: String,
    val isDirectory: Boolean,
    val isImage: Boolean = false,
    val lastEditedMs: Long = 0L,
)

/**
 * The folder backing one folder-backed bullet.
 *
 * @property folderRel The folder's path relative to the vault root, e.g.
 *   `Recipes` or `Recipes/Pasta`. While the bullet sits in the trash (it
 *   was deleted this session) this is its path under `.trash/`.
 */
data class PromotedRef(val folderRel: String) {
    /** Vault-relative path of the folder's outline file. */
    val fileRel: String get() = NoteRepository.outlineFileOf(folderRel)
}

/**
 * Reads and writes the vault.
 *
 * ### Callers
 * - `Document` for loading, splicing and saving one open outline.
 * - `DocumentRegistry` for vault listings, images and folder stubs.
 * - The web Starred modal for `Starred.md`.
 *
 * @property rootDirectory Absolute path to the vault root. Public so
 *   platform glue (the web renderer's image-asset URL builder) can resolve
 *   vault-relative paths against the same root. Deliberately has no
 *   default: each platform resolves it (on Electron from `TREEFACTS_VAULT`
 *   / `TREEFACTS_LOCAL_DATA`, falling back to `~/treefacts-db`).
 * @property rootFileName The file panes open with; the vault root's
 *   outline, [OUTLINE_FILE_NAME], by default.
 * @param fileSystem Platform (or, in tests, in-memory) filesystem.
 * @param nowMillis Wall clock, used only to stamp trash folder names.
 */
class NoteRepository(
    private val fileSystem: FileSystem,
    val rootDirectory: String,
    val rootFileName: String = OUTLINE_FILE_NAME,
    private val nowMillis: () -> Long = ::systemNowMillis,
) {

    /**
     * Result of [loadFile] / [loadSubtree].
     *
     * @property lines Composed editor lines. Always non-empty for
     *   [loadFile] — an empty document is `listOf("")`.
     * @property promotedByRow Row in [lines] → the backing folder of each
     *   folder-backed (`+`) bullet on that row.
     */
    data class Loaded(
        val lines: List<String>,
        val promotedByRow: Map<Int, PromotedRef>,
    )

    /**
     * Result of [save].
     *
     * @property promotedByRow Row → backing folder of every bullet that is
     *   folder-backed after this save, at its new path. Rows missing here
     *   are leaves.
     * @property trashed Old folder path → path under `.trash/` for every
     *   dead ref passed to [save] whose folder was moved to the trash
     *   (including folders nested inside another trashed folder).
     */
    data class SaveResult(
        val promotedByRow: Map<Int, PromotedRef>,
        val trashed: Map<String, String> = emptyMap(),
    )

    // ------------------------------------------------------------------ load

    /**
     * Reads [fileRel] (vault-relative) as editor lines. A `.treefacts`
     * outline is parsed per [SubtreeCodec]: bullets at column 0, each `+`
     * bullet recorded in [Loaded.promotedByRow]. Any other file is read as
     * plain lines. A missing file reads as an empty document.
     *
     * Children of `+` bullets are not followed; `Document` lazy-loads each
     * one through [loadSubtree] when the user expands it.
     */
    suspend fun loadFile(fileRel: String): Loaded {
        fileSystem.ensureDirectory(rootDirectory)
        val text = fileSystem.readFileIfExists(abs(fileRel))
        if (!isOutlineFile(fileRel)) {
            if (text.isNullOrEmpty()) return Loaded(listOf(""), emptyMap())
            return Loaded(text.split("\n"), emptyMap())
        }
        val node = composeNode(folderOfOutline(fileRel), text ?: "", indent = 0)
        return if (node.lines.isEmpty()) Loaded(listOf(""), emptyMap()) else node
    }

    /**
     * Reads the node folder [folderRel]'s outline for splicing under its
     * `+` bullet, every line indented to [parentIndent] + 2. A missing
     * outline (a folder with no bullets yet) splices nothing.
     *
     * Called by `Document` when a pane expands a folder-backed bullet.
     *
     * @param folderRel The bullet's backing folder, vault-relative.
     * @param parentIndent Column of the parent bullet's `*`.
     */
    suspend fun loadSubtree(folderRel: String, parentIndent: Int): Loaded {
        val text = fileSystem.readFileIfExists(abs(outlineFileOf(folderRel)))
            ?: return Loaded(emptyList(), emptyMap())
        return composeNode(folderRel, text, parentIndent + TAB_SIZE)
    }

    /** Parses one outline file and composes it at [indent]. */
    private fun composeNode(folderRel: String, text: String, indent: Int): Loaded {
        val composed = SubtreeCodec.composeNodeLines(SubtreeCodec.parseNodeFile(text), indent)
        val refs = composed.folderByRow.mapValues { (_, name) -> PromotedRef(join(folderRel, name)) }
        return Loaded(composed.lines, refs)
    }

    // ------------------------------------------------------------------ save

    /**
     * Writes one document's composed [lines] back to the vault, applying
     * the storage rules in a single pass:
     *
     *  - **A leaf gets its first child:** create `<name>/.treefacts`, move
     *    the children into it, write the parent line as `+ [title](name)`.
     *  - **The last child bullet or block is removed:** if the folder holds
     *    nothing else, delete it and write the line back as `* title`; if
     *    it still holds files, keep the folder and the `+` line. User files
     *    are never deleted.
     *  - **A title is edited:** rename the folder to the newly encoded name.
     *  - **A subtree is moved** (indent, outdent, move, cut and paste):
     *    `rename` the folder, so attachments travel with it.
     *  - **A folder-backed bullet is deleted** ([deadRefs]): move its
     *    folder to `<vault>/.trash/<timestamp> <name>/`. Passing a ref
     *    whose folder is in the trash as a live row moves it back (that is
     *    how undo restores it).
     *  - **Empty title with children:** name the folder `Untitled`,
     *    `Untitled (2)`, …; renamed once a title is typed.
     *
     * Folder names come from [FolderName]; sibling collisions (with other
     * bullets and with anything already on disk) are case-insensitive.
     * Unchanged outline files are not rewritten.
     *
     * Non-outline files are written verbatim (trailing empty bullets
     * dropped).
     *
     * @param fileRel The document's file. For an outline, its folder is
     *   the root node of [lines].
     * @param lines The composed outline.
     * @param promotedByRow Rows known to be folder-backed and where their
     *   folder currently is (as returned by the previous load or save).
     * @param unloadedRows Subset of [promotedByRow]'s rows whose children
     *   are *not* in [lines] (a collapsed, never-spliced `+` bullet). Their
     *   outline files are left untouched. Any in-memory rows under such a
     *   bullet are appended to its outline rather than dropped.
     * @param deadRefs Folders of `+` bullets deleted from [lines] since the
     *   last save; moved to the trash.
     * @param onPhaseChange Called with `true` before a save that changes
     *   the folder structure and `false` once it is done. Pure content
     *   saves do not call it.
     */
    suspend fun save(
        fileRel: String,
        lines: List<String>,
        promotedByRow: Map<Int, PromotedRef>,
        unloadedRows: Set<Int> = emptySet(),
        deadRefs: Collection<PromotedRef> = emptyList(),
        onPhaseChange: (Boolean) -> Unit = {},
    ): SaveResult {
        fileSystem.ensureDirectory(rootDirectory)
        if (!isOutlineFile(fileRel)) {
            val body = stripTrailingEmptyBullets(lines).joinToString("\n")
            if (fileSystem.readFileIfExists(abs(fileRel)) != body) fileSystem.writeFile(abs(fileRel), body)
            return SaveResult(emptyMap())
        }
        val docFolder = folderOfOutline(fileRel)
        var phaseOpen = false
        fun openPhase() {
            if (!phaseOpen) { phaseOpen = true; onPhaseChange(true) }
        }
        try {
            // Phase 1: trash deleted folder-backed bullets. Done first so
            // their names are free and the planning below sees the
            // post-trash disk.
            if (deadRefs.isNotEmpty()) openPhase()
            val trashed = trashFolders(deadRefs.map { it.folderRel })

            // Live refs whose folder sat inside a trashed folder travelled
            // with it; follow them there.
            val current = HashMap<Int, String>(promotedByRow.size)
            for ((row, ref) in promotedByRow) current[row] = remapUnder(ref.folderRel, trashed)

            // Phase 2: plan.
            val plan = Planner(lines, current, unloadedRows)
            plan.planRoot(docFolder)
            if (plan.moves.isNotEmpty() || plan.demotions.isNotEmpty() || plan.promotions > 0) openPhase()

            // Phase 3: apply. Demoted outlines go first (at their current
            // paths), then the moves, then every write at its new path.
            for (d in plan.demotions) fileSystem.deleteFile(abs(outlineFileOf(d)))
            applyMoves(plan.moves)
            for (d in plan.demotions.sortedByDescending { depth(it) }) {
                val now = postMovePath(d, plan.moves)
                if (now.isNotEmpty()) fileSystem.deleteDirectoryIfEmpty(abs(now))
            }
            for ((folder, text) in plan.writes) {
                val path = abs(outlineFileOf(folder))
                val onDisk = fileSystem.readFileIfExists(path)
                if (onDisk == text) continue
                // An empty node never creates its outline file.
                if (text.isEmpty() && onDisk == null) continue
                println("[autosave]   write $path")
                fileSystem.writeFile(path, text)
            }
            for (folder in plan.clearedOutlines) fileSystem.deleteFile(abs(outlineFileOf(folder)))
            for ((folder, extra) in plan.appends) {
                val path = abs(outlineFileOf(folder))
                val existing = fileSystem.readFileIfExists(path) ?: ""
                val merged = SubtreeCodec.parseNodeFile(existing) + extra
                fileSystem.writeFile(path, SubtreeCodec.formatNodeFile(merged))
            }
            return SaveResult(
                promotedByRow = plan.assigned.mapValues { (_, folder) -> PromotedRef(folder) },
                trashed = trashed,
            )
        } finally {
            if (phaseOpen) onPhaseChange(false)
        }
    }

    /**
     * One save's plan, computed from the composed outline and the disk as
     * it is after trashing. Reads directories; writes nothing.
     *
     * All "current" folder paths are pre-move paths; all "desired" paths
     * are where things end up.
     *
     * @param lines The composed outline.
     * @param current Row → current folder of every known folder-backed row.
     * @param unloadedRows Rows whose children are not in [lines].
     */
    private inner class Planner(
        private val lines: List<String>,
        private val current: Map<Int, String>,
        private val unloadedRows: Set<Int>,
    ) {
        /** Row → desired folder of every bullet that ends up folder-backed. */
        val assigned = HashMap<Int, String>()

        /** Explicit folder moves; a folder that moves with its parent is not listed. */
        val moves = ArrayList<Pair<String, String>>()

        /** Current paths of folders whose bullet lost its last child. */
        val demotions = ArrayList<String>()

        /** Desired folder → full outline text, for every node whose children are known. */
        val writes = ArrayList<Pair<String, String>>()

        /** Desired folders that stay (they still hold files) but have no bullets. */
        val clearedOutlines = ArrayList<String>()

        /** Desired folder → rows to append to an unloaded node's outline. */
        val appends = ArrayList<Pair<String, List<NodeLine>>>()

        /** Number of bullets newly backed by a folder in this save. */
        var promotions = 0

        /** Every folder currently known to belong to a live bullet. */
        private val trackedFolders: Set<String> = current.values.toHashSet()

        private val listingCache = HashMap<String, List<VaultDirectoryEntry>>()

        private suspend fun listing(folder: String): List<VaultDirectoryEntry> =
            listingCache.getOrPut(folder) { fileSystem.listDirectoryEntries(abs(folder)) }

        private suspend fun dirExists(folder: String): Boolean {
            if (folder.isEmpty()) return true
            val name = folder.substringAfterLast('/')
            return listing(parentOf(folder)).any { it.isDirectory && it.name == name }
        }

        /**
         * `true` when [folder] holds anything other than its outline file
         * and the folders of live bullets (which stay tracked, or are
         * about to move elsewhere).
         */
        private suspend fun holdsOtherEntries(folder: String): Boolean =
            listing(folder).any { e ->
                e.name != OUTLINE_FILE_NAME && join(folder, e.name) !in trackedFolders
            }

        /** Plans the document's root node, which lives at [docFolder]. */
        suspend fun planRoot(docFolder: String) {
            val rootLines = planChildren(SubtreeCodec.parseComposed(lines), docFolder, docFolder)
            writes += docFolder to SubtreeCodec.formatNodeFile(rootLines)
        }

        /**
         * Plans one node's children and returns the node's outline lines.
         *
         * @param kids The node's direct children in the composed outline.
         * @param desired Where the node's folder ends up.
         * @param cur Where the node's folder is now, or `null` when it does
         *   not exist yet.
         */
        private suspend fun planChildren(
            kids: List<ComposedItem>,
            desired: String,
            cur: String?,
        ): List<NodeLine> {
            val trimmed = trimTrailingEmpty(kids)
            val bullets = trimmed.filterIsInstance<ComposedItem.Bullet>()

            // Decide which bullets are folder-backed.
            val curOf = HashMap<Int, String?>()
            val backed = HashSet<Int>()
            for (b in bullets) {
                val tracked = current[b.row]
                val existing = if (tracked != null && dirExists(tracked)) tracked else null
                curOf[b.row] = existing
                val isBacked = when {
                    tracked != null && b.row in unloadedRows -> true
                    hasContent(b.children) -> true
                    existing != null -> holdsOtherEntries(existing)
                    else -> false
                }
                if (isBacked) backed += b.row
                else if (existing != null) demotions += existing
            }

            // Name them. Anything already on disk in this folder that does
            // not belong to a live bullet is reserved.
            val used = HashSet<String>()
            if (cur != null) {
                for (e in listing(cur)) {
                    if (join(cur, e.name) !in trackedFolders) used += e.name.lowercase()
                }
            }
            val nameOf = HashMap<Int, String>()
            // First pass: keep a current name that still fits the title.
            for (b in bullets) {
                if (b.row !in backed) continue
                val existing = curOf[b.row] ?: continue
                if (cur == null || parentOf(existing) != cur) continue
                val name = existing.substringAfterLast('/')
                val base = FolderName.forTitle(b.title)
                if (FolderName.isVariantOf(name, base) && name.lowercase() !in used) {
                    nameOf[b.row] = name
                    used += name.lowercase()
                }
            }
            // Second pass: fresh names for everything else.
            for (b in bullets) {
                if (b.row !in backed || b.row in nameOf) continue
                val name = FolderName.unique(FolderName.forTitle(b.title), used)
                nameOf[b.row] = name
                used += name.lowercase()
            }

            // Record moves and recurse.
            val out = ArrayList<NodeLine>(trimmed.size)
            for (item in trimmed) {
                when (item) {
                    is ComposedItem.Bullet -> {
                        if (item.row !in backed) {
                            out += NodeLine.Leaf(item.title)
                            continue
                        }
                        val name = nameOf.getValue(item.row)
                        val target = join(desired, name)
                        assigned[item.row] = target
                        val existing = curOf[item.row]
                        if (current[item.row] == null) promotions++
                        if (existing != null && existing != target) {
                            val travelsWithParent = cur != null && parentOf(existing) == cur &&
                                existing.substringAfterLast('/') == name
                            if (!travelsWithParent) moves += existing to target
                        }
                        out += NodeLine.Folder(item.title, name)
                        if (item.row in unloadedRows && current[item.row] != null) {
                            if (item.children.isNotEmpty()) {
                                appends += target to planChildren(item.children, target, existing)
                            }
                            continue
                        }
                        val childLines = planChildren(item.children, target, existing)
                        if (childLines.isEmpty()) clearedOutlines += target
                        else writes += target to SubtreeCodec.formatNodeFile(childLines)
                    }
                    is ComposedItem.Block -> out += NodeLine.Block(item.content)
                    is ComposedItem.Text -> out += NodeLine.Text(item.text)
                }
            }
            return out
        }

        private fun hasContent(items: List<ComposedItem>): Boolean = trimTrailingEmpty(items).isNotEmpty()

        /**
         * [SubtreeCodec.trimTrailingEmpty], except that a known
         * folder-backed bullet is never trimmed: its content may be on
         * disk only.
         */
        private fun trimTrailingEmpty(items: List<ComposedItem>): List<ComposedItem> {
            var end = items.size
            while (end > 0) {
                val last = items[end - 1]
                val empty = last is ComposedItem.Bullet && last.title.isBlank() &&
                    last.row !in current && !hasContent(last.children)
                if (!empty) break
                end--
            }
            return if (end == items.size) items else items.subList(0, end)
        }
    }

    /**
     * Moves each folder in [folders] to `.trash/<timestamp> <name>/`.
     * Folders nested inside another listed folder travel with it. Folders
     * already under `.trash/` and folders missing from disk are skipped.
     *
     * @return Old path → trash path, for every folder that is now in the
     *   trash.
     */
    private suspend fun trashFolders(folders: List<String>): Map<String, String> {
        if (folders.isEmpty()) return emptyMap()
        val paths = folders.filter { it.isNotEmpty() && !isInTrash(it) }.distinct().sortedBy { it.length }
        val tops = paths.filter { p -> paths.none { q -> q != p && p.startsWith("$q/") } }
        val out = HashMap<String, String>()
        val stamp = formatTimestamp(nowMillis())
        for (p in tops) {
            val name = p.substringAfterLast('/')
            val exists = fileSystem.listDirectoryEntries(abs(parentOf(p))).any { it.isDirectory && it.name == name }
            if (!exists) continue
            val taken = fileSystem.listDirectory(abs(TRASH_DIR)).map { it.lowercase() }.toHashSet()
            val dest = "$TRASH_DIR/" + FolderName.unique("$stamp $name", taken)
            println("[autosave]   trash $p -> $dest")
            fileSystem.moveDirectory(abs(p), abs(dest))
            out[p] = dest
        }
        for (p in paths) {
            if (p in out) continue
            val top = tops.firstOrNull { p.startsWith("$it/") } ?: continue
            val dest = out[top] ?: continue
            out[p] = dest + p.substring(top.length)
        }
        return out
    }

    /**
     * Applies explicit folder moves without ever renaming onto an occupied
     * path: every mover is first parked in `.trash/.moving/` (deepest
     * first, so a nested mover leaves before its parent does), then placed
     * at its destination (shallowest first, so a destination's parent is
     * in place before the child arrives). Parking inside the trash means a
     * crash mid-save can never lose a folder.
     */
    private suspend fun applyMoves(moves: List<Pair<String, String>>) {
        if (moves.isEmpty()) return
        val taken = fileSystem.listDirectory(abs(MOVING_DIR)).toHashSet()
        var n = 0
        val parked = ArrayList<Pair<String, String>>(moves.size)
        for ((from, to) in moves.sortedByDescending { depth(it.first) }) {
            var slot: String
            do { slot = "m${++n}" } while (slot in taken)
            val park = "$MOVING_DIR/$slot"
            fileSystem.moveDirectory(abs(from), abs(park))
            parked += park to to
        }
        for ((park, to) in parked.sortedBy { depth(it.second) }) {
            println("[autosave]   move -> $to")
            fileSystem.moveDirectory(abs(park), abs(to))
        }
        fileSystem.deleteDirectoryIfEmpty(abs(MOVING_DIR))
    }

    /**
     * Where [path] (a pre-move folder path) is after [moves]: follows the
     * deepest move whose source contains it.
     */
    private fun postMovePath(path: String, moves: List<Pair<String, String>>): String {
        var best: Pair<String, String>? = null
        for (m in moves) {
            if ((path == m.first || path.startsWith(m.first + "/")) &&
                (best == null || m.first.length > best.first.length)
            ) best = m
        }
        val b = best ?: return path
        return b.second + path.substring(b.first.length)
    }

    /** Maps [path] into the trash if it sat inside a folder that [trashed] moved. */
    private fun remapUnder(path: String, trashed: Map<String, String>): String {
        trashed[path]?.let { return it }
        var best: String? = null
        for (k in trashed.keys) {
            if (path.startsWith("$k/") && (best == null || k.length > best.length)) best = k
        }
        val b = best ?: return path
        return trashed.getValue(b) + path.substring(b.length)
    }

    /**
     * Drops trailing empty bullets and blank lines from a plain file's
     * [content], keeping at least one row. Plain files only; outline
     * trimming is structural (see [SubtreeCodec.trimTrailingEmpty]).
     */
    private fun stripTrailingEmptyBullets(content: List<String>): List<String> {
        var end = content.size
        while (end > 0) {
            val last = content[end - 1]
            if (!(last.isBlank() || DocumentLayout.isEmptyBulletLine(last))) break
            end--
        }
        if (end == content.size) return content
        if (end == 0) return listOf("")
        return content.subList(0, end).toList()
    }

    // -------------------------------------------------------- vault listing

    /**
     * Every note file in the vault, vault-relative: `.md` files and node
     * outlines (`<folder>/.treefacts`), excluding the root outline and
     * anything under a dot-folder (the trash). Used by `VaultIndex` to
     * find nodes and notes not reachable through `+` bullets.
     */
    suspend fun listAllNoteFiles(): List<String> {
        fileSystem.ensureDirectory(rootDirectory)
        val out = mutableListOf<String>()
        walkNoteFiles("", out)
        return out
    }

    private suspend fun walkNoteFiles(dirRel: String, out: MutableList<String>) {
        for (entry in fileSystem.listDirectoryEntries(abs(dirRel))) {
            val pathRel = join(dirRel, entry.name)
            if (entry.name == OUTLINE_FILE_NAME && !entry.isDirectory) {
                if (dirRel.isNotEmpty()) out += pathRel
                continue
            }
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) walkNoteFiles(pathRel, out)
            else if (entry.name.endsWith(NOTE_EXTENSION)) out += pathRel
        }
    }

    /**
     * Lists the direct entries under `<rootDirectory>/<dirRel>` for the
     * filesystem-tree footer: subdirectories, `.md` files and images.
     * Dotfiles (the outline file, the trash) are left out. Directories
     * come first; the footer reorders within each group.
     *
     * @param dirRel Directory relative to [rootDirectory]; `""` is the
     *   vault root.
     */
    suspend fun listVaultLevel(dirRel: String): List<VaultEntry> {
        val raw = fileSystem.listDirectoryEntries(abs(dirRel))
        val dirs = ArrayList<VaultEntry>()
        val files = ArrayList<VaultEntry>()
        for (entry in raw) {
            if (entry.name.startsWith(".")) continue
            val pathRel = join(dirRel, entry.name)
            when {
                entry.isDirectory -> dirs += VaultEntry(
                    name = FolderName.decode(entry.name),
                    pathRel = pathRel,
                    isDirectory = true,
                )
                entry.name.endsWith(NOTE_EXTENSION) -> files += VaultEntry(
                    name = entry.name.removeSuffix(NOTE_EXTENSION),
                    pathRel = pathRel,
                    isDirectory = false,
                    lastEditedMs = entry.lastModifiedMs,
                )
                isImagePath(entry.name) -> files += VaultEntry(
                    name = entry.name,
                    pathRel = pathRel,
                    isDirectory = false,
                    isImage = true,
                    lastEditedMs = entry.lastModifiedMs,
                )
            }
        }
        return dirs + files
    }

    // ---------------------------------------------------------------- images

    /**
     * Writes a pasted image into the vault's `Images/` folder and returns
     * the vault-relative path actually written. Name collisions get `-2`,
     * `-3`, … before the extension.
     *
     * @param suggestedName Filename including extension, no path.
     * @param bytes Raw image data.
     */
    suspend fun saveImageBytes(suggestedName: String, bytes: ByteArray): String {
        val imagesAbs = abs(IMAGES_DIR)
        fileSystem.ensureDirectory(imagesAbs)
        val existing = fileSystem.listDirectory(imagesAbs).toSet()
        val finalName = uniqueImageFilename(suggestedName, existing)
        fileSystem.writeBinary("$imagesAbs/$finalName", bytes)
        return "$IMAGES_DIR/$finalName"
    }

    private fun uniqueImageFilename(suggested: String, existing: Set<String>): String {
        if (suggested !in existing) return suggested
        val dot = suggested.lastIndexOf('.')
        val stem = if (dot < 0) suggested else suggested.substring(0, dot)
        val ext = if (dot < 0) "" else suggested.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$stem-$n$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }

    /**
     * Image files directly under `Images/`, vault-relative, sorted.
     * Not cached: saves and pastes change the folder at any time.
     */
    suspend fun listImageFiles(): List<String> =
        fileSystem.listDirectory(abs(IMAGES_DIR))
            .filter { !it.startsWith(".") && isImagePath(it) }
            .map { "$IMAGES_DIR/$it" }
            .sorted()

    // --------------------------------------------------------------- starred

    /**
     * Appends one bookmark bullet to [STARRED_FILE_NAME], creating the
     * file when missing. Called by the Starred modal.
     *
     * @param title Label shown in the bookmark list.
     * @param targetPathRel Vault-relative path of the bookmarked file.
     * @param targetRow Optional 0-based row in that file; encoded as a
     *   `#r=<row>` fragment. `null` bookmarks the whole file.
     */
    suspend fun appendStarredEntry(title: String, targetPathRel: String, targetRow: Int?) {
        fileSystem.ensureDirectory(rootDirectory)
        val absPath = abs(STARRED_FILE_NAME)
        val existing = fileSystem.readFileIfExists(absPath) ?: ""
        val href = if (targetRow != null) "$targetPathRel#r=$targetRow" else targetPathRel
        val newBullet = SubtreeCodec.formatPlainLinkBullet(indent = 0, label = title, href = href)
        val nextContent = when {
            existing.isEmpty() -> newBullet + "\n"
            existing.endsWith("\n") -> existing + newBullet + "\n"
            else -> existing + "\n" + newBullet + "\n"
        }
        fileSystem.writeFile(absPath, nextContent)
    }

    /**
     * Removes every bookmark in [STARRED_FILE_NAME] that points at
     * `(targetPathRel, targetRow)`; other lines are kept verbatim. Called
     * by the Starred modal's un-star toggle.
     */
    suspend fun removeStarredEntry(targetPathRel: String, targetRow: Int?) {
        val absPath = abs(STARRED_FILE_NAME)
        val existing = fileSystem.readFileIfExists(absPath) ?: return
        val rowMarker = "#r="
        val kept = existing.split("\n").filter { line ->
            val link = SubtreeCodec.parseAnyLinkBullet(line) ?: return@filter true
            val url = link.url
            val hashIdx = url.indexOf(rowMarker)
            val (path, row) = if (hashIdx >= 0) {
                val n = url.substring(hashIdx + rowMarker.length).toIntOrNull()
                if (n != null) url.substring(0, hashIdx) to n else url to null
            } else {
                url to null
            }
            !(path == targetPathRel && row == targetRow)
        }
        fileSystem.writeFile(absPath, kept.joinToString("\n"))
    }

    /**
     * Creates an empty file at [fileRel] unless something already exists
     * there. Used by `DocumentRegistry.ensureFolderStub` to give a folder
     * picked in the Insert Link modal its outline file.
     *
     * @return `true` when a new file was written.
     */
    suspend fun createEmptyFile(fileRel: String): Boolean {
        val absPath = abs(fileRel)
        if (fileSystem.readFileIfExists(absPath) != null) return false
        fileSystem.writeFile(absPath, "")
        return true
    }

    private fun abs(rel: String): String = if (rel.isEmpty()) rootDirectory else "$rootDirectory/$rel"

    companion object {
        /** Name of the hidden outline file every node folder holds. */
        const val OUTLINE_FILE_NAME: String = ".treefacts"

        /** Extension of Markdown notes. */
        const val NOTE_EXTENSION: String = ".md"

        /** Vault-relative Starred bookmarks file; a plain (non-outline) file. */
        const val STARRED_FILE_NAME: String = "Starred$NOTE_EXTENSION"

        /** Vault-relative trash folder. Never emptied automatically. */
        const val TRASH_DIR: String = ".trash"

        /** Parking area for folders mid-move; see [applyMoves]. */
        const val MOVING_DIR: String = "$TRASH_DIR/.moving"

        /** Vault-relative folder where pasted images live. */
        const val IMAGES_DIR: String = "Images"

        private const val TAB_SIZE: Int = 2

        /** Image extensions the renderer loads natively (case-insensitive). */
        val IMAGE_EXTENSIONS: List<String> =
            listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg")

        /** `true` when [pathRel] has one of [IMAGE_EXTENSIONS]. */
        fun isImagePath(pathRel: String): Boolean {
            val lower = pathRel.lowercase()
            return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
        }

        /** `true` when [fileRel] is a node outline (`.treefacts`) file. */
        fun isOutlineFile(fileRel: String): Boolean =
            fileRel == OUTLINE_FILE_NAME || fileRel.endsWith("/$OUTLINE_FILE_NAME")

        /** Outline file of the node folder [folderRel] (`""` = vault root). */
        fun outlineFileOf(folderRel: String): String =
            if (folderRel.isEmpty()) OUTLINE_FILE_NAME else "$folderRel/$OUTLINE_FILE_NAME"

        /** Node folder of the outline file [fileRel]; the inverse of [outlineFileOf]. */
        fun folderOfOutline(fileRel: String): String =
            fileRel.removeSuffix(OUTLINE_FILE_NAME).removeSuffix("/")

        /** Display name of the vault root's outline in titles and tabs. */
        const val ROOT_DISPLAY_NAME: String = "Home"

        /**
         * Human-readable name of [fileRel] for titles, tabs and bookmark
         * labels: a node outline shows its decoded folder name (the root
         * outline shows [ROOT_DISPLAY_NAME]); a `.md` note its basename
         * without the extension; anything else its basename.
         */
        fun displayNameOf(fileRel: String): String {
            if (isOutlineFile(fileRel)) {
                val folder = folderOfOutline(fileRel)
                return if (folder.isEmpty()) ROOT_DISPLAY_NAME else FolderName.decode(folder.substringAfterLast('/'))
            }
            return fileRel.substringAfterLast('/').removeSuffix(NOTE_EXTENSION)
        }

        /** `true` when [folderRel] is inside the trash. */
        fun isInTrash(folderRel: String): Boolean =
            folderRel == TRASH_DIR || folderRel.startsWith("$TRASH_DIR/")

        private fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

        private fun parentOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

        private fun depth(path: String): Int = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

        /**
         * Formats [millis] (UTC) as `yyyy-MM-dd HH.mm.ss` for trash folder
         * names. Dots rather than colons, which Finder shows as slashes.
         */
        fun formatTimestamp(millis: Long): String {
            val secs = millis.floorDiv(1000L)
            val days = secs.floorDiv(86_400L)
            val rem = secs - days * 86_400L
            // Civil-from-days (Howard Hinnant).
            val z = days + 719_468L
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097L
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            fun two(v: Long) = v.toString().padStart(2, '0')
            return "$y-${two(m)}-${two(d)} ${two(rem / 3600)}.${two(rem % 3600 / 60)}.${two(rem % 60)}"
        }
    }
}

/** Default wall clock for [NoteRepository]. */
@OptIn(ExperimentalTime::class)
private fun systemNowMillis(): Long = Clock.System.now().toEpochMilliseconds()
