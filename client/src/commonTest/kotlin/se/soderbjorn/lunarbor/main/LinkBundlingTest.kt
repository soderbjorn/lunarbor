/*
 * LinkBundlingTest.kt (commonTest)
 * --------------------------------
 * Tests for [LinkBundling]: the tree path a bundled link follows (through
 * the lowest common ancestor, from folded bodies when folded) and the
 * B-spline curve drawn along it (exact ends, pulled towards the path,
 * straight at beta 0).
 */

package se.soderbjorn.lunarbor.main

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkBundlingTest {

    /** Root → Work (→ Alpha → Deep, Beta), Life (→ Garden). */
    private val graph = VaultGraph(
        mapOf(
            "" to GraphNode("", null, "Home", 0, listOf("Work", "Life"), 0),
            "Work" to GraphNode("Work", "", "Work", 1, listOf("Work/Alpha", "Work/Beta"), 0),
            "Work/Alpha" to GraphNode("Work/Alpha", "Work", "Alpha", 2, listOf("Work/Alpha/Deep"), 0),
            "Work/Alpha/Deep" to GraphNode("Work/Alpha/Deep", "Work/Alpha", "Deep", 3, emptyList(), 0),
            "Work/Beta" to GraphNode("Work/Beta", "Work", "Beta", 2, emptyList(), 0),
            "Life" to GraphNode("Life", "", "Life", 1, listOf("Life/Garden"), 0),
            "Life/Garden" to GraphNode("Life/Garden", "Life", "Garden", 2, emptyList(), 0),
        ),
    )

    @Test
    fun pathRunsUpToTheCommonAncestorAndDown() {
        assertEquals(
            listOf("Work/Alpha/Deep", "Work/Alpha", "Work", "", "Life", "Life/Garden"),
            LinkBundling.controlPath(graph, emptySet(), "Work/Alpha/Deep", "Life/Garden"),
        )
        assertEquals(
            listOf("Work/Beta", "Work", "Work/Alpha"),
            LinkBundling.controlPath(graph, emptySet(), "Work/Beta", "Work/Alpha"),
        )
    }

    @Test
    fun pathToAnAncestorEndsThere() {
        assertEquals(
            listOf("Work/Alpha/Deep", "Work/Alpha", "Work"),
            LinkBundling.controlPath(graph, emptySet(), "Work/Alpha/Deep", "Work"),
        )
    }

    @Test
    fun foldedEndsStartAtTheFoldedBody() {
        assertEquals(
            listOf("Work", "", "Life", "Life/Garden"),
            LinkBundling.controlPath(graph, setOf("Work"), "Work/Alpha/Deep", "Life/Garden"),
        )
        // Both ends inside one folded body: nothing to draw.
        assertNull(LinkBundling.controlPath(graph, setOf("Work"), "Work/Alpha/Deep", "Work/Beta"))
        assertNull(LinkBundling.controlPath(graph, emptySet(), "Work", "Nowhere"))
    }

    @Test
    fun curveStartsAndEndsExactlyOnTheEnds() {
        val pts = listOf(SpaceVec(0.0, 0.0, 0.0), SpaceVec(5.0, 10.0, 0.0), SpaceVec(10.0, 0.0, 0.0))
        val c = LinkBundling.curve(pts)
        assertEquals(pts.first(), c.first())
        assertEquals(pts.last(), c.last())
        assertTrue(c.size > 3)
    }

    @Test
    fun curveIsPulledTowardsThePathAndStraightAtBetaZero() {
        val pts = listOf(SpaceVec(0.0, 0.0, 0.0), SpaceVec(5.0, 10.0, 0.0), SpaceVec(10.0, 0.0, 0.0))
        val bundled = LinkBundling.curve(pts, beta = 1.0)
        assertTrue(bundled.maxOf { it.y } > 4.0)
        val straight = LinkBundling.curve(pts, beta = 0.0)
        assertTrue(straight.all { abs(it.y) < 1e-9 })
        // Monotone along x on a straight line.
        assertTrue(straight.zipWithNext().all { (p, q) -> q.x >= p.x - 1e-9 })
    }

    @Test
    fun twoPointPathIsAStraightSegment() {
        val pts = listOf(SpaceVec(0.0, 0.0, 0.0), SpaceVec(1.0, 2.0, 3.0))
        assertEquals(pts, LinkBundling.curve(pts))
    }
}
