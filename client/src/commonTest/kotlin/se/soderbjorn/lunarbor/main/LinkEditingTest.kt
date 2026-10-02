/*
 * LinkEditingTest.kt (commonTest)
 * -------------------------------
 * Tests for the link popup's intents on [PaneBackingViewModel]:
 * [PaneBackingViewModel.retargetLinkAt], [PaneBackingViewModel.removeLinkAt]
 * and [PaneBackingViewModel.editLinkTextAt], against the real stack on
 * [InMemoryFileSystem].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LinkEditingTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun TestScope.note(text: String): PaneBackingViewModel {
        fs.writeFile("$root/_node.md", "- x\n")
        fs.writeFile("$root/Note.md", text)
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "Note.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines

    @Test
    fun changing_a_wiki_link_makes_a_tf_link_with_the_same_text() = runTest {
        val p = note("Read [[How to be concise]] (Wes)")
        p.retargetLinkAt(0, 8, LinkTarget("Reading/Concise.md", "Concise", VaultEntryKind.MARKDOWN))
        assertEquals("Read [How to be concise](lunarbor:/Reading/Concise.md) (Wes)", p.lines[0])
        p.undo()
        assertEquals("Read [[How to be concise]] (Wes)", p.lines[0])
    }

    @Test
    fun removing_a_link_keeps_its_text() = runTest {
        val p = note("See [soups](lunarbor:/Soups) now")
        p.removeLinkAt(0, 6)
        assertEquals("See soups now", p.lines[0])
    }

    @Test
    fun edit_text_puts_the_caret_at_the_end_of_the_label() = runTest {
        val p = note("See [soups](lunarbor:/Soups) now")
        p.editLinkTextAt(0, 6)
        val s = p.stateFlow.value
        assertEquals(0 to 10, s.cursorRow to s.cursorCol)
    }
}
