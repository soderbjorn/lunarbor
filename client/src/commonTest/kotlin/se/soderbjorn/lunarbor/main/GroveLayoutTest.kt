/*
 * GroveLayoutTest.kt (commonTest)
 * -------------------------------
 * Tests for 3D mode's "Grove" space ([GroveLayout]): poses over the whole
 * vault — Home facing the camera, child pages all round, every frame
 * upright and right-handed, no two pages overlapping however many
 * children, a big branch pushing only its own pages out, determinism —
 * the camera pose, quaternions, and the vault tree with the live page
 * spliced in ([GroveLayout.treeOf]).
 */

package se.soderbjorn.lunarbor.main

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroveLayoutTest {

    private val g = PageSpaceLayout.geometry(1400.0, 900.0)

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6

    /** A tree of [fanout]^[depth] pages under "r", keys by path. */
    private fun bushy(fanout: Int, depth: Int, key: String = "r"): SpaceTree =
        SpaceTree(key, if (depth == 0) emptyList() else (1..fanout).map { bushy(fanout, depth - 1, "$key/$it") })

    @Test
    fun the_root_faces_the_camera_at_the_origin() {
        val at = GroveLayout.layout(SpaceTree("r", listOf(SpaceTree("a"))), g)
        assertEquals(SpacePose.ROOT, at["r"])
        val cam = GroveLayout.cameraFor(at.getValue("r"), g)
        assertTrue(near(cam.position.z, g.cameraDistance))
        assertTrue(near(cam.position.x, 0.0) && near(cam.position.y, 0.0))
        assertEquals(SpaceVec(0.0, 0.0, 1.0), cam.normal)
    }

    @Test
    fun every_page_has_an_upright_right_handed_frame() {
        for ((_, p) in GroveLayout.layout(bushy(4, 3), g)) {
            assertTrue(near(p.right.length, 1.0) && near(p.up.length, 1.0) && near(p.normal.length, 1.0))
            assertTrue(near(p.right.dot(p.up), 0.0) && near(p.up.dot(p.normal), 0.0) && near(p.right.dot(p.normal), 0.0))
            val n = p.right.cross(p.up)
            assertTrue(near(n.x, p.normal.x) && near(n.y, p.normal.y) && near(n.z, p.normal.z))
        }
    }

    @Test
    fun two_children_hang_left_and_right_behind_the_page_facing_back() {
        val at = GroveLayout.layout(SpaceTree("r", listOf(SpaceTree("a"), SpaceTree("b"))), g)
        val a = at.getValue("a")
        val b = at.getValue("b")
        assertTrue(a.position.x < 0 && b.position.x > 0)
        assertTrue(a.position.z < 0 && b.position.z < 0)
        // Each faces back along its branch, towards the page it hangs off.
        assertTrue(near(a.normal.dot((SpaceVec.ZERO - a.position).normalized()!!), 1.0))
        // Its up stays as close to the parent's as the turn allows.
        assertTrue(a.up.y > 0.9)
    }

    @Test
    fun children_spread_all_round_the_axis_off_the_page() {
        val tree = SpaceTree("r", (1..8).map { SpaceTree("c$it") })
        val at = GroveLayout.layout(tree, g)
        val kids = (1..8).map { at.getValue("c$it").position }
        // Up, down, left and right are all used.
        assertTrue(kids.any { it.y > 100 } && kids.any { it.y < -100 } && kids.any { it.x > 100 } && kids.any { it.x < -100 })
        // The first goes left.
        assertTrue(kids[0].x < 0)
        // None hangs straight behind the root page, and none nearer than the first radius.
        for (k in kids) {
            val off = kotlin.math.acos(-k.z / k.length)
            assertTrue(off >= GroveLayout.HOLE - 1e-9, "child at $k is behind the page")
            assertTrue(k.length >= GroveLayout.FIRST_RADIUS - 1e-6)
        }
    }

    @Test
    fun no_two_pages_overlap_however_many_children() {
        val trees = listOf(bushy(3, 4), bushy(6, 3), SpaceTree("r", (1..40).map { SpaceTree("c$it", listOf(SpaceTree("c$it/x"))) }))
        for (tree in trees) {
            val pos = GroveLayout.layout(tree, g).values.map { it.position }
            for (i in pos.indices) for (j in i + 1 until pos.size) {
                assertTrue((pos[i] - pos[j]).length >= 2 * GroveLayout.pageRadius(g) - 1e-6, "pages $i and $j overlap")
            }
        }
    }

    @Test
    fun a_big_branch_pushes_only_its_own_pages_out() {
        val small = GroveLayout.layout(SpaceTree("r", listOf(SpaceTree("a"), SpaceTree("b"))), g)
        val big = GroveLayout.layout(SpaceTree("r", listOf(SpaceTree("a"), bushy(6, 3, "b"))), g)
        // The root's children stay within a few page widths, whatever hangs under them.
        assertTrue(small.getValue("a").position.length < 6000)
        assertTrue(big.getValue("a").position.length < 6000)
        assertTrue(big.getValue("b").position.length < 6000)
    }

    @Test
    fun layout_is_deterministic_and_keeps_a_repeated_key_once() {
        val tree = SpaceTree("r", listOf(SpaceTree("a", listOf(SpaceTree("b"))), SpaceTree("b")))
        val first = GroveLayout.layout(tree, g)
        assertEquals(first, GroveLayout.layout(tree, g))
        assertEquals(setOf("r", "a", "b"), first.keys)
    }

    @Test
    fun quaternions_turn_axes_onto_the_frame_and_slerp_between() {
        val p = GroveLayout.layout(SpaceTree("r", listOf(SpaceTree("a"), SpaceTree("b"), SpaceTree("c"))), g).getValue("b")
        val q = p.rotation
        fun same(u: SpaceVec, v: SpaceVec) = (u - v).length < 1e-9
        assertTrue(same(q.rotate(SpaceVec(1.0, 0.0, 0.0)), p.right))
        assertTrue(same(q.rotate(SpaceVec(0.0, 1.0, 0.0)), p.up))
        assertTrue(same(q.rotate(SpaceVec(0.0, 0.0, 1.0)), p.normal))
        assertTrue(same(q.conjugate().rotate(p.normal), SpaceVec(0.0, 0.0, 1.0)))
        assertEquals(SpaceQuat.IDENTITY, SpaceQuat.slerp(SpaceQuat.IDENTITY, q, 0.0))
        val end = SpaceQuat.slerp(SpaceQuat.IDENTITY, q, 1.0)
        assertTrue(same(end.rotate(SpaceVec(0.0, 0.0, 1.0)), p.normal))
    }

    @Test
    fun the_camera_sits_in_front_of_the_page_turned_like_it() {
        val page = GroveLayout.layout(SpaceTree("r", listOf(SpaceTree("a"))), g).getValue("a")
        val cam = GroveLayout.cameraFor(page, g)
        val back = cam.position - page.position
        assertTrue(near(back.length, g.cameraDistance))
        assertTrue(near(back.normalized()!!.dot(page.normal), 1.0))
        assertEquals(page.normal, cam.normal)
    }

    @Test
    fun the_vault_tree_splices_in_the_live_page() {
        val graph = VaultGraph(
            mapOf(
                "" to GraphNode("", null, "Home", 0, listOf("A", "B"), 0),
                "A" to GraphNode("A", "", "A", 1, listOf("A/X"), 0),
                "A/X" to GraphNode("A/X", "A", "X", 2, emptyList(), 0),
                "B" to GraphNode("B", "", "B", 1, emptyList(), 0),
            ),
        )
        // On A: its children come from the outline (a new, unsaved one included).
        val onA = SpacePage(
            PageSpaceKeys.ofFolder("A"), "A", PageSpaceKeys.ofFolder(""),
            listOf(
                SpaceChild(PageSpaceKeys.ofFolder("A/X"), "X", LineId(1), "A/X", emptyList()),
                SpaceChild("z:A/_node.md#2", "New", LineId(2), null, emptyList()),
            ),
        )
        val t = GroveLayout.treeOf(graph, onA)
        assertEquals("n:", t.key)
        assertEquals(listOf("n:A", "n:B"), t.children.map { it.key })
        assertEquals(listOf("n:A/X", "z:A/_node.md#2"), t.children[0].children.map { it.key })
        // A note hangs under its folder's node.
        val note = SpacePage(PageSpaceKeys.ofFile("B/plan.md"), "plan", PageSpaceKeys.ofFolder("B"), emptyList())
        assertEquals(listOf("f:B/plan.md"), GroveLayout.treeOf(graph, note).children[1].children.map { it.key })
        // While the graph loads: the live page alone.
        assertEquals("n:A", GroveLayout.treeOf(VaultGraph.EMPTY, onA).key)
    }
}
