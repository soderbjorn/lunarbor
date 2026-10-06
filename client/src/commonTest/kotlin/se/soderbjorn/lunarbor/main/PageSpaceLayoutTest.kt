/*
 * PageSpaceLayoutTest.kt (commonTest)
 * -----------------------------------
 * Tests for LBR-11's 3D "Pages" space: the pure layout
 * ([PageSpaceLayout] — where pages and cameras hang, deterministically)
 * and the page model ([PageSpaceModel], [PaneBackingViewModel.spacePageOf]
 * — which pages surround the page a pane is on), the latter against the
 * real stack on [InMemoryFileSystem].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PrivacyFilter
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PageSpaceLayoutTest {

    private val g = PageSpaceLayout.geometry(1400.0, 900.0)

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6

    // ------------------------------------------------------------- layout

    @Test
    fun geometry_shows_the_current_page_at_one_to_one() {
        // A page at z = 0 and the camera at cameraDistance: a 25° half-angle
        // spans exactly half the view's height.
        val half = g.cameraDistance * kotlin.math.tan(PageSpaceLayout.FOV_DEGREES / 2 * kotlin.math.PI / 180)
        assertTrue(near(half, 450.0))
        assertEquals(PageSpaceLayout.MAX_PAGE_WIDTH, g.pageWidth)
        assertEquals(900.0 - PageSpaceLayout.TOP_PAD - PageSpaceLayout.BOTTOM_PAD, g.pageHeight)
        // A mid-sized view leaves room beside the page; a narrow one shrinks it to fit.
        assertEquals(round(1200.0 * PageSpaceLayout.PAGE_WIDTH_SHARE), PageSpaceLayout.geometry(1200.0, 600.0).pageWidth)
        assertEquals(PageSpaceLayout.MIN_PAGE_WIDTH, PageSpaceLayout.geometry(500.0, 600.0).pageWidth)
        assertEquals(380.0 - PageSpaceLayout.SIDE_MARGIN, PageSpaceLayout.geometry(380.0, 600.0).pageWidth)
        // A short view (a split window) keeps the page inside it.
        assertEquals(200.0 - PageSpaceLayout.TOP_PAD - PageSpaceLayout.BOTTOM_PAD, PageSpaceLayout.geometry(500.0, 200.0).pageHeight)
    }

    @Test
    fun children_split_into_a_left_and_a_right_column_further_back() {
        val offsets = PageSpaceLayout.childOffsets(5, g)
        assertEquals(5, offsets.size)
        // First half (rounded up) left, the rest right.
        assertTrue(offsets.take(3).all { it.x < 0 })
        assertTrue(offsets.drop(3).all { it.x > 0 })
        // Each column starts one depth step back; each further page is further back.
        assertEquals(-PageSpaceLayout.DEPTH_STEP, offsets[0].z)
        assertEquals(-PageSpaceLayout.DEPTH_STEP - 2 * PageSpaceLayout.COLUMN_STEP, offsets[2].z)
        assertEquals(-PageSpaceLayout.DEPTH_STEP, offsets[3].z)
        // Top to bottom in outline order, centred on the page.
        assertTrue(offsets[0].y > offsets[1].y && offsets[1].y > offsets[2].y)
        assertTrue(near(offsets[1].y, 0.0))
        assertTrue(near(offsets[3].y, -offsets[4].y))
    }

    @Test
    fun child_pages_land_beside_the_current_page_not_behind_it() {
        val child = PageSpaceLayout.childOffsets(1, g).single()
        val k = g.columnScale
        // The child's projected inner edge clears the current page's edge.
        val innerEdge = abs(child.x) * k - g.pageWidth * k / 2
        assertTrue(innerEdge >= g.pageWidth / 2)
    }

    @Test
    fun layout_is_deterministic_and_relative_to_its_origin() {
        val tree = SpaceTree("a", listOf(SpaceTree("b", listOf(SpaceTree("d"))), SpaceTree("c")))
        val first = PageSpaceLayout.layout(tree, g)
        assertEquals(first, PageSpaceLayout.layout(tree, g))
        assertEquals(SpaceVec.ZERO, first["a"])
        val offsets = PageSpaceLayout.childOffsets(2, g)
        assertEquals(offsets[0], first["b"])
        assertEquals(offsets[1], first["c"])
        assertEquals(offsets[0] + PageSpaceLayout.columnOffsets(1, -1.0, g)[0], first["d"])
        // Moved to another origin, every page moves with it.
        val origin = SpaceVec(100.0, -50.0, -3000.0)
        val moved = PageSpaceLayout.layout(tree, g, origin)
        for ((key, at) in first) assertEquals(at + origin, moved[key])
    }

    @Test
    fun grandchildren_hang_on_their_parents_outer_side() {
        val kids = (1..4).map { SpaceTree("g$it") }
        val tree = SpaceTree("a", listOf(SpaceTree("left", kids), SpaceTree("right", kids.map { SpaceTree(it.key + "r") })))
        val at = PageSpaceLayout.layout(tree, g)
        assertTrue(kids.all { at.getValue(it.key).x < at.getValue("left").x })
        assertTrue(kids.all { at.getValue(it.key + "r").x > at.getValue("right").x })
    }

    @Test
    fun going_up_puts_the_parent_where_the_child_stays_in_place() {
        val child = SpaceVec(10.0, 20.0, -5000.0)
        val parent = PageSpaceLayout.parentPosition(child, index = 2, siblingCount = 4, g = g)
        val tree = SpaceTree("p", listOf("a", "b", "c", "d").map { SpaceTree(it) })
        assertEquals(child, PageSpaceLayout.layout(tree, g, parent)["c"])
    }

    @Test
    fun the_whole_vault_hangs_round_the_current_page_by_pages_rules() {
        val c = SpaceTree("c", listOf(SpaceTree("c1", listOf(SpaceTree("c1a"))), SpaceTree("c2")))
        val p = SpaceTree("p", listOf(SpaceTree("s1"), c, SpaceTree("s3", listOf(SpaceTree("s3a")))))
        val root = SpaceTree("r", listOf(p, SpaceTree("q", listOf(SpaceTree("q1")))))
        val at = SpaceVec(100.0, 50.0, -4000.0)
        val whole = PageSpaceLayout.wholeLayout(root, "c", at, g)
        // Every page has a place.
        assertEquals(setOf("r", "p", "q", "q1", "s1", "c", "c1", "c1a", "c2", "s3", "s3a"), whole.keys)
        // The current page's subtree is exactly where Pages shows it.
        for ((key, pos) in PageSpaceLayout.layout(c, g, at)) assertEquals(pos, whole[key])
        // Its parent is where going up puts it, its siblings in their slots.
        val parent = PageSpaceLayout.parentPosition(at, index = 1, siblingCount = 3, g = g)
        assertEquals(parent, whole["p"])
        val offsets = PageSpaceLayout.childOffsets(3, g)
        assertEquals(parent + offsets[0], whole["s1"])
        assertEquals(parent + offsets[2], whole["s3"])
        // And so on up to the root.
        assertEquals(PageSpaceLayout.parentPosition(parent, index = 0, siblingCount = 2, g = g), whole["r"])
        // An unknown current page lays the tree out from there.
        assertEquals(at, PageSpaceLayout.wholeLayout(root, "nope", at, g)["r"])
    }

    @Test
    fun the_camera_sits_in_front_of_the_page() {
        val page = SpaceVec(300.0, 40.0, -1600.0)
        val cam = PageSpaceLayout.cameraFor(page, g)
        assertEquals(page.x, cam.x)
        assertEquals(page.z + g.cameraDistance, cam.z)
        // With equal pads the page is centred vertically.
        assertEquals(page.y, cam.y)
    }

    @Test
    fun flights_last_between_three_quarters_and_one_and_a_half_seconds() {
        assertEquals(0.75, PageSpaceLayout.flightSeconds(0.0))
        assertEquals(1.5, PageSpaceLayout.flightSeconds(1e6))
    }

    // -------------------------------------------------------------- model

    @Test
    fun only_items_with_children_folders_or_unloaded_children_are_pages() {
        val lines = listOf("* Leaf", "* Parent", "  * Kid", "    * Grandkid", "* Folded", "* Linked")
        val ids = lines.indices.map { LineId(it.toLong() + 1) }
        val children = PageSpaceModel.childrenOf(
            lines, ids, 0, lines.lastIndex, "_node.md",
            folderOf = { if (it == ids[5]) "Linked" else null },
            unloaded = setOf(ids[4]),
            previewOf = { if (it == "Linked") listOf(LinkPreviewItem("One", null), LinkPreviewItem("Two", "Linked/Two")) else null },
        )
        assertEquals(listOf("Parent", "Folded", "Linked"), children.map { it.title })
        val parent = children[0]
        assertEquals(PageSpaceKeys.ofLine("_node.md", ids[1]), parent.key)
        assertEquals(listOf("Kid" to 0, "Grandkid" to 1), parent.items.map { it.title to it.depth })
        // Kid has a row under it: a grandchild page.
        assertEquals(listOf("Kid"), parent.children.map { it.title })
        val linked = children[2]
        assertEquals(PageSpaceKeys.ofFolder("Linked"), linked.key)
        assertEquals(listOf("One", "Two"), linked.items.map { it.title })
        // Grandchildren of a folder that is not loaded come from its listing.
        assertEquals(listOf(PageSpaceKeys.ofFolder("Linked/Two")), linked.children.map { it.key })
    }

    @Test
    fun rows_a_privacy_mode_hides_are_neither_pages_nor_preview_rows() {
        val lines = listOf("* Open", "  * Seen", "  * Diary #private", "    * secret", "* Hidden #private", "  * kid")
        val ids = lines.indices.map { LineId(it.toLong() + 1) }
        val hidden = PrivacyLayout.hiddenRows(lines, PrivacyFilter.of(listOf("#private")))
        val children = PageSpaceModel.childrenOf(
            lines, ids, 0, lines.lastIndex, "_node.md",
            folderOf = { null }, unloaded = emptySet(), previewOf = { null }, hidden = hidden,
        )
        assertEquals(listOf("Open"), children.map { it.title })
        assertEquals(listOf("Seen"), children.single().items.map { it.title })
        // Diary is hidden, so it is no grandchild page either.
        assertTrue(children.single().children.isEmpty())
    }

    @Test
    fun titles_are_plain_text() {
        assertEquals("Bold and link", PageSpaceModel.plainTitle("# **Bold** and [link](https://x)"))
        assertEquals("Open tasks", PageSpaceModel.plainTitle("Open tasks {{search: #todo}}"))
    }

    // ------------------------------------------------- against a real pane

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private suspend fun TestScope.pane(): PaneBackingViewModel {
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    /** [PaneBackingViewModel.spacePageOf] once the node listings it asked for are in. */
    private fun TestScope.page(p: PaneBackingViewModel): SpacePage {
        p.spacePageOf()
        runCurrent()
        p.spacePageOf()
        runCurrent()
        return p.spacePageOf()!!
    }

    @Test
    fun a_node_is_one_page_however_the_pane_reached_it() = runTest {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n- Spicy [↳](<Spicy/_node.md>)\n")
        seed("Recipes/Soups/Spicy/_node.md", "- Harira\n")
        val p = pane()
        val home = page(p)
        assertEquals(PageSpaceKeys.ofFolder(""), home.key)
        assertNull(home.parentKey)
        assertEquals(listOf(PageSpaceKeys.ofFolder("Recipes")), home.children.map { it.key })
        // The folded child's preview and grandchildren come from its listing.
        val recipes = home.children.single()
        assertEquals(listOf("Pasta", "Soups"), recipes.items.map { it.title })
        assertEquals(listOf(PageSpaceKeys.ofFolder("Recipes/Soups")), recipes.children.map { it.key })

        // Zoomed into Recipes in the root outline …
        p.zoomInto(1)
        runCurrent()
        val zoomed = page(p)
        assertEquals(PageSpaceKeys.ofFolder("Recipes"), zoomed.key)
        assertEquals(PageSpaceKeys.ofFolder(""), zoomed.parentKey)
        assertEquals(listOf(PageSpaceKeys.ofFolder("Recipes/Soups")), zoomed.children.map { it.key })
        assertEquals(listOf(PageSpaceKeys.ofFolder("Recipes/Soups/Spicy")), zoomed.children.single().children.map { it.key })

        // … or with its own outline open: the same page.
        p.navigateToVaultFile("Recipes/_node.md")
        p.stateFlow.first { it.isLoaded && it.activeFileRel == "Recipes/_node.md" }
        runCurrent()
        val opened = page(p)
        assertEquals(zoomed.key, opened.key)
        assertEquals(zoomed.parentKey, opened.parentKey)
        assertEquals(zoomed.children.map { it.key }, opened.children.map { it.key })
    }

    @Test
    fun an_indent_that_creates_a_child_creates_a_page_at_once() = runTest {
        seed("_node.md", "- A\n- B\n")
        val p = pane()
        assertTrue(page(p).children.isEmpty())
        p.moveTo(1, p.stateFlow.value.lines[1].length)
        p.indentLine()
        val children = page(p).children
        assertEquals(listOf("A"), children.map { it.title })
        assertEquals(listOf("B"), children.single().items.map { it.title })
    }

    @Test
    fun a_privacy_mode_hides_pages_from_node_listings_too() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n- Diary #private [↳](<Diary/_node.md>)\n")
        seed("Work/_node.md", "- Plan [↳](<Plan/_node.md>)\n- Salary #private [↳](<Salary/_node.md>)\n- Notes\n")
        seed("Work/Plan/_node.md", "- Q1\n")
        seed("Work/Salary/_node.md", "- 2026\n")
        seed("Diary/_node.md", "- Monday\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val mode = PrivacyMode("m1", "Colleagues", listOf("private"))
        registry.setPrivacyModes(listOf(mode))
        registry.setPrivacyMode(mode.id)
        val p = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        p.stateFlow.first { it.isLoaded }
        runCurrent()
        val home = page(p)
        assertEquals(listOf(PageSpaceKeys.ofFolder("Work")), home.children.map { it.key })
        val work = home.children.single()
        assertEquals(listOf("Plan", "Notes"), work.items.map { it.title })
        assertEquals(listOf(PageSpaceKeys.ofFolder("Work/Plan")), work.children.map { it.key })
    }

    @Test
    fun a_note_is_a_page_of_its_own_with_no_children() = runTest {
        seed("_node.md", "- A\n")
        seed("Work/Plan.md", "# Plan\ntext\n")
        val p = pane()
        p.navigateToVaultFile("Work/Plan.md")
        p.stateFlow.first { it.isLoaded && it.activeFileRel == "Work/Plan.md" }
        val note = page(p)
        assertEquals(PageSpaceKeys.ofFile("Work/Plan.md"), note.key)
        assertEquals(PageSpaceKeys.ofFolder("Work"), note.parentKey)
        assertTrue(note.children.isEmpty())
    }
}
