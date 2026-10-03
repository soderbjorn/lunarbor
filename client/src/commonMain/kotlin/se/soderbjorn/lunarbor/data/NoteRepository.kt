/*
 * NoteRepository.kt (commonMain)
 * ------------------------------
 * Persistence boundary for Lunarbor, and the only class that touches
 * [FileSystem].
 *
 * ### Storage model: one folder per parent bullet
 *
 *  - A bullet is backed by a folder if and only if it has content: child
 *    bullets, blocks, or files in that folder.
 *  - Every node folder holds one outline file, [OUTLINE_FILE_NAME]
 *    (`_node.md`, plain Markdown), with that node's direct children only
 *    (format: [SubtreeCodec]). The vault root is the root node; its outline
 *    is `<vault>/_node.md`.
 *  - Leaf bullets are lines in their parent's outline file.
 *  - Every folder is a node: a folder without an outline file is a node
 *    with no bullets yet.
 *
 * In memory the editor still works on one flat, indented outline per open
 * document. [loadFile] reads one node file; [loadSubtree] reads a child
 * node for splicing under its folder-backed bullet when the user expands it. [save]
 * takes the flat outline back and, in one pass, compares it with the
 * folders on disk and applies every promotion (leaf gets its first child),
 * demotion (last child removed), rename (title edited), move (subtree
 * indented, outdented, moved or cut and pasted) and trash (folder-backed
 * bullet deleted). The rules are documented on [save].
 *
 * Files that are not `_node.md` outlines (`Starred.md`, other `.md`
 * notes) are loaded and saved as plain lines, exactly as written: no
 * folders, no promotion, no outline file (TRF-7 Markdown mode).
 *
 * Pasted images are written into the folder of the node being edited
 * ([saveImageBytes]); when a row moves to another node, the images it
 * references by bare file name follow it ([moveAttachments]). Image
 * paths are resolved per [ImagePaths].
 *
 * Links (TRF-8) are `lunarbor:` paths ([LunarborLink]). The repository lists what
 * links may point at ([listLinkTargets]), says what is at a path
 * ([kindOf]), rewrites links in a file on disk after renames and moves
 * ([rewriteLinksInFile]) and reports every note text it reads or writes
 * to [noteTextObserver], which keeps `VaultIndex`'s link index current.
 * Starred entries are `* [Label](lunarbor:/…)` bullets in [STARRED_FILE_NAME].
 *
 * Pure parsing/formatting lives in [SubtreeCodec]; folder naming in
 * [FolderName].
 */

package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.main.FolderContents
import se.soderbjorn.lunarbor.main.NoteConversion
import se.soderbjorn.lunarbor.platform.FileSystem
import se.soderbjorn.lunarbor.platform.toNfc
import se.soderbjorn.lunarbor.platform.VaultDirectoryEntry
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What kind of thing a [VaultEntry] is; drives the type glyph in the
 * folder contents list.
 */
enum class VaultEntryKind {
    /** A subfolder: a Lunarbor node folder or a foreign folder. */
    FOLDER,

    /** A `.md` note. */
    MARKDOWN,

    /** An image the renderer can show (extension in [NoteRepository.IMAGE_EXTENSIONS]). */
    IMAGE,

    /**
     * An Excalidraw drawing ([NoteRepository.DRAWING_EXTENSION]): opens in
     * the pane's drawing editor and is saved back as Excalidraw JSON.
     */
    DRAWING,

    /**
     * An HTML page ([NoteRepository.HTML_EXTENSIONS]): shown in the pane
     * as a sandboxed web page, read-only.
     */
    HTML,

    /** Any other file. */
    FILE,
}

/**
 * One entry of a folder listing, as produced by
 * [NoteRepository.listVaultLevel] and filtered for display by
 * `FolderContents.visible`.
 *
 * @property name Display name. For folders, the decoded folder name
 *   ([FolderName.decode]); for files the full basename, extension
 *   included (`Plan.md`).
 * @property pathRel Path relative to the vault root.
 * @property kind Folder, Markdown note, image or other file.
 * @property isReferenced `true` for a folder that a child link of the listed
 *   folder's own outline (`_node.md`) points at — the folder of one of
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

    /** `true` for Excalidraw drawings. */
    val isDrawing: Boolean get() = kind == VaultEntryKind.DRAWING
}

/**
 * The folder backing one folder-backed bullet.
 *
 * @property folderRel The folder's path relative to the vault root, e.g.
 *   `Recipes` or `Recipes/Pasta`. While the bullet sits in the trash (it
 *   was deleted this session) this is its path under `.trash/`.
 * @property keptAt For a deleted bullet whose folder held user files: the
 *   folder's original path, where those files stayed when only its
 *   outline and child bullets went to the trash ([NoteRepository.save]).
 *   Undo merges the trashed parts back into it. `null` otherwise.
 */
data class PromotedRef(val folderRel: String, val keptAt: String? = null) {
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
 * - `DocumentRegistry` for vault listings, images, link targets and
 *   the link rewrite after renames and moves.
 * - The web Starred modal for `Starred.md`.
 *
 * @property rootDirectory Absolute path to the vault root. Public so
 *   platform glue (the web renderer's image-asset URL builder) can resolve
 *   vault-relative paths against the same root. Deliberately has no
 *   default: each platform resolves it (on Electron from `LUNARBOR_VAULT`
 *   / `LUNARBOR_LOCAL_DATA`, falling back to `~/lunarbor-db`).
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
     * Told about the text of every note file (`_node.md` outline or
     * `.md` note) this repository reads or writes, by vault-relative
     * path; `null` text means the file was deleted. `DocumentRegistry`
     * sets it to keep `VaultIndex`'s link index current as files load and
     * save (TRF-8). `null` (the default) observes nothing.
     */
    var noteTextObserver: ((fileRel: String, text: String?) -> Unit)? = null

    private fun observe(fileRel: String, text: String?) {
        if (isOutlineFile(fileRel) || fileRel.endsWith(NOTE_EXTENSION)) noteTextObserver?.invoke(fileRel, text)
    }

    /**
     * Result of [loadFile] / [loadSubtree].
     *
     * @property lines Composed editor lines. Always non-empty for
     *   [loadFile] — an empty outline is a single empty bullet
     *   (`listOf("* ")`, every outline line is a bullet), an empty plain
     *   file `listOf("")`.
     * @property promotedByRow Row in [lines] → the backing folder of each
     *   folder-backed bullet on that row.
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
     *   dead ref passed to [save] whose folder (or, for a folder in
     *   [keptInPlace], whose outline and child bullets) went to the trash,
     *   including folders nested inside another trashed folder.
     * @property keptInPlace Old paths in [trashed] whose folder stayed where
     *   it was because it holds user files; only its outline and child
     *   bullets moved to the trash path.
     * @property trashMoves Every move into the trash this save made, file
     *   by file where a folder was split — what actually moved, for the
     *   link index.
     * @property adoptedItems Row → the items its folder's outline already
     *   listed, for every row that adopted an existing folder in this save
     *   and found bullets there. They were written after the row's own
     *   children; `Document` splices them in the same place.
     */
    data class SaveResult(
        val promotedByRow: Map<Int, PromotedRef>,
        val trashed: Map<String, String> = emptyMap(),
        val keptInPlace: Set<String> = emptySet(),
        val trashMoves: List<PathMove> = emptyList(),
        val adoptedItems: Map<Int, List<NodeLine>> = emptyMap(),
    )

    // ------------------------------------------------------------------ load

    /**
     * The direct children of the node folder [folderRel], as its outline
     * file lists them; empty when the folder has no outline (or no such
     * folder exists — a file path, a missing folder).
     *
     * Called by `DocumentRegistry.requestLinkPreview` to preview a linked
     * node under the link.
     */
    suspend fun nodeItemsOf(folderRel: String): List<NodeLine> {
        val fileRel = outlineFileOf(folderRel)
        val text = fileSystem.readFileIfExists(abs(fileRel)) ?: return emptyList()
        observe(fileRel, text)
        return SubtreeCodec.parseNodeFile(text)
    }

    /**
     * Reads [fileRel] (vault-relative) as editor lines. A `_node.md`
     * outline is parsed per [SubtreeCodec]: bullets at column 0, each
     * folder-backed bullet recorded in [Loaded.promotedByRow]. Any other file is read as
     * plain lines. A missing file reads as an empty document.
     *
     * Children of folder-backed bullets are not followed; `Document` lazy-loads each
     * one through [loadSubtree] when the user expands it.
     */
    suspend fun loadFile(fileRel: String): Loaded {
        fileSystem.ensureDirectory(rootDirectory)
        val text = fileSystem.readFileIfExists(abs(fileRel))
        observe(fileRel, text)
        if (!isOutlineFile(fileRel)) {
            if (text.isNullOrEmpty()) return Loaded(listOf(""), emptyMap())
            return Loaded(text.split("\n"), emptyMap())
        }
        val node = composeNode(folderOfOutline(fileRel), text ?: "", indent = 0)
        return if (node.lines.isEmpty()) Loaded(listOf(EMPTY_OUTLINE_LINE), emptyMap()) else node
    }

    /**
     * Reads the node folder [folderRel]'s outline for splicing under its
     * folder-backed bullet, every line indented to [parentIndent] + 2. A missing
     * outline (a folder with no bullets yet) splices nothing.
     *
     * Called by `Document` when a pane expands a folder-backed bullet.
     *
     * @param folderRel The bullet's backing folder, vault-relative.
     * @param parentIndent Column of the parent bullet's `*`.
     */
    suspend fun loadSubtree(folderRel: String, parentIndent: Int): Loaded {
        val text = fileSystem.readFileIfExists(abs(outlineFileOf(folderRel)))
        observe(outlineFileOf(folderRel), text)
        if (text == null) return Loaded(emptyList(), emptyMap())
        return composeNode(folderRel, text, parentIndent + TAB_SIZE)
    }

    /**
     * Parses one outline file and composes it at [indent].
     *
     * A child link (or folder-backed block) whose folder is missing — moved
     * or deleted outside the app, or left behind by an old bug — loads as
     * a plain leaf: no chevron over children that cannot be loaded, and
     * the next save writes it back as `* title` (or a plain block). Its
     * title is never lost.
     */
    private suspend fun composeNode(folderRel: String, text: String, indent: Int): Loaded =
        composeItems(folderRel, SubtreeCodec.parseNodeFile(text), indent)

    /**
     * [adoptedItems] (a row's [SaveResult.adoptedItems]) composed for
     * splicing under that row, like [loadSubtree] composes a whole outline.
     *
     * Called by `Document` right after the save that adopted [folderRel].
     *
     * @param folderRel The row's backing folder, vault-relative.
     * @param parentIndent Column of the row's `*` (or block marker).
     */
    suspend fun composeAdoptedItems(folderRel: String, adoptedItems: List<NodeLine>, parentIndent: Int): Loaded =
        composeItems(folderRel, adoptedItems, parentIndent + TAB_SIZE)

    /**
     * Composes the parsed outline [items] of the node folder [folderRel]
     * at [indent], keeping only the folder refs whose folder exists (see
     * [composeNode]).
     */
    private suspend fun composeItems(folderRel: String, items: List<NodeLine>, indent: Int): Loaded {
        val composed = SubtreeCodec.composeNodeLines(items, indent)
        if (composed.folderByRow.isEmpty()) return Loaded(composed.lines, emptyMap())
        val dirs = fileSystem.listDirectoryEntries(abs(folderRel)).filter { it.isDirectory }.map { it.name }.toHashSet()
        val refs = composed.folderByRow
            .filterValues { it in dirs }
            .mapValues { (_, name) -> PromotedRef(join(folderRel, name)) }
        return Loaded(composed.lines, refs)
    }

    // ------------------------------------------------------------------ save

    /**
     * Writes one document's composed [lines] back to the vault, applying
     * the storage rules in a single pass:
     *
     *  - **A leaf gets its first child:** create `<name>/_node.md`, move
     *    the children into it, write the parent line as `- title [↳](<name/_node.md>)`.
     *  - **The last child bullet or block is removed:** if the folder holds
     *    nothing else, delete it and write the line back as `* title`; if
     *    it still holds files, keep the folder and the child link. User files
     *    are never deleted.
     *  - **A title is edited:** rename the folder to the newly encoded name
     *    — unless the bullet has no child bullets: then its folder only
     *    holds files, and the bullet lets go of it (becomes a leaf; the
     *    folder keeps its name and files).
     *  - **A subtree is moved** (indent, outdent, move, cut and paste):
     *    `rename` the folder, so attachments travel with it.
     *  - **A folder-backed bullet is deleted** ([deadRefs]): move its
     *    folder to `<vault>/.trash/<timestamp> <name>/` — unless it holds
     *    user files (anything but its outline and its child bullets'
     *    folders, dotfiles aside). Then the folder stays where it is with
     *    those files, and only its outline and child bullets go to the
     *    trash, by the same rule for each child. Passing a ref whose folder
     *    is in the trash as a live row moves it back, merging a split
     *    folder back into its kept files (that is how undo restores it).
     *  - **Empty title with children:** name the folder `Untitled`,
     *    `Untitled (2)`, …; renamed once a title is typed.
     *  - **A bullet is named like a folder no bullet uses** (a foreign
     *    folder, a deleted bullet's kept files; compared ignoring case and
     *    Unicode normalization): a bullet getting its first child adopts
     *    that folder instead of making `<name> (2)`, and so does a leaf
     *    bullet when the folder holds anything. Bullets the folder's
     *    outline already lists stay, after the new children
     *    ([SaveResult.adoptedItems]).
     *
     * Folder names come from [FolderName]; sibling collisions (with other
     * bullets and with anything already on disk) are case-insensitive.
     * Unchanged outline files are not rewritten.
     *
     * Non-outline files (Markdown mode) are written verbatim — the lines
     * joined with `\n`, nothing added or trimmed — and never get a
     * `_node.md` file or folder.
     *
     * @param fileRel The document's file. For an outline, its folder is
     *   the root node of [lines].
     * @param lines The composed outline.
     * @param promotedByRow Rows known to be folder-backed and where their
     *   folder currently is (as returned by the previous load or save).
     * @param unloadedRows Subset of [promotedByRow]'s rows whose children
     *   are *not* in [lines] (a collapsed, never-spliced folder-backed bullet). Their
     *   outline files are left untouched. Any in-memory rows under such a
     *   bullet are appended to its outline rather than dropped.
     * @param deadRefs Folders of folder-backed bullets deleted from [lines] since the
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
            if (fileSystem.readFileIfExists(abs(fileRel)) != body) {
                fileSystem.writeFile(abs(fileRel), body)
                observe(fileRel, body)
            }
            return SaveResult(emptyMap())
        }
        val docFolder = folderOfOutline(fileRel)
        var phaseOpen = false
        fun openPhase() {
            if (!phaseOpen) { phaseOpen = true; onPhaseChange(true) }
        }
        try {
            // Phase 0: undo of a deletion that split a folder — merge the
            // trashed outline and child bullets back into the folder whose
            // files stayed put.
            val restored = HashMap<String, String>()
            for (ref in promotedByRow.values.filter { it.keptAt != null && isInTrash(it.folderRel) }
                .sortedBy { depth(it.folderRel) }) {
                val from = remapUnder(ref.folderRel, restored)
                if (from != ref.folderRel || !pathExists(from)) continue
                openPhase()
                mergeBack(from, ref.keptAt!!)
                restored[from] = ref.keptAt
            }

            // Phase 1: trash deleted folder-backed bullets. Done first so
            // their names are free and the planning below sees the
            // post-trash disk.
            if (deadRefs.isNotEmpty()) openPhase()
            val live = promotedByRow.values.mapTo(HashSet()) { remapUnder(it.folderRel, restored) }
            val trash = trashFolders(deadRefs.map { it.folderRel }, live)

            // Live refs whose folder sat inside a trashed folder travelled
            // with it; follow them there.
            val current = HashMap<Int, String>(promotedByRow.size)
            for ((row, ref) in promotedByRow) {
                current[row] = LunarborLink.remap(remapUnder(ref.folderRel, restored), trash.moves)
                    ?: remapUnder(ref.folderRel, restored)
            }

            // Phase 2: plan.
            val plan = Planner(lines, current, unloadedRows)
            plan.planRoot(docFolder)
            if (plan.moves.isNotEmpty() || plan.demotions.isNotEmpty() || plan.promotions > 0) openPhase()

            // Phase 3: apply. Demoted outlines go first (at their current
            // paths), then the moves, then every write at its new path.
            for (d in plan.demotions) {
                fileSystem.deleteFile(abs(outlineFileOf(d)))
                observe(outlineFileOf(d), null)
            }
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
                observe(outlineFileOf(folder), text)
            }
            for (folder in plan.clearedOutlines) {
                fileSystem.deleteFile(abs(outlineFileOf(folder)))
                observe(outlineFileOf(folder), null)
            }
            for ((folder, extra) in plan.appends) {
                val path = abs(outlineFileOf(folder))
                val existing = fileSystem.readFileIfExists(path) ?: ""
                val merged = SubtreeCodec.formatNodeFile(SubtreeCodec.parseNodeFile(existing) + extra)
                fileSystem.writeFile(path, merged)
                observe(outlineFileOf(folder), merged)
            }
            return SaveResult(
                promotedByRow = plan.assigned.mapValues { (_, folder) -> PromotedRef(folder) },
                trashed = trash.trashed,
                keptInPlace = trash.kept,
                trashMoves = trash.moves,
                adoptedItems = plan.adoptedItems,
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

        /** Row → items already in the outline of the folder it adopted. */
        val adoptedItems = HashMap<Int, List<NodeLine>>()

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
         * @param mayAdopt Whether a new folder-backed bullet may take over
         *   an untracked folder of its name in [cur]. `false` for rows
         *   appended to an unloaded node, whose on-disk bullets' folders
         *   are untracked but not free.
         * @param onDiskRefs Lower-cased names of folders in [cur] that bullets
         *   known only on disk use (an adopted folder's outline); never
         *   adopted.
         */
        private suspend fun planChildren(
            kids: List<ComposedItem>,
            desired: String,
            cur: String?,
            mayAdopt: Boolean = true,
            onDiskRefs: Set<String> = emptySet(),
        ): List<NodeLine> {
            val trimmed = trimTrailingEmpty(kids)
            // Bullets and blocks alike can have children, so either can be
            // folder-backed; "bullet" below means either.
            val bullets = trimmed.filterIsInstance<ComposedItem.Node>()

            // Where each bullet's folder is now, if it has one.
            val curOf = HashMap<Int, String?>()
            for (b in bullets) {
                val tracked = current[b.row]
                curOf[b.row] = if (tracked != null && dirExists(tracked)) tracked else null
            }

            // Adoption: a bullet with no folder yet takes over an untracked
            // folder of its name in this node's folder rather than making
            // `<name> (2)` beside it — when it gets children, or, with none,
            // when the folder holds something (a plain bullet named like a
            // folder of files becomes that folder's node).
            val adopted = HashMap<Int, String>()
            if (cur != null && mayAdopt) {
                val free = listing(cur)
                    .filter { it.isDirectory && join(cur, it.name) !in trackedFolders }
                    .filter { it.name.lowercase() !in onDiskRefs }
                    .associateByTo(HashMap()) { nameKey(it.name) }
                for (b in bullets) {
                    if (curOf[b.row] != null) continue
                    val withChildren = hasContent(b.children)
                    if (!withChildren && (b !is ComposedItem.Bullet || FolderName.plainTextOf(b.title).isBlank())) continue
                    val key = nameKey(FolderName.forTitle(b.title))
                    val dir = free[key] ?: continue
                    if (!withChildren && listing(join(cur, dir.name)).none { !it.name.startsWith(".") }) continue
                    free.remove(key)
                    adopted[b.row] = join(cur, dir.name)
                }
            }

            // Decide which bullets are folder-backed.
            val backed = HashSet<Int>()
            for (b in bullets) {
                val existing = curOf[b.row]
                val isBacked = when {
                    // Folded with its children on disk only: keep the folder,
                    // unless it has gone missing — then there is nothing to keep.
                    existing != null && b.row in unloadedRows -> true
                    hasContent(b.children) -> true
                    b.row in adopted -> true
                    // No child bullets: the bullet stands for the folder's
                    // files only while its title names the folder. Retitled,
                    // it lets go (the folder keeps its files and its name).
                    existing != null -> holdsOtherEntries(existing) &&
                        namesFolder(b.title, existing.substringAfterLast('/'))
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
            for ((row, folder) in adopted) nameOf[row] = folder.substringAfterLast('/')
            // Keep a current name that still fits the title.
            for (b in bullets) {
                if (b.row !in backed || b.row in nameOf) continue
                val existing = curOf[b.row] ?: continue
                if (cur == null || parentOf(existing) != cur) continue
                val name = existing.substringAfterLast('/')
                if (namesFolder(b.title, name) && name.lowercase() !in used) {
                    nameOf[b.row] = name
                    used += name.lowercase()
                }
            }
            // Fresh names for everything else.
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
                    is ComposedItem.Node -> {
                        if (item.row !in backed) {
                            out += when (item) {
                                is ComposedItem.Bullet -> NodeLine.Leaf(item.title)
                                is ComposedItem.Block -> NodeLine.Block(item.content)
                            }
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
                        out += when (item) {
                            is ComposedItem.Bullet -> NodeLine.Folder(item.title, name)
                            is ComposedItem.Block ->
                                NodeLine.Block(item.content, name, FolderName.plainTextOf(item.title))
                        }
                        if (item.row in unloadedRows && existing != null) {
                            if (item.children.isNotEmpty()) {
                                appends += target to planChildren(item.children, target, existing, mayAdopt = false)
                            }
                            continue
                        }
                        val adoptedAt = adopted[item.row]
                        val onDisk = adoptedAt?.let { fileSystem.readFileIfExists(abs(outlineFileOf(it))) }
                            ?.let { SubtreeCodec.parseNodeFile(it) }.orEmpty()
                        val onDiskRefs = onDisk.mapNotNullTo(HashSet()) { line ->
                            when (line) {
                                is NodeLine.Folder -> line.folder.lowercase()
                                is NodeLine.Block -> line.folder?.lowercase()
                                else -> null
                            }
                        }
                        var childLines = planChildren(item.children, target, existing ?: adoptedAt, onDiskRefs = onDiskRefs)
                        if (onDisk.isNotEmpty()) {
                            // The folder's own bullets stay, after the new children.
                            adoptedItems[item.row] = onDisk
                            childLines = childLines + onDisk
                        }
                        if (childLines.isEmpty()) clearedOutlines += target
                        else writes += target to SubtreeCodec.formatNodeFile(childLines)
                    }
                    is ComposedItem.Text -> out += NodeLine.Text(item.text)
                }
            }
            return out
        }

        private fun hasContent(items: List<ComposedItem>): Boolean = trimTrailingEmpty(items).isNotEmpty()

        /**
         * `true` when the folder name [name] is what [title] encodes to, or
         * that with a collision suffix — ignoring case and Unicode
         * normalization (macOS file names are decomposed).
         */
        private fun namesFolder(title: String, name: String): Boolean =
            FolderName.isVariantOf(nameKey(name), nameKey(FolderName.forTitle(title)))

        /** [name] in the form sibling names are compared in: NFC, lower case. */
        private fun nameKey(name: String): String = name.toNfc().lowercase()

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
     * What [trashFolders] did.
     *
     * @property trashed Old folder path → its path under `.trash/`.
     * @property kept Old paths that stayed in place holding user files
     *   (their outline and child bullets went to the trash path).
     * @property moves Every folder or file moved into the trash.
     */
    private class TrashOutcome(
        val trashed: Map<String, String>,
        val kept: Set<String>,
        val moves: List<PathMove>,
    )

    /**
     * Moves each folder in [folders] (deleted bullets' folders) to
     * `.trash/<timestamp> <name>/`. Folders nested inside another listed
     * folder travel with it. Folders already under `.trash/` and folders
     * missing from disk are skipped.
     *
     * A folder that holds user files ([holdsUserFiles]) is not moved:
     * it stays where it is with those files, and only its outline goes to
     * the trash path, plus each child bullet's folder by the same rule —
     * so the trash path mirrors the folder minus the files that stayed.
     *
     * @param live Folders of live bullets. They are never trashed, and a
     *   deleted folder containing one counts as holding user files.
     */
    private suspend fun trashFolders(folders: List<String>, live: Set<String>): TrashOutcome {
        if (folders.isEmpty()) return TrashOutcome(emptyMap(), emptySet(), emptyList())
        val paths = folders.filter { it.isNotEmpty() && !isInTrash(it) }.distinct().sortedBy { it.length }
        val tops = paths.filter { p -> paths.none { q -> q != p && p.startsWith("$q/") } }
        val out = HashMap<String, String>()
        val kept = HashSet<String>()
        val moves = ArrayList<PathMove>()
        val stamp = formatTimestamp(nowMillis())
        for (p in tops) {
            if (!pathExists(p)) continue
            val name = p.substringAfterLast('/')
            val taken = fileSystem.listDirectory(abs(TRASH_DIR)).map { it.lowercase() }.toHashSet()
            val dest = "$TRASH_DIR/" + FolderName.unique("$stamp $name", taken)
            trashNode(p, dest, live, kept, moves)
            out[p] = dest
        }
        for (p in paths) {
            if (p in out) continue
            val top = tops.firstOrNull { p.startsWith("$it/") } ?: continue
            val dest = out[top] ?: continue
            out[p] = dest + p.substring(top.length)
        }
        return TrashOutcome(out, kept, moves)
    }

    /**
     * Trashes the node folder [src] to [dest]: whole when it holds no user
     * files, otherwise its outline plus each child bullet's folder
     * (recursively), leaving [src] and its files in place.
     */
    private suspend fun trashNode(
        src: String,
        dest: String,
        live: Set<String>,
        kept: MutableSet<String>,
        moves: MutableList<PathMove>,
    ) {
        if (!holdsUserFiles(src, live)) {
            println("[autosave]   trash $src -> $dest")
            fileSystem.moveDirectory(abs(src), abs(dest))
            moves += PathMove(src, dest)
            return
        }
        println("[autosave]   trash outline of $src -> $dest (its files stay)")
        kept += src
        val outline = fileSystem.readFileIfExists(abs(outlineFileOf(src)))
        val children = outline?.let { referencedFolderNames(it) }.orEmpty()
        if (outline != null) {
            fileSystem.moveFile(abs(outlineFileOf(src)), abs(outlineFileOf(dest)))
            moves += PathMove(outlineFileOf(src), outlineFileOf(dest))
        }
        for (e in fileSystem.listDirectoryEntries(abs(src))) {
            val child = join(src, e.name)
            if (!e.isDirectory || e.name.lowercase() !in children || child in live) continue
            trashNode(child, join(dest, e.name), live, kept, moves)
        }
    }

    /**
     * `true` when the node folder [folder] holds anything a user put
     * there: a file other than its outline and dotfiles, a folder its
     * outline does not reference, a live bullet's folder, or a child
     * bullet's folder that itself holds user files.
     */
    private suspend fun holdsUserFiles(folder: String, live: Set<String>): Boolean {
        val children = fileSystem.readFileIfExists(abs(outlineFileOf(folder)))
            ?.let { referencedFolderNames(it) }.orEmpty()
        for (e in fileSystem.listDirectoryEntries(abs(folder))) {
            if (e.name == OUTLINE_FILE_NAME || e.name.startsWith(".")) continue
            if (!e.isDirectory) return true
            val child = join(folder, e.name)
            if (child in live || e.name.lowercase() !in children) return true
            if (holdsUserFiles(child, live)) return true
        }
        return false
    }

    /**
     * Undo of a split deletion: moves everything under the trash folder
     * [from] back into [to], where the node's files stayed. Folders
     * present on both sides are merged; a file whose name is taken in
     * [to] meanwhile stays in the trash rather than overwrite it. A
     * missing [to] (removed outside the app) gets the whole folder back.
     */
    private suspend fun mergeBack(from: String, to: String) {
        if (!pathExists(to)) {
            fileSystem.moveDirectory(abs(from), abs(to))
            return
        }
        println("[autosave]   restore $from -> $to")
        val there = fileSystem.listDirectoryEntries(abs(to)).associateBy { it.name.lowercase() }
        for (e in fileSystem.listDirectoryEntries(abs(from))) {
            val src = join(from, e.name)
            val dst = join(to, e.name)
            val clash = there[e.name.lowercase()]
            when {
                clash == null && e.isDirectory -> fileSystem.moveDirectory(abs(src), abs(dst))
                clash == null -> fileSystem.moveFile(abs(src), abs(dst))
                e.isDirectory && clash.isDirectory -> mergeBack(src, dst)
                else -> println("[autosave]   restore: $dst exists, $src stays in the trash")
            }
        }
        fileSystem.deleteDirectoryIfEmpty(abs(from))
    }

    /** `true` when the vault-relative folder [path] exists on disk. */
    private suspend fun pathExists(path: String): Boolean {
        if (path.isEmpty()) return true
        val name = path.substringAfterLast('/')
        return fileSystem.listDirectoryEntries(abs(parentOf(path))).any { it.isDirectory && it.name == name }
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
     * Starts watching the vault for changes made outside the app (a
     * coding agent, Finder, a sync tool) through
     * [FileSystem.watchExternalChanges]. [onChange] receives the changed
     * paths vault-relative; paths outside the vault are dropped.
     *
     * Called once by `DocumentRegistry` at startup.
     */
    fun watchExternalChanges(onChange: (List<String>) -> Unit) {
        val prefix = "$rootDirectory/"
        fileSystem.watchExternalChanges(rootDirectory) { paths ->
            val rel = paths.mapNotNull { p ->
                when {
                    p == rootDirectory -> null
                    p.startsWith(prefix) -> p.substring(prefix.length).trimEnd('/')
                    else -> null
                }
            }.distinct()
            if (rel.isNotEmpty()) onChange(rel)
        }
    }

    // -------------------------------------------------------- vault listing

    /**
     * Every note file in the vault, vault-relative: `.md` files and node
     * outlines (`<folder>/_node.md`), excluding the root outline and
     * anything under a dot-folder (the trash). Used by `VaultIndex` to
     * find nodes and notes not reachable through folder-backed bullets.
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
     * subfolder and every file, except dotfiles (the `.trash` folder,
     * `.DS_Store`, …) and app files ([isAppFile]: the node's own
     * [OUTLINE_FILE_NAME] and the root's `Starred.md`). Each folder is flagged
     * [VaultEntry.isReferenced] when `<dirRel>/_node.md` has a child link
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
            if (!entry.isDirectory && isAppFile(pathRel)) continue
            out += when {
                entry.isDirectory -> VaultEntry(
                    name = FolderName.decode(entry.name),
                    pathRel = pathRel,
                    kind = VaultEntryKind.FOLDER,
                    isReferenced = entry.name.lowercase() in referenced,
                )
                else -> {
                    val kind = kindOfFileName(entry.name)
                    VaultEntry(
                        // The full file name, `.md` included: the folder
                        // contents list shows files as they are on disk.
                        name = entry.name,
                        pathRel = pathRel,
                        kind = kind,
                        lastEditedMs = entry.lastModifiedMs,
                    )
                }
            }
        }
        return out
    }

    /**
     * Lower-cased folder names of every child link (of a bullet or a
     * folder-backed block) in one outline file's [text] — its direct
     * children that are folder-backed.
     */
    private fun referencedFolderNames(text: String): Set<String> =
        SubtreeCodec.parseNodeFile(text)
            .mapNotNull {
                when (it) {
                    is NodeLine.Folder -> it.folder.lowercase()
                    is NodeLine.Block -> it.folder?.lowercase()
                    else -> null
                }
            }
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

    /**
     * Creates the text file [stem]`.`[extension] in the folder [dirRel]
     * holding [text]. The stem is [FolderName]-encoded; a name already
     * taken in the folder (case-insensitively) gets ` (2)`, ` (3)`, …
     * Never overwrites anything. The folder is created when missing.
     *
     * Called by `DocumentRegistry.createFile` for the agent tool
     * `create_file` (`McpTools`).
     *
     * @param stem The name without its extension; not blank.
     * @param extension Without the dot, e.g. `md`.
     * @return The new file's vault-relative path.
     */
    suspend fun createTextFile(dirRel: String, stem: String, extension: String, text: String): String {
        val base = FolderName.encode(stem.trim())
        require(base.isNotEmpty()) { "A file needs a name" }
        fileSystem.ensureDirectory(abs(dirRel))
        val taken = fileSystem.listDirectoryEntries(abs(dirRel)).map { it.name.lowercase() }.toHashSet()
        var name = "$base.$extension"
        var n = 2
        while (name.lowercase() in taken) name = "$base (${n++}).$extension"
        val rel = join(dirRel, name)
        fileSystem.writeFile(abs(rel), text)
        observe(rel, text)
        return rel
    }

    /**
     * Creates an empty Excalidraw drawing ([EMPTY_DRAWING]) named
     * `Untitled.excalidraw` (or `Untitled 2.excalidraw`, …) in [dirRel].
     * Never overwrites anything.
     *
     * Called by `DocumentRegistry.createDrawingFile` for the "New Excalidraw drawing"
     * palette command.
     *
     * @param dirRel An existing folder, vault-relative (`""` = root).
     * @return The new file's vault-relative path.
     */
    suspend fun createDrawingFile(dirRel: String): String {
        val taken = fileSystem.listDirectoryEntries(abs(dirRel)).map { it.name.lowercase() }.toHashSet()
        val rel = join(dirRel, untitledNoteName(taken, DRAWING_EXTENSION))
        fileSystem.writeFile(abs(rel), EMPTY_DRAWING)
        return rel
    }

    /**
     * The raw JSON text of the drawing [fileRel], or `null` when it does
     * not exist. Drawings hold no `lunarbor:` links, so nothing is observed.
     */
    suspend fun readDrawingText(fileRel: String): String? = fileSystem.readFileIfExists(abs(fileRel))

    /**
     * Writes [text] (Excalidraw JSON, verbatim) to the drawing [fileRel].
     * Called by `DocumentRegistry.saveDrawing`.
     */
    suspend fun writeDrawingText(fileRel: String, text: String) {
        fileSystem.writeFile(abs(fileRel), text)
    }

    /**
     * What [convertFolderTree] wrote.
     *
     * @property writtenOutlines Vault-relative paths of every outline file
     *   it created or extended.
     * @property convertedNotes Each `.md` note it copied into a node → that
     *   node's folder. The notes themselves are left in place.
     */
    data class TreeConversion(
        val writtenOutlines: List<String>,
        val convertedNotes: Map<String, String>,
    )

    /**
     * The recursive half of "Convert to node" on a folder: turns everything
     * under [folderRel] into nodes, on disk. In [folderRel] and in every
     * folder below it (dot folders aside):
     *
     * - each subfolder no bullet of that folder's outline points at gets a
     *   child link (every subfolder is walked, referenced or not);
     * - each `.md` note (app files aside) gets a node of its name holding one
     *   block with its Markdown ([NoteConversion.blockContentOf]), in a new
     *   folder named like any bullet's ([FolderName], ` (2)` when taken).
     *
     * New lines go after the bullets an outline already lists, folders
     * first, then notes, each in [FolderContents.naturalCompare] order. The
     * notes, images and other files stay where they are.
     *
     * Called by `DocumentRegistry.convertFolderTree`, which saves open
     * documents first and reloads the ones it touched afterwards.
     *
     * @param folderRel An existing folder, vault-relative, not the root.
     */
    suspend fun convertFolderTree(folderRel: String): TreeConversion {
        val written = ArrayList<String>()
        val notes = LinkedHashMap<String, String>()
        suspend fun writeOutline(folder: String, items: List<NodeLine>) {
            val rel = outlineFileOf(folder)
            val text = SubtreeCodec.formatNodeFile(items)
            fileSystem.writeFile(abs(rel), text)
            observe(rel, text)
            written += rel
        }
        suspend fun walk(dir: String) {
            val entries = fileSystem.listDirectoryEntries(abs(dir)).filter { !it.name.startsWith(".") }
            val outlineText = fileSystem.readFileIfExists(abs(outlineFileOf(dir)))
            val referenced = outlineText?.let { referencedFolderNames(it) }.orEmpty()
            val used = entries.mapTo(HashSet()) { it.name.lowercase() }
            val added = ArrayList<NodeLine>()
            val byName = Comparator<VaultDirectoryEntry> { a, b -> FolderContents.naturalCompare(a.name, b.name) }
            for (sub in entries.filter { it.isDirectory }.sortedWith(byName)) {
                walk(join(dir, sub.name))
                if (sub.name.lowercase() !in referenced) {
                    added += NodeLine.Folder(FolderName.decode(sub.name).toNfc(), sub.name)
                }
            }
            val noteEntries = entries.filter {
                !it.isDirectory && it.name.endsWith(NOTE_EXTENSION) && !isAppFile(join(dir, it.name))
            }
            for (note in noteEntries.sortedWith(byName)) {
                val noteRel = join(dir, note.name)
                val text = fileSystem.readFileIfExists(abs(noteRel)) ?: continue
                val title = displayNameOf(noteRel)
                val name = FolderName.unique(FolderName.forTitle(title), used)
                used += name.lowercase()
                val nodeFolder = join(dir, name)
                fileSystem.ensureDirectory(abs(nodeFolder))
                writeOutline(nodeFolder, listOf(NodeLine.Block(NoteConversion.blockContentOf(noteRel, text))))
                added += NodeLine.Folder(title, name)
                notes[noteRel] = nodeFolder
            }
            if (added.isEmpty()) return
            val existing = outlineText?.let { SubtreeCodec.parseNodeFile(it) }.orEmpty()
            writeOutline(dir, existing + added)
        }
        walk(folderRel)
        return TreeConversion(written, notes)
    }

    /**
     * Where the file [fileRel] goes when it is renamed to [title]: a `.md`
     * note becomes `<title>.md`, an image or drawing ([isFileViewPath])
     * `<title><its own extension>` — a typed extension that matches it is
     * dropped first, any other is part of the name, so a rename never turns
     * a `.png` into something else. Same folder; the title is trimmed and
     * encoded like a folder name ([FolderName.encode], so `Q3/Q4` stays one
     * file name), with ` (2)`, ` (3)`, … when another entry already has
     * that name (case-insensitively; the file itself doesn't count, so a
     * case-only rename keeps its name).
     *
     * Called by `DocumentRegistry.renameFile` before it moves the file.
     *
     * @return The new vault-relative path; [fileRel] itself when the name
     *   would not change; `null` when [title] is blank or [fileRel] is not
     *   a renameable file (an outline, `Starred.md`, anything that is not a
     *   note, image or drawing).
     */
    suspend fun renameTargetOf(fileRel: String, title: String): String? {
        val ownName = fileRel.substringAfterLast('/')
        val ext = when {
            isAppFile(fileRel) -> return null
            fileRel.endsWith(NOTE_EXTENSION) -> NOTE_EXTENSION
            isFileViewPath(fileRel) && ownName.contains('.') -> "." + ownName.substringAfterLast('.')
            else -> return null
        }
        val trimmed = title.trim()
        val bare = if (trimmed.endsWith(ext, ignoreCase = true)) trimmed.dropLast(ext.length).trim() else trimmed
        val stem = FolderName.encode(bare)
        if (stem.isEmpty()) return null
        val dirRel = fileRel.substringBeforeLast('/', missingDelimiterValue = "")
        if ("$stem$ext" == ownName) return fileRel
        val taken = fileSystem.listDirectoryEntries(abs(dirRel))
            .map { it.name.lowercase() }
            .filter { it != ownName.lowercase() }
            .toHashSet()
        // The node's own outline is a `.md` too: a note never takes its name.
        taken += OUTLINE_FILE_NAME.lowercase()
        var name = "$stem$ext"
        var n = 2
        while (name.lowercase() in taken) name = "$stem (${n++})$ext"
        return join(dirRel, name)
    }

    /**
     * Moves the file [fromRel] — a note, image or drawing — to [toRel]
     * (same folder, new name). Called by `Document.renameTo` under its save
     * lock, after a final save, and by `DocumentRegistry.renameFile` for an
     * image or drawing.
     */
    suspend fun moveNote(fromRel: String, toRel: String) {
        fileSystem.moveFile(abs(fromRel), abs(toRel))
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
     * Every image and Excalidraw drawing in the vault, vault-relative,
     * sorted — every node folder and any other folder, skipping dot-folders
     * (the trash). Not cached: saves and pastes change the tree at any
     * time. Used by the Insert Image palette; a drawing picked there is
     * embedded like an image and drawn as a picture of the scene.
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
            else if (isFileViewPath(entry.name)) out += pathRel
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
     * Appends one bookmark bullet, `* [title](lunarbor:/…)`, to
     * [STARRED_FILE_NAME], creating the file when missing. Starred entries
     * use the same `lunarbor:` paths as links (TRF-8), so a save that renames or
     * moves the target rewrites them too. Called by the Starred modal.
     *
     * @param title Label shown in the bookmark list.
     * @param targetPathRel Vault-relative folder or file being starred
     *   (`""` = the vault root).
     */
    suspend fun appendStarredEntry(title: String, targetPathRel: String) {
        fileSystem.ensureDirectory(rootDirectory)
        val absPath = abs(STARRED_FILE_NAME)
        val existing = fileSystem.readFileIfExists(absPath) ?: ""
        val newBullet = SubtreeCodec.formatPlainLinkBullet(indent = 0, label = title, href = LunarborLink.format(targetPathRel))
        val nextContent = when {
            existing.isEmpty() -> newBullet + "\n"
            existing.endsWith("\n") -> existing + newBullet + "\n"
            else -> existing + "\n" + newBullet + "\n"
        }
        fileSystem.writeFile(absPath, nextContent)
        observe(STARRED_FILE_NAME, nextContent)
    }

    /**
     * The text of the vault's privacy modes file ([PrivacyConfig.FILE_NAME]),
     * or `null` when there is none. Called by `DocumentRegistry.loadPrivacyModes`.
     */
    suspend fun readPrivacyConfig(): String? = fileSystem.readFileIfExists(abs(PrivacyConfig.FILE_NAME))

    /**
     * Writes the vault's privacy modes file ([PrivacyConfig.FILE_NAME]).
     * Called by `DocumentRegistry.setPrivacyModes`.
     */
    suspend fun writePrivacyConfig(text: String) {
        fileSystem.ensureDirectory(rootDirectory)
        fileSystem.writeFile(abs(PrivacyConfig.FILE_NAME), text)
    }

    /**
     * Removes every bookmark in [STARRED_FILE_NAME] whose `lunarbor:` target is
     * [targetPathRel]; other lines are kept verbatim. Called by the
     * Starred modal's un-star toggle.
     */
    suspend fun removeStarredEntry(targetPathRel: String) {
        val absPath = abs(STARRED_FILE_NAME)
        val existing = fileSystem.readFileIfExists(absPath) ?: return
        val kept = existing.split("\n").filter { line ->
            val link = SubtreeCodec.parseAnyLinkBullet(line) ?: return@filter true
            LunarborLink.parse(link.url) != targetPathRel
        }
        val text = kept.joinToString("\n")
        fileSystem.writeFile(absPath, text)
        observe(STARRED_FILE_NAME, text)
    }

    // ----------------------------------------------------------------- links

    /**
     * What is at [pathRel] right now: [VaultEntryKind.FOLDER] for a folder
     * (the vault root `""` included), the file's kind for a file, `null`
     * when nothing is there. Names are compared exactly. Used to tell a
     * working link from a broken one and to decide how a click opens it.
     */
    suspend fun kindOf(pathRel: String): VaultEntryKind? = kindsOf(listOf(pathRel))[pathRel]

    /**
     * [kindOf] for many paths at once, listing each parent folder once.
     *
     * @return Every path in [paths] mapped to its kind, or to `null` when
     *   it does not exist.
     */
    suspend fun kindsOf(paths: Collection<String>): Map<String, VaultEntryKind?> {
        val listings = HashMap<String, List<VaultDirectoryEntry>>()
        val out = HashMap<String, VaultEntryKind?>(paths.size)
        for (p in paths) {
            if (p.isEmpty()) { out[p] = VaultEntryKind.FOLDER; continue }
            val parent = parentOf(p)
            val name = p.substringAfterLast('/')
            val entries = listings.getOrPut(parent) { fileSystem.listDirectoryEntries(abs(parent)) }
            val e = entries.firstOrNull { it.name == name }
            out[p] = when {
                e == null -> null
                e.isDirectory -> VaultEntryKind.FOLDER
                else -> kindOfFileName(e.name)
            }
        }
        return out
    }

    /**
     * `true` when [folderRel] is the folder of a folder-backed bullet: its
     * parent's outline has a child link naming it. Such a folder is opened
     * by zooming into that bullet; any other folder opens as a node of its
     * own. `false` for the vault root.
     */
    suspend fun isBulletFolder(folderRel: String): Boolean {
        if (folderRel.isEmpty()) return false
        val outline = fileSystem.readFileIfExists(abs(outlineFileOf(parentOf(folderRel)))) ?: return false
        return folderRel.substringAfterLast('/').lowercase() in referencedFolderNames(outline)
    }

    /**
     * Every folder and file a link may point at (TRF-8), walking the whole
     * vault from the root and skipping dot entries (the trash, outline
     * files): each non-empty folder — one holding any entry, or whose
     * outline has at least one line — and every file. Empty folders are
     * left out; leaf bullets never have a folder, so they cannot appear.
     *
     * A folder's title is its bullet's plain-text title from the parent's
     * outline when it has one (so a collision-suffixed `Soup (2)` still
     * shows the bullet's text), its decoded name otherwise; the vault root
     * is [ROOT_DISPLAY_NAME]. Not cached: `VaultIndex` caches it.
     */
    suspend fun listLinkTargets(): List<LinkTarget> {
        fileSystem.ensureDirectory(rootDirectory)
        val out = ArrayList<LinkTarget>()
        walkLinkTargets("", ROOT_DISPLAY_NAME, emptyList(), out)
        return out
    }

    /**
     * Adds [dirRel] (when non-empty) and everything below it to [out].
     *
     * @param title The folder's display title.
     * @param crumbs Titles of the folder's ancestors, root excluded.
     */
    private suspend fun walkLinkTargets(dirRel: String, title: String, crumbs: List<String>, out: MutableList<LinkTarget>) {
        val entries = fileSystem.listDirectoryEntries(abs(dirRel)).sortedBy { it.name.lowercase() }
        val outline = fileSystem.readFileIfExists(abs(outlineFileOf(dirRel)))
        val items = if (outline == null) emptyList() else SubtreeCodec.parseNodeFile(outline)
        val titleOf = HashMap<String, String>()
        for (item in items) {
            if (item is NodeLine.Folder) titleOf[item.folder.lowercase()] = FolderName.plainTextOf(item.title)
            if (item is NodeLine.Block && item.folder != null) titleOf[item.folder.lowercase()] = item.title
        }
        val visible = entries.filter {
            !it.name.startsWith(".") && !(!it.isDirectory && isAppFile(join(dirRel, it.name)))
        }
        if (items.isEmpty() && visible.isEmpty()) return
        out += LinkTarget(dirRel, title, VaultEntryKind.FOLDER, crumbs)
        val childCrumbs = if (dirRel.isEmpty()) emptyList() else crumbs + title
        for (e in visible) {
            val pathRel = join(dirRel, e.name)
            if (e.isDirectory) {
                val childTitle = titleOf[e.name.lowercase()]?.takeIf { it.isNotBlank() } ?: FolderName.decode(e.name)
                walkLinkTargets(pathRel, childTitle, childCrumbs, out)
            } else {
                val kind = kindOfFileName(e.name)
                val fileTitle = if (kind == VaultEntryKind.MARKDOWN) e.name.removeSuffix(NOTE_EXTENSION) else e.name
                out += LinkTarget(pathRel, fileTitle, kind, childCrumbs)
            }
        }
    }

    /**
     * Every file that can hold `lunarbor:` links, vault-relative: all node
     * outlines (the root's `_node.md` included) and all `.md` notes,
     * outside dot-folders. Used to build `VaultIndex`'s link index.
     */
    suspend fun listLinkBearingFiles(): List<String> {
        val out = ArrayList<String>()
        if (fileSystem.readFileIfExists(abs(OUTLINE_FILE_NAME)) != null) out += OUTLINE_FILE_NAME
        out += listAllNoteFiles()
        return out
    }

    /**
     * Moves the file [fileRel] to `.trash/<timestamp> <name>`, like a
     * deleted bullet's folder. The trash is never emptied automatically.
     *
     * Called through `DocumentRegistry.trashNote` after "Convert to node"
     * when the user chooses to delete the original note.
     *
     * @return The file's vault-relative path in the trash, or `null` when
     *   [fileRel] does not exist or is already in the trash.
     */
    suspend fun trashFile(fileRel: String): String? {
        if (fileRel.isEmpty() || isInTrash(fileRel) || !fileExists(fileRel)) return null
        fileSystem.ensureDirectory(abs(TRASH_DIR))
        val taken = fileSystem.listDirectory(abs(TRASH_DIR)).map { it.lowercase() }.toHashSet()
        val name = fileRel.substringAfterLast('/')
        val dest = "$TRASH_DIR/" + FolderName.unique("${formatTimestamp(nowMillis())} $name", taken)
        fileSystem.moveFile(abs(fileRel), abs(dest))
        return dest
    }

    /**
     * Moves the whole folder [folderRel] to `<vault>/.trash/<timestamp>
     * <name>/`, everything in it included. For folders no bullet points
     * at; a node's folder goes through [save]'s trash rules instead.
     *
     * Called by `DocumentRegistry.trashFolder` (agent `delete`).
     *
     * @return The folder's path under the trash, or `null` when it is the
     *   root, already in the trash, or not there.
     */
    suspend fun trashFolder(folderRel: String): String? {
        if (folderRel.isEmpty() || isInTrash(folderRel) || kindOf(folderRel) != VaultEntryKind.FOLDER) return null
        fileSystem.ensureDirectory(abs(TRASH_DIR))
        val taken = fileSystem.listDirectory(abs(TRASH_DIR)).map { it.lowercase() }.toHashSet()
        val name = folderRel.substringAfterLast('/')
        val dest = "$TRASH_DIR/" + FolderName.unique("${formatTimestamp(nowMillis())} $name", taken)
        fileSystem.moveDirectory(abs(folderRel), abs(dest))
        return dest
    }

    /**
     * Moves the node whose folder is [srcFolder] — an item of its parent's
     * outline — to be an item of the node [dstParent], on disk: its folder
     * moves (renamed after the title, ` (2)` on a clash), its line leaves
     * the old parent's `_node.md` and joins the new one's at
     * [index]. Within one parent this reorders (and, with [newTitle],
     * retitles). Bullets and folder-backed blocks alike.
     *
     * Called by `DocumentRegistry.moveForAgent`, which has saved every open
     * document first and reloads them after.
     *
     * @param index Position among the new parent's items (0 = first),
     *   or `null` / past the end for last.
     * @param newTitle A new title for a bullet; `null` keeps it. Blocks
     *   keep theirs (it is their first line).
     * @return The node's new folder.
     * @throws IllegalArgumentException when [srcFolder] is no item of its
     *   parent, or [dstParent] is inside it.
     */
    suspend fun moveNode(srcFolder: String, dstParent: String, index: Int?, newTitle: String?): String {
        require(srcFolder.isNotEmpty()) { "The root cannot move" }
        require(dstParent != srcFolder && !dstParent.startsWith("$srcFolder/")) { "A node cannot move into itself" }
        val srcParent = srcFolder.substringBeforeLast('/', "")
        val name = srcFolder.substringAfterLast('/')
        val srcItems = nodeItemsOf(srcParent).toMutableList()
        val at = srcItems.indexOfFirst {
            (it is NodeLine.Folder && it.folder.equals(name, true)) || (it is NodeLine.Block && it.folder.equals(name, true))
        }
        require(at >= 0) { "$srcFolder is no item of its parent" }
        val item = srcItems.removeAt(at)
        val title = when (item) {
            is NodeLine.Folder -> newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: item.title
            is NodeLine.Block -> item.title
            else -> error("unreachable")
        }
        val samePlace = dstParent == srcParent
        val taken = fileSystem.listDirectory(abs(dstParent)).map { it.lowercase() }.toHashSet()
        if (samePlace) taken -= name.lowercase()
        val base = FolderName.forTitle(if (item is NodeLine.Block) SubtreeCodec.blockTitleOf(item.content) else title)
        val newName = if (samePlace && FolderName.isVariantOf(name, base)) name else FolderName.unique(base, taken)
        val dstFolder = join(dstParent, newName)
        if (dstFolder != srcFolder) fileSystem.moveDirectory(abs(srcFolder), abs(dstFolder))
        val moved = when (item) {
            is NodeLine.Folder -> NodeLine.Folder(title, newName)
            is NodeLine.Block -> item.copy(folder = newName)
            else -> item
        }
        val dstItems = if (samePlace) srcItems else nodeItemsOf(dstParent).toMutableList()
        dstItems.add((index ?: dstItems.size).coerceIn(0, dstItems.size), moved)
        if (!samePlace) writeOutline(srcParent, srcItems)
        writeOutline(dstParent, dstItems)
        return dstFolder
    }

    /**
     * Moves the file or plain folder (one no bullet points at) [srcRel]
     * into the folder [dstDir], named [newName] or its own name; a taken
     * name gets ` (2)` before the extension.
     *
     * Called by `DocumentRegistry.moveForAgent`.
     *
     * @return The new vault-relative path.
     */
    suspend fun movePath(srcRel: String, dstDir: String, newName: String?): String {
        val isDir = kindOf(srcRel) == VaultEntryKind.FOLDER
        require(!isDir || (dstDir != srcRel && !dstDir.startsWith("$srcRel/"))) { "A folder cannot move into itself" }
        val name = newName?.trim()?.takeIf { it.isNotEmpty() } ?: srcRel.substringAfterLast('/')
        val ext = if (isDir || !name.contains('.')) "" else "." + name.substringAfterLast('.')
        val stem = FolderName.encode(name.removeSuffix(ext))
        val taken = fileSystem.listDirectory(abs(dstDir)).map { it.lowercase() }.toHashSet()
        if (srcRel.substringBeforeLast('/', "") == dstDir) taken -= srcRel.substringAfterLast('/').lowercase()
        var candidate = "$stem$ext"
        var n = 2
        while (candidate.lowercase() in taken) candidate = "$stem (${n++})$ext"
        val dst = join(dstDir, candidate)
        if (dst == srcRel) return dst
        if (isDir) fileSystem.moveDirectory(abs(srcRel), abs(dst)) else fileSystem.moveFile(abs(srcRel), abs(dst))
        return dst
    }

    /** Size in bytes of the file [fileRel], or `null` when there is none. Called by `McpTools` via the registry. */
    suspend fun fileSizeOf(fileRel: String): Long? = fileSystem.fileSize(abs(fileRel))

    /** The file [fileRel]'s bytes, or `null`. Called by `McpTools` via the registry. */
    suspend fun readFileBytes(fileRel: String): ByteArray? = fileSystem.readBinaryIfExists(abs(fileRel))

    /** The file [fileRel] as UTF-8 text, or `null`. Called by `McpTools` via the registry. */
    suspend fun readTextFile(fileRel: String): String? = fileSystem.readFileIfExists(abs(fileRel))

    /** Writes the node [folderRel]'s outline from [items] and feeds the indexes. */
    private suspend fun writeOutline(folderRel: String, items: List<NodeLine>) {
        val rel = outlineFileOf(folderRel)
        val text = SubtreeCodec.formatNodeFile(items)
        fileSystem.ensureDirectory(abs(folderRel))
        fileSystem.writeFile(abs(rel), text)
        observe(rel, text)
    }

    /**
     * Whether the vault file [fileRel] exists. The root outline always
     * counts as existing (a fresh vault loads it empty). Used when a pane
     * reopens at its persisted location after a restart, so a file removed
     * meanwhile falls back to the root instead of being recreated.
     *
     * @param fileRel Vault-relative file path.
     */
    suspend fun fileExists(fileRel: String): Boolean {
        if (fileRel.isEmpty() || fileRel == rootFileName) return true
        val dir = fileRel.substringBeforeLast('/', "")
        val name = fileRel.substringAfterLast('/')
        return runCatching { name in fileSystem.listDirectory(abs(dir)) }.getOrDefault(false)
    }

    /**
     * The raw text of the note file [fileRel], or `null` when it does not
     * exist. Reported to [noteTextObserver] like any other read.
     */
    suspend fun readNoteText(fileRel: String): String? {
        val text = fileSystem.readFileIfExists(abs(fileRel))
        observe(fileRel, text)
        return text
    }

    /**
     * Rewrites the `lunarbor:` links in the file [fileRel] on disk per [moves]
     * ([LunarborLink.rewriteText]), for a file no open document holds. Works on
     * the raw text, so an outline's structure and a note's formatting are
     * untouched.
     *
     * Called by `DocumentRegistry` after a save renamed or moved folders
     * or files.
     *
     * @return `true` when the file was rewritten.
     */
    suspend fun rewriteLinksInFile(fileRel: String, moves: List<PathMove>): Boolean {
        val text = fileSystem.readFileIfExists(abs(fileRel)) ?: return false
        val rewritten = LunarborLink.rewriteText(text, moves)
        if (rewritten == null) {
            observe(fileRel, text)
            return false
        }
        println("[links]   rewrite $fileRel")
        fileSystem.writeFile(abs(fileRel), rewritten)
        observe(fileRel, rewritten)
        return true
    }

    /**
     * Points every image embed in the note file [fileRel] that shows the
     * file [from] at [to] ([ImagePaths.rewriteEmbeds]; every line of an
     * outline or note is stored in the file's own folder) and writes it
     * back when anything changed.
     *
     * Called by `DocumentRegistry.renameFile` for each note file not held
     * by an open document.
     *
     * @return `true` when the file was rewritten.
     */
    suspend fun rewriteImageEmbedsInFile(fileRel: String, from: String, to: String): Boolean {
        val text = fileSystem.readFileIfExists(abs(fileRel)) ?: return false
        if (!text.contains(from.substringAfterLast('/'))) return false
        val folder = fileRel.substringBeforeLast('/', "")
        var changed = false
        val lines = text.split('\n').map { line ->
            ImagePaths.rewriteEmbeds(line, folder, from, to)?.also { changed = true } ?: line
        }
        if (!changed) return false
        val rewritten = lines.joinToString("\n")
        fileSystem.writeFile(abs(fileRel), rewritten)
        observe(fileRel, rewritten)
        return true
    }

    private fun abs(rel: String): String = if (rel.isEmpty()) rootDirectory else "$rootDirectory/$rel"

    companion object {
        /**
         * Name of the outline file every node folder holds: plain Markdown
         * (see `SubtreeCodec`), so it renders in any Markdown viewer. Visible
         * on purpose, so Finder shows what a folder's node contains; the
         * `_` sorts it first and keeps the reserved note name to `_node`.
         */
        const val OUTLINE_FILE_NAME: String = "_node.md"

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

        /** Kind of a file named [name]: Markdown note, image, drawing or other file. */
        fun kindOfFileName(name: String): VaultEntryKind = when {
            name.endsWith(NOTE_EXTENSION) -> VaultEntryKind.MARKDOWN
            isImagePath(name) -> VaultEntryKind.IMAGE
            isDrawingPath(name) -> VaultEntryKind.DRAWING
            isHtmlPath(name) -> VaultEntryKind.HTML
            else -> VaultEntryKind.FILE
        }

        /** Extension of Excalidraw drawings, which panes open in the drawing editor. */
        const val DRAWING_EXTENSION: String = ".excalidraw"

        /** `true` when [pathRel] is an Excalidraw drawing ([DRAWING_EXTENSION], any case). */
        fun isDrawingPath(pathRel: String): Boolean = pathRel.lowercase().endsWith(DRAWING_EXTENSION)

        /** Extensions of HTML pages, which panes show as web pages (case-insensitive). */
        val HTML_EXTENSIONS: List<String> = listOf(".html", ".htm")

        /** `true` when [pathRel] has one of [HTML_EXTENSIONS]. */
        fun isHtmlPath(pathRel: String): Boolean {
            val lower = pathRel.lowercase()
            return HTML_EXTENSIONS.any { lower.endsWith(it) }
        }

        /**
         * `true` when a pane shows [pathRel] without a `Document`: an image
         * (read-only image view), a drawing (drawing editor) or an HTML
         * page (web page view). Such files never go through
         * `DocumentRegistry.acquire`.
         */
        fun isFileViewPath(pathRel: String): Boolean =
            isImagePath(pathRel) || isDrawingPath(pathRel) || isHtmlPath(pathRel)

        /**
         * What a new, empty drawing file holds: an Excalidraw scene with no
         * elements, as Excalidraw itself would export it.
         */
        const val EMPTY_DRAWING: String =
            "{\n  \"type\": \"excalidraw\",\n  \"version\": 2,\n  \"source\": \"lunarbor\",\n" +
                "  \"elements\": [],\n  \"appState\": {},\n  \"files\": {}\n}\n"

        /** `true` when [pathRel] has one of [IMAGE_EXTENSIONS]. */
        fun isImagePath(pathRel: String): Boolean {
            val lower = pathRel.lowercase()
            return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
        }

        /** The only line of an empty outline: a bullet with no text. */
        const val EMPTY_OUTLINE_LINE: String = "* "

        /** `true` when [fileRel] is a node outline ([OUTLINE_FILE_NAME]) file. */
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
            // Decoded, so a note renamed to `Q3/Q4` ([renameTargetOf]) shows
            // as typed; a stray `%` in a hand-made name is kept literally.
            // NFC, because macOS names are decomposed (`o` + U+0308) while
            // typed text and note contents are composed (`ö`).
            return FolderName.decode(fileRel.substringAfterLast('/').removeSuffix(NOTE_EXTENSION)).toNfc()
        }

        /** Base name of notes made by [createMarkdownFile]. */
        const val UNTITLED_NOTE_BASE: String = "Untitled"

        /**
         * First of `Untitled.md`, `Untitled 2.md`, `Untitled 3.md`, … whose
         * lower-cased name is not in [takenLowercase]. [extension] swaps `.md`
         * for another one (`.excalidraw` for [createDrawingFile]).
         */
        fun untitledNoteName(takenLowercase: Set<String>, extension: String = NOTE_EXTENSION): String {
            var n = 1
            while (true) {
                val stem = if (n == 1) UNTITLED_NOTE_BASE else "$UNTITLED_NOTE_BASE $n"
                val candidate = stem + extension
                if (candidate.lowercase() !in takenLowercase) return candidate
                n++
            }
        }

        /**
         * `true` when the file [fileRel] is one Lunarbor keeps for itself
         * (the root's [STARRED_FILE_NAME], the root's privacy modes
         * [PrivacyConfig.FILE_NAME] and every node's outline file,
         * [OUTLINE_FILE_NAME]) rather than user content: it is left out of
         * the folder contents list ([listVaultLevel]) and of link /
         * Navigate to search ([listLinkTargets]). Starred and the outlines
         * stay in the link index so their `lunarbor:` links are still
         * rewritten on moves.
         *
         * @param fileRel Vault-relative file path.
         */
        fun isAppFile(fileRel: String): Boolean =
            fileRel == STARRED_FILE_NAME || fileRel == PrivacyConfig.FILE_NAME || isOutlineFile(fileRel)

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
