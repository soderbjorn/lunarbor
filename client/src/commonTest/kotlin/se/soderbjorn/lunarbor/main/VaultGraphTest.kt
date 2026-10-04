/*
 * VaultGraphTest.kt (commonTest)
 * ------------------------------
 * Tests for LBR-11's map shapes: the graph built from node listings
 * ([VaultGraphBuilder] — bodies, leaves, links, and what the privacy mode
 * hides left out) and the three layouts ([GraphLayout] — deterministic,
 * every visible body placed, folds respected).
 */

package se.soderbjorn.lunarbor.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultGraphTest {

    /** Root → Work (→ Alpha, Beta), Life (→ Secret #private), a leaf. */
    private val listings: Map<String, List<LinkPreviewItem>> = mapOf(
        "" to listOf(
            LinkPreviewItem("Work", "Work"),
            LinkPreviewItem("Life", "Life"),
            LinkPreviewItem("Buy milk", null),
        ),
        "Work" to listOf(
            LinkPreviewItem("Alpha", "Work/Alpha"),
            LinkPreviewItem("Beta #work", "Work/Beta", setOf("work")),
            LinkPreviewItem("note", null),
        ),
        "Work/Alpha" to listOf(LinkPreviewItem("a1", null), LinkPreviewItem("a2", null)),
        "Work/Beta" to listOf(LinkPreviewItem("b1", null)),
        "Life" to listOf(
            LinkPreviewItem("Secret #private", "Life/Secret", setOf("private")),
            LinkPreviewItem("diary #private", null, setOf("private")),
            LinkPreviewItem("walk", null),
        ),
        "Life/Secret" to listOf(LinkPreviewItem("Deeper", "Life/Secret/Deeper")),
        "Life/Secret/Deeper" to listOf(LinkPreviewItem("x", null)),
    )

    private val hidesPrivate: (String, LinkPreviewItem) -> Boolean = { _, item -> "private" in item.tagKeys }

    private fun graph(hidden: Boolean = false) =
        VaultGraphBuilder.build("Home", { listings[it] }, if (hidden) hidesPrivate else { _, _ -> false })

    @Test
    fun builds_bodies_breadth_first_with_leaf_counts() {
        val g = graph()
        assertEquals(listOf("", "Work", "Life", "Work/Alpha", "Work/Beta", "Life/Secret", "Life/Secret/Deeper"), g.nodes.keys.toList())
        assertEquals("Home", g.root?.title)
        assertEquals(1, g.nodes.getValue("").leafCount)
        assertEquals(listOf("Work/Alpha", "Work/Beta"), g.nodes.getValue("Work").children)
        assertEquals(2, g.nodes.getValue("Work/Alpha").depth)
        assertEquals("Work", g.nodes.getValue("Work/Alpha").area)
        // Tags are left out of titles (labels), as in breadcrumbs.
        assertEquals("Beta", g.nodes.getValue("Work/Beta").title)
        assertEquals(setOf("work"), g.nodes.getValue("Work/Beta").tagKeys)
    }

    @Test
    fun privacy_mode_leaves_out_hidden_items_and_their_subtrees() {
        val g = graph(hidden = true)
        assertFalse("Life/Secret" in g.nodes)
        assertFalse("Life/Secret/Deeper" in g.nodes)
        assertEquals(emptyList(), g.nodes.getValue("Life").children)
        // The hidden leaf is not dust either.
        assertEquals(1, g.nodes.getValue("Life").leafCount)
    }

    @Test
    fun unknown_listing_is_an_unloaded_body() {
        val g = VaultGraphBuilder.build("Home", { if (it == "") listOf(LinkPreviewItem("Work", "Work")) else null })
        assertTrue(g.nodes.getValue("").loaded)
        assertFalse(g.nodes.getValue("Work").loaded)
    }

    @Test
    fun node_cap_truncates() {
        val g = VaultGraphBuilder.build("Home", { listings[it] }, maxNodes = 3)
        assertEquals(3, g.nodes.size)
        assertTrue(g.truncated)
    }

    @Test
    fun anchor_finds_nearest_body() {
        val g = graph(hidden = true)
        assertEquals("Work/Alpha", g.anchorOf("Work/Alpha"))
        assertEquals("Work", g.anchorOf("Work/note.md"))
        assertEquals("Life", g.anchorOf("Life/Secret/Deeper"))
        assertNull(VaultGraph.EMPTY.anchorOf("x"))
    }

    @Test
    fun link_edges_join_bodies_and_skip_hidden_ends() {
        val g = graph(hidden = true)
        val links = mapOf(
            "Work/Alpha/_node.md" to setOf("Work/Beta", "Work/Alpha/pic.png"),
            "Work/Beta/_node.md" to setOf("Work/Alpha"),
            "Life/_node.md" to setOf("Life/Secret"),
            "Life/Secret/_node.md" to setOf("Work"),
        )
        val hidden = { p: String -> p.startsWith("Life/Secret") }
        val edges = VaultGraphBuilder.linkEdges(g, links, hidden)
        assertEquals(listOf("Work/Alpha" to "Work/Beta"), edges)
    }

    // ------------------------------------------------------------ layout

    @Test
    fun every_shape_places_every_unfolded_body_deterministically() {
        val g = graph().copy(links = listOf("Work/Alpha" to "Life"))
        for (shape in listOf(SpaceShape.CROWN, SpaceShape.CONE, SpaceShape.GALAXY)) {
            val a = GraphLayout.layout(g, emptySet(), shape)
            val b = GraphLayout.layout(g, emptySet(), shape)
            assertEquals(g.nodes.keys, a.keys, "$shape places every body")
            assertEquals(a, b, "$shape is deterministic")
            assertTrue(a.values.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        }
    }

    @Test
    fun crown_grows_up_and_cone_hangs_down() {
        val g = graph()
        val crown = GraphLayout.layout(g, emptySet(), SpaceShape.CROWN)
        assertTrue(crown.getValue("Work").y > crown.getValue("").y - 4)
        assertTrue(crown.getValue("Work/Alpha").y > crown.getValue("").y)
        val cone = GraphLayout.layout(g, emptySet(), SpaceShape.CONE)
        assertTrue(cone.getValue("Work").y < cone.getValue("").y)
        assertTrue(cone.getValue("Work/Alpha").y < cone.getValue("Work").y)
    }

    @Test
    fun galaxy_keeps_root_at_centre() {
        val p = GraphLayout.layout(graph(), emptySet(), SpaceShape.GALAXY).getValue("")
        assertEquals(0.0, p.x)
        assertEquals(0.0, p.z)
    }

    @Test
    fun folded_nodes_hide_their_children() {
        val g = graph()
        val p = GraphLayout.layout(g, setOf("Work"), SpaceShape.CROWN)
        assertTrue("Work" in p)
        assertFalse("Work/Alpha" in p)
        assertEquals("Work", GraphLayout.visibleAnchor(g, setOf("Work"), "Work/Alpha"))
        assertEquals("Life", GraphLayout.visibleAnchor(g, setOf("Life"), "Life/Secret/Deeper"))
        assertEquals("Work/Beta", GraphLayout.visibleAnchor(g, emptySet(), "Work/Beta"))
    }

    @Test
    fun palette_spreads_area_hues_and_keeps_the_root_neutral() {
        val hues = SpacePalette.areaHues(listOf("Work", "Life", "Retro"))
        assertEquals(3, hues.size)
        val sorted = hues.values.sorted()
        // Golden-angle steps: no two of three areas closer than a tenth of the wheel.
        assertTrue(sorted.zipWithNext().all { (a, b) -> b - a > 0.1 } && 1 - sorted.last() + sorted.first() > 0.1)
        assertEquals(hues["Work"], SpacePalette.hueOf("Work/Alpha/_node.md", hues))
        assertNull(SpacePalette.hueOf("", hues))
        assertNull(SpacePalette.hueOf("Inbox.md", hues))
        assertTrue(SpacePalette.hueOf("Unknown/x", hues) != null)
        assertEquals("Work/Alpha", SpacePalette.pathOfKey(PageSpaceKeys.ofFolder("Work/Alpha")))
        assertEquals("Work/_node.md", SpacePalette.pathOfKey(PageSpaceKeys.ofLine("Work/_node.md", LineId(3))))
    }

    @Test
    fun shapes_cycle() {
        assertEquals(SpaceShape.CROWN, SpaceShape.PAGES.next())
        assertEquals(SpaceShape.PAGES, SpaceShape.GALAXY.next())
        assertEquals(SpaceShape.CONE, SpaceShape.of("CONE"))
        assertEquals(SpaceShape.PAGES, SpaceShape.of("nope"))
    }

    @Test
    fun defaultFoldsOpenADeepNarrowTopBreadthFirstWithinTheBudget() {
        // Home → Main → {A, B}; A and B each hold 200 nodes with 5 children each.
        val nodes = LinkedHashMap<String, GraphNode>()
        fun add(id: String, parent: String?, depth: Int, kids: List<String>) {
            nodes[id] = GraphNode(id, parent, id.ifEmpty { "Home" }, depth, kids, 0)
        }
        val big = listOf("Main/A", "Main/B")
        add("", null, 0, listOf("Main"))
        add("Main", "", 1, big)
        for (b in big) add(b, "Main", 2, (1..200).map { "$b/$it" })
        for (b in big) for (i in 1..200) add("$b/$i", b, 3, (1..5).map { "$b/$i/$it" })
        for (b in big) for (i in 1..200) for (j in 1..5) add("$b/$i/$j", "$b/$i", 4, emptyList())

        val folded = GraphLayout.defaultFolds(VaultGraph(nodes))

        assertFalse("" in folded)
        assertFalse("Main" in folded)
        assertFalse("Main/A" in folded) // 4 + 200 bodies fit
        assertTrue("Main/B" in folded) // another 200 would not
        assertFalse("Main/A/1" in folded) // small ones still open after a big one stays shut
        assertTrue(nodes.keys.count { it !in folded && nodes.getValue(it).children.isNotEmpty() } > 3)
    }
}
