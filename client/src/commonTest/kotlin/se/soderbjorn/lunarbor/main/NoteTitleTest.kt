/*
 * NoteTitleTest.kt (commonTest)
 * -----------------------------
 * Tests for a `.md` note's page title, against the real stack —
 * [PaneBackingViewModel] over [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem]:
 *
 *  - a first line `# <file name>` is hidden from the editor (the title
 *    already shows it), and the caret can never reach it;
 *  - editing the title renames the file (`renameActiveFile`), rewrites
 *    that hidden heading, keeps every pane on the note and every link to
 *    it pointing at the new name, and never overwrites another file;
 *  - an image's or drawing's title renames it the same way, keeping its
 *    extension, rewrites the embeds that show it, and a drawing's
 *    unwritten change goes with it.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoteTitleTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private lateinit var registry: DocumentRegistry

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private fun read(rel: String): String? = fs.read(root, rel)

    /** Opens a pane on [fileRel] (a fresh registry unless [shared]) and waits for it to load. */
    private suspend fun TestScope.pane(fileRel: String, shared: Boolean = false): PaneBackingViewModel {
        if (!shared) registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, fileRel)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.s get() = stateFlow.value

    // ---------------------------------------------------- hidden heading

    @Test
    fun heading_repeating_the_file_name_is_hidden_and_unreachable() = runTest {
        seed("Jakob.md", "# Jakob\nfirst\nsecond")
        val p = pane("Jakob.md")
        assertTrue(p.s.hidesTitleHeading)
        assertEquals(1, p.s.firstEditableRow)
        // The caret starts on the first visible row, not the heading.
        assertEquals(1, p.s.cursorRow)
        p.moveUp()
        assertEquals(1, p.s.cursorRow)
        p.moveLineStart()
        p.moveLeft()
        assertEquals(1, p.s.cursorRow)
        // Backspace at the start never merges into the hidden heading.
        p.backspace()
        assertEquals(listOf("# Jakob", "first", "second"), p.s.lines)
        p.selectAll()
        assertEquals(1, p.s.anchorRow)
    }

    @Test
    fun blank_lines_after_the_hidden_heading_are_hidden_too() = runTest {
        seed("Jakob.md", "# Jakob\n\n  \n*19:22* body\n\nmore")
        val p = pane("Jakob.md")
        assertEquals(3, p.s.firstEditableRow)
        // The caret opens at the start of the first visible row — not at
        // the column just past the hidden heading's `# `.
        assertEquals(3 to 0, p.s.cursorRow to p.s.cursorCol)
        p.moveUp()
        assertEquals(3, p.s.cursorRow)
        // Only blanks after the heading: the last one stays visible.
        seed("Empty.md", "# Empty\n\n")
        val q = pane("Empty.md", shared = true)
        assertEquals(q.s.lines.lastIndex, q.s.firstEditableRow)
    }

    @Test
    fun a_decomposed_file_name_still_matches_a_composed_heading() = runTest {
        // macOS spells the name's `ö` as `o` + U+0308; the text has U+00F6.
        val fileRel = "2026-09-15 Johan Lundstro\u0308m.md"
        seed(fileRel, "# 2026-09-15 Johan Lundstr\u00F6m\nbody")
        val p = pane(fileRel)
        assertTrue(p.s.hidesTitleHeading)
        assertEquals("2026-09-15 Johan Lundstr\u00F6m", NoteRepository.displayNameOf(fileRel))
    }

    @Test
    fun other_first_lines_stay_visible() = runTest {
        seed("A.md", "# Something else\nbody")
        seed("B.md", "## B\nbody")
        seed("C.md", "# C")
        registry = DocumentRegistry(repo, backgroundScope)
        for (file in listOf("A.md", "B.md", "C.md")) {
            val p = pane(file, shared = true)
            assertFalse(p.s.hidesTitleHeading, file)
            assertEquals(0, p.s.firstEditableRow, file)
        }
        // Outlines never hide a row.
        seed("_node.md", "- x\n")
        assertFalse(pane("_node.md", shared = true).s.hidesTitleHeading)
    }

    // ------------------------------------------------------------ rename

    @Test
    fun renaming_moves_the_file_and_rewrites_the_hidden_heading() = runTest {
        seed("Jakob.md", "# Jakob\nbody")
        val p = pane("Jakob.md")
        p.renameActiveFile("  Jakob 1:1  ")
        runCurrent()
        // `:` is unsafe in a file name: encoded like a folder name.
        val newRel = "Jakob 1%3A1.md"
        assertEquals(newRel, p.s.activeFileRel)
        assertNull(read("Jakob.md"))
        assertEquals("# Jakob 1:1\nbody", read(newRel)?.trimEnd())
        assertEquals("Jakob 1:1", NoteRepository.displayNameOf(newRel))
        assertTrue(p.s.hidesTitleHeading)
        // Edits keep saving to the new name.
        p.insertText("!")
        registry.acquire(newRel).also { it.flush() }
        registry.release(newRel)
        assertNull(read("Jakob.md"))
        assertTrue(read(newRel)!!.contains("!"))
    }

    @Test
    fun renaming_leaves_content_alone_without_a_title_heading() = runTest {
        seed("Old.md", "just text")
        val p = pane("Old.md")
        p.renameActiveFile("New")
        runCurrent()
        assertEquals("New.md", p.s.activeFileRel)
        assertEquals("just text", read("New.md")?.trimEnd())
    }

    @Test
    fun a_taken_name_gets_a_suffix_and_nothing_is_overwritten() = runTest {
        seed("Old.md", "mine")
        seed("New.md", "theirs")
        val p = pane("Old.md")
        p.renameActiveFile("new")
        runCurrent()
        assertEquals("new (2).md", p.s.activeFileRel)
        assertEquals("theirs", read("New.md"))
    }

    @Test
    fun blank_unchanged_and_app_file_titles_do_nothing() = runTest {
        seed("Old.md", "x")
        seed("Starred.md", "* [A](lunarbor:/A)")
        val p = pane("Old.md")
        p.renameActiveFile("   ")
        p.renameActiveFile("Old")
        runCurrent()
        assertEquals("Old.md", p.s.activeFileRel)
        assertEquals("x", read("Old.md"))
        val starred = pane("Starred.md", shared = true)
        assertFalse(starred.s.canRenameFromTitle)
        starred.renameActiveFile("Bookmarks")
        runCurrent()
        assertEquals("Starred.md", starred.s.activeFileRel)
    }

    @Test
    fun other_panes_history_and_links_follow_the_rename() = runTest {
        seed("_node.md", "- See [note](lunarbor:/Old.md)\n")
        seed("Old.md", "# Old\nbody")
        registry = DocumentRegistry(repo, backgroundScope)
        val outline = pane("_node.md", shared = true)
        outline.navigateToVaultFile("Old.md")
        outline.stateFlow.first { it.activeFileRel == "Old.md" && it.isLoaded }
        val other = pane("Old.md", shared = true)
        other.renameActiveFile("New")
        runCurrent()
        assertEquals("New.md", other.s.activeFileRel)
        assertEquals("New.md", outline.s.activeFileRel)
        assertEquals("# New\nbody", read("New.md")?.trimEnd())
        // The link in the (not open) root outline was rewritten on disk.
        assertTrue(read("_node.md")!!.contains("[note](New.md)"), read("_node.md"))
        assertEquals("New.md", registry.renamedTo("Old.md"))
        // Back to the outline, forward again: the history holds the new name.
        outline.zoomBack()
        outline.stateFlow.first { it.activeFileRel == "_node.md" && it.isLoaded }
        outline.zoomForward()
        outline.stateFlow.first { it.activeFileRel == "New.md" && it.isLoaded }
    }

    // ------------------------------------------------- images, drawings

    /** Opens a pane on the root outline, then moves it to the file view [fileRel]. */
    private suspend fun TestScope.fileViewPane(fileRel: String): PaneBackingViewModel {
        val p = pane("_node.md")
        p.navigateToVaultFile(fileRel)
        p.stateFlow.first { it.activeFileRel == fileRel && it.isFileView }
        runCurrent()
        return p
    }

    @Test
    fun an_image_title_renames_the_file_and_keeps_its_extension() = runTest {
        seed("_node.md", "- See [shot](lunarbor:/shot.png)\n")
        seed("shot.png", "PNG")
        seed("photo.png", "other")
        val p = fileViewPane("shot.png")
        assertTrue(p.s.canRenameFromTitle)
        // A typed matching extension is dropped, so it is not doubled.
        p.renameActiveFile("Beach.png")
        runCurrent()
        assertEquals("Beach.png", p.s.activeFileRel)
        assertEquals("PNG", read("Beach.png"))
        assertNull(read("shot.png"))
        // Without an extension the old one is kept; a taken name gets ` (2)`.
        p.renameActiveFile("photo")
        runCurrent()
        assertEquals("photo (2).png", p.s.activeFileRel)
        assertEquals("other", read("photo.png"))
        // Any other typed extension is part of the name, never a new type.
        p.renameActiveFile("pic.jpg")
        runCurrent()
        assertEquals("pic.jpg.png", p.s.activeFileRel)
        registry.flushAll()
        assertTrue(read("_node.md")!!.contains("[shot](pic.jpg.png)"), read("_node.md"))
    }

    @Test
    fun renaming_an_embedded_file_rewrites_its_embeds() = runTest {
        // Open outline: a bare-name embed; on disk: a vault-rooted one in a
        // note elsewhere, and a same-named file in another folder left alone.
        seed("_node.md", "- Flow ![wide|300](Flow.excalidraw) and ![](other.png)\n")
        seed("Flow.excalidraw", "{}")
        seed("Notes/Plan.md", "See ![](/Flow.excalidraw)\nAnd ![](Flow.excalidraw)\n")
        val p = fileViewPane("Flow.excalidraw")
        p.renameActiveFile("Process flow")
        runCurrent()
        assertEquals("Process flow.excalidraw", p.s.activeFileRel)
        registry.flushAll()
        assertEquals(
            "- Flow ![wide|300](<Process flow.excalidraw>) and ![](other.png)",
            read("_node.md")!!.trimEnd(),
        )
        assertEquals(
            "See ![](</Process flow.excalidraw>)\nAnd ![](Flow.excalidraw)\n",
            read("Notes/Plan.md"),
        )
    }

    @Test
    fun a_drawing_rename_takes_its_unwritten_change_along() = runTest {
        seed("_node.md", "- x\n")
        seed("Untitled.excalidraw", "{\"v\":1}")
        val p = fileViewPane("Untitled.excalidraw")
        p.onDrawingChanged("Untitled.excalidraw", "{\"v\":2}")
        runCurrent()
        p.renameActiveFile("Flow")
        runCurrent()
        assertEquals("Flow.excalidraw", p.s.activeFileRel)
        assertNull(read("Untitled.excalidraw"))
        assertEquals("{\"v\":2}", read("Flow.excalidraw"))
        assertEquals("Flow.excalidraw", registry.renamedTo("Untitled.excalidraw"))
    }
}
