/*
 * NoteRepository.kt
 * -----------------
 * Persistence boundary for Notegrow. The in-memory model is a single flat
 * outline (`lines: List<String>`), but on disk that outline is split across
 * a tree of `.nogr` files connected by `[[<Title>/<Title>.nogr]]` references
 * — see `auto-promote-plan.md` for the full layout. This class owns the
 * splitting/composition: `load()` reads the disk tree and returns one flat
 * outline plus a row→dirRel map; `save()` accepts the flat outline plus the
 * map and writes the disk tree, applying [PromotionPolicy] to decide which
 * subtrees should be promoted, demoted, or live-renamed.
 *
 * Pure split/compose helpers live in [SubtreeCodec]. The repository is the
 * only place that touches [FileSystem].
 */

package se.soderbjorn.notegrow.data

import se.soderbjorn.notegrow.main.DocumentLayout
import se.soderbjorn.notegrow.platform.FileSystem

/**
 * @property fileSystem Platform filesystem used for all I/O.
 * @property rootDirectory Absolute directory under which `root.nogr` and the
 *   nested `<Title>/<Title>.nogr` tree live.
 * @property rootFileName Filename of the top-level outline. Defaults to
 *   `root.nogr`.
 */
class NoteRepository(
    private val fileSystem: FileSystem,
    private val rootDirectory: String = DEFAULT_DIRECTORY,
    private val rootFileName: String = DEFAULT_FILE_NAME,
) {
    private val rootBasename: String = rootFileName.removeSuffix(".nogr").ifEmpty { "root" }

    /**
     * Result of [load]: the composed flat outline plus the row→dirRel map
     * the document VM needs to keep auto-promotion idempotent across saves.
     *
     * @property lines One entry per logical line of the composed outline.
     *   Always non-empty — an empty document is `listOf("")`.
     * @property promotedByRow Maps row index in [lines] to the directory
     *   (relative to [rootDirectory]) that holds the promoted subtree's child
     *   file. The file itself lives at `<rootDirectory>/<dirRel>/<basename>.nogr`
     *   where `basename` is the last segment of `dirRel`.
     */
    data class Loaded(val lines: List<String>, val promotedByRow: Map<Int, String>)

    /**
     * Reads only `root.nogr` and returns its content with every `[[…]]`
     * reference left verbatim. Children files are not followed; the document
     * VM lazy-loads each subtree via [loadSubtree] when the user expands the
     * corresponding bullet.
     *
     * Missing referenced files are tolerated and not registered in
     * [Loaded.promotedByRow] — they appear to the user as plain `[[ref]]`
     * lines and the chevron click will simply produce an empty subtree.
     *
     * @return [Loaded] where every entry in [Loaded.promotedByRow] is a row
     *   whose bullet line still contains the verbatim `[[ref]]` token. The
     *   document VM uses these to recognise file boundaries on expand.
     */
    suspend fun loadRoot(): Loaded {
        fileSystem.ensureDirectory(rootDirectory)
        val rootText = fileSystem.readFileIfExists("$rootDirectory/$rootFileName")
        if (rootText.isNullOrEmpty()) return Loaded(listOf(""), emptyMap())
        return parseFileShallow(directoryRel = "", fileText = rootText)
    }

    /**
     * Loads the file at `<rootDirectory>/<directoryRel>/<basename>.nogr`
     * (where `basename` is the last segment of [directoryRel]) without
     * recursing into nested `[[…]]` references. The returned [Loaded.lines]
     * are reindented by [parentIndent] + [TAB_SIZE] so they slot under the
     * parent bullet at the correct depth in the composed outline.
     *
     * Used by [se.soderbjorn.notegrow.main.DocumentBackingViewModel] when
     * the user expands a previously-collapsed reference bullet.
     *
     * @param directoryRel Directory of the child file, relative to
     *   [rootDirectory]. `"foo/bar"` resolves to
     *   `<rootDirectory>/foo/bar/bar.nogr`.
     * @param parentIndent The bullet column of the parent reference row in
     *   the composed outline. The child file's lines are deepened by
     *   `parentIndent + TAB_SIZE` so the topmost child sits one indent step
     *   below its parent.
     * @return Empty [Loaded] (`listOf("")`, no promoted rows) when the file
     *   is absent — broken refs degrade to a no-op expand.
     */
    suspend fun loadSubtree(directoryRel: String, parentIndent: Int): Loaded {
        if (directoryRel.isEmpty()) return Loaded(listOf(""), emptyMap())
        val basename = directoryRel.substringAfterLast('/')
        val absChildPath = "$rootDirectory/$directoryRel/$basename.nogr"
        val childText = fileSystem.readFileIfExists(absChildPath) ?: return Loaded(emptyList(), emptyMap())
        val shallow = parseFileShallow(directoryRel = directoryRel, fileText = childText)
        if (shallow.lines.isEmpty()) return shallow
        // A file ending in `\n` produces a trailing empty line under `split("\n")`.
        // That empty would splice into the parent right after the subtree's last
        // bullet, where it survives a subsequent collapse (subtreeEnd stops at it)
        // and accumulates one extra blank line per expand/collapse cycle. Trim
        // trailing empties so the spliced content is exactly the bullet rows.
        val trimmed = shallow.lines.dropLastWhile { it.isEmpty() }
        if (trimmed.isEmpty()) return Loaded(emptyList(), emptyMap())
        val reindented = SubtreeCodec.reindentBy(trimmed, parentIndent + TAB_SIZE)
        return Loaded(reindented, shallow.promotedByRow)
    }

    /**
     * Parses one file into lines without following any nested references.
     * Each `[[ref]]` token is stripped from its bullet line (matching the
     * shape the editor sees — a plain bullet without the reference suffix)
     * and the row is recorded in [Loaded.promotedByRow] keyed by its index.
     * The caller (or the document VM) restores the `[[ref]]` on save via
     * [SubtreeCodec.formatRef]; in the meantime, expand/collapse decides
     * whether the child file's content is spliced under that row.
     *
     * @param directoryRel Directory of the file being parsed, relative to
     *   [rootDirectory]. References inside the file resolve relative to this
     *   directory; the result records the child's directory relative to
     *   [rootDirectory] (i.e. with [directoryRel] prepended).
     * @param fileText Verbatim text of the file.
     */
    private fun parseFileShallow(directoryRel: String, fileText: String): Loaded {
        val rawLines = if (fileText.isEmpty()) listOf("") else fileText.split("\n")
        val out = ArrayList<String>(rawLines.size)
        val promoted = HashMap<Int, String>()
        for (line in rawLines) {
            val ref = SubtreeCodec.parseRef(line)
            if (ref == null) {
                out += line
                continue
            }
            val childFileRel =
                if (directoryRel.isEmpty()) ref.refPath else "$directoryRel/${ref.refPath}"
            val childDirRel = childFileRel.substringBeforeLast('/')
            promoted[out.size] = childDirRel
            out += ref.bulletText
        }
        return Loaded(out, promoted)
    }

    /**
     * Writes the composed [lines] back to disk, applying [PromotionPolicy] to
     * decide which subtrees should live in their own files.
     *
     * @param lines The composed outline to persist.
     * @param promotedByRow Row→dirRel map carried over from the previous load
     *   or save. Entries here describe subtrees that are *currently* on disk
     *   as their own files; the save may rename, demote, or leave them alone.
     * @param expandedRefRows Subset of [promotedByRow]'s keys whose subtrees
     *   are currently spliced into [lines] in memory. Rows in [promotedByRow]
     *   but *not* in this set are file boundaries the user has folded — their
     *   children are absent from [lines] and must be left untouched on disk
     *   (no child-file rewrite, no rename, no demote). Default: every
     *   promoted row is treated as expanded (back-compat with callers that
     *   pre-date lazy loading).
     * @param onPhaseChange Invoked with `true` immediately before the save
     *   begins fanning out file writes/deletes for a *restructuring* tick —
     *   i.e. one that promotes a fresh subtree or demotes a previously
     *   promoted one — and with `false` once those writes complete (or fail).
     *   Plain saves where every promotion already existed never invoke the
     *   callback, so callers see no signal for routine ticks. The callback
     *   runs on the calling coroutine; keep it cheap and non-suspending.
     * @return The new row→dirRel map, ready to be stashed in
     *   `DocumentBackingViewModel` for the next save.
     */
    suspend fun save(
        lines: List<String>,
        promotedByRow: Map<Int, String>,
        expandedRefRows: Set<Int> = promotedByRow.keys,
        onPhaseChange: (Boolean) -> Unit = {},
    ): Map<Int, String> {
        fileSystem.ensureDirectory(rootDirectory)

        val measurements = SubtreeCodec.findSubtrees(lines)
        val measurementByStartRow = measurements.associateBy { it.startRow }

        // Step 1: decide which rows are promoted in this save.
        val promotedRowsOut = HashSet<Int>()
        for (m in measurements) {
            val title = SubtreeCodec.titleOf(lines[m.startRow])
            val span = SubtreeSpan(
                startRow = m.startRow,
                endRowInclusive = m.endRowInclusive,
                indent = m.indent,
                descendantCount = m.descendantCount,
                globalDepth = m.indent / TAB_SIZE,
                titleLength = title.length,
            )
            val wasPromoted = m.startRow in promotedByRow
            val isUnloaded = wasPromoted && m.startRow !in expandedRefRows
            val keep = when {
                // Unloaded refs are file boundaries whose children aren't in
                // [lines]. We have no view into their real descendant count
                // and must not rename or demote them — pass through as-is.
                isUnloaded -> true
                wasPromoted ->
                    // An already-promoted (and loaded) subtree stays unless it
                    // shrinks below the demote line OR loses its title (would
                    // force a rename to `untitled` which is rarely useful).
                    title.isNotEmpty() && !PromotionPolicy.shouldDemote(m.descendantCount)
                else ->
                    PromotionPolicy.shouldPromote(span, alreadyPromoted = false)
            }
            if (keep) promotedRowsOut += m.startRow
        }

        // Compare against the previous map to detect whether this tick will
        // actually reshape the on-disk tree. Pure-content saves (every
        // already-promoted subtree still qualifies, no new ones cross the
        // promote line) skip the phase signal entirely.
        val willPromote = promotedRowsOut.any { it !in promotedByRow }
        val willDemote = promotedByRow.keys.any { it !in promotedRowsOut }
        val isRestructuring = willPromote || willDemote

        if (isRestructuring) onPhaseChange(true)
        try {
            // Step 2: walk the outline once, building per-file content lists and
            // assigning child directory names with collision resolution.
            val plans = mutableListOf<FilePlan>()
            val newPromotedByRow = HashMap<Int, String>()
            val usedByDir = HashMap<String, MutableSet<String>>()
            usedByDir.getOrPut("") { HashSet() }.add(rootBasename)

            decomposeIntoFiles(
                lines = lines,
                measurementByStartRow = measurementByStartRow,
                promotedRowsOut = promotedRowsOut,
                promotedByRow = promotedByRow,
                expandedRefRows = expandedRefRows,
                start = 0,
                endExclusive = lines.size,
                indentBaseline = 0,
                dirRel = "",
                basename = rootBasename,
                isRoot = true,
                usedByDir = usedByDir,
                plansOut = plans,
                newPromotedByRow = newPromotedByRow,
            )

            // Step 3: write all files. We don't bother with atomic moveDirectory
            // here — write-everywhere + delete-orphans is simpler, equally safe
            // for our sizes, and survives partial failure better (write succeeds
            // even if the old dir was renamed externally).
            for (plan in plans) {
                val absDir = if (plan.dirRel.isEmpty()) rootDirectory
                             else "$rootDirectory/${plan.dirRel}"
                fileSystem.ensureDirectory(absDir)
            }
            for (plan in plans) {
                val absFile = if (plan.isRoot) "$rootDirectory/$rootFileName"
                              else "$rootDirectory/${plan.dirRel}/${plan.basename}.nogr"
                fileSystem.writeFile(absFile, plan.content.joinToString("\n"))
            }

            // Step 4: collect orphaned old paths (entries in promotedByRow whose
            // dirRel is no longer used by any current promotion). These are
            // either demoted subtrees or renamed-and-moved subtrees.
            val keptDirs = newPromotedByRow.values.toHashSet()
            val orphanedOldDirs = promotedByRow.values.filter { it !in keptDirs }.toSet()

            // Delete deepest first so a dir's contents are gone before we try to
            // remove the dir itself.
            val sortedOrphans = orphanedOldDirs.sortedByDescending { it.count { ch -> ch == '/' } }
            for (orphanDir in sortedOrphans) {
                val basename = orphanDir.substringAfterLast('/')
                fileSystem.deleteFile("$rootDirectory/$orphanDir/$basename.nogr")
                fileSystem.deleteDirectoryIfEmpty("$rootDirectory/$orphanDir")
            }

            return newPromotedByRow
        } finally {
            if (isRestructuring) onPhaseChange(false)
        }
    }

    /**
     * Recursive helper for [save]. Walks one file's slice of the composed
     * outline, decomposing nested promoted subtrees into their own
     * [FilePlan]s and accumulating the current file's plan into [plansOut].
     *
     * @param expandedRefRows Rows in [promotedByRow] whose children are
     *   currently in [lines]. Rows in [promotedByRow] but NOT in this set
     *   are unloaded — we emit their `[[ref]]` in the parent file but skip
     *   recursion (no FilePlan, child file untouched on disk) and pin the
     *   directory name to its previous basename so a title edit while
     *   collapsed doesn't accidentally rename the file.
     */
    private fun decomposeIntoFiles(
        lines: List<String>,
        measurementByStartRow: Map<Int, SubtreeMeasurement>,
        promotedRowsOut: Set<Int>,
        promotedByRow: Map<Int, String>,
        expandedRefRows: Set<Int>,
        start: Int,
        endExclusive: Int,
        indentBaseline: Int,
        dirRel: String,
        basename: String,
        isRoot: Boolean,
        usedByDir: MutableMap<String, MutableSet<String>>,
        plansOut: MutableList<FilePlan>,
        newPromotedByRow: MutableMap<Int, String>,
    ) {
        // Pre-pass: collect every row promoted at THIS file scope — i.e. the
        // rows in `promotedRowsOut` not nested inside another such row. We
        // walk the slice and skip past each match's subtree; deeper
        // promotions will be picked up by the recursive call instead.
        // Resolve basenames with existing-promotion priority so an already-
        // promoted sibling claims its name first and a same-titled new
        // sibling falls back to the ` 2` suffix.
        val topLevelPromoted = mutableListOf<Int>()
        run {
            var i = start
            while (i < endExclusive) {
                if (i in promotedRowsOut) {
                    val m = measurementByStartRow.getValue(i)
                    topLevelPromoted += i
                    i = m.endRowInclusive + 1
                } else {
                    i++
                }
            }
        }
        // Pin unloaded refs' basenames first so their existing dir survives
        // the save unchanged even if a sibling has the same title.
        val unloaded = topLevelPromoted.filter { it !in expandedRefRows && it in promotedByRow }
        val loadedPreviously = topLevelPromoted.filter { it in expandedRefRows && it in promotedByRow }
        val newlyPromoted = topLevelPromoted.filter { it !in promotedByRow }
        val nameOfRow = HashMap<Int, String>()
        val used = usedByDir.getOrPut(dirRel) { HashSet() }
        for (row in unloaded) {
            val existingDir = promotedByRow.getValue(row)
            val pinned = existingDir.substringAfterLast('/')
            used += pinned
            nameOfRow[row] = pinned
        }
        for (row in loadedPreviously) claimName(lines, row, used, nameOfRow)
        for (row in newlyPromoted) claimName(lines, row, used, nameOfRow)

        // Build phase: walk lines, emit content for this file, recurse into
        // each promoted subtree.
        val content = mutableListOf<String>()
        var i = start
        while (i < endExclusive) {
            if (i in promotedRowsOut && i in nameOfRow) {
                val m = measurementByStartRow.getValue(i)
                val name = nameOfRow.getValue(i)
                val childDirRel = if (dirRel.isEmpty()) name else "$dirRel/$name"
                newPromotedByRow[i] = childDirRel

                val rebasedHead = rebase(lines[i], indentBaseline)
                content += SubtreeCodec.formatRef(rebasedHead, "$name/$name.nogr")

                // The child file's directory entry is reserved by its own
                // basename, so deeper promotions can't collide with it.
                usedByDir.getOrPut(childDirRel) { HashSet() }.add(name)

                // Recurse for any row whose children are physically in `lines`:
                // newly promoted rows (not yet in promotedByRow — child file
                // doesn't exist on disk and must be written for the first
                // time), and previously promoted rows the user has expanded
                // (in expandedRefRows — child file gets rewritten). Skip only
                // for previously promoted but unloaded rows: their children
                // are not in `lines`, and rewriting from `lines` would
                // truncate the child file to empty.
                val isUnloadedRef = i in promotedByRow && i !in expandedRefRows
                if (!isUnloadedRef) {
                    decomposeIntoFiles(
                        lines = lines,
                        measurementByStartRow = measurementByStartRow,
                        promotedRowsOut = promotedRowsOut,
                        promotedByRow = promotedByRow,
                        expandedRefRows = expandedRefRows,
                        start = i + 1,
                        endExclusive = m.endRowInclusive + 1,
                        indentBaseline = m.indent + TAB_SIZE,
                        dirRel = childDirRel,
                        basename = name,
                        isRoot = false,
                        usedByDir = usedByDir,
                        plansOut = plansOut,
                        newPromotedByRow = newPromotedByRow,
                    )
                }
                // Unloaded refs: skip recursion; the existing child file on
                // disk is left untouched. m.endRowInclusive equals i for an
                // unloaded ref (no descendants in `lines`), so the increment
                // below also works for that case.

                i = m.endRowInclusive + 1
            } else {
                content += rebase(lines[i], indentBaseline)
                i++
            }
        }

        plansOut += FilePlan(dirRel, basename, content, isRoot)
    }

    /** Claims a unique basename for [row]'s subtree in [used] (mutating). */
    private fun claimName(
        lines: List<String>,
        row: Int,
        used: MutableSet<String>,
        out: MutableMap<Int, String>,
    ) {
        val title = SubtreeCodec.titleOf(lines[row])
        val desired = SubtreeCodec.safeFilename(title)
        val name = SubtreeCodec.uniqueFilename(desired, used)
        used += name
        out[row] = name
    }

    /**
     * Drops up to [indentBaseline] leading spaces from [line] so it can be
     * written into a file whose depth-0 corresponds to the composed outline's
     * column [indentBaseline].
     */
    private fun rebase(line: String, indentBaseline: Int): String {
        if (indentBaseline <= 0) return line
        var drop = 0
        while (drop < indentBaseline && drop < line.length && line[drop] == ' ') drop++
        return if (drop > 0) line.substring(drop) else line
    }

    /** A single file ready to be written. */
    private data class FilePlan(
        val dirRel: String,
        val basename: String,
        val content: List<String>,
        val isRoot: Boolean,
    )

    companion object {
        const val DEFAULT_DIRECTORY: String = "/Users/soderbjorn/notegrow-db"
        const val DEFAULT_FILE_NAME: String = "root.nogr"
        private const val TAB_SIZE: Int = 2
    }
}
