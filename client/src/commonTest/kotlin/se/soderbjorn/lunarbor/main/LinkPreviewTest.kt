/*
 * LinkPreviewTest.kt (commonTest)
 * -------------------------------
 * Read-only previews of linked nodes: which bullets can preview
 * ([linkPreviewPathOf]), what a preview lists ([linkPreviewItemsOf]), and
 * the pane end to end — [PaneBackingViewModel.linkPreviewOf] reading the
 * node through the registry, [PaneBackingViewModel.toggleLinkPreview],
 * and the preview following a save of the linked node.
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

    /** Asks for row [row]'s preview, lets the read finish, and asks again. */
    private fun TestScope.previewOf(p: PaneBackingViewModel, row: Int): LinkPreview? {
        p.linkPreviewOf(p.stateFlow.value, row)
        runCurrent()
        return p.linkPreviewOf(p.stateFlow.value, row)
    }

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
    fun a_link_bullet_to_a_node_previews_it_and_follows_its_saves() = runTest {
        fs.writeFile("$root/_node.md", "- Framna [↳](<Framna/_node.md>)\n- Framna - känsligt [↳](<Framna - känsligt/_node.md>)\n")
        fs.writeFile("$root/Framna/_node.md", "- [Framna - känsligt](lunarbor:/Framna%20-%20känsligt)\n- [none](lunarbor:/Missing)\n")
        fs.writeFile("$root/Framna - känsligt/_node.md", "- 1-1:s\n- Plans [↳](<Plans/_node.md>)\n")
        fs.writeFile("$root/Framna - känsligt/Plans/_node.md", "- x\n")
        val p = pane()
        p.toggleCollapse(p.id(0))
        runCurrent()
        assertEquals("  * [Framna - känsligt](lunarbor:/Framna%20-%20känsligt)", p.lines[1])

        val preview = previewOf(p, 1)
        assertEquals("Framna - känsligt", preview?.pathRel)
        assertEquals(listOf(LinkPreviewItem("1-1:s", null), LinkPreviewItem("Plans", "Framna - känsligt/Plans")), preview?.items)
        // A link to nothing previews nothing; nor does a folder-backed bullet.
        assertNull(previewOf(p, 2))
        assertNull(previewOf(p, 0))

        // Opening it is pane state only.
        p.toggleLinkPreview(p.id(1))
        assertTrue(p.id(1) in p.stateFlow.value.expandedLinkIds)
        p.toggleLinkPreview(p.id(1))
        assertFalse(p.id(1) in p.stateFlow.value.expandedLinkIds)

        // The linked node changes on disk; the next refresh shows it.
        fs.writeFile("$root/Framna - känsligt/_node.md", "- 1-1:s\n- new\n")
        registry.refreshVaultListings()
        runCurrent()
        assertEquals(
            listOf(LinkPreviewItem("1-1:s", null), LinkPreviewItem("new", null)),
            p.linkPreviewOf(p.stateFlow.value, 1)?.items,
        )
    }

    @Test
    fun zoomed_into_a_link_bullet_the_page_shows_its_preview_read_only() = runTest {
        fs.writeFile("$root/_node.md", "- [Plans](lunarbor:/Plans)\n- Plans [↳](<Plans/_node.md>)\n")
        fs.writeFile("$root/Plans/_node.md", "- x\n- y\n")
        val p = pane()
        previewOf(p, 0)
        val before = p.lines
        p.zoomInto(0)
        runCurrent()
        // No placeholder child to type in, and the zoom holds without one.
        assertEquals(before, p.lines)
        assertEquals(p.id(0), p.stateFlow.value.zoomedLineId)
        assertTrue(p.stateFlow.value.isReadOnlyPage)
        assertEquals(listOf("x", "y"), p.zoomLinkPreviewOf(p.stateFlow.value)?.items?.map { it.title })

        // Nothing edits it.
        p.insertText("own")
        p.insertNewline()
        assertEquals(before, p.lines)
    }
}
