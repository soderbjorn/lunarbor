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
 * notes) are loaded and saved as plain lines, exactly as written: no
 * folders, no promotion, no outline file (TRF-7 Markdown mode).
 *
 * Pasted images are written into the folder of the node being edited
 * ([saveImageBytes]); when a row moves to another node, the images it
 * references by bare file name follow it ([moveAttachments]). Image
 * paths are resolved per [ImagePaths].
 *
 * Pure parsing/formatting lives in [SubtreeCodec]; folder naming in
 * [FolderName].
 */

package se.soderbjorn.treefacts.data

import se.soderbjorn.treefacts.platform.FileSystem
import se.soderbjorn.treefacts.platform.VaultDirectoryEntry
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What kind of thing a [VaultEntry] is; drives the type glyph in the
 * folder contents list.
 */
enum class VaultEntryKind {
    /** A subfolder: a TreeFacts node folder or a foreign folder. */
    FOLDER,

    /** A `.md` note. */
    MARKDOWN,

    /** An image the renderer can show (extension in [NoteRepository.IMAGE_EXTENSIONS]). */
    IMAGE,

    /** Any other file. */
    FILE,
}

/**
 * One entry of a folder listing, as produced by
 * [NoteRepository.listVaultLevel] and filtered for display by
 * `FolderContents.visible`.
 *
 * @property name Display name. For folders, the decoded folder name
 *   ([FolderName.decode]); for `.md` files the basename minus the
 *   extension; for every other file the full basename.
 * @property pathRel Path relative to the vault root.
 * @property kind Folder, Markdown note, image or other file.
 * @property isReferenced `true` for a folder that a `+` line of the listed
 *   folder's own outline (`.treefacts`) points at — the folder of one of
 *   the node's bullets. Such folders are already shown as bullets, so the
 *   contents list hides them. Always `false` for files.
 * @property lastEditedMs Last-modified timestamp of the underlying file in
 *   milliseconds since the Unix epoch. `0` for directories and on platforms
 *   that cannot provide one.
 */
data class VaultEntry(
    val name: String,
    val pathRel: String,
    val kind: VaultEntryKind,
    val isReferenced: Boolean = false,
    val lastEditedMs: Long = 0L,
) {
    /** `true` for subfolders. */
    val isDirectory: Boolean get() = kind == VaultEntryKind.FOLDER

    /** `true` for images. */
    val isImage: Boolean get() = kind == VaultEntryKind.IMAGE
}

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
 * One image file to move with a row that moved to another node
 * ([NoteRepository.moveAttachments]).
 *
 * @property fromFolder Vault-relative folder the row was stored in.
 * @property toFolder Vault-relative folder the row is stored in now.
 * @property name The image's bare file name, as referenced by the row.
 */
data class AttachmentMove(val fromFolder: String, val toFolder: String, val name: String)

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
     *   [loadFile] — an empty outline is a single empty bullet
     *   (`listOf("* ")`, every outline line is a bullet), an empty plain
     *   file `listOf("")`.
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
        return if (node.lines.isEmpty()) Loaded(listOf(EMPTY_OUTLINE_LINE), emptyMap()) else node
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
     * Non-outline files (Markdown mode) are written verbatim — the lines
     * joined with `\n`, nothing added or trimmed — and never get a
     * `.treefacts` file or folder.
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
            val body = lines.joinToString("\n")
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
     * Lists the direct entries under `<rootDirectory>/<dirRel>`: every
     * subfolder and every file, except dotfiles (the `.treefacts` outline,
     * the `.trash` folder, `.DS_Store`, …). Each folder is flagged
     * [VaultEntry.isReferenced] when `<dirRel>/.treefacts` has a `+` line
     * pointing at it (compared case-insensitively, like folder names on a
     * default macOS volume). Unsorted; `FolderContents.visible` filters and
     * orders the entries for display.
     *
     * Called by `DocumentRegistry` to fill and refresh its shared
     * listings cache.
     *
     * @param dirRel Directory relative to [rootDirectory]; `""` is the
     *   vault root.
     */
    suspend fun listVaultLevel(dirRel: String): List<VaultEntry> {
        val raw = fileSystem.listDirectoryEntries(abs(dirRel))
        val outline = fileSystem.readFileIfExists(abs(outlineFileOf(dirRel)))
        val referenced = if (outline == null) emptySet() else referencedFolderNames(outline)
        val out = ArrayList<VaultEntry>(raw.size)
        for (entry in raw) {
            if (entry.name.startsWith(".")) continue
            val pathRel = join(dirRel, entry.name)
            out += when {
                entry.isDirectory -> VaultEntry(
                    name = FolderName.decode(entry.name),
                    pathRel = pathRel,
                    kind = VaultEntryKind.FOLDER,
                    isReferenced = entry.name.lowercase() in referenced,
                )
                entry.name.endsWith(NOTE_EXTENSION) -> VaultEntry(
                    name = entry.name.removeSuffix(NOTE_EXTENSION),
                    pathRel = pathRel,
                    kind = VaultEntryKind.MARKDOWN,
                    lastEditedMs = entry.lastModifiedMs,
                )
                isImagePath(entry.name) -> VaultEntry(
                    name = entry.name,
                    pathRel = pathRel,
                    kind = VaultEntryKind.IMAGE,
                    lastEditedMs = entry.lastModifiedMs,
                )
                else -> VaultEntry(
                    name = entry.name,
                    pathRel = pathRel,
                    kind = VaultEntryKind.FILE,
                    lastEditedMs = entry.lastModifiedMs,
                )
            }
        }
        return out
    }

    /**
     * Lower-cased folder names of every `+` line in one outline file's
     * [text] — its direct children that are folder-backed.
     */
    private fun referencedFolderNames(text: String): Set<String> =
        SubtreeCodec.parseNodeFile(text)
            .filterIsInstance<NodeLine.Folder>()
            .map { it.folder.lowercase() }
            .toHashSet()

    /**
     * Creates an empty Markdown note in the folder [dirRel], named
     * `Untitled.md`, or `Untitled 2.md`, `Untitled 3.md`, … when that name
     * is taken (compared case-insensitively against everything in the
     * folder). Never overwrites anything.
     *
     * Called by `DocumentRegistry.createMarkdownFile` for the "New
     * Markdown file" palette command.
     *
     * @param dirRel An existing folder, vault-relative (`""` = root).
     * @return The new file's vault-relative path.
     */
    suspend fun createMarkdownFile(dirRel: String): String {
        val taken = fileSystem.listDirectoryEntries(abs(dirRel)).map { it.name.lowercase() }.toHashSet()
        val name = untitledNoteName(taken)
        val rel = join(dirRel, name)
        fileSystem.writeFile(abs(rel), "")
        return rel
    }

    // ---------------------------------------------------------------- images

    /**
     * Writes a pasted image into the folder [dirRel] — the folder of the
     * node being edited, or of the `.md` note — and returns the
     * vault-relative path actually written. Name collisions (with any
     * entry in the folder, case-insensitively) get `-2`, `-3`, … before
     * the extension.
     *
     * Called by `DocumentRegistry.saveImageBytes` for a paste or drop.
     *
     * @param dirRel Vault-relative folder (`""` = vault root). Created if
     *   missing.
     * @param suggestedName Filename including extension, no path.
     * @param bytes Raw image data.
     */
    suspend fun saveImageBytes(dirRel: String, suggestedName: String, bytes: ByteArray): String {
        val dirAbs = abs(dirRel)
        fileSystem.ensureDirectory(dirAbs)
        val existing = fileSystem.listDirectory(dirAbs).map { it.lowercase() }.toSet()
        val finalName = uniqueImageFilename(suggestedName, existing)
        val rel = join(dirRel, finalName)
        fileSystem.writeBinary(abs(rel), bytes)
        return rel
    }

    private fun uniqueImageFilename(suggested: String, existingLowercase: Set<String>): String {
        if (suggested.lowercase() !in existingLowercase) return suggested
        val dot = suggested.lastIndexOf('.')
        val stem = if (dot < 0) suggested else suggested.substring(0, dot)
        val ext = if (dot < 0) "" else suggested.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$stem-$n$ext"
            if (candidate.lowercase() !in existingLowercase) return candidate
            n++
        }
    }

    /**
     * Every image in the vault, vault-relative, sorted — every node
     * folder and any other folder, skipping dot-folders (the trash).
     * Not cached: saves and pastes change the tree at any time. Used by
     * the Insert Image palette.
     */
    suspend fun listImageFiles(): List<String> {
        val out = ArrayList<String>()
        walkImages("", out)
        return out.sorted()
    }

    private suspend fun walkImages(dirRel: String, out: MutableList<String>) {
        for (entry in fileSystem.listDirectoryEntries(abs(dirRel))) {
            if (entry.name.startsWith(".")) continue
            val pathRel = join(dirRel, entry.name)
            if (entry.isDirectory) walkImages(pathRel, out)
            else if (isImagePath(entry.name)) out += pathRel
        }
    }

    /**
     * Moves image files that belong to rows which moved to another
     * node's folder. Each move is `fromFolder/name → toFolder/name`
     * (vault-relative folders, bare file names). A move is skipped when
     * the source is gone (it already moved, e.g. with its folder) or the
     * destination name is taken — the file is never overwritten.
     *
     * Called by `Document` after a save, for rows whose storage folder
     * changed ([ImagePaths.isFolderLocal] images only).
     *
     * @return The moves that were applied.
     */
    suspend fun moveAttachments(moves: List<AttachmentMove>): List<AttachmentMove> {
        val applied = ArrayList<AttachmentMove>()
        val listings = HashMap<String, Set<String>>()
        suspend fun namesIn(folder: String): Set<String> = listings.getOrPut(folder) {
            fileSystem.listDirectoryEntries(abs(folder)).filter { !it.isDirectory }.map { it.name }.toHashSet()
        }
        for (m in moves) {
            if (m.fromFolder == m.toFolder) continue
            if (m.name !in namesIn(m.fromFolder)) continue
            val taken = namesIn(m.toFolder).any { it.equals(m.name, ignoreCase = true) }
            if (taken) {
                println("[autosave]   keep ${m.fromFolder}/${m.name}: ${m.toFolder} already has one")
                continue
            }
            fileSystem.moveFile(abs(join(m.fromFolder, m.name)), abs(join(m.toFolder, m.name)))
            listings[m.fromFolder] = namesIn(m.fromFolder) - m.name
            listings[m.toFolder] = namesIn(m.toFolder) + m.name
            applied += m
        }
        return applied
    }

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

        private const val TAB_SIZE: Int = 2

        /** Image extensions the renderer loads natively (case-insensitive). */
        val IMAGE_EXTENSIONS: List<String> =
            listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg")

        /** `true` when [pathRel] has one of [IMAGE_EXTENSIONS]. */
        fun isImagePath(pathRel: String): Boolean {
            val lower = pathRel.lowercase()
            return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
        }

        /** The only line of an empty outline: a bullet with no text. */
        const val EMPTY_OUTLINE_LINE: String = "* "

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

        /** Base name of notes made by [createMarkdownFile]. */
        const val UNTITLED_NOTE_BASE: String = "Untitled"

        /**
         * First of `Untitled.md`, `Untitled 2.md`, `Untitled 3.md`, … whose
         * lower-cased name is not in [takenLowercase].
         */
        fun untitledNoteName(takenLowercase: Set<String>): String {
            var n = 1
            while (true) {
                val stem = if (n == 1) UNTITLED_NOTE_BASE else "$UNTITLED_NOTE_BASE $n"
                val candidate = stem + NOTE_EXTENSION
                if (candidate.lowercase() !in takenLowercase) return candidate
                n++
            }
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
