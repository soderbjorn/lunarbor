/*
 * InlineStyleToggleTest.kt (commonTest)
 * -------------------------------------
 * Tests for toggling an inline style (Cmd-B / Cmd-I, the style palette)
 * on a selection through [PaneBackingViewModel.applyInlineStyle], over
 * the real stack on [InMemoryFileSystem]. Pins that repeated toggles
 * alternate on / off, whichever side of the hidden markers the selection
 * ends land on, and that toggling one style keeps the other.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InlineStyleToggleTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun TestScope.pane(content: String): PaneBackingViewModel {
        fs.writeFile("$root/_node.md", content)
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.line get() = stateFlow.value.lines[0]

    /** Selects [from]..[to] of row 0, as a DOM selection sync would. */
    private fun PaneBackingViewModel.select(from: Int, to: Int) = setSelection(0, from, 0, to)

    @Test
    fun italic_toggles_on_and_off_repeatedly() = runTest {
        val p = pane("* word\n")
        p.select(2, 6)
        repeat(3) {
            p.applyInlineStyle(InlineStyle.ITALIC)
            assertEquals("* *word*", p.line)
            p.applyInlineStyle(InlineStyle.ITALIC)
            assertEquals("* word", p.line)
        }
    }

    @Test
    fun a_selection_that_includes_the_hidden_markers_still_toggles_off() = runTest {
        val p = pane("* *word*\n")
        p.select(2, 8)
        p.applyInlineStyle(InlineStyle.ITALIC)
        assertEquals("* word", p.line)
        // Only one end past the markers.
        p.select(2, 6)
        p.applyInlineStyle(InlineStyle.ITALIC)
        p.select(3, 8)
        p.applyInlineStyle(InlineStyle.ITALIC)
        assertEquals("* word", p.line)
    }

    @Test
    fun italic_on_bold_text_adds_italic_and_removes_only_italic() = runTest {
        val p = pane("* **word**\n")
        p.select(4, 8)
        p.applyInlineStyle(InlineStyle.ITALIC)
        assertEquals("* ***word***", p.line)
        p.applyInlineStyle(InlineStyle.ITALIC)
        assertEquals("* **word**", p.line)
        // Same with the markers inside the selection.
        p.select(2, 10)
        p.applyInlineStyle(InlineStyle.ITALIC)
        assertEquals("* ***word***", p.line)
        p.select(2, 12)
        p.applyInlineStyle(InlineStyle.BOLD)
        assertEquals("* *word*", p.line)
    }
}
