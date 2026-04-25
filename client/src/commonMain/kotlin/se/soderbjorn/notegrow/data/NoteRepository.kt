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
     * Reads `root.nogr` and recursively follows every `[[…]]` reference,
     * inlining child files into one composed outline.
     *
     * Missing referenced files are tolerated: the ref line is left verbatim in
     * the result and not added to [Loaded.promotedByRow]. This means an editor
     * that opens a half-broken tree (e.g. a child file was renamed externally)
     * still loads, and the broken ref is visible to the user as plain text.
     */
    suspend fun load(): Loaded {
        fileSystem.ensureDirectory(rootDirectory)
        val rootText = fileSystem.readFileIfExists("$rootDirectory/$rootFileName")
        if (rootText.isNullOrEmpty()) return Loaded(listOf(""), emptyMap())
        return composeFile(directoryRel = "", fileText = rootText)
    }

    /**
     * Recursive helper for [load].
     *
     * @param directoryRel Path relative to [rootDirectory] of the directory
     *   that contains the file currently being composed. References inside
     *   that file resolve relative to this directory.
     * @param fileText Verbatim text of the file being composed.
     */
    private suspend fun composeFile(directoryRel: String, fileText: String): Loaded {
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
            val absChildPath = "$rootDirectory/$childFileRel"
            val childText = fileSystem.readFileIfExists(absChildPath)
            if (childText == null) {
                // Broken ref — keep the line verbatim; surfaced to the user
                // for manual fix.
                out += line
                continue
            }
            promoted[out.size] = childDirRel
            out += ref.bulletText
            val sub = composeFile(childDirRel, childText)
            val reindented = SubtreeCodec.reindentBy(sub.lines, ref.indent + TAB_SIZE)
            val baseRow = out.size
            for ((subRow, dir) in sub.promotedByRow) {
                promoted[baseRow + subRow] = dir
            }
            out.addAll(reindented)
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
     * @return The new row→dirRel map, ready to be stashed in
     *   `DocumentBackingViewModel` for the next save.
     */
    suspend fun save(
        lines: List<String>,
        promotedByRow: Map<Int, String>,
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
            val keep = if (wasPromoted) {
                // An already-promoted subtree stays unless it shrinks below
                // the demote line OR loses its title (would force a rename to
                // `untitled` which is rarely useful).
                title.isNotEmpty() && !PromotionPolicy.shouldDemote(m.descendantCount)
            } else {
                PromotionPolicy.shouldPromote(span, alreadyPromoted = false)
            }
            if (keep) promotedRowsOut += m.startRow
        }

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
    }

    /**
     * Recursive helper for [save]. Walks one file's slice of the composed
     * outline, decomposing nested promoted subtrees into their own
     * [FilePlan]s and accumulating the current file's plan into [plansOut].
     */
    private fun decomposeIntoFiles(
        lines: List<String>,
        measurementByStartRow: Map<Int, SubtreeMeasurement>,
        promotedRowsOut: Set<Int>,
        promotedByRow: Map<Int, String>,
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
        val previouslyPromoted = topLevelPromoted.filter { it in promotedByRow }
        val newlyPromoted = topLevelPromoted.filter { it !in promotedByRow }
        val nameOfRow = HashMap<Int, String>()
        val used = usedByDir.getOrPut(dirRel) { HashSet() }
        for (row in previouslyPromoted) claimName(lines, row, used, nameOfRow)
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

                decomposeIntoFiles(
                    lines = lines,
                    measurementByStartRow = measurementByStartRow,
                    promotedRowsOut = promotedRowsOut,
                    promotedByRow = promotedByRow,
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
