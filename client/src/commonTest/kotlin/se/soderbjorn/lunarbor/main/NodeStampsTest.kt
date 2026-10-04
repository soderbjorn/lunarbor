/*
 * NodeStampsTest.kt (commonTest)
 * ------------------------------
 * Tests for LBR-16, node timestamps: the `created` / `updated` front
 * matter `NoteRepository` writes into `_node.md` files (set on creation,
 * restamped only when the node's own items change, kept with other tools'
 * keys, never bubbled up to ancestors, the retitled node stamped too), and
 * the Navigate-to recency order `VaultIndex` builds from them.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.NodeFrontMatter
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.data.PromotedRef
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.data.VaultIndex
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeStampsTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()

    /** The repository's clock, in seconds; tests move it forward. */
    private var nowSeconds = 1_000L
    private val repo = NoteRepository(fs, root, nowMillis = { nowSeconds * 1000 })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private fun raw(rel: String): String? = fs.raw(root, rel)

    private fun stampAt(seconds: Long) = NodeFrontMatter.stampOf(seconds * 1000)

    private fun stamps(rel: String): NodeFrontMatter? = raw(rel)?.let { SubtreeCodec.splitFrontMatter(it).frontMatter }

    @Test
    fun a_new_outline_gets_created_and_updated() = runTest {
        repo.save("_node.md", listOf("* a"), emptyMap())
        assertEquals("---\ncreated: ${stampAt(1_000)}\nupdated: ${stampAt(1_000)}\n---\n- a\n", raw("_node.md"))
        // Loading shows only the items.
        assertEquals(listOf("* a"), repo.loadFile("_node.md").lines)
    }

    @Test
    fun saving_identical_content_does_not_restamp() = runTest {
        repo.save("_node.md", listOf("* a"), emptyMap())
        val first = raw("_node.md")
        nowSeconds = 2_000
        repo.save("_node.md", listOf("* a"), emptyMap())
        assertEquals(first, raw("_node.md"))
        nowSeconds = 3_000
        repo.save("_node.md", listOf("* a", "* b"), emptyMap())
        assertEquals(stampAt(1_000), stamps("_node.md")?.created)
        assertEquals(stampAt(3_000), stamps("_node.md")?.updated)
    }

    @Test
    fun a_file_without_stamps_stays_unknown_until_it_changes() = runTest {
        seed("_node.md", "- a\n")
        repo.save("_node.md", listOf("* a"), emptyMap())
        assertEquals("- a\n", raw("_node.md"))
        // Another tool's formatting is normalized without a stamp: the items are the same.
        seed("_node.md", "* a\n\n* b\n")
        repo.save("_node.md", listOf("* a", "* b"), emptyMap())
        assertEquals("- a\n- b\n", raw("_node.md"))
        // A real change stamps `updated`; `created` stays unknown (no backfill).
        repo.save("_node.md", listOf("* a", "* b", "* c"), emptyMap())
        assertNull(stamps("_node.md")?.created)
        assertEquals(stampAt(1_000), stamps("_node.md")?.updated)
    }

    @Test
    fun other_front_matter_keys_are_kept() = runTest {
        seed("_node.md", "---\ntags: [recipe]\naliases: Soups\n---\n- a\n")
        repo.save("_node.md", listOf("* a", "* b"), emptyMap())
        assertEquals("---\nupdated: ${stampAt(1_000)}\ntags: [recipe]\naliases: Soups\n---\n- a\n- b\n", raw("_node.md"))
    }

    @Test
    fun only_the_node_whose_items_changed_is_stamped() = runTest {
        // t=1000: Recipes gets its first child — a new folder, so a new outline.
        val first = repo.save("_node.md", listOf("* Recipes", "  * Soup"), emptyMap())
        assertEquals(stampAt(1_000), stamps("Recipes/_node.md")?.created)
        // t=2000: Soup gets a child: Soup's outline is created, Recipes' item
        // Soup gained its child link (Recipes changed), the root did not.
        nowSeconds = 2_000
        val second = repo.save("_node.md", listOf("* Recipes", "  * Soup", "    * Tomato"), first.promotedByRow)
        assertEquals(stampAt(2_000), stamps("Recipes/Soup/_node.md")?.created)
        assertEquals(stampAt(2_000), stamps("Recipes/_node.md")?.updated)
        assertEquals(stampAt(1_000), stamps("_node.md")?.updated)
        // t=3000: a grandchild edit stamps only Soup — no bubbling.
        nowSeconds = 3_000
        repo.save("_node.md", listOf("* Recipes", "  * Soup", "    * Tomatoes"), second.promotedByRow)
        assertEquals(stampAt(3_000), stamps("Recipes/Soup/_node.md")?.updated)
        assertEquals(stampAt(2_000), stamps("Recipes/_node.md")?.updated)
        assertEquals(stampAt(1_000), stamps("_node.md")?.updated)
        assertEquals(stampAt(1_000), stamps("Recipes/_node.md")?.created)
    }

    @Test
    fun retitling_a_node_stamps_its_parent_and_itself() = runTest {
        val first = repo.save("_node.md", listOf("* Recipes", "  * Soup", "    * Tomato"), emptyMap())
        nowSeconds = 2_000
        // Rename (the folder moves) …
        val second = repo.save("_node.md", listOf("* Recipes", "  * Soups", "    * Tomato"), first.promotedByRow)
        assertEquals("- Tomato\n", fs.read(root, "Recipes/Soups/_node.md"))
        assertEquals(stampAt(2_000), stamps("Recipes/Soups/_node.md")?.updated)
        assertEquals(stampAt(1_000), stamps("Recipes/Soups/_node.md")?.created)
        assertEquals(stampAt(2_000), stamps("Recipes/_node.md")?.updated)
        // … and a title edit that keeps the folder name (formatting only).
        nowSeconds = 3_000
        repo.save("_node.md", listOf("* Recipes", "  * **Soups**", "    * Tomato"), second.promotedByRow)
        assertEquals(stampAt(3_000), stamps("Recipes/Soups/_node.md")?.updated)
        assertEquals(stampAt(3_000), stamps("Recipes/_node.md")?.updated)
        assertEquals(stampAt(1_000), stamps("_node.md")?.updated)
    }

    @Test
    fun a_moved_node_keeps_its_stamps_and_both_parents_are_stamped() = runTest {
        repo.save("_node.md", listOf("* A", "  * Child", "    * x", "* B", "  * y"), emptyMap())
        nowSeconds = 2_000
        // Child moves from A to B (A keeps another item so it stays a folder).
        val lines = listOf("* A", "  * keep", "* B", "  * y", "  * Child", "    * x")
        val promoted = mapOf(0 to PromotedRef("A"), 2 to PromotedRef("B"), 4 to PromotedRef("A/Child"))
        repo.save("_node.md", lines, promoted)
        assertEquals("- x\n", fs.read(root, "B/Child/_node.md"))
        assertEquals(stampAt(1_000), stamps("B/Child/_node.md")?.updated)
        assertEquals(stampAt(2_000), stamps("A/_node.md")?.updated)
        assertEquals(stampAt(2_000), stamps("B/_node.md")?.updated)
    }

    @Test
    fun agent_moves_stamp_both_parents_and_a_retitle_the_node() = runTest {
        repo.save("_node.md", listOf("* A", "  * Child", "    * x", "* B", "  * y"), emptyMap())
        nowSeconds = 2_000
        repo.moveNode("A/Child", "B", index = null, newTitle = null)
        assertEquals(stampAt(1_000), stamps("B/Child/_node.md")?.updated)
        assertEquals(stampAt(2_000), stamps("A/_node.md")?.updated)
        assertEquals(stampAt(2_000), stamps("B/_node.md")?.updated)
        nowSeconds = 3_000
        repo.moveNode("B/Child", "B", index = 0, newTitle = "Kid")
        assertEquals(stampAt(3_000), stamps("B/Kid/_node.md")?.updated)
        assertEquals(stampAt(1_000), stamps("B/Kid/_node.md")?.created)
    }

    // ------------------------------------------------------ Navigate to order

    private fun target(path: String, title: String, kind: VaultEntryKind = VaultEntryKind.FOLDER) =
        LinkTarget(path, title, kind)

    private fun outlineAt(seconds: Long?) =
        if (seconds == null) "- x\n" else "---\nupdated: ${stampAt(seconds)}\n---\n- x\n"

    @Test
    fun navigate_to_lists_recently_changed_nodes_first() = runTest {
        val targets = listOf(
            target("", "Home"),
            target("A", "Soup one"),
            target("B", "Soup two"),
            target("C", "Soup six"),
            target("Plain", "Plain folder"),
            target("A/Soup.md", "Soup", VaultEntryKind.MARKDOWN),
        )
        val index = VaultIndex(listTargets = { targets }, listLinkBearingFiles = { emptyList() }, readText = { null })
        index.noteText("_node.md", outlineAt(null))
        index.noteText("A/_node.md", outlineAt(1_000))
        index.noteText("B/_node.md", outlineAt(5_000))
        index.noteText("C/_node.md", outlineAt(null))

        // Empty query: stamped nodes newest first, then the rest in walk order; no files.
        assertEquals(listOf("B", "A", "", "C", "Plain"), index.recentNodes().map { it.pathRel })

        // With a query, match quality still wins (folders before notes);
        // recency only breaks ties between equally good matches, unknown last.
        assertEquals(listOf("B", "A", "C", "A/Soup.md"), index.search("soup", byRecency = true).map { it.pathRel })
        // Without byRecency (Insert Link, Link to node), ties fall back to the path.
        assertEquals(listOf("A", "B", "C", "A/Soup.md"), index.search("soup").map { it.pathRel })

        // A save that restamps moves a node up; a move carries its stamp along.
        index.noteText("A/_node.md", outlineAt(9_000))
        assertEquals(listOf("A", "B", "C", "A/Soup.md"), index.search("soup", byRecency = true).map { it.pathRel })
        index.moveKeys(listOf(PathMove("A", "Moved")))
        assertEquals(9_000_000L, index.updatedOf("Moved"))
        assertNull(index.updatedOf("A"))
    }
}
