/*
 * NoteRepository.kt
 * -----------------
 * Persistence boundary for Notegrow. The in-memory model is a single flat
 * outline (`lines: List<String>`), but on disk that outline is split across
 * a tree of `.md` files connected by `[Title](path/to/file.md#notegrow)`
 * markdown links. This class owns the splitting/composition: `load()` reads
 * the disk tree and returns one flat outline plus a row→[PromotedRef] map;
 * `save()` accepts the flat outline plus the map and writes the disk tree,
 * applying [PromotionPolicy] to decide which subtrees should be promoted,
 * demoted, or live-renamed.
 *
 * ### How Notegrow distinguishes its own bullets
 *
 * A markdown link bullet is a Notegrow promoted-ref boundary if-and-only-if
 * its URL ends in the literal `#notegrow` fragment (see [SubtreeCodec]).
 * Plain markdown links and links with any other fragment render as
 * literal link bullets — Notegrow never splices their target files, never
 * rewrites them. Files themselves carry no Notegrow-specific syntax (no
 * frontmatter ceremony, no custom keys). User-authored YAML frontmatter
 * is preserved verbatim across load/save round-trips via the per-file
 * [frontmatterByFile] cache.
 *
 * ### File paths
 *
 * Each promoted ref carries an explicit [PromotedRef.fileRel] — the file's
 * path relative to the vault root, including its `.md` extension. This
 * lets adopted-foreign files live at arbitrary paths (`links.md` at the
 * vault root, `Recipes/Quick Granola.md` next to siblings, …) without
 * forcing them into the doubled-name `<Name>/<Name>.md` shape Notegrow's
 * own auto-promotion uses for files it creates.
 *
 * Pure split/compose helpers live in [SubtreeCodec]. The repository is
 * the only place that touches [FileSystem].
 */

package se.soderbjorn.notegrow.data

import se.soderbjorn.notegrow.main.DocumentLayout
import se.soderbjorn.notegrow.platform.FileSystem

/**
 * One entry in the filesystem-tree footer's lazy-loaded directory listing.
 *
 * @property name Display name. For files this is the basename minus `.md`;
 *   for directories it's the directory name.
 * @property pathRel Path relative to the vault root.
 * @property isDirectory `true` for subdirectories, `false` for `.md` files.
 *   Files of other extensions are filtered out before reaching this type.
 */
data class VaultEntry(
    val name: String,
    val pathRel: String,
    val isDirectory: Boolean,
)

/**
 * One promoted-subtree boundary's persistence metadata.
 *
 * @property fileRel The file's path relative to the vault root, including
 *   its `.md` extension (e.g. `Recipes/Recipes.md`, `links.md`,
 *   `Recipes/Quick Granola.md`). Notegrow's own auto-promotion produces
 *   doubled-name `<Name>/<Name>.md` paths; adopted-foreign files keep
 *   whatever path the user navigated to.
 * @property noAutoPromote When `true`, the autosave loop pins the file at
 *   [fileRel] forever — it never demotes (regardless of subtree size) and
 *   never renames on title edits. Set on adoption of foreign markdown
 *   files so Notegrow doesn't surprise-restructure files it didn't create.
 *   Files Notegrow auto-promoted itself leave this `false` and follow the
 *   existing rename-on-title-edit / demote-when-small rules.
 */
data class PromotedRef(
    val fileRel: String,
    val noAutoPromote: Boolean,
)

/**
 * @property fileSystem Platform filesystem used for all I/O.
 * @property rootDirectory Absolute directory under which `Root.md` and the
 *   nested `<Title>/<Title>.md` tree live.
 * @property rootFileName Filename of the top-level outline. Defaults to
 *   `Root.md`.
 */
class NoteRepository(
    private val fileSystem: FileSystem,
    private val rootDirectory: String = DEFAULT_DIRECTORY,
    val rootFileName: String = DEFAULT_FILE_NAME,
) {

    /**
     * Per-file YAML frontmatter cache, keyed by [PromotedRef.fileRel] for
     * promoted files and by [rootFileName] for the root. Populated on every
     * read, consumed on every write so user-authored frontmatter (Obsidian
     * `tags`, `aliases`, …) round-trips byte-perfectly. Files that have no
     * frontmatter on disk get no entry — [save] writes their bodies as-is.
     *
     * Maps to the verbatim frontmatter block including the surrounding
     * `---\n…\n---\n` fences and the trailing newline. Re-prepending it
     * unchanged is the simplest way to preserve user data.
     */
    private val frontmatterByFile: MutableMap<String, String> = mutableMapOf()

    /**
     * Result of [loadFile] / [loadSubtree]: the composed flat outline plus
     * per-row metadata the document VM needs to keep auto-promotion
     * idempotent across saves.
     *
     * @property lines One entry per logical line of the composed outline.
     *   Always non-empty for [loadFile] — an empty document is `listOf("")`.
     * @property promotedByRow Maps row index in [lines] to the
     *   [PromotedRef] metadata for that subtree's child file. Populated
     *   only for `[Title](path#notegrow)` bullets; the line text in
     *   [lines] for these rows is the plain `* Title` form the editor
     *   displays. Markdown link bullets without the `#notegrow` fragment
     *   render as literal text and are not in this map.
     */
    data class Loaded(
        val lines: List<String>,
        val promotedByRow: Map<Int, PromotedRef>,
    )

    /**
     * Reads the file at [fileRel] (vault-relative, including `.md`) and
     * returns its body as the editor's flat row list. Children files are
     * not followed; the document VM lazy-loads each subtree via
     * [loadSubtree] when the user expands the corresponding `#notegrow`
     * bullet.
     *
     * Missing files return an empty `Loaded` — the editor shows an empty
     * document. Used for the initial load of `Root.md` and for switching
     * the active document when the user clicks a file in the footer.
     */
    suspend fun loadFile(fileRel: String): Loaded {
        fileSystem.ensureDirectory(rootDirectory)
        val text = fileSystem.readFileIfExists("$rootDirectory/$fileRel")
        if (text.isNullOrEmpty()) {
            frontmatterByFile.remove(fileRel)
            return Loaded(listOf(""), emptyMap())
        }
        val (frontmatter, body) = splitFrontmatter(text)
        if (frontmatter != null) frontmatterByFile[fileRel] = frontmatter
        else frontmatterByFile.remove(fileRel)
        val parentDir = fileRel.substringBeforeLast('/', missingDelimiterValue = "")
        return parseFileShallow(parentDir = parentDir, fileText = body)
    }

    /** Convenience alias: loads the configured root file. */
    suspend fun loadRoot(): Loaded = loadFile(rootFileName)

    /**
     * Loads the file at `<rootDirectory>/<fileRel>` without recursing into
     * nested links. The returned [Loaded.lines] are reindented by
     * [parentIndent] + [TAB_SIZE] so they slot under the parent bullet at
     * the correct depth in the composed outline.
     *
     * Used by [se.soderbjorn.notegrow.main.Document] when
     * the user expands a previously-collapsed reference bullet.
     *
     * Missing files produce an empty splice (the chevron flips open with
     * no children, same UX as before).
     *
     * @param fileRel File path of the child, relative to [rootDirectory],
     *   including the `.md` extension.
     * @param parentIndent The bullet column of the parent reference row in
     *   the composed outline. The child file's lines are deepened by
     *   `parentIndent + TAB_SIZE` so the topmost child sits one indent step
     *   below its parent.
     */
    suspend fun loadSubtree(fileRel: String, parentIndent: Int): Loaded {
        if (fileRel.isEmpty()) return Loaded(listOf(""), emptyMap())
        val absChildPath = "$rootDirectory/$fileRel"
        val childText = fileSystem.readFileIfExists(absChildPath) ?: return Loaded(emptyList(), emptyMap())
        val (frontmatter, body) = splitFrontmatter(childText)
        if (frontmatter != null) frontmatterByFile[fileRel] = frontmatter
        else frontmatterByFile.remove(fileRel)
        val parentDir = fileRel.substringBeforeLast('/', missingDelimiterValue = "")
        val shallow = parseFileShallow(parentDir = parentDir, fileText = body)
        if (shallow.lines.isEmpty()) return shallow
        // A file ending in `\n` produces a trailing empty line under
        // `split("\n")`. That empty would splice into the parent right after
        // the subtree's last bullet, where it survives a subsequent collapse
        // and accumulates one extra blank line per expand/collapse cycle.
        // Trim trailing empties so the spliced content is exactly the rows.
        val trimmed = shallow.lines.dropLastWhile { it.isEmpty() }
        if (trimmed.isEmpty()) return Loaded(emptyList(), emptyMap())
        val reindented = SubtreeCodec.reindentBy(trimmed, parentIndent + TAB_SIZE)
        return Loaded(reindented, shallow.promotedByRow)
    }

    /**
     * Parses one file's body (frontmatter already stripped) into lines
     * without following any nested references. Each `* [Title](path#notegrow)`
     * markdown-link bullet is rewritten to its plain `* Title` form
     * (matching the shape the editor sees) and the row is recorded in
     * [Loaded.promotedByRow] keyed by its index. The caller (or the
     * document VM) restores the link on save via [SubtreeCodec.formatRef].
     *
     * Markdown-link bullets without the `#notegrow` fragment are passed
     * through verbatim — the bullet renders the raw `[Title](path)` text,
     * no chevron, no splice. This makes Obsidian-style cross-references
     * coexist safely with Notegrow-managed bullets in the same file.
     *
     * @param parentDir Directory of the file being parsed, relative to
     *   [rootDirectory]. References inside the file resolve relative to
     *   this directory; the resulting [PromotedRef.fileRel] always carries
     *   the path from the vault root.
     * @param fileText Verbatim file body with any leading frontmatter
     *   already removed.
     */
    private fun parseFileShallow(parentDir: String, fileText: String): Loaded {
        val rawLines = if (fileText.isEmpty()) listOf("") else fileText.split("\n")
        val out = ArrayList<String>(rawLines.size)
        val promoted = HashMap<Int, PromotedRef>()
        for (line in rawLines) {
            val ref = SubtreeCodec.parseRef(line)
            if (ref == null) {
                // Plain text, plain bullet, or markdown link without the
                // `#notegrow` fragment — render as literal text. The
                // footer's switchTo flow lets the user navigate to any
                // file directly; we don't need to track bare-URL links
                // here.
                out += line
                continue
            }
            // refPath has the `#notegrow` fragment stripped. Resolve to
            // a vault-root-relative path by joining with the parent dir.
            val childFileRel = if (parentDir.isEmpty()) ref.refPath else "$parentDir/${ref.refPath}"
            promoted[out.size] = PromotedRef(fileRel = childFileRel, noAutoPromote = false)
            out += ref.bulletText
        }
        return Loaded(out, promoted)
    }

    /**
     * Writes the composed [lines] back to disk, applying [PromotionPolicy] to
     * decide which subtrees should live in their own files.
     *
     * @param lines The composed outline to persist.
     * @param promotedByRow Row→[PromotedRef] map carried over from the
     *   previous load or save. Entries here describe subtrees that are
     *   *currently* on disk as their own files; the save may rename, demote,
     *   or leave them alone (refs flagged [PromotedRef.noAutoPromote] are
     *   always left alone).
     * @param expandedRefRows Subset of [promotedByRow]'s keys whose subtrees
     *   are currently spliced into [lines] in memory. Rows in [promotedByRow]
     *   but *not* in this set are file boundaries the user has folded —
     *   their children are absent from [lines] and must be left untouched
     *   on disk.
     * @param onPhaseChange Invoked with `true` immediately before the save
     *   begins fanning out file writes/deletes for a *restructuring* tick
     *   (one that promotes a fresh subtree or demotes a previously promoted
     *   one), and with `false` once those writes complete.
     * @return The new row→[PromotedRef] map, ready to be stashed in the
     *   document VM for the next save.
     */
    suspend fun save(
        activeFileRel: String,
        lines: List<String>,
        promotedByRow: Map<Int, PromotedRef>,
        expandedRefRows: Set<Int> = promotedByRow.keys,
        onPhaseChange: (Boolean) -> Unit = {},
    ): Map<Int, PromotedRef> {
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
            val existing = promotedByRow[m.startRow]
            val wasPromoted = existing != null
            val isUnloaded = wasPromoted && m.startRow !in expandedRefRows
            val keep = when {
                // Unloaded refs are file boundaries whose children aren't in
                // [lines]. We have no view into their real descendant count
                // and must not rename or demote them — pass through as-is.
                isUnloaded -> true
                // Adopted-foreign files (noAutoPromote=true) are pinned: never
                // demoted, never renamed, regardless of size or title edits.
                existing?.noAutoPromote == true -> true
                wasPromoted ->
                    // An already-promoted (and loaded) subtree stays unless it
                    // shrinks below the demote line OR loses its title.
                    title.isNotEmpty() && !PromotionPolicy.shouldDemote(m.descendantCount)
                else ->
                    // Starred.md is exempt from new auto-promotion: its bullet
                    // tree is a hand-curated bookmark list that must never
                    // fragment into subfiles regardless of size.
                    activeFileRel != STARRED_FILE_NAME &&
                    PromotionPolicy.shouldPromote(span, alreadyPromoted = false)
            }
            if (keep) promotedRowsOut += m.startRow
        }

        // Compare against the previous map to detect whether this tick will
        // actually reshape the on-disk tree. Pure-content saves skip the
        // phase signal entirely.
        val willPromote = promotedRowsOut.any { it !in promotedByRow }
        val willDemote = promotedByRow.keys.any { it !in promotedRowsOut }
        val isRestructuring = willPromote || willDemote

        if (isRestructuring) onPhaseChange(true)
        try {
            // Step 2: walk the outline once, building per-file content lists
            // and assigning child file paths with collision resolution.
            val plans = mutableListOf<FilePlan>()
            val newPromotedByRow = HashMap<Int, PromotedRef>()
            val usedByDir = HashMap<String, MutableSet<String>>()
            // Reserve the active file's basename in its parent directory
            // so a top-level promotion can't try to write a child file
            // that shadows it.
            val activeParentDir = activeFileRel.substringBeforeLast('/', missingDelimiterValue = "")
            usedByDir.getOrPut(activeParentDir) { HashSet() }.add(basenameOf(activeFileRel))

            decomposeIntoFiles(
                lines = lines,
                measurementByStartRow = measurementByStartRow,
                promotedRowsOut = promotedRowsOut,
                promotedByRow = promotedByRow,
                expandedRefRows = expandedRefRows,
                start = 0,
                endExclusive = lines.size,
                indentBaseline = 0,
                parentFileRel = activeFileRel,
                usedByDir = usedByDir,
                plansOut = plans,
                newPromotedByRow = newPromotedByRow,
            )

            // Step 3: write all files. Each file's verbatim user-authored
            // frontmatter (if any) is re-prepended; Notegrow itself adds no
            // frontmatter ceremony — promoted-ref-ness lives in link URLs.
            for (plan in plans) {
                val parentDir = plan.fileRel.substringBeforeLast('/', missingDelimiterValue = "")
                val absDir = if (parentDir.isEmpty()) rootDirectory else "$rootDirectory/$parentDir"
                fileSystem.ensureDirectory(absDir)
            }
            for (plan in plans) {
                val absFile = "$rootDirectory/${plan.fileRel}"
                val stripped = stripTrailingEmptyBullets(plan.content)
                val body = stripped.joinToString("\n")
                val frontmatter = frontmatterByFile[plan.fileRel] ?: ""
                println("[autosave]   write $absFile (${stripped.size} lines)")
                fileSystem.writeFile(absFile, frontmatter + body)
            }

            // Step 4: collect orphaned old paths (files registered in
            // promotedByRow whose fileRel is no longer used by any current
            // promotion). These are demoted or renamed-and-moved subtrees.
            val keptFiles = newPromotedByRow.values.map { it.fileRel }.toHashSet()
            val orphanedFiles = promotedByRow.values
                .map { it.fileRel }
                .filter { it !in keptFiles }
                .toHashSet()

            // Drop frontmatter cache entries for orphans so a future re-load
            // of the same path starts clean.
            for (orphan in orphanedFiles) frontmatterByFile.remove(orphan)

            // Delete deepest first so a dir's contents are gone before we
            // try to remove the dir itself.
            val sortedOrphans = orphanedFiles.sortedByDescending { it.count { ch -> ch == '/' } }
            for (orphanFile in sortedOrphans) {
                fileSystem.deleteFile("$rootDirectory/$orphanFile")
                val parentDir = orphanFile.substringBeforeLast('/', missingDelimiterValue = "")
                if (parentDir.isNotEmpty()) {
                    fileSystem.deleteDirectoryIfEmpty("$rootDirectory/$parentDir")
                }
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
     */
    private fun decomposeIntoFiles(
        lines: List<String>,
        measurementByStartRow: Map<Int, SubtreeMeasurement>,
        promotedRowsOut: Set<Int>,
        promotedByRow: Map<Int, PromotedRef>,
        expandedRefRows: Set<Int>,
        start: Int,
        endExclusive: Int,
        indentBaseline: Int,
        parentFileRel: String,
        usedByDir: MutableMap<String, MutableSet<String>>,
        plansOut: MutableList<FilePlan>,
        newPromotedByRow: MutableMap<Int, PromotedRef>,
    ) {
        // Pre-pass: collect every row promoted at THIS file scope.
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

        val parentDir = parentFileRel.substringBeforeLast('/', missingDelimiterValue = "")
        val used = usedByDir.getOrPut(parentDir) { HashSet() }

        // Decide each promoted row's child fileRel + carry forward the
        // noAutoPromote flag from existing entries.
        val refOfRow = HashMap<Int, PromotedRef>()
        // Two passes: pinned (existing or unloaded) first, so their basenames
        // are reserved before fresh title-derived names try to claim them.
        val pinned = topLevelPromoted.filter { row ->
            val ex = promotedByRow[row]
            ex != null && (ex.noAutoPromote || row !in expandedRefRows)
        }
        val loadedAuto = topLevelPromoted.filter { row ->
            val ex = promotedByRow[row]
            ex != null && !ex.noAutoPromote && row in expandedRefRows
        }
        val newlyPromoted = topLevelPromoted.filter { row -> row !in promotedByRow }

        for (row in pinned) {
            val ex = promotedByRow.getValue(row)
            refOfRow[row] = ex
            used += basenameOf(ex.fileRel)
        }
        for (row in loadedAuto) {
            val ex = promotedByRow.getValue(row)
            val title = SubtreeCodec.titleOf(lines[row])
            val desired = SubtreeCodec.safeFilename(title)
            val currentBasename = basenameOf(ex.fileRel)
            // For Notegrow-auto-promoted files we keep them in the doubled-name
            // shape under [parentDir]. If the title's safe filename matches the
            // current basename, keep the existing fileRel as-is. Otherwise emit
            // a fresh `<parentDir>/<newName>/<newName>.md` path; the orphan-
            // collection step in [save] will delete the old path.
            val newFileRel = if (desired == currentBasename) {
                used += currentBasename
                ex.fileRel
            } else {
                val unique = SubtreeCodec.uniqueFilename(desired, used)
                used += unique
                if (parentDir.isEmpty()) "$unique/$unique$NOTE_EXTENSION"
                else "$parentDir/$unique/$unique$NOTE_EXTENSION"
            }
            refOfRow[row] = PromotedRef(fileRel = newFileRel, noAutoPromote = false)
        }
        for (row in newlyPromoted) {
            val title = SubtreeCodec.titleOf(lines[row])
            val desired = SubtreeCodec.safeFilename(title)
            val name = SubtreeCodec.uniqueFilename(desired, used)
            used += name
            val newFileRel = if (parentDir.isEmpty()) "$name/$name$NOTE_EXTENSION"
                             else "$parentDir/$name/$name$NOTE_EXTENSION"
            refOfRow[row] = PromotedRef(fileRel = newFileRel, noAutoPromote = false)
        }

        // Build phase: walk lines, emit content for this file, recurse into
        // each promoted subtree.
        val content = mutableListOf<String>()
        var i = start
        while (i < endExclusive) {
            if (i in promotedRowsOut && i in refOfRow) {
                val m = measurementByStartRow.getValue(i)
                val ref = refOfRow.getValue(i)
                newPromotedByRow[i] = ref

                val rebasedHead = rebase(lines[i], indentBaseline)
                val headIndent = DocumentLayout.bulletAsteriskColumn(rebasedHead).coerceAtLeast(0)
                val title = SubtreeCodec.titleOf(rebasedHead)
                val relativeUrl = relativizeFromParent(parentDir, ref.fileRel)
                content += SubtreeCodec.formatRef(headIndent, title, relativeUrl)

                // Reserve the child's directory entry so deeper promotions
                // can't collide with it. Only meaningful when the child
                // lives in its own subdirectory (the doubled-name case).
                val childDir = ref.fileRel.substringBeforeLast('/', missingDelimiterValue = "")
                if (childDir.isNotEmpty() && childDir != parentDir) {
                    usedByDir.getOrPut(childDir) { HashSet() }.add(basenameOf(ref.fileRel))
                }

                // Recurse for any row whose children are physically in
                // `lines`: newly promoted rows and previously-promoted rows
                // the user has expanded. Skip for previously-promoted-but-
                // unloaded rows: their children are not in `lines`, and
                // rewriting from `lines` would truncate the child file.
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
                        parentFileRel = ref.fileRel,
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

        plansOut += FilePlan(fileRel = parentFileRel, content = content)
    }

    /**
     * Drops up to [indentBaseline] leading spaces from [line] so it can be
     * written into a file whose depth-0 corresponds to the composed
     * outline's column [indentBaseline].
     */
    private fun rebase(line: String, indentBaseline: Int): String {
        if (indentBaseline <= 0) return line
        var drop = 0
        while (drop < indentBaseline && drop < line.length && line[drop] == ' ') drop++
        return if (drop > 0) line.substring(drop) else line
    }

    /**
     * Returns [childFileRel]'s URL when the bullet that points at it is
     * emitted into the file at [parentDir]. When the child lives under
     * the parent's directory we return the suffix; otherwise we fall back
     * to the absolute (vault-root-relative) path. CommonMark resolves both
     * shapes correctly when followed manually from the parent.
     */
    private fun relativizeFromParent(parentDir: String, childFileRel: String): String {
        if (parentDir.isEmpty()) return childFileRel
        val prefix = "$parentDir/"
        return if (childFileRel.startsWith(prefix)) childFileRel.substring(prefix.length)
        else childFileRel
    }

    /** Strips the `.md` extension from a fileRel's last segment. */
    private fun basenameOf(fileRel: String): String =
        fileRel.substringAfterLast('/').removeSuffix(NOTE_EXTENSION)

    /** A single file ready to be written. */
    private data class FilePlan(
        val fileRel: String,
        val content: List<String>,
    )

    /**
     * Drops trailing empty-bullet rows (and trailing blank lines) from the
     * tail of [content]. An "empty bullet" is a row matching
     * [DocumentLayout.isEmptyBulletLine] — `"  * "`, `"* "`, etc. with no
     * actual text after the marker.
     *
     * Implements the "don't write any trailing bullets without content on
     * the last line" rule: the editor freely materializes empty
     * placeholder bullets at zoom-into-leaf time and at the bottom of
     * the file as the user types, but those should never reach disk if
     * they end up at the very end of the file. Pure-content saves with a
     * fully populated file are unaffected.
     *
     * Always preserves at least one row so an emptied-out file still
     * round-trips through [loadFile] as `listOf("")`.
     */
    private fun stripTrailingEmptyBullets(content: List<String>): List<String> {
        if (content.isEmpty()) return content
        var end = content.size
        while (end > 0) {
            val last = content[end - 1]
            val isStrippable = last.isEmpty() ||
                last.all { it.isWhitespace() } ||
                DocumentLayout.isEmptyBulletLine(last)
            if (!isStrippable) break
            end--
        }
        if (end == content.size) return content
        if (end == 0) return listOf("")
        return content.subList(0, end).toList()
    }

    // ----------------------------------------------------------- frontmatter

    /**
     * Splits a leading YAML frontmatter block (`---\n…\n---\n`) off [text].
     * Returns `(frontmatterBlockOrNull, body)` where the frontmatter block,
     * if present, includes the surrounding fences and the trailing newline
     * — so re-prepending it at save time is a verbatim concatenation.
     *
     * Files without a frontmatter block return `(null, text)` unchanged.
     * Malformed frontmatter (opening `---` without a closing one) is left
     * unstripped — better to show the user weird text than silently delete
     * content.
     *
     * Notegrow does **not** read or write the `notegrow: true` marker
     * anymore; promoted-ref-ness is signalled per-link via the `#notegrow`
     * URL fragment in [SubtreeCodec]. Frontmatter is preserved purely as
     * user data.
     */
    internal fun splitFrontmatter(text: String): Pair<String?, String> {
        if (!text.startsWith("---\n")) return Pair(null, text)
        val close = findFenceLine(text, startAt = 4)
        if (close < 0) return Pair(null, text)
        val afterFence = (close + 3).let { if (it < text.length && text[it] == '\n') it + 1 else it }
        val frontmatter = text.substring(0, afterFence)
        val body = text.substring(afterFence)
        return Pair(frontmatter, body)
    }

    /** Returns the byte offset of the next line that is exactly `---`, or -1. */
    private fun findFenceLine(text: String, startAt: Int): Int {
        var pos = startAt
        while (pos < text.length) {
            val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            if (end - pos == 3 &&
                text[pos] == '-' && text[pos + 1] == '-' && text[pos + 2] == '-'
            ) return pos
            pos = end + 1
        }
        return -1
    }

    // -------------------------------------------------------- vault listing

    /**
     * Lists the direct entries under `<rootDirectory>/<dirRel>`. Pass `""`
     * to list the vault root. Filters to `.md` files plus subdirectories;
     * dotfiles and other extensions are dropped. The body of `.md` files is
     * never read here — the listing is purely structural.
     *
     * Used by the vault-tree footer in the editor view to render one folder
     * level at a time. Each subsequent folder click triggers another call
     * with the deeper [dirRel], so deep vaults don't pay an upfront walk.
     *
     * Sort order: directories first, then files, both alphabetic by name
     * (case-insensitive) — matches typical file-browser conventions.
     *
     * @param dirRel Directory path relative to [rootDirectory]. Empty
     *   string means the vault root.
     */
    suspend fun listVaultLevel(dirRel: String): List<VaultEntry> {
        val absPath = if (dirRel.isEmpty()) rootDirectory else "$rootDirectory/$dirRel"
        val raw = fileSystem.listDirectoryEntries(absPath)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<VaultEntry>(raw.size)
        for (entry in raw) {
            if (entry.name.startsWith(".")) continue
            val pathRel = if (dirRel.isEmpty()) entry.name else "$dirRel/${entry.name}"
            if (entry.isDirectory) {
                out += VaultEntry(
                    name = entry.name,
                    pathRel = pathRel,
                    isDirectory = true,
                )
                continue
            }
            if (!entry.name.endsWith(NOTE_EXTENSION)) continue
            val displayName = entry.name.removeSuffix(NOTE_EXTENSION)
            out += VaultEntry(
                name = displayName,
                pathRel = pathRel,
                isDirectory = false,
            )
        }
        return out.sortedWith(
            compareByDescending<VaultEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Appends a single bookmark entry to the vault's [STARRED_FILE_NAME]
     * file (creating it with no frontmatter if it does not exist yet).
     *
     * The entry is rendered as a plain markdown-link bullet via
     * [SubtreeCodec.formatPlainLinkBullet] — explicitly *not* a Notegrow
     * promoted ref, so the autosave loop never tries to splice the target
     * file's contents into Starred.md.
     *
     * @param title Human-readable label shown in the bookmark list.
     * @param targetPathRel Vault-relative path of the file the bookmark
     *   points at, including `.md`. Pass exactly what
     *   [se.soderbjorn.notegrow.main.PaneBackingViewModel.navigateToVaultFile]
     *   would accept.
     * @param targetRow Optional 0-indexed row within [targetPathRel];
     *   when non-null the link's URL fragment becomes `#r=<row>` so the
     *   click handler can re-zoom precisely after the file loads. Row
     *   indices are used (rather than `LineId`s) because they survive
     *   cold reloads — `LineId`s are reassigned every time a file is
     *   loaded from disk. Pass `null` to bookmark the whole file.
     */
    suspend fun appendStarredEntry(
        title: String,
        targetPathRel: String,
        targetRow: Int?,
    ) {
        fileSystem.ensureDirectory(rootDirectory)
        val absPath = "$rootDirectory/$STARRED_FILE_NAME"
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
     * Removes every bookmark line in [STARRED_FILE_NAME] whose markdown
     * link points at the same `(targetPathRel, targetRow)` tuple as the
     * arguments. Used by the Starred modal's "Remove from starred" toggle
     * to undo a prior [appendStarredEntry] without leaving duplicates
     * behind.
     *
     * Matching mirrors the logic the Starred modal uses to decide whether
     * a target is "currently starred":
     *
     * - If the bookmark URL ends with `#r=<n>`, the path part and the row
     *   must both match.
     * - Otherwise the URL is matched against [targetPathRel] verbatim, and
     *   only when [targetRow] is `null`.
     *
     * Lines that are not markdown-link bullets are preserved as-is so any
     * hand-authored content the user added to `Starred.md` (headings,
     * notes, plain bullets) survives the rewrite.
     *
     * @param targetPathRel Vault-relative path of the file the bookmark
     *   points at, including `.md`.
     * @param targetRow Optional 0-indexed row within [targetPathRel].
     *   Pass `null` to remove a whole-file bookmark.
     */
    suspend fun removeStarredEntry(
        targetPathRel: String,
        targetRow: Int?,
    ) {
        val absPath = "$rootDirectory/$STARRED_FILE_NAME"
        val existing = fileSystem.readFileIfExists(absPath) ?: return
        val rowMarker = "#r="
        val lines = existing.split("\n")
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            val link = SubtreeCodec.parseAnyLinkBullet(line)
            if (link == null) {
                kept += line
                continue
            }
            val url = link.url
            val hashIdx = url.indexOf(rowMarker)
            val (path, row) = if (hashIdx >= 0) {
                val tail = url.substring(hashIdx + rowMarker.length)
                val n = tail.toIntOrNull()
                if (n != null) url.substring(0, hashIdx) to n
                else url to null
            } else {
                url to null
            }
            if (path == targetPathRel && row == targetRow) continue
            kept += line
        }
        val nextContent = kept.joinToString("\n")
        fileSystem.writeFile(absPath, nextContent)
    }

    companion object {
        const val DEFAULT_DIRECTORY: String = "/Users/soderbjorn/notegrow-db"
        const val NOTE_EXTENSION: String = ".md"
        const val DEFAULT_FILE_NAME: String = "Root$NOTE_EXTENSION"
        /**
         * Vault-relative filename for the Starred bookmarks list. Has two
         * roles:
         * 1. The Starred-modal in the web UI loads/displays/appends to it.
         * 2. [save] suppresses **new** auto-promotion when this file is the
         *    active document, so the bookmark list never spontaneously
         *    fragments into subfiles even if it grows large.
         */
        const val STARRED_FILE_NAME: String = "Starred$NOTE_EXTENSION"
        private const val TAB_SIZE: Int = 2
    }
}
