/*
 * LinkEditingTest.kt (commonTest)
 * -------------------------------
 * Tests for the Edit link dialog's intents on [PaneBackingViewModel]:
 * [PaneBackingViewModel.linkAt], [PaneBackingViewModel.updateLinkAt] and
 * [PaneBackingViewModel.removeLinkAt], against the real stack on
 * [InMemoryFileSystem].
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
    fun changing_a_wiki_link_makes_a_markdown_link_with_the_same_text() = runTest {
        val p = note("Read [[How to be concise]] (Wes)")
        p.updateLinkAt(0, 8, "How to be concise", "lunarbor:/Reading/Concise.md")
        assertEquals("Read [How to be concise](lunarbor:/Reading/Concise.md) (Wes)", p.lines[0])
        p.undo()
        assertEquals("Read [[How to be concise]] (Wes)", p.lines[0])
    }

    @Test
    fun updating_sets_text_and_url_and_escapes_brackets() = runTest {
        val p = note("See [soups](lunarbor:/Soups) now")
        val link = p.linkAt(0, 6)!!
        assertEquals("soups" to "lunarbor:/Soups", link.text to link.url)
        p.updateLinkAt(0, 6, "Soups [all]", "https://example.org/a b")
        assertEquals("See [Soups \\[all\\]](<https://example.org/a b>) now", p.lines[0])
        assertEquals("Soups [all]", p.linkAt(0, 6)!!.text)
    }

    @Test
    fun a_url_shown_as_itself_is_written_bare_and_a_blank_url_unlinks() = runTest {
        val p = note("See [x](https://a.org) now")
        p.updateLinkAt(0, 6, "", "https://b.org")
        assertEquals("See https://b.org now", p.lines[0])
        val q = note("See [soups](lunarbor:/Soups) now")
        q.updateLinkAt(0, 6, "soups", " ")
        assertEquals("See soups now", q.lines[0])
    }

    @Test
    fun removing_a_link_keeps_its_text() = runTest {
        val p = note("See [soups](lunarbor:/Soups) now")
        p.removeLinkAt(0, 6)
        assertEquals("See soups now", p.lines[0])
    }
}
