/*
 * OpenLocationTest.kt (commonTest)
 * --------------------------------
 * Tests for opening a pane where another one is ("New window" in the web
 * shell): [PaneBackingViewModel.currentLocation] and
 * [PaneBackingViewModel.openLocation], against the real stack on
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
class OpenLocationTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private lateinit var registry: DocumentRegistry

    private suspend fun TestScope.pane(fileRel: String = "_node.md"): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, fileRel)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    @Test
    fun a_new_pane_opens_at_a_zoomed_block_inside_a_folded_node() = runTest {
        fs.writeFile("$root/_node.md", "- 13 april [↳](<13 april/_node.md>)\n- other\n")
        fs.writeFile("$root/13 april/_node.md", "> foo\n>\n> kk\n")
        registry = DocumentRegistry(repo, backgroundScope)
        val a = pane()
        a.zoomInto(0)
        runCurrent()
        a.zoomInto(1)
        runCurrent()
        val location = a.currentLocation()
        assertEquals(listOf("13 april", "foo"), location.zoomTitlePath)
        val b = PaneBackingViewModel(registry, backgroundScope, location.fileRel)
        b.openLocation(location)
        runCurrent()
        val s = b.stateFlow.value
        assertEquals(a.stateFlow.value.zoomedLineId, s.zoomedLineId)
        assertEquals(listOf("13 april", "foo"), b.zoomPathSegments(s))
    }

    @Test
    fun a_new_pane_opens_at_a_note_and_at_an_image() = runTest {
        fs.writeFile("$root/_node.md", "- x\n")
        fs.writeFile("$root/Note.md", "hello")
        registry = DocumentRegistry(repo, backgroundScope)
        val b = pane()
        b.openLocation(PaneBackingViewModel.FileHistoryEntry("Note.md"))
        runCurrent()
        assertEquals("Note.md", b.stateFlow.value.activeFileRel)
        assertEquals(emptyList(), b.stateFlow.value.fileHistory)
        b.openLocation(PaneBackingViewModel.FileHistoryEntry("pic.png"))
        runCurrent()
        assertEquals("pic.png", b.stateFlow.value.activeFileRel)
    }
}
