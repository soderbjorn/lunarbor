/*
 * DynalistImportTest.kt (commonTest)
 * ----------------------------------
 * App-side half of the TRF-9 Dynalist import tests. The import itself is a
 * terminal script (`scripts/dynalist_to_lunarbor.py`, tested by
 * `scripts/test_dynalist_to_lunarbor.py`); this file pins that what it
 * writes is exactly what the app would write:
 *
 *  - [folderNameCases] is the same title → folder-name table as the
 *    Python test's `FOLDER_NAME_CASES`, run through [FolderName.forTitle],
 *    so the script's port of the encoder cannot drift from the app.
 *  - An import-shaped vault (a copy of part of
 *    `scripts/fixtures/dynalist/expected/`) loads into a [Document] as the
 *    full tree, with notes as blocks, and a save of the fully expanded tree
 *    rewrites no file and renames no folder: the script's output is a fixed
 *    point of `NoteRepository.save`.
 *
 * Keep both tables and both fixtures in step when either side changes.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class DynalistImportTest {

    /** Title → folder name. Identical to `FOLDER_NAME_CASES` in the Python test. */
    private val folderNameCases: List<Pair<String, String>> = listOf(
        "Q3/Q4 plan" to "Q3%2FQ4 plan",
        "50% done" to "50%25 done",
        "50% done..." to "50%25 done%2E%2E%2E",
        "a/b\\c:d*e?f\"g<h>i|j%k" to "a%2Fb%5Cc%3Ad%2Ae%3Ff%22g%3Ch%3Ei%7Cj%25k",
        ".hidden" to "%2Ehidden",
        "pad  " to "pad%20%20",
        "tab\tin" to "tab%09in",
        "v1.2 notes" to "v1.2 notes",
        "Trip to **Lisbon**" to "Trip to Lisbon",
        "# Heading" to "Heading",
        "> Quote *it*" to "Quote it",
        "[site](https://example.com)" to "site",
        "See [the site](https://example.com) [draft]" to "See the site [draft]",
        "![](Images/x.png)" to "Untitled",
        "![alt|300](pic.png) caption" to " caption",
        "`**code**` and ~~gone~~" to "%2A%2Acode%2A%2A and gone",
        "*it **bo** it*" to "it bo it",
        "lonely * star" to "lonely %2A star",
        "#tag and **#bold**" to "and",
        "1-1 #framna-sensitive" to "1-1",
        "Meet #x with Bob" to "Meet with Bob",
        "#private" to "Untitled",
        "Issue id#42" to "Issue id#42",
        "" to "Untitled",
        "Möten & %2F" to "Möten & %252F",
        "x".repeat(200) to "x".repeat(120),
        "y".repeat(118) + "/z" to "y".repeat(118),
        "å".repeat(100) to "å".repeat(60),
    )

    @Test
    fun script_folder_names_match_the_app() {
        for ((title, expected) in folderNameCases) {
            assertEquals(expected, FolderName.forTitle(title), "title: $title")
        }
    }

    private val root = "/vault"

    /** Part of `scripts/fixtures/dynalist/expected/`, verbatim. */
    private val imported: Map<String, String> = linkedMapOf(
        "_node.md" to "- Dynalist Import [↳](<Dynalist Import/_node.md>)\n",
        "Dynalist Import/_node.md" to "- Empty doc\n- Work [↳](<Work/_node.md>)\n",
        "Dynalist Import/Work/_node.md" to
            "- Meetings [↳](<Meetings/_node.md>)\n" +
            "- Q3/Q4 plan [↳](<Q3%252FQ4 plan/_node.md>)\n" +
            "- Call Bob\n" +
            "- Fence note [↳](<Fence note/_node.md>)\n" +
            "- Ideas [↳](<Ideas/_node.md>)\n" +
            "- ideas [↳](<ideas (2)/_node.md>)\n" +
            "- [↳](<Untitled/_node.md>)\n" +
            "- See [the site](https://example.com) [draft] [↳](<See the site [draft]/_node.md>)\n" +
            "- 50% done... [↳](<50%2525 done%252E%252E%252E/_node.md>)\n" +
            "- Blank note\n",
        "Dynalist Import/Work/Meetings/_node.md" to "- Weekly **sync** [↳](<Weekly sync/_node.md>)\n- 1:1 with Anna\n",
        "Dynalist Import/Work/Meetings/Weekly sync/_node.md" to
            "> Agenda:\n> - status\n> - blockers\n- Prepare slides\n",
        "Dynalist Import/Work/Q3%2FQ4 plan/_node.md" to "- Hire\n- Ship\n",
        "Dynalist Import/Work/Fence note/_node.md" to "> Before\n> :::\n> After\n",
        "Dynalist Import/Work/Ideas/_node.md" to "- One\n",
        "Dynalist Import/Work/ideas (2)/_node.md" to "- Two\n",
        "Dynalist Import/Work/Untitled/_node.md" to "- Orphan child\n",
        "Dynalist Import/Work/See the site [draft]/_node.md" to "- Link child\n",
        "Dynalist Import/Work/50%25 done%2E%2E%2E/_node.md" to "- Tail\n",
    )

    @Test
    fun an_import_loads_as_the_full_tree_and_saves_back_unchanged() = runTest {
        val fs = InMemoryFileSystem()
        for ((rel, text) in imported) fs.writeFile("$root/$rel", text)
        val dirsBefore = fs.dirs.toSet()
        val repo = NoteRepository(fs, root, nowMillis = { 0L })
        val doc = Document(repo, backgroundScope, "_node.md")
        doc.start()
        doc.stateFlow.first { it.isLoaded }

        // Expand every folder-backed bullet, level by level.
        while (true) {
            val state = doc.stateFlow.value
            val next = state.lineIds.firstOrNull { it in state.unloadedRefIds } ?: break
            doc.acquireExpansion(next)
        }

        val b = BlockLayout::firstLine
        val n = BlockLayout::nextLine
        assertEquals(
            listOf(
                "* Dynalist Import",
                "  * Empty doc",
                "  * Work",
                "    * Meetings",
                "      * Weekly **sync**",
                b(8, "Agenda:"), n(8, "- status"), n(8, "- blockers"),
                "        * Prepare slides",
                "      * 1:1 with Anna",
                "    * Q3/Q4 plan",
                "      * Hire",
                "      * Ship",
                "    * Call Bob",
                "    * Fence note",
                b(6, "Before"), n(6, ":::"), n(6, "After"),
                "    * Ideas",
                "      * One",
                "    * ideas",
                "      * Two",
                "    * ",
                "      * Orphan child",
                "    * See [the site](https://example.com) [draft]",
                "      * Link child",
                "    * 50% done...",
                "      * Tail",
                "    * Blank note",
            ),
            doc.stateFlow.value.lines,
        )

        // Force a save of the whole expanded tree: add a root leaf, save,
        // take it away again, save.
        val last = doc.stateFlow.value.lines.lastIndex
        doc.insertText(last, doc.stateFlow.value.lines[last].length, "\n* temp")
        doc.flush()
        doc.deleteLine(doc.stateFlow.value.lines.lastIndex)
        doc.flush()

        for ((rel, text) in imported) assertEquals(text, fs.read(root, rel), rel)
        assertEquals(dirsBefore, fs.dirs.toSet())
    }
}
