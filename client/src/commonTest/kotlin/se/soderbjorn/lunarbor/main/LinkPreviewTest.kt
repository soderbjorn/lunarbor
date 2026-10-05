/*
 * LinkPreviewTest.kt (commonTest)
 * -------------------------------
 * Link bullets to nodes: which bullets can mirror a node
 * ([linkPreviewPathOf]), what a node's listing holds
 * ([linkPreviewItemsOf], used by 3D mode), and zooming into a mirror,
 * whose page is the node itself, editable (more in `MirrorTest`).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NodeLine
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPreviewTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private lateinit var registry: DocumentRegistry

    private suspend fun TestScope.pane(): PaneBackingViewModel {
        registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    @Test
    fun only_a_bullet_with_exactly_one_tf_link_can_preview() {
        assertEquals("Work/Secret", linkPreviewPathOf("  * [Secret](lunarbor:/Work/Secret)"))
        assertEquals("Work", linkPreviewPathOf("* See [work](lunarbor:/Work) first"))
        assertNull(linkPreviewPathOf("* [a](lunarbor:/A) and [b](lunarbor:/B)"))
        assertNull(linkPreviewPathOf("* [site](https://example.com)"))
        assertNull(linkPreviewPathOf("* plain"))
        assertNull(linkPreviewPathOf(BlockLayout.firstLine(0, "[a](lunarbor:/A)")))
    }

    @Test
    fun a_preview_lists_the_nodes_bullets_with_their_own_folders() {
        val items = linkPreviewItemsOf(
            "Work",
            listOf(
                NodeLine.Leaf("**1-1**:s"),
                NodeLine.Folder("Plans", "Plans"),
                NodeLine.Block(listOf("# Notes", "x"), "Notes"),
                NodeLine.Text("stray"),
                NodeLine.Leaf(""),
            ),
        )
        assertEquals(
            listOf(
                LinkPreviewItem("1-1:s", null),
                LinkPreviewItem("Plans", "Work/Plans"),
                LinkPreviewItem("Notes", "Work/Notes"),
            ),
            items,
        )
    }

    @Test
    fun zoomed_into_a_mirror_the_page_is_the_node_editable() = runTest {
        fs.writeFile("$root/_node.md", "- [Plans](lunarbor:/Plans)\n- Other\n")
        fs.writeFile("$root/Plans/_node.md", "- x\n- y\n")
        val p = pane()
        p.zoomInto(0)
        runCurrent()
        assertEquals(listOf("* [Plans](lunarbor:/Plans)", "  * x", "  * y", "* Other"), p.lines)
        assertEquals(p.id(0), p.stateFlow.value.zoomedLineId)
        assertFalse(p.stateFlow.value.isReadOnlyPage)
        p.moveTo(2, p.lines[2].length)
        p.insertText("z")
        registry.flushAll()
        assertEquals("- x\n- yz\n", fs.read(root, "Plans/_node.md"))
    }
}
